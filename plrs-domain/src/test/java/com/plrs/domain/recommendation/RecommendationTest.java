package com.plrs.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.DomainInvariantException;
import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.user.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecommendationTest {

    private static final Instant T0 = Instant.parse("2026-04-25T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final UserId USER =
            UserId.of(UUID.fromString("11111111-2222-3333-4444-555555555555"));
    private static final ContentId CONTENT = ContentId.of(42L);
    private static final RecommendationScore SCORE = RecommendationScore.of(0.7321);
    private static final RecommendationReason REASON =
            new RecommendationReason("Because you mastered Algebra");

    private static Recommendation fresh() {
        return Recommendation.create(USER, CONTENT, SCORE, 3, REASON, "popularity_v1", CLOCK);
    }

    @Test
    void createPopulatesEveryField() {
        Recommendation r = fresh();

        assertThat(r.userId()).isEqualTo(USER);
        assertThat(r.contentId()).isEqualTo(CONTENT);
        assertThat(r.createdAt()).isEqualTo(T0);
        assertThat(r.score()).isEqualTo(SCORE);
        assertThat(r.rankPosition()).isEqualTo(3);
        assertThat(r.reason()).isEqualTo(REASON);
        assertThat(r.modelVariant()).isEqualTo("popularity_v1");
        assertThat(r.clickedAt()).isEmpty();
        assertThat(r.completedAt()).isEmpty();
    }

    @Test
    void createWithClockUsesClockInstant() {
        Clock other = Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC);
        Recommendation r =
                Recommendation.create(USER, CONTENT, SCORE, 1, REASON, "popularity_v1", other);
        assertThat(r.createdAt()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
    }

    @Test
    void rejectsRankBelow1OrAbove50() {
        assertThatThrownBy(
                        () ->
                                Recommendation.create(
                                        USER, CONTENT, SCORE, 0, REASON,
                                        "popularity_v1", CLOCK))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("[1, 50]");
        assertThatThrownBy(
                        () ->
                                Recommendation.create(
                                        USER, CONTENT, SCORE, 51, REASON,
                                        "popularity_v1", CLOCK))
                .isInstanceOf(DomainInvariantException.class);
    }

    @Test
    void rejectsModelVariantOver30Chars() {
        String tooLong = "a".repeat(31);
        assertThatThrownBy(
                        () ->
                                Recommendation.create(
                                        USER, CONTENT, SCORE, 1, REASON, tooLong, CLOCK))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("at most 30");
    }

    @Test
    void rejectsBlankModelVariant() {
        assertThatThrownBy(
                        () ->
                                Recommendation.create(
                                        USER, CONTENT, SCORE, 1, REASON, "   ", CLOCK))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsNullCoreFields() {
        assertThatThrownBy(
                        () ->
                                Recommendation.create(
                                        null, CONTENT, SCORE, 1, REASON,
                                        "popularity_v1", CLOCK))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(
                        () ->
                                Recommendation.create(
                                        USER, null, SCORE, 1, REASON,
                                        "popularity_v1", CLOCK))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(
                        () ->
                                Recommendation.create(
                                        USER, CONTENT, null, 1, REASON,
                                        "popularity_v1", CLOCK))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(
                        () ->
                                Recommendation.create(
                                        USER, CONTENT, SCORE, 1, null,
                                        "popularity_v1", CLOCK))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(
                        () -> Recommendation.create(USER, CONTENT, SCORE, 1, REASON, "v1", null))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void recordClickReturnsNewInstanceWithClickedAtSet() {
        Recommendation r = fresh();
        Instant clickAt = T0.plusSeconds(60);

        Recommendation clicked = r.recordClick(clickAt);

        assertThat(r.clickedAt()).isEmpty();
        assertThat(clicked.clickedAt()).contains(clickAt);
        assertThat(clicked).isNotSameAs(r);
        assertThat(clicked.userId()).isEqualTo(r.userId());
        assertThat(clicked.score()).isEqualTo(r.score());
    }

    @Test
    void recordClickIsIdempotentForAlreadyClickedRec() {
        Recommendation r = fresh().recordClick(T0.plusSeconds(60));
        Recommendation again = r.recordClick(T0.plusSeconds(120));
        assertThat(again).isSameAs(r);
        assertThat(again.clickedAt()).contains(T0.plusSeconds(60));
    }

    @Test
    void recordClickRejectsTimestampBeforeCreatedAt() {
        Recommendation r = fresh();
        assertThatThrownBy(() -> r.recordClick(T0.minusSeconds(1)))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining(">= createdAt");
    }

    @Test
    void recordClickRejectedOnCompletedRecommendation() {
        Recommendation completed =
                Recommendation.rehydrate(
                        USER,
                        CONTENT,
                        T0,
                        SCORE,
                        1,
                        REASON,
                        "popularity_v1",
                        Optional.of(T0.plusSeconds(30)),
                        Optional.of(T0.plusSeconds(120)));
        assertThatThrownBy(() -> completed.recordClick(T0.plusSeconds(200)))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("already completed");
    }

    @Test
    void rehydrateRejectsCompletedWithoutClicked() {
        assertThatThrownBy(
                        () ->
                                Recommendation.rehydrate(
                                        USER,
                                        CONTENT,
                                        T0,
                                        SCORE,
                                        1,
                                        REASON,
                                        "popularity_v1",
                                        Optional.empty(),
                                        Optional.of(T0.plusSeconds(60))))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("completedAt is set but clickedAt is empty");
    }

    @Test
    void rehydrateRejectsCompletedBeforeClicked() {
        assertThatThrownBy(
                        () ->
                                Recommendation.rehydrate(
                                        USER,
                                        CONTENT,
                                        T0,
                                        SCORE,
                                        1,
                                        REASON,
                                        "popularity_v1",
                                        Optional.of(T0.plusSeconds(60)),
                                        Optional.of(T0.plusSeconds(30))))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining(">= clickedAt");
    }

    @Test
    void equalityIsCompositeNaturalKey() {
        Recommendation a = fresh();
        Recommendation b =
                Recommendation.rehydrate(
                        USER,
                        CONTENT,
                        T0,
                        RecommendationScore.of(0.10),
                        1,
                        new RecommendationReason("Different reason"),
                        "experiment_v2",
                        Optional.of(T0.plusSeconds(5)),
                        Optional.empty());
        // Same (userId, contentId, createdAt) → equal even though
        // every other field differs.
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());

        Recommendation different =
                Recommendation.create(
                        USER,
                        ContentId.of(99L),
                        SCORE,
                        1,
                        REASON,
                        "popularity_v1",
                        CLOCK);
        assertThat(a).isNotEqualTo(different);
    }
}
