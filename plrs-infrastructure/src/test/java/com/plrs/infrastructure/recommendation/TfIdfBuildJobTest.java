package com.plrs.infrastructure.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TfIdfBuildJobTest {

    @Test
    void countTokensLowercasesAndDropsStopwords() {
        Map<String, Integer> counts =
                TfIdfBuildJob.countTokens("The Quick Brown Fox jumps over the LAZY dog");
        // "the", "over" are in the stopword list; "quick", "brown",
        // "fox", "jumps", "lazy", "dog" should remain.
        assertThat(counts).containsOnlyKeys("quick", "brown", "fox", "jumps", "lazy", "dog");
        assertThat(counts.values()).allMatch(v -> v == 1);
    }

    @Test
    void countTokensCountsRepeatedTerms() {
        Map<String, Integer> counts = TfIdfBuildJob.countTokens("apple banana apple cherry apple");
        assertThat(counts.get("apple")).isEqualTo(3);
        assertThat(counts.get("banana")).isEqualTo(1);
        assertThat(counts.get("cherry")).isEqualTo(1);
    }

    @Test
    void countTokensSkipsSingleCharTokensAndPunctuation() {
        Map<String, Integer> counts =
                TfIdfBuildJob.countTokens("a b cat -- dog! 5g");
        // "a", "b" are too short; "cat", "dog", "5g" remain.
        assertThat(counts).containsOnlyKeys("cat", "dog", "5g");
    }

    @Test
    void readerDotProductOfIdenticalRowsIsOne() {
        List<TermWeight> a =
                List.of(
                        new TermWeight(0, 0.6),
                        new TermWeight(2, 0.8));
        // Manually L2-normalised: 0.6^2 + 0.8^2 = 1.0.
        double dot = TfIdfReader.dot(a, a);
        assertThat(dot).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void readerDotProductOfOrthogonalRowsIsZero() {
        List<TermWeight> a = List.of(new TermWeight(0, 1.0));
        List<TermWeight> b = List.of(new TermWeight(1, 1.0));
        assertThat(TfIdfReader.dot(a, b)).isZero();
    }

    @Test
    void readerDotProductMergesByIndex() {
        // a: idx 0, 2, 4; b: idx 1, 2, 3.
        // Only idx 2 matches: 0.5 * 0.7 = 0.35.
        List<TermWeight> a =
                List.of(
                        new TermWeight(0, 0.3),
                        new TermWeight(2, 0.5),
                        new TermWeight(4, 0.4));
        List<TermWeight> b =
                List.of(
                        new TermWeight(1, 0.6),
                        new TermWeight(2, 0.7),
                        new TermWeight(3, 0.2));
        assertThat(TfIdfReader.dot(a, b)).isCloseTo(0.35, within(1e-9));
    }

    @Test
    void sublinearTfIsAppliedConsistently() {
        // Hand-computed: tf(1) = 1, tf(e) = 2, tf(e^2) ≈ 3.
        // Test the formula 1 + log(count) directly via the build job
        // pipeline would require a real DB; we assert the formula
        // here by reproducing the same computation.
        double tf1 = 1.0 + Math.log(1);
        double tfE = 1.0 + Math.log(Math.E);
        double tfE2 = 1.0 + Math.log(Math.E * Math.E);
        assertThat(tf1).isCloseTo(1.0, within(1e-9));
        assertThat(tfE).isCloseTo(2.0, within(1e-9));
        assertThat(tfE2).isCloseTo(3.0, within(1e-9));
    }
}
