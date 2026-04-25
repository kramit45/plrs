package com.plrs.application.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.plrs.domain.content.ContentId;
import com.plrs.domain.recommendation.Recommendation;
import com.plrs.domain.recommendation.RecommendationReason;
import com.plrs.domain.recommendation.RecommendationRepository;
import com.plrs.domain.recommendation.RecommendationScore;
import com.plrs.domain.user.UserId;
import com.plrs.domain.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GenerateRecommendationsUseCaseTest {

    private static final Instant T0 = Instant.parse("2026-04-25T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final UserId USER =
            UserId.of(UUID.fromString("11111111-2222-3333-4444-555555555555"));

    @Mock private RecommendationService recommendationService;
    @Mock private RecommendationRepository recommendationRepository;
    @Mock private UserRepository userRepository;
    @Mock private TopNCacheStore cacheStore;

    private GenerateRecommendationsUseCase useCase() {
        return new GenerateRecommendationsUseCase(
                recommendationService,
                recommendationRepository,
                userRepository,
                cacheStore,
                CLOCK);
    }

    private Recommendation rec(long contentId, int rank, double score, String variant) {
        return Recommendation.rehydrate(
                USER,
                ContentId.of(contentId),
                T0.minusSeconds(60L * rank),
                RecommendationScore.of(score),
                rank,
                new RecommendationReason("reason " + contentId),
                variant,
                Optional.empty(),
                Optional.empty());
    }

    @Test
    void cacheHitReturnsCachedAndDoesNotInvokeService() {
        long version = 5L;
        List<Recommendation> cached =
                List.of(
                        rec(1L, 1, 0.9, "popularity_v1"),
                        rec(2L, 2, 0.7, "popularity_v1"),
                        rec(3L, 3, 0.5, "popularity_v1"));
        when(userRepository.getSkillsVersion(USER)).thenReturn(version);
        when(cacheStore.get(USER))
                .thenReturn(Optional.of(new CachedTopN(version, cached, T0)));

        List<Recommendation> out = useCase().handle(USER, 3);

        assertThat(out).hasSize(3);
        assertThat(out).extracting(r -> r.contentId().value()).containsExactly(1L, 2L, 3L);
        verify(recommendationService, never()).generate(any(), org.mockito.ArgumentMatchers.anyInt(), any());
        verify(recommendationRepository, never()).saveAll(any());
        verify(cacheStore, never()).put(any(), any());
    }

    @Test
    void versionMismatchInvalidatesAndRecomputes() {
        long currentVersion = 7L;
        long staleVersion = 5L;
        List<Recommendation> cachedStale = List.of(rec(99L, 1, 0.9, "popularity_v1"));
        List<Recommendation> fresh = List.of(rec(1L, 1, 0.5, "popularity_v1"));
        when(userRepository.getSkillsVersion(USER)).thenReturn(currentVersion);
        when(cacheStore.get(USER))
                .thenReturn(Optional.of(new CachedTopN(staleVersion, cachedStale, T0)));
        when(recommendationService.generate(USER, 1, "popularity_v1")).thenReturn(fresh);

        List<Recommendation> out = useCase().handle(USER, 1);

        assertThat(out).extracting(r -> r.contentId().value()).containsExactly(1L);
        verify(recommendationRepository).saveAll(fresh);
        ArgumentCaptor<CachedTopN> putCap = ArgumentCaptor.forClass(CachedTopN.class);
        verify(cacheStore).put(eq(USER), putCap.capture());
        assertThat(putCap.getValue().version()).isEqualTo(currentVersion);
        assertThat(putCap.getValue().items()).isEqualTo(fresh);
    }

    @Test
    void cacheMissComputesPersistsAndStores() {
        long version = 1L;
        List<Recommendation> fresh =
                List.of(rec(1L, 1, 0.9, "popularity_v1"), rec(2L, 2, 0.7, "popularity_v1"));
        when(userRepository.getSkillsVersion(USER)).thenReturn(version);
        when(cacheStore.get(USER)).thenReturn(Optional.empty());
        when(recommendationService.generate(USER, 2, "popularity_v1")).thenReturn(fresh);

        List<Recommendation> out = useCase().handle(USER, 2);

        assertThat(out).isEqualTo(fresh);
        verify(recommendationRepository).saveAll(fresh);
        verify(cacheStore).put(eq(USER), any(CachedTopN.class));
    }

    @Test
    void cachedSlateLargerThanKIsTrimmed() {
        long version = 3L;
        List<Recommendation> cached =
                List.of(
                        rec(1L, 1, 0.9, "popularity_v1"),
                        rec(2L, 2, 0.7, "popularity_v1"),
                        rec(3L, 3, 0.5, "popularity_v1"));
        when(userRepository.getSkillsVersion(USER)).thenReturn(version);
        when(cacheStore.get(USER))
                .thenReturn(Optional.of(new CachedTopN(version, cached, T0)));

        List<Recommendation> out = useCase().handle(USER, 2);

        assertThat(out).hasSize(2);
        assertThat(out).extracting(r -> r.contentId().value()).containsExactly(1L, 2L);
        verifyNoInteractions(recommendationService);
    }

    @Test
    void cachedSlateSmallerThanKForcesRecompute() {
        long version = 3L;
        List<Recommendation> cachedSmall = List.of(rec(1L, 1, 0.9, "popularity_v1"));
        List<Recommendation> fresh =
                List.of(rec(1L, 1, 0.9, "popularity_v1"), rec(2L, 2, 0.7, "popularity_v1"));
        when(userRepository.getSkillsVersion(USER)).thenReturn(version);
        when(cacheStore.get(USER))
                .thenReturn(Optional.of(new CachedTopN(version, cachedSmall, T0)));
        when(recommendationService.generate(USER, 2, "popularity_v1")).thenReturn(fresh);

        List<Recommendation> out = useCase().handle(USER, 2);

        assertThat(out).isEqualTo(fresh);
        verify(recommendationRepository).saveAll(fresh);
    }

    @Test
    void rejectsZeroOrNegativeK() {
        assertThatThrownBy(() -> useCase().handle(USER, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> useCase().handle(USER, -3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cachedEntryStampsCurrentVersionEvenIfClockIsLater() {
        long version = 42L;
        List<Recommendation> fresh = List.of(rec(1L, 1, 0.5, "popularity_v1"));
        when(userRepository.getSkillsVersion(USER)).thenReturn(version);
        when(cacheStore.get(USER)).thenReturn(Optional.empty());
        when(recommendationService.generate(USER, 1, "popularity_v1")).thenReturn(fresh);

        useCase().handle(USER, 1);

        ArgumentCaptor<CachedTopN> cap = ArgumentCaptor.forClass(CachedTopN.class);
        verify(cacheStore).put(eq(USER), cap.capture());
        assertThat(cap.getValue().version()).isEqualTo(version);
        assertThat(cap.getValue().computedAt()).isEqualTo(T0);
    }
}
