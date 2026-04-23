package com.plrs.web.auth;

import com.plrs.application.user.EmailAlreadyRegisteredException;
import com.plrs.application.user.RegisterUserCommand;
import com.plrs.application.user.RegisterUserUseCase;
import com.plrs.domain.common.DomainValidationException;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Server-rendered registration flow. Complements the JSON endpoint
 * ({@link AuthController}) with a classic HTML form for browser users,
 * backed by the same {@link RegisterUserUseCase} so both surfaces share
 * one validation and persistence path.
 *
 * <p>Error translation on POST:
 *
 * <ul>
 *   <li>Bean-validation failures surface through {@link BindingResult}
 *       and re-render the form with per-field messages.
 *   <li>{@link EmailAlreadyRegisteredException} is mapped to an inline
 *       field error on {@code email} so the user sees a targeted message
 *       in context rather than a 409 page.
 *   <li>{@link DomainValidationException} is mapped to a global form
 *       error (no single field owns the breach — the use case decides).
 * </ul>
 *
 * <p>Success redirects to {@code /login?registered} with a flash-scoped
 * success message. The login view does not exist yet (lands in Theme I);
 * visitors will see a 404 there until then, which the step spec
 * acknowledges.
 *
 * <p>Gated with {@link ConditionalOnProperty} on {@code spring.datasource.url}
 * so the no-DB {@code PlrsApplicationTests} smoke test does not need the
 * use-case bean.
 *
 * <p>Traces to: §2.c (accessible web UI), §3.a (server-rendered views).
 */
@Controller
@ConditionalOnProperty(name = "spring.datasource.url")
public class AuthFormController {

    private final RegisterUserUseCase registerUseCase;

    public AuthFormController(RegisterUserUseCase registerUseCase) {
        this.registerUseCase = registerUseCase;
    }

    @GetMapping("/register")
    public String showForm(Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new RegisterFormModel("", ""));
        }
        return "auth/register";
    }

    /**
     * Renders the login view. The {@code POST /login} that this form
     * submits to is handled by Spring Security's form-login filter, not
     * by a controller method. Three query-string flags surface states
     * produced by that filter (and by registration):
     *
     * <ul>
     *   <li>{@code ?registered} — set by
     *       {@link #submit(RegisterFormModel, BindingResult, RedirectAttributes)}
     *       on successful registration, so the user lands here with a
     *       success banner.
     *   <li>{@code ?logout} — set by Spring Security on successful
     *       logout.
     *   <li>{@code ?error} — set by Spring Security on failed form
     *       login; the view renders the generic "invalid email or
     *       password" copy, matching the API's 401 body.
     * </ul>
     */
    @GetMapping("/login")
    public String showLogin(
            @RequestParam(required = false) String error,
            @RequestParam(required = false) String logout,
            @RequestParam(required = false) String registered,
            Model model) {
        model.addAttribute("error", error);
        model.addAttribute("logout", logout);
        model.addAttribute("registered", registered);
        return "auth/login";
    }

    @PostMapping("/register")
    public String submit(
            @Valid @ModelAttribute("form") RegisterFormModel form,
            BindingResult binding,
            RedirectAttributes ra) {
        if (binding.hasErrors()) {
            return "auth/register";
        }
        try {
            registerUseCase.handle(
                    new RegisterUserCommand(form.email(), form.password(), "form-registration"));
            ra.addFlashAttribute("flashSuccess", "Registered. Please log in.");
            return "redirect:/login?registered";
        } catch (EmailAlreadyRegisteredException e) {
            binding.rejectValue("email", "duplicate", "Email already registered");
            return "auth/register";
        } catch (DomainValidationException e) {
            binding.reject("domain", e.getMessage());
            return "auth/register";
        }
    }
}
