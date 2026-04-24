package com.plrs.domain.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.AuditFields;
import com.plrs.domain.common.DomainInvariantException;
import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ContentDraftTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-04-25T10:00:00Z"), ZoneOffset.UTC);
    private static final TopicId TOPIC = TopicId.of(1L);

    private static AuditFields audit() {
        return AuditFields.initial("system", CLOCK);
    }

    private static ContentDraft draft(ContentType ctype) {
        return new ContentDraft(
                TOPIC,
                "Intro",
                ctype,
                Difficulty.BEGINNER,
                15,
                "https://example.com/x",
                Optional.of("desc"),
                Set.of("algebra"),
                Optional.of(UserId.newId()),
                audit());
    }

    private static ContentDraft withEstMinutes(int estMinutes) {
        return new ContentDraft(
                TOPIC,
                "title",
                ContentType.VIDEO,
                Difficulty.BEGINNER,
                estMinutes,
                "https://x.y",
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                audit());
    }

    private static ContentDraft withUrl(String url) {
        return new ContentDraft(
                TOPIC,
                "title",
                ContentType.VIDEO,
                Difficulty.BEGINNER,
                10,
                url,
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                audit());
    }

    private static ContentDraft withTitle(String title) {
        return new ContentDraft(
                TOPIC,
                title,
                ContentType.VIDEO,
                Difficulty.BEGINNER,
                10,
                "https://x.y",
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                audit());
    }

    private static ContentDraft withTags(Set<String> tags) {
        return new ContentDraft(
                TOPIC,
                "title",
                ContentType.VIDEO,
                Difficulty.BEGINNER,
                10,
                "https://x.y",
                Optional.empty(),
                tags,
                Optional.empty(),
                audit());
    }

    @Test
    void validVideoDraftConstructs() {
        ContentDraft d = draft(ContentType.VIDEO);

        assertThat(d.ctype()).isEqualTo(ContentType.VIDEO);
        assertThat(d.title()).isEqualTo("Intro");
        assertThat(d.tags()).containsExactly("algebra");
    }

    @Test
    void validArticleDraftConstructs() {
        assertThat(draft(ContentType.ARTICLE).ctype()).isEqualTo(ContentType.ARTICLE);
    }

    @Test
    void validExerciseDraftConstructs() {
        assertThat(draft(ContentType.EXERCISE).ctype()).isEqualTo(ContentType.EXERCISE);
    }

    @Test
    void rejectsQuizCtypeWithPointerToNewQuizFactory() {
        assertThatThrownBy(() -> draft(ContentType.QUIZ))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("QUIZ")
                .hasMessageContaining("Content.newQuiz");
    }

    @Test
    void acceptsEmptyTagSet() {
        ContentDraft d = withTags(Set.of());

        assertThat(d.tags()).isEmpty();
    }

    @Test
    void acceptsEmptyDescription() {
        ContentDraft d =
                new ContentDraft(
                        TOPIC,
                        "title",
                        ContentType.VIDEO,
                        Difficulty.BEGINNER,
                        10,
                        "https://x.y",
                        Optional.empty(),
                        Set.of(),
                        Optional.empty(),
                        audit());

        assertThat(d.description()).isEmpty();
    }

    @Test
    void trimsTitleAndTags() {
        ContentDraft d =
                new ContentDraft(
                        TOPIC,
                        "  padded  ",
                        ContentType.VIDEO,
                        Difficulty.BEGINNER,
                        10,
                        "https://x.y",
                        Optional.empty(),
                        Set.of("  tag-a  "),
                        Optional.empty(),
                        audit());

        assertThat(d.title()).isEqualTo("padded");
        assertThat(d.tags()).containsExactly("tag-a");
    }

    @Test
    void rejectsBlankTitle() {
        assertThatThrownBy(() -> withTitle("   "))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rejectsTitleOver200Chars() {
        assertThatThrownBy(() -> withTitle("x".repeat(201)))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("200");
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 601, 1000, Integer.MAX_VALUE})
    void rejectsEstMinutesOutsideRange(int bad) {
        assertThatThrownBy(() -> withEstMinutes(bad))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("[1, 600]");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 15, 300, 600})
    void acceptsEstMinutesInsideRange(int good) {
        assertThat(withEstMinutes(good).estMinutes()).isEqualTo(good);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ftp://example.com",
                "file:///etc/passwd",
                "javascript:alert(1)",
                "not-a-url",
                "://no-scheme.example.com"
            })
    void rejectsUrlWithoutHttpScheme(String badUrl) {
        assertThatThrownBy(() -> withUrl(badUrl))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("http");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://x.y", "https://example.com/path?q=1"})
    void acceptsHttpAndHttpsUrls(String good) {
        assertThat(withUrl(good).url()).isEqualTo(good);
    }

    @Test
    void rejectsBlankTag() {
        assertThatThrownBy(() -> withTags(Set.of("   ")))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rejectsTagOver60Chars() {
        assertThatThrownBy(() -> withTags(Set.of("x".repeat(61))))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("60");
    }

    @Test
    void rejectsNullRequiredFields() {
        assertThatThrownBy(
                        () -> new ContentDraft(
                                null,
                                "t",
                                ContentType.VIDEO,
                                Difficulty.BEGINNER,
                                10,
                                "https://x.y",
                                Optional.empty(),
                                Set.of(),
                                Optional.empty(),
                                audit()))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("topicId");
        assertThatThrownBy(
                        () -> new ContentDraft(
                                TOPIC,
                                null,
                                ContentType.VIDEO,
                                Difficulty.BEGINNER,
                                10,
                                "https://x.y",
                                Optional.empty(),
                                Set.of(),
                                Optional.empty(),
                                audit()))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("title");
        assertThatThrownBy(
                        () -> new ContentDraft(
                                TOPIC,
                                "t",
                                null,
                                Difficulty.BEGINNER,
                                10,
                                "https://x.y",
                                Optional.empty(),
                                Set.of(),
                                Optional.empty(),
                                audit()))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("ctype");
    }

    @Test
    void rejectsNullCreatedByOptional() {
        assertThatThrownBy(
                        () -> new ContentDraft(
                                TOPIC,
                                "t",
                                ContentType.VIDEO,
                                Difficulty.BEGINNER,
                                10,
                                "https://x.y",
                                Optional.empty(),
                                Set.of(),
                                null,
                                audit()))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("createdBy");
    }
}
