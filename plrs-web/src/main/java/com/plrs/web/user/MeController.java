package com.plrs.web.user;

import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trivial authenticated endpoint that echoes the caller's identity back
 * from the SecurityContext. The Iter 1 JSON API chain has no other
 * non-auth routes to protect, so this serves two purposes:
 *
 * <ul>
 *   <li>It gives the Newman E2E collection (step 44) a concrete
 *       authenticated request to make — a fourth station in the
 *       register → login → authenticated-call → logout flow — instead
 *       of asserting security reactively on error paths.
 *   <li>It pins the JWT filter's contract in a tiny testable surface:
 *       the {@code userId} in the response is the {@code sub} claim from
 *       the access token, and {@code roles} are the
 *       {@code ROLE_}-prefixed authorities the filter materialises.
 * </ul>
 *
 * <p>No {@code @ConditionalOnProperty} gate: the controller has no
 * database dependency and should be present in every web profile that
 * loads Spring Security.
 *
 * <p>Traces to: §7 (JWT authenticated API surface).
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    @GetMapping
    public Map<String, Object> me(Authentication auth) {
        List<String> roles =
                auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
        return Map.of("userId", auth.getName(), "roles", roles);
    }
}
