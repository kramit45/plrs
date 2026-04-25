package com.plrs.application.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.plrs.domain.interaction.InteractionRepository;
import com.plrs.domain.user.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WeeklyActivityServiceTest {

    /**
     * 2026-04-25 is a Saturday in ISO week 17 of 2026 — picked so the
     * 8-week window straddles a year boundary cleanly inside the same
     * year. Each of the 7 prior weeks is +/-7 days back.
     */
    private static final Instant T0 = Instant.parse("2026-04-25T10:00:00Z");

    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final UserId USER_ID =
            UserId.of(UUID.fromString("11111111-2222-3333-4444-555555555555"));

    @Mock private InteractionRepository repo;

    private WeeklyActivityService service() {
        return new WeeklyActivityService(repo, CLOCK);
    }

    @Test
    void returnsExactly8BucketsWhenRepoReturnsNothing() {
        when(repo.countByIsoWeekSince(eq(USER_ID), any(Instant.class)))
                .thenReturn(Map.of());

        List<WeeklyBucket> out = service().last8Weeks(USER_ID);

        assertThat(out).hasSize(8);
        assertThat(out).allSatisfy(b -> assertThat(b.count()).isZero());
    }

    @Test
    void bucketsAreOrderedOldestFirstAndKeysFollowYyyyDashWwFormat() {
        when(repo.countByIsoWeekSince(eq(USER_ID), any(Instant.class)))
                .thenReturn(Map.of());

        List<WeeklyBucket> out = service().last8Weeks(USER_ID);

        assertThat(out)
                .extracting(WeeklyBucket::isoYearWeek)
                .isSorted()
                .last()
                .isEqualTo("2026-17");
        assertThat(out)
                .extracting(WeeklyBucket::isoYearWeek)
                .allSatisfy(k -> assertThat(k).matches("\\d{4}-\\d{2}"));
    }

    @Test
    void zeroFillsMissingWeeksAndCopiesPresentCounts() {
        // Repo returns counts for the current week and one earlier week.
        when(repo.countByIsoWeekSince(eq(USER_ID), any(Instant.class)))
                .thenReturn(Map.of("2026-17", 5, "2026-15", 2));

        List<WeeklyBucket> out = service().last8Weeks(USER_ID);

        WeeklyBucket current = out.get(7);
        assertThat(current.isoYearWeek()).isEqualTo("2026-17");
        assertThat(current.count()).isEqualTo(5);
        WeeklyBucket twoBack = out.get(5);
        assertThat(twoBack.isoYearWeek()).isEqualTo("2026-15");
        assertThat(twoBack.count()).isEqualTo(2);
        // Every other week is zero.
        for (int i = 0; i < 8; i++) {
            if (i == 7 || i == 5) {
                continue;
            }
            assertThat(out.get(i).count()).as("bucket " + i).isZero();
        }
    }

    @Test
    void ignoresExtraKeysOutsideTheWindow() {
        // The service's responsibility is the canonical 8-week sequence
        // — extra keys returned by the adapter (e.g. for a week older
        // than 8 weeks back) must not appear in the output.
        when(repo.countByIsoWeekSince(eq(USER_ID), any(Instant.class)))
                .thenReturn(Map.of("2025-30", 99));

        List<WeeklyBucket> out = service().last8Weeks(USER_ID);

        assertThat(out).hasSize(8);
        assertThat(out)
                .extracting(WeeklyBucket::isoYearWeek)
                .doesNotContain("2025-30");
    }

    @Test
    void requestsCountsForExactlyEightWeeksBack() {
        when(repo.countByIsoWeekSince(eq(USER_ID), any(Instant.class)))
                .thenReturn(Map.of());

        service().last8Weeks(USER_ID);

        org.mockito.ArgumentCaptor<Instant> sinceCaptor =
                org.mockito.ArgumentCaptor.forClass(Instant.class);
        org.mockito.Mockito.verify(repo).countByIsoWeekSince(eq(USER_ID), sinceCaptor.capture());
        Instant since = sinceCaptor.getValue();
        // 56 days = 8 weeks — exact equality, no off-by-one.
        assertThat(java.time.Duration.between(since, T0).toDays()).isEqualTo(56L);
    }
}
