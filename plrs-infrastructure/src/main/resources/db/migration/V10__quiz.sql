-- Traces to: §3.c.1.3 quiz_items + quiz_item_options, §3.b.5.1 (TRG-1
-- ctype-quiz coupling), §3.b.5.2 (TRG-2 deferred exactly-one-correct),
-- FR-19 (quiz authoring).
--
-- Filename note: V10 (not V9 as the step prompt suggested) because V9 was
-- used by the outbox migration in step 73.

CREATE TABLE plrs_ops.quiz_items (
  content_id   BIGINT NOT NULL REFERENCES plrs_ops.content(content_id) ON DELETE CASCADE,
  item_order   INT    NOT NULL,
  topic_id     BIGINT NOT NULL REFERENCES plrs_ops.topics(topic_id) ON DELETE RESTRICT,
  stem         TEXT   NOT NULL,
  explanation  TEXT,
  PRIMARY KEY (content_id, item_order),
  CONSTRAINT qitem_order_positive CHECK (item_order > 0),
  CONSTRAINT qitem_stem_nn        CHECK (length(trim(stem)) > 0)
);

CREATE INDEX idx_quiz_items_topic ON plrs_ops.quiz_items(topic_id);

-- TRG-1 (§3.b.5.1): a quiz_item must reference content of ctype = QUIZ.
CREATE OR REPLACE FUNCTION plrs_ops.fn_check_quiz_items_parent_is_quiz()
RETURNS TRIGGER AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM plrs_ops.content
    WHERE content_id = NEW.content_id AND ctype = 'QUIZ'
  ) THEN
    RAISE EXCEPTION
      'quiz_items.content_id % must reference content with ctype=QUIZ', NEW.content_id
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END; $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_quiz_items_parent_is_quiz
  BEFORE INSERT OR UPDATE OF content_id ON plrs_ops.quiz_items
  FOR EACH ROW EXECUTE FUNCTION plrs_ops.fn_check_quiz_items_parent_is_quiz();

-- Mirror: prevent ctype change away from QUIZ when quiz_items exist.
CREATE OR REPLACE FUNCTION plrs_ops.fn_prevent_ctype_change_when_quiz_items()
RETURNS TRIGGER AS $$
BEGIN
  IF OLD.ctype = 'QUIZ' AND NEW.ctype <> 'QUIZ'
     AND EXISTS (SELECT 1 FROM plrs_ops.quiz_items WHERE content_id = OLD.content_id) THEN
    RAISE EXCEPTION 'cannot change ctype away from QUIZ while quiz_items exist'
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END; $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_prevent_ctype_change
  BEFORE UPDATE OF ctype ON plrs_ops.content
  FOR EACH ROW EXECUTE FUNCTION plrs_ops.fn_prevent_ctype_change_when_quiz_items();

-- quiz_item_options: doubly-weak entity (composite PK on content_id + item_order + option_order).
CREATE TABLE plrs_ops.quiz_item_options (
  content_id   BIGINT  NOT NULL,
  item_order   INT     NOT NULL,
  option_order INT     NOT NULL,
  option_text  TEXT    NOT NULL,
  is_correct   BOOLEAN NOT NULL,
  PRIMARY KEY (content_id, item_order, option_order),
  FOREIGN KEY (content_id, item_order)
    REFERENCES plrs_ops.quiz_items(content_id, item_order) ON DELETE CASCADE,
  CONSTRAINT qopt_order_positive CHECK (option_order > 0),
  CONSTRAINT qopt_text_nn        CHECK (length(trim(option_text)) > 0)
);

-- TRG-2 (§3.b.5.2): exactly one is_correct=TRUE per item, DEFERRABLE INITIALLY DEFERRED
-- so callers can swap two options' is_correct flags within one transaction
-- without the intermediate state failing the constraint.
CREATE OR REPLACE FUNCTION plrs_ops.fn_check_exactly_one_correct_option()
RETURNS TRIGGER AS $$
DECLARE
  v_content_id BIGINT;
  v_item_order INT;
  correct_count INT;
BEGIN
  IF TG_OP = 'DELETE' THEN
    v_content_id := OLD.content_id;
    v_item_order := OLD.item_order;
  ELSE
    v_content_id := NEW.content_id;
    v_item_order := NEW.item_order;
  END IF;

  -- Skip when no options remain (cascade-delete cleared the item entirely).
  IF EXISTS (
    SELECT 1 FROM plrs_ops.quiz_item_options
    WHERE content_id = v_content_id AND item_order = v_item_order
  ) THEN
    SELECT COUNT(*) INTO correct_count
    FROM plrs_ops.quiz_item_options
    WHERE content_id = v_content_id
      AND item_order = v_item_order
      AND is_correct = TRUE;
    IF correct_count <> 1 THEN
      RAISE EXCEPTION
        'quiz item (%, %) must have exactly one correct option, found %',
        v_content_id, v_item_order, correct_count
        USING ERRCODE = '23514';
    END IF;
  END IF;

  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  ELSE
    RETURN NEW;
  END IF;
END; $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_exactly_one_correct_option
  AFTER INSERT OR UPDATE OR DELETE ON plrs_ops.quiz_item_options
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION plrs_ops.fn_check_exactly_one_correct_option();

COMMENT ON TABLE plrs_ops.quiz_items IS
  'Weak entity owned by content of ctype=QUIZ. TRG-1 enforces parent ctype.';
COMMENT ON TABLE plrs_ops.quiz_item_options IS
  'Doubly-weak entity. TRG-2 (deferred) enforces exactly one is_correct per item.';
