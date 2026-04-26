package com.plrs.web.admin;

import com.plrs.application.admin.ConfigParam;
import com.plrs.application.admin.ConfigParamService;
import com.plrs.domain.user.UserId;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * FR-40 admin runtime tunable editor at {@code GET /admin/config}.
 * Lists every {@code config_params} row with its current value;
 * {@code POST /admin/config/{name}} accepts a new value, updates +
 * evicts cache (handled by {@link ConfigParamService#update}), then
 * redirects back.
 *
 * <p>Traces to: FR-40.
 */
@Controller
@ConditionalOnProperty(name = "spring.datasource.url")
public class AdminConfigController {

    private final ConfigParamService configService;

    public AdminConfigController(ConfigParamService configService) {
        this.configService = configService;
    }

    @GetMapping("/admin/config")
    @PreAuthorize("hasRole('ADMIN')")
    public String view(Model model) {
        List<ConfigParam> params = configService.findAll();
        model.addAttribute("params", params);
        return "admin/config";
    }

    @PostMapping("/admin/config/{name}")
    @PreAuthorize("hasRole('ADMIN')")
    public String update(@PathVariable String name, @RequestParam String value) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UserId actor = UserId.of(UUID.fromString(auth.getName()));
        configService.update(name, value, actor);
        return "redirect:/admin/config";
    }
}
