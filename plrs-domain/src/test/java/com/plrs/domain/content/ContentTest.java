package com.plrs.domain.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.AuditFields;
import com.plrs.domain.common.DomainInvariantException;
import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.quiz.QuizItem;
import com.plrs.domain.quiz.QuizItemOption;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ContentTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-04-25T10:00:00Z"), ZoneOffset.UTC);
    private static final TopicId TOPIC = TopicId.of(1L);
    private static final UserId AUTHOR = UserId.newId();

    private static AuditFields audit() {
        return AuditFields.initial("system", CLOCK);
    }

    private static Content rehydrate(ContentType ctype) {
        return Content.rehydrate(
                ContentId.of(42L),
                TOPIC,
                "Intro to " + ctype.name(),
                ctype,
                Difficulty.BEGINNER,
                15,
                "https://example.com/" + ctype.name().toLowerCase(),
                Optional.of("a description"),
                Set.of("algebra", "warmup"),
                Optional.of(AUTHOR),
                audit());
    }

    @Test
    void rehydrateRoundTripsVideo() {
        Content content = rehydrate(ContentType.VIDEO);

        assertThat(content.id()).isEqualTo(ContentId.of(42L));
        assertThat(content.topicId()).isEqualTo(TOPIC);
        assertThat(content.title()).isEqualTo("Intro to VIDEO");
        assertThat(content.ctype()).isEqualTo(ContentType.VIDEO);
        assertThat(content.difficulty()).isEqualTo(Difficulty.BEGINNER);
        assertThat(content.estMinutes()).isEqualTo(15);
        assertThat(content.url()).isEqualTo("https://example.com/video");
        assertThat(content.description()).contains("a description");
        assertThat(content.tags()).containsExactlyInAnyOrder("algebra", "warmup");
        assertThat(content.createdBy()).contains(AUTHOR);
        assertThat(content.audit().createdBy()).isEqualTo("system");
    }

    @Test
    void rehydrateRoundTripsArticle() {
        Content content = rehydrate(ContentType.ARTICLE);

        assertThat(content.ctype()).isEqualTo(ContentType.ARTICLE);
    }

    @Test
    void rehydrateRoundTripsExercise() {
        Content content = rehydrate(ContentType.EXERCISE);

        assertThat(content.ctype()).isEqualTo(ContentType.EXERCISE);
    }

    private static QuizItem oneItem(int order) {
        return QuizItem.of(
                order,
                TOPIC,
                "stem-" + order,
                Optional.empty(),
                List.of(
                        new QuizItemOption(1, "a", true),
                        new QuizItemOption(2, "b", false)));
    }

    @Test
    void quizRehydrateWithItemsSucceeds() {
        Content quiz =
                Content.rehydrate(
                        ContentId.of(42L),
                        TOPIC,
                        "Intro to QUIZ",
                        ContentType.QUIZ,
                        Difficulty.BEGINNER,
                        15,
                        "https://example.com/quiz",
                        Optional.of("desc"),
                        Set.of(),
                        Optional.empty(),
                        audit(),
                        List.of(oneItem(1), oneItem(2)));

        assertThat(quiz.ctype()).isEqualTo(ContentType.QUIZ);
        assertThat(quiz.quizItems()).hasSize(2);
    }

    @Test
    void quizRehydrateWithoutItemsThrows() {
        // The 11-arg rehydrate defaults items to empty — incompatible with QUIZ.
        assertThatThrownBy(() -> rehydrate(ContentType.QUIZ))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("QUIZ must carry at least one QuizItem");
    }

    @Test
    void nonQuizRehydrateWithItemsThrows() {
        assertThatThrownBy(
                        () ->
                                Content.rehydrate(
                                        ContentId.of(42L),
                                        TOPIC,
                                        "title",
                                        ContentType.VIDEO,
                                        Difficulty.BEGINNER,
                                        10,
                                        "https://x.y",
                                        Optional.empty(),
                                        Set.of(),
                                        Optional.empty(),
                                        audit(),
                                        List.of(oneItem(1))))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("must not carry quiz items");
    }

    @Test
    void quizItemsAccessorReturnsUnmodifiableList() {
        Content quiz =
                Content.rehydrate(
                        ContentId.of(42L),
                        TOPIC,
                        "Quiz",
                        ContentType.QUIZ,
                        Difficulty.BEGINNER,
                        10,
                        "https://x.y",
                        Optional.empty(),
                        Set.of(),
                        Optional.empty(),
                        audit(),
                        List.of(oneItem(1)));

        assertThatThrownBy(() -> quiz.quizItems().add(oneItem(99)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nonQuizContentReturnsEmptyQuizItemsList() {
        Content video = rehydrate(ContentType.VIDEO);

        assertThat(video.quizItems()).isEmpty();
    }

    @Test
    void rehydrateRejectsNullDescriptionOptional() {
        assertThatThrownBy(
                        () -> Content.rehydrate(
                                ContentId.of(1L),
                                TOPIC,
                                "title",
                                ContentType.VIDEO,
                                Difficulty.BEGINNER,
                                10,
                                "https://x.y",
                                null,
                                Set.of(),
                                Optional.empty(),
                                audit()))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("description");
    }

    @Test
    void rehydrateRejectsEstMinutesAboveMax() {
        assertThatThrownBy(
                        () -> Content.rehydrate(
                                ContentId.of(1L),
                                TOPIC,
                                "title",
                                ContentType.VIDEO,
                                Difficulty.BEGINNER,
                                601,
                                "https://x.y",
                                Optional.empty(),
                                Set.of(),
                                Optional.empty(),
                                audit()))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("600");
    }

    @Test
    void equalsAndHashCodeAreIdBased() {
        Content a = rehydrate(ContentType.VIDEO);
        Content b =
                Content.rehydrate(
                        ContentId.of(42L),
                        TopicId.of(999L),
                        "Totally different title",
                        ContentType.ARTICLE,
                        Difficulty.ADVANCED,
                        600,
                        "https://other.example.com",
                        Optional.empty(),
                        Set.of(),
                        Optional.empty(),
                        audit());
        Content other =
                Content.rehydrate(
                        ContentId.of(43L),
                        TOPIC,
                        "Intro to VIDEO",
                        ContentType.VIDEO,
                        Difficulty.BEGINNER,
                        15,
                        "https://example.com/video",
                        Optional.of("a description"),
                        Set.of("algebra", "warmup"),
                        Optional.of(AUTHOR),
                        audit());

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(other);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("not a Content");
    }

    @Test
    void toStringExcludesUrl() {
        Content content = rehydrate(ContentType.VIDEO);

        String str = content.toString();

        assertThat(str)
                .contains("42")
                .contains("Intro to VIDEO")
                .contains("VIDEO")
                .contains("TopicId(1)");
        assertThat(str).doesNotContain("https://").doesNotContain("example.com");
    }

    @Test
    void tagsAccessorReturnsImmutableSet() {
        Content content = rehydrate(ContentType.VIDEO);

        Set<String> tags = content.tags();

        assertThatThrownBy(() -> tags.add("extra")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void tagsAreCopiedSoExternalMutationDoesNotLeak() {
        Set<String> mutableTags = new HashSet<>(Set.of("algebra"));

        Content content =
                Content.rehydrate(
                        ContentId.of(1L),
                        TOPIC,
                        "title",
                        ContentType.VIDEO,
                        Difficulty.BEGINNER,
                        10,
                        "https://x.y",
                        Optional.empty(),
                        mutableTags,
                        Optional.empty(),
                        audit());

        mutableTags.add("mutated-after-the-fact");

        assertThat(content.tags()).containsExactly("algebra");
    }
}
