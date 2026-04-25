package com.plrs.domain.mastery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.plrs.domain.common.DomainInvariantException;
import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class UserSkillTest {

    private static final UserId USER = UserId.newId();
    private static final TopicId TOPIC = TopicId.of(1L);
    private static final Instant T0 = Instant.parse("2026-04-25T10:00:00Z");
    private static final Clock CLOCK_T0 = Clock.fixed(T0, ZoneOffset.UTC);

    @Test
    void initialSetsNeutralMasteryAndConfidence() {
        UserSkill s = UserSkill.initial(USER, TOPIC, CLOCK_T0);

        assertThat(s.mastery().value()).isEqualTo(0.5);
        assertThat(s.confidence()).isEqualByComparingTo("0.100");
        assertThat(s.updatedAt()).isEqualTo(T0);
    }

    @Test
    void applyEwmaUsesBlendWithCorrectly() {
        // Golden values from §2.e.2.4.3:
        // current = 0.62, other = 0.80, alpha = 0.24 → 0.6632
        UserSkill skill =
                UserSkill.rehydrate(
                        USER, TOPIC, MasteryScore.of(0.62), new BigDecimal("0.500"), T0);

        UserSkill updated =
                skill.applyEwma(new BigDecimal("0.80"), 0.24, CLOCK_T0);

        assertThat(updated.mastery().value()).isCloseTo(0.6632, within(1e-9));
    }

    @Test
    void applyEwmaIncrementsConfidenceByTenth() {
        UserSkill skill =
                UserSkill.rehydrate(
                        USER, TOPIC, MasteryScore.NEUTRAL, new BigDecimal("0.500"), T0);

        UserSkill updated = skill.applyEwma(new BigDecimal("0.5"), 0.1, CLOCK_T0);

        assertThat(updated.confidence()).isEqualByComparingTo("0.600");
    }

    @Test
    void applyEwmaCapsConfidenceAtOne() {
        UserSkill skill =
                UserSkill.rehydrate(
                        USER, TOPIC, MasteryScore.NEUTRAL, new BigDecimal("0.950"), T0);

        UserSkill updated = skill.applyEwma(new BigDecimal("1.0"), 0.1, CLOCK_T0);

        assertThat(updated.confidence()).isEqualByComparingTo("1.000");
    }

    @Test
    void applyEwmaCapsAtAlreadyOne() {
        UserSkill skill =
                UserSkill.rehydrate(
                        USER, TOPIC, MasteryScore.NEUTRAL, new BigDecimal("1.000"), T0);

        UserSkill updated = skill.applyEwma(new BigDecimal("0.5"), 0.1, CLOCK_T0);

        assertThat(updated.confidence()).isEqualByComparingTo("1.000");
    }

    @Test
    void applyEwmaRejectsScoreFractionAboveOne() {
        UserSkill skill = UserSkill.initial(USER, TOPIC, CLOCK_T0);

        assertThatThrownBy(() -> skill.applyEwma(new BigDecimal("1.5"), 0.1, CLOCK_T0))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("scoreFraction");
    }

    @Test
    void applyEwmaRejectsNegativeAlpha() {
        UserSkill skill = UserSkill.initial(USER, TOPIC, CLOCK_T0);

        assertThatThrownBy(() -> skill.applyEwma(new BigDecimal("0.5"), -0.1, CLOCK_T0))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("alphaEffective");
    }

    @Test
    void applyEwmaRejectsAlphaAboveOne() {
        UserSkill skill = UserSkill.initial(USER, TOPIC, CLOCK_T0);

        assertThatThrownBy(() -> skill.applyEwma(new BigDecimal("0.5"), 1.5, CLOCK_T0))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("alphaEffective");
    }

    @Test
    void applyEwmaUpdatesUpdatedAtViaClock() {
        Instant t1 = Instant.parse("2026-12-31T23:59:59Z");
        Clock clockT1 = Clock.fixed(t1, ZoneOffset.UTC);
        UserSkill skill = UserSkill.initial(USER, TOPIC, CLOCK_T0);

        UserSkill updated = skill.applyEwma(new BigDecimal("0.7"), 0.1, clockT1);

        assertThat(updated.updatedAt()).isEqualTo(t1);
    }

    @Test
    void immutabilityOriginalUnchangedAfterApplyEwma() {
        UserSkill skill = UserSkill.initial(USER, TOPIC, CLOCK_T0);

        skill.applyEwma(new BigDecimal("0.7"), 0.1, CLOCK_T0);

        assertThat(skill.mastery().value()).isEqualTo(0.5);
        assertThat(skill.confidence()).isEqualByComparingTo("0.100");
    }

    @Test
    void rehydrateRoundTripsAllFields() {
        UserSkill s =
                UserSkill.rehydrate(
                        USER, TOPIC, MasteryScore.of(0.7), new BigDecimal("0.300"), T0);

        assertThat(s.userId()).isEqualTo(USER);
        assertThat(s.topicId()).isEqualTo(TOPIC);
        assertThat(s.mastery().value()).isEqualTo(0.7);
        assertThat(s.confidence()).isEqualByComparingTo("0.300");
        assertThat(s.updatedAt()).isEqualTo(T0);
    }

    @Test
    void rejectsConfidenceOutOfRange() {
        assertThatThrownBy(
                        () ->
                                UserSkill.rehydrate(
                                        USER,
                                        TOPIC,
                                        MasteryScore.NEUTRAL,
                                        new BigDecimal("1.500"),
                                        T0))
                .isInstanceOf(DomainInvariantException.class);
        assertThatThrownBy(
                        () ->
                                UserSkill.rehydrate(
                                        USER,
                                        TOPIC,
                                        MasteryScore.NEUTRAL,
                                        new BigDecimal("-0.001"),
                                        T0))
                .isInstanceOf(DomainInvariantException.class);
    }

    @Test
    void equalsAndHashCodeUseNaturalKey() {
        UserSkill a =
                UserSkill.rehydrate(
                        USER, TOPIC, MasteryScore.of(0.5), new BigDecimal("0.100"), T0);
        UserSkill b =
                UserSkill.rehydrate(
                        USER,
                        TOPIC,
                        MasteryScore.of(0.9),
                        new BigDecimal("0.500"),
                        T0.plusSeconds(60));
        UserSkill different =
                UserSkill.rehydrate(
                        USER, TopicId.of(99L), MasteryScore.NEUTRAL, new BigDecimal("0.100"), T0);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(different);
    }
}
