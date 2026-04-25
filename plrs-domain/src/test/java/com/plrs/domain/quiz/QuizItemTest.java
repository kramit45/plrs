package com.plrs.domain.quiz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.DomainInvariantException;
import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.topic.TopicId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class QuizItemTest {

    private static final TopicId TOPIC = TopicId.of(1L);

    private static QuizItemOption opt(int order, String text, boolean correct) {
        return new QuizItemOption(order, text, correct);
    }

    @Test
    void validWithTwoOptionsOneCorrect() {
        QuizItem q =
                QuizItem.of(
                        1,
                        TOPIC,
                        "What?",
                        Optional.empty(),
                        List.of(opt(1, "yes", true), opt(2, "no", false)));

        assertThat(q.itemOrder()).isEqualTo(1);
        assertThat(q.options()).hasSize(2);
    }

    @Test
    void validWithSixOptionsOneCorrect() {
        QuizItem q =
                QuizItem.of(
                        1,
                        TOPIC,
                        "Pick the right one",
                        Optional.empty(),
                        List.of(
                                opt(1, "a", true),
                                opt(2, "b", false),
                                opt(3, "c", false),
                                opt(4, "d", false),
                                opt(5, "e", false),
                                opt(6, "f", false)));

        assertThat(q.options()).hasSize(6);
    }

    @Test
    void rejectsOneOption() {
        assertThatThrownBy(
                        () ->
                                QuizItem.of(
                                        1,
                                        TOPIC,
                                        "stem",
                                        Optional.empty(),
                                        List.of(opt(1, "only", true))))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("between 2 and 6");
    }

    @Test
    void rejectsSevenOptions() {
        List<QuizItemOption> seven =
                List.of(
                        opt(1, "a", true),
                        opt(2, "b", false),
                        opt(3, "c", false),
                        opt(4, "d", false),
                        opt(5, "e", false),
                        opt(6, "f", false),
                        opt(7, "g", false));

        assertThatThrownBy(() -> QuizItem.of(1, TOPIC, "stem", Optional.empty(), seven))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("between 2 and 6");
    }

    @Test
    void rejectsZeroCorrectOptions() {
        assertThatThrownBy(
                        () ->
                                QuizItem.of(
                                        1,
                                        TOPIC,
                                        "stem",
                                        Optional.empty(),
                                        List.of(opt(1, "a", false), opt(2, "b", false))))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("exactly one correct option");
    }

    @Test
    void rejectsTwoCorrectOptions() {
        assertThatThrownBy(
                        () ->
                                QuizItem.of(
                                        1,
                                        TOPIC,
                                        "stem",
                                        Optional.empty(),
                                        List.of(opt(1, "a", true), opt(2, "b", true))))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void rejectsBlankStem() {
        assertThatThrownBy(
                        () ->
                                QuizItem.of(
                                        1,
                                        TOPIC,
                                        "   ",
                                        Optional.empty(),
                                        List.of(opt(1, "a", true), opt(2, "b", false))))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("stem");
    }

    @Test
    void rejectsNullTopicId() {
        assertThatThrownBy(
                        () ->
                                QuizItem.of(
                                        1,
                                        null,
                                        "stem",
                                        Optional.empty(),
                                        List.of(opt(1, "a", true), opt(2, "b", false))))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("topicId");
    }

    @Test
    void rejectsItemOrderZeroOrNegative() {
        assertThatThrownBy(
                        () ->
                                QuizItem.of(
                                        0,
                                        TOPIC,
                                        "stem",
                                        Optional.empty(),
                                        List.of(opt(1, "a", true), opt(2, "b", false))))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining(">= 1");
        assertThatThrownBy(
                        () ->
                                QuizItem.of(
                                        -1,
                                        TOPIC,
                                        "stem",
                                        Optional.empty(),
                                        List.of(opt(1, "a", true), opt(2, "b", false))))
                .isInstanceOf(DomainInvariantException.class);
    }

    @Test
    void rejectsDuplicateOptionOrders() {
        assertThatThrownBy(
                        () ->
                                QuizItem.of(
                                        1,
                                        TOPIC,
                                        "stem",
                                        Optional.empty(),
                                        List.of(opt(1, "a", true), opt(1, "b", false))))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("unique");
    }

    @Test
    void acceptsNonConsecutiveOptionOrders() {
        // Design choice: option orders need only be unique + positive, not
        // consecutive. Lets instructors delete an option without renumbering.
        QuizItem q =
                QuizItem.of(
                        1,
                        TOPIC,
                        "stem",
                        Optional.empty(),
                        List.of(opt(1, "a", true), opt(3, "c", false), opt(4, "d", false)));

        assertThat(q.options())
                .extracting(QuizItemOption::optionOrder)
                .containsExactly(1, 3, 4);
    }

    @Test
    void explanationOptionalEmptyWorks() {
        QuizItem q =
                QuizItem.of(
                        1,
                        TOPIC,
                        "stem",
                        Optional.empty(),
                        List.of(opt(1, "a", true), opt(2, "b", false)));

        assertThat(q.explanation()).isEmpty();
    }

    @Test
    void explanationOptionalPresentRoundTrips() {
        QuizItem q =
                QuizItem.of(
                        1,
                        TOPIC,
                        "stem",
                        Optional.of("because reasons"),
                        List.of(opt(1, "a", true), opt(2, "b", false)));

        assertThat(q.explanation()).contains("because reasons");
    }

    @Test
    void optionsListIsUnmodifiable() {
        QuizItem q =
                QuizItem.of(
                        1,
                        TOPIC,
                        "stem",
                        Optional.empty(),
                        List.of(opt(1, "a", true), opt(2, "b", false)));

        assertThatThrownBy(() -> q.options().add(opt(3, "c", false)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void defensiveCopyPreventsExternalMutation() {
        List<QuizItemOption> mutable = new ArrayList<>();
        mutable.add(opt(1, "a", true));
        mutable.add(opt(2, "b", false));

        QuizItem q = QuizItem.of(1, TOPIC, "stem", Optional.empty(), mutable);
        mutable.clear();

        assertThat(q.options()).hasSize(2);
    }

    @Test
    void equalsAndHashCodeUseNaturalKey() {
        QuizItem a =
                QuizItem.of(
                        1,
                        TOPIC,
                        "stem",
                        Optional.empty(),
                        List.of(opt(1, "a", true), opt(2, "b", false)));
        // Same natural key, different options → still equal.
        QuizItem b =
                QuizItem.of(
                        1,
                        TOPIC,
                        "stem",
                        Optional.of("different explanation"),
                        List.of(opt(1, "x", true), opt(2, "y", false)));
        QuizItem differentOrder =
                QuizItem.of(
                        2,
                        TOPIC,
                        "stem",
                        Optional.empty(),
                        List.of(opt(1, "a", true), opt(2, "b", false)));

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(differentOrder);
    }

    @Test
    void toStringTruncatesLongStem() {
        String longStem = "x".repeat(120);
        QuizItem q =
                QuizItem.of(
                        1,
                        TOPIC,
                        longStem,
                        Optional.empty(),
                        List.of(opt(1, "a", true), opt(2, "b", false)));

        String s = q.toString();

        assertThat(s).contains("...").contains("options=2");
        assertThat(s.length()).isLessThan(200);
    }
}
