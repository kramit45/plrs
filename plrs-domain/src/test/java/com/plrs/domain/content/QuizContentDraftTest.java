package com.plrs.domain.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.AuditFields;
import com.plrs.domain.common.DomainInvariantException;
import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.quiz.QuizItem;
import com.plrs.domain.quiz.QuizItemOption;
import com.plrs.domain.topic.TopicId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QuizContentDraftTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-04-25T10:00:00Z"), ZoneOffset.UTC);
    private static final TopicId TOPIC = TopicId.of(1L);

    private static AuditFields audit() {
        return AuditFields.initial("system", CLOCK);
    }

    private static QuizItem item(int order) {
        return QuizItem.of(
                order,
                TOPIC,
                "stem-" + order,
                Optional.empty(),
                List.of(
                        new QuizItemOption(1, "a", true),
                        new QuizItemOption(2, "b", false)));
    }

    private static QuizContentDraft draft(List<QuizItem> items) {
        return new QuizContentDraft(
                TOPIC,
                "Quiz Title",
                Difficulty.BEGINNER,
                15,
                "https://example.com/q",
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                audit(),
                items);
    }

    @Test
    void validQuizDraftWithThreeItems() {
        QuizContentDraft d = draft(List.of(item(1), item(2), item(3)));

        assertThat(d.items()).hasSize(3);
        assertThat(d.title()).isEqualTo("Quiz Title");
    }

    @Test
    void rejectsEmptyItems() {
        assertThatThrownBy(() -> draft(List.of()))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("at least one item");
    }

    @Test
    void rejectsNullItems() {
        assertThatThrownBy(() -> draft(null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("items");
    }

    @Test
    void rejectsDuplicateItemOrdersAcrossItems() {
        assertThatThrownBy(() -> draft(List.of(item(1), item(1))))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("unique");
    }

    @Test
    void rejectsInvalidUrlScheme() {
        assertThatThrownBy(
                        () ->
                                new QuizContentDraft(
                                        TOPIC,
                                        "title",
                                        Difficulty.BEGINNER,
                                        10,
                                        "ftp://example.com/q",
                                        Optional.empty(),
                                        Set.of(),
                                        Optional.empty(),
                                        audit(),
                                        List.of(item(1))))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("http");
    }

    @Test
    void rejectsBlankTitle() {
        assertThatThrownBy(
                        () ->
                                new QuizContentDraft(
                                        TOPIC,
                                        "   ",
                                        Difficulty.BEGINNER,
                                        10,
                                        "https://x.y",
                                        Optional.empty(),
                                        Set.of(),
                                        Optional.empty(),
                                        audit(),
                                        List.of(item(1))))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rejectsEstMinutesOutOfRange() {
        assertThatThrownBy(
                        () ->
                                new QuizContentDraft(
                                        TOPIC,
                                        "title",
                                        Difficulty.BEGINNER,
                                        0,
                                        "https://x.y",
                                        Optional.empty(),
                                        Set.of(),
                                        Optional.empty(),
                                        audit(),
                                        List.of(item(1))))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("[1, 600]");
    }

    @Test
    void tagsSetIsImmutable() {
        Set<String> mutable = new HashSet<>(Set.of("a", "b"));
        QuizContentDraft d =
                new QuizContentDraft(
                        TOPIC,
                        "title",
                        Difficulty.BEGINNER,
                        10,
                        "https://x.y",
                        Optional.empty(),
                        mutable,
                        Optional.empty(),
                        audit(),
                        List.of(item(1)));

        mutable.add("post-construction");

        assertThat(d.tags()).containsExactlyInAnyOrder("a", "b");
        assertThatThrownBy(() -> d.tags().add("more"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void itemsListIsImmutable() {
        List<QuizItem> mutable = new ArrayList<>();
        mutable.add(item(1));
        mutable.add(item(2));

        QuizContentDraft d = draft(mutable);
        mutable.clear();

        assertThat(d.items()).hasSize(2);
        assertThatThrownBy(() -> d.items().add(item(99)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsBlankTagInside() {
        assertThatThrownBy(
                        () ->
                                new QuizContentDraft(
                                        TOPIC,
                                        "title",
                                        Difficulty.BEGINNER,
                                        10,
                                        "https://x.y",
                                        Optional.empty(),
                                        Set.of("   "),
                                        Optional.empty(),
                                        audit(),
                                        List.of(item(1))))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("blank");
    }
}
