package com.plrs.web.admin;

import com.plrs.application.admin.KpiService;
import com.plrs.application.admin.KpiService.KpiSnapshot;
import com.plrs.infrastructure.admin.RefreshKpiViewsJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * ADMIN-only KPI dashboard at {@code GET /admin/dashboard} (FR-36).
 *
 * <p>Loads the {@link KpiSnapshot} from {@link KpiService}, plus the two
 * trend series (30-day completion rate and weekly average rating) that
 * the template feeds to Chart.js line charts.
 *
 * <p>{@code POST /api/admin/kpi/refresh} kicks
 * {@link RefreshKpiViewsJob#refreshNow()} synchronously so an admin
 * watching the dashboard can prove a fresh number landed without
 * waiting for the cron.
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")}.
 *
 * <p>Traces to: FR-36, §3.c.2.4.
 */
@Controller
@ConditionalOnProperty(name = "spring.datasource.url")
public class AdminDashboardController {

    private final KpiService kpiService;
    private final RefreshKpiViewsJob refreshJob;

    public AdminDashboardController(KpiService kpiService, RefreshKpiViewsJob refreshJob) {
        this.kpiService = kpiService;
        this.refreshJob = refreshJob;
    }

    @GetMapping("/admin/dashboard")
    @PreAuthorize("hasRole('ADMIN')")
    public String dashboard(Model model) {
        KpiSnapshot snap = kpiService.snapshot();
        model.addAttribute("kpis", snap);
        model.addAttribute("completionTrend", snap.completionRateLast30Days());
        model.addAttribute("ratingTrend", snap.avgRatingWeekly());
        return "admin/dashboard";
    }

    @PostMapping("/api/admin/kpi/refresh")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> refreshNow() {
        refreshJob.refreshNow();
        return ResponseEntity.noContent().build();
    }
}
