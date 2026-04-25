package com.plrs.web.admin;

import com.plrs.application.admin.RecomputeRecommender;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN-only synchronous recompute trigger so the Newman Iter 3 flow
 * doesn't need a sleep between "student records interactions" and
 * "recommendations include them" — calling this guarantees the
 * sim slabs / TF-IDF matrix reflect the latest data before the
 * student fetches recs.
 */
@RestController
@RequestMapping("/api/admin/recommender")
@ConditionalOnProperty(name = {"spring.datasource.url", "spring.data.redis.host"})
public class AdminRecomputeController {

    private final RecomputeRecommender recompute;

    public AdminRecomputeController(RecomputeRecommender recompute) {
        this.recompute = recompute;
    }

    @PostMapping("/recompute")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> recompute() {
        recompute.recomputeNow();
        return Map.of("status", "OK");
    }
}
