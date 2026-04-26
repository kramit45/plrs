package com.plrs.web.admin;

import com.plrs.application.audit.AuditQueryService;
import com.plrs.application.audit.AuditQueryService.AuditPage;
import com.plrs.domain.user.UserId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * FR-42: ADMIN audit log viewer. {@code GET /admin/audit} renders a
 * filterable, paginated table of {@code plrs_ops.audit_log} rows.
 * All query parameters are optional; with none the most-recent 50
 * entries surface.
 *
 * <p>Traces to: FR-42, NFR-29.
 */
@Controller
@ConditionalOnProperty(name = "spring.datasource.url")
public class AdminAuditController {

    private final AuditQueryService auditService;

    public AdminAuditController(AuditQueryService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/admin/audit")
    @PreAuthorize("hasRole('ADMIN')")
    public String view(
            @RequestParam(required = false) UUID actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Model model) {
        AuditPage result =
                auditService.search(
                        Optional.ofNullable(actor).map(UserId::of),
                        Optional.ofNullable(action),
                        Optional.ofNullable(from),
                        Optional.ofNullable(to),
                        size,
                        page);
        model.addAttribute("page", result);
        model.addAttribute("filterActor", actor);
        model.addAttribute("filterAction", action);
        model.addAttribute("filterFrom", from);
        model.addAttribute("filterTo", to);
        return "admin/audit";
    }
}
