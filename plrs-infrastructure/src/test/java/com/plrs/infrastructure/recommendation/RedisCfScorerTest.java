package com.plrs.infrastructure.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plrs.application.recommendation.ArtifactPayload;
import com.plrs.application.recommendation.ArtifactRepository;
import com.plrs.application.recommendation.SimNeighbour;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.interaction.InteractionEvent;
import com.plrs.domain.interaction.InteractionRepository;
import com.plrs.domain.user.UserId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class RedisCfScorerTest {

    private static final UserId USER =
            UserId.of(UUID.fromString("11111111-2222-3333-4444-555555555555"));
    private static final Instant T0 = Instant.parse("2026-04-25T10:00:00Z");

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private InteractionRepository interactionRepository;
    @Mock private ArtifactRepository artifactRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RedisCfScorer scorer() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        return new RedisCfScorer(redis, interactionRepository, artifactRepository, objectMapper);
    }

    private static InteractionEvent completedAt(long contentId, Instant at) {
        return InteractionEvent.complete(
                USER,
                ContentId.of(contentId),
                at,
                Optional.empty(),
                Optional.empty());
    }

    @Test
    void emptyHistoryYieldsAllZeros() {
        when(interactionRepository.findRecentPositives(eq(USER), anyInt(), anyInt()))
                .thenReturn(List.of());

        Map<ContentId, Double> out =
                scorer()
                        .score(
                                USER,
                                Set.of(ContentId.of(1L), ContentId.of(2L)));

        assertThat(out)
                .containsOnlyKeys(ContentId.of(1L), ContentId.of(2L))
                .allSatisfy((id, score) -> assertThat(score).isZero());
    }

    @Test
    void candidateAlreadyInHistoryScoresZero() throws Exception {
        when(interactionRepository.findRecentPositives(eq(USER), anyInt(), anyInt()))
                .thenReturn(List.of(completedAt(42L, T0)));
        // Even if the slab references item 42, the candidate==42 short-circuits.

        Map<ContentId, Double> out = scorer().score(USER, Set.of(ContentId.of(42L)));

        assertThat(out.get(ContentId.of(42L))).isZero();
    }

    @Test
    void candidateWithNoSlabScoresZero() {
        when(interactionRepository.findRecentPositives(eq(USER), anyInt(), anyInt()))
                .thenReturn(List.of(completedAt(10L, T0)));
        when(valueOps.get(anyString())).thenReturn(null);
        when(artifactRepository.find(eq("SIM_SLAB"), anyString())).thenReturn(Optional.empty());

        Map<ContentId, Double> out = scorer().score(USER, Set.of(ContentId.of(99L)));

        assertThat(out.get(ContentId.of(99L))).isZero();
    }

    @Test
    void normalisesScoresWithMostSimilarCandidateAtOne() throws Exception {
        // History: completed item 10 and 11.
        when(interactionRepository.findRecentPositives(eq(USER), anyInt(), anyInt()))
                .thenReturn(List.of(completedAt(10L, T0), completedAt(11L, T0)));
        // Candidate 100's slab has neighbours 10 (sim 0.9) and 11 (sim 0.7).
        // Average match = 0.8.
        // Candidate 200's slab has only 10 (sim 0.4). Average match = 0.4.
        // Candidate 300's slab has 5 (sim 0.95) — not in history, no matches.
        String slab100 =
                objectMapper.writeValueAsString(
                        List.of(
                                new SimNeighbour(10L, 0.9),
                                new SimNeighbour(11L, 0.7)));
        String slab200 =
                objectMapper.writeValueAsString(List.of(new SimNeighbour(10L, 0.4)));
        String slab300 =
                objectMapper.writeValueAsString(List.of(new SimNeighbour(5L, 0.95)));
        when(valueOps.get("sim:item:100")).thenReturn(slab100);
        when(valueOps.get("sim:item:200")).thenReturn(slab200);
        when(valueOps.get("sim:item:300")).thenReturn(slab300);

        Map<ContentId, Double> out =
                scorer()
                        .score(
                                USER,
                                Set.of(ContentId.of(100L), ContentId.of(200L), ContentId.of(300L)));

        // 0.8 / 0.8 = 1.0 for top, 0.4 / 0.8 = 0.5 for mid, 0.0 for no-match.
        assertThat(out.get(ContentId.of(100L))).isCloseTo(1.0, within(1e-9));
        assertThat(out.get(ContentId.of(200L))).isCloseTo(0.5, within(1e-9));
        assertThat(out.get(ContentId.of(300L))).isZero();
    }

    @Test
    void slabFallsBackToArtifactRepositoryAndWarmsRedis() throws Exception {
        when(interactionRepository.findRecentPositives(eq(USER), anyInt(), anyInt()))
                .thenReturn(List.of(completedAt(10L, T0)));
        when(valueOps.get("sim:item:100")).thenReturn(null);
        String slab =
                objectMapper.writeValueAsString(List.of(new SimNeighbour(10L, 0.5)));
        when(artifactRepository.find("SIM_SLAB", "100"))
                .thenReturn(
                        Optional.of(
                                ArtifactPayload.ofString(
                                        "SIM_SLAB", "100", slab, "v1", T0)));

        Map<ContentId, Double> out = scorer().score(USER, Set.of(ContentId.of(100L)));

        assertThat(out.get(ContentId.of(100L))).isCloseTo(1.0, within(1e-9));
        // Verify the warm-write was attempted.
        org.mockito.Mockito.verify(valueOps).set(eq("sim:item:100"), eq(slab), any());
    }

    @Test
    void emptyCandidateSetReturnsEmpty() {
        Map<ContentId, Double> out = scorer().score(USER, Set.of());
        assertThat(out).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(interactionRepository);
    }
}
