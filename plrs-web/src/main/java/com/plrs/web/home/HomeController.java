package com.plrs.web.home;

import java.util.List;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Root-page controller. Renders {@code home.html} with {@code principal} and
 * {@code authorities} model attributes populated when a user is signed in,
 * or null/empty when the request is anonymous. The template uses
 * {@code sec:authorize} directives for the primary conditional rendering;
 * the model attributes exist so the welcome message can include the user's
 * email without a second Thymeleaf-Spring-Security expression.
 *
 * <p>The anonymous-auth carve-out matters: Spring Security passes an
 * {@link AnonymousAuthenticationToken} (not {@code null}) when the user is
 * unauthenticated under the web chain, so a plain null check would
 * misreport {@code "anonymousUser"} as the principal name. The explicit
 * check on the token type is what makes the welcome path work.
 *
 * <p>Traces to: §2.c (accessible web UI).
 */
@Controller
public class HomeController {

    @GetMapping("/")
    public String home(Authentication auth, Model model) {
        boolean authenticated =
                auth != null
                        && auth.isAuthenticated()
                        && !(auth instanceof AnonymousAuthenticationToken);
        model.addAttribute("principal", authenticated ? auth.getName() : null);
        model.addAttribute(
                "authorities",
                authenticated
                        ? auth.getAuthorities().stream()
                                .map(GrantedAuthority::getAuthority)
                                .toList()
                        : List.of());
        return "home";
    }
}
