package com.plrs.application.dashboard;

import com.plrs.domain.interaction.InteractionRepository;
import com.plrs.domain.user.UserId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Builds the FR-35 weekly activity sparkline. Returns exactly
 * {@link #WEEKS_BACK} buckets (one per ISO week, oldest first), each
 * with the count of interactions for that learner in that week — zero
 * if none.
 *
 * <p>The "now" used to anchor the 8-week window is read from the
 * injected {@link Clock} so tests can pin a deterministic moment.
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")}
 * to keep the bean out of the no-DB smoke test.
 *
 * <p>Traces to: FR-35 (weekly activity sparkline).
 */
@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class WeeklyActivityService {

    /** Number of ISO weeks the sparkline covers. */
    public static final int WEEKS_BACK = 8;

    private final InteractionRepository interactionRepository;
    private final Clock clock;

    public WeeklyActivityService(InteractionRepository interactionRepository, Clock clock) {
        this.interactionRepository = interactionRepository;
        this.clock = clock;
    }

    public List<WeeklyBucket> last8Weeks(UserId userId) {
        Instant now = Instant.now(clock);
        Instant since = now.minus(Duration.ofDays(7L * WEEKS_BACK));
        Map<String, Integer> counts = interactionRepository.countByIsoWeekSince(userId, since);

        // Build the canonical sequence of ISO year-week keys ending at
        // the current week, oldest first. Fill with zero where the
        // adapter didn't return a row.
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        List<WeeklyBucket> buckets = new ArrayList<>(WEEKS_BACK);
        for (int i = WEEKS_BACK - 1; i >= 0; i--) {
            LocalDate inWeek = today.minusWeeks(i);
            String key = isoYearWeekKey(inWeek);
            buckets.add(new WeeklyBucket(key, counts.getOrDefault(key, 0)));
        }
        return buckets;
    }

    private static String isoYearWeekKey(LocalDate date) {
        int year = date.get(IsoFields.WEEK_BASED_YEAR);
        int week = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        return String.format(Locale.ROOT, "%04d-%02d", year, week);
    }
}
