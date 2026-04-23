package com.plrs.web.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Two-chain security configuration for the dual-surface Iter 1 API.
 *
 * <p>{@link #apiChain(HttpSecurity, JwtAuthenticationFilter)} (order 1)
 * covers {@code /api/**}: stateless, CSRF disabled (clients send tokens,
 * not cookies), Bearer-token authentication via
 * {@link JwtAuthenticationFilter}. {@code /api/auth/register} and
 * {@code /api/auth/login} are public; every other {@code /api} route
 * requires an authenticated principal.
 *
 * <p>{@link #webChain(HttpSecurity)} (order 2) covers everything else —
 * the server-rendered Thymeleaf views. Session-backed authentication
 * with Spring's built-in form login, CSRF left at its defaults
 * (cookie-based token rendered by Thymeleaf's {@code _csrf} object in
 * step 36's template), session fixation migrated on login, and
 * {@code /logout} handled by Spring Security's filter (invalidates the
 * HTTP session and clears {@code JSESSIONID}).
 *
 * <p>The splits let each surface use the auth model appropriate to it:
 * APIs get tokens that are easy for SPAs and mobile clients to handle,
 * while the browser flow stays stateful and benefits from battle-tested
 * Spring Security primitives.
 *
 * <p>Traces to: §7 (dual auth: JWT for API, HTTP session for JSP), §3.a.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain apiChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter)
            throws Exception {
        http.securityMatcher("/api/**")
                .csrf(c -> c.disable())
                .sessionManagement(
                        s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        a ->
                                a.requestMatchers("/api/auth/register", "/api/auth/login")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(
                        e ->
                                e.authenticationEntryPoint(
                                                (req, res, ex) ->
                                                        res.sendError(401, "Unauthorized"))
                                        .accessDeniedHandler(
                                                (req, res, ex) -> res.sendError(403, "Forbidden")));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain webChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(
                        a ->
                                a.requestMatchers(
                                                "/",
                                                "/register",
                                                "/login",
                                                "/health",
                                                "/css/**",
                                                "/js/**",
                                                "/webjars/**",
                                                "/actuator/health",
                                                "/actuator/info")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .formLogin(
                        f ->
                                f.loginPage("/login")
                                        .loginProcessingUrl("/login")
                                        .defaultSuccessUrl("/", true)
                                        .failureUrl("/login?error")
                                        .permitAll())
                .logout(
                        l ->
                                l.logoutUrl("/logout")
                                        .logoutSuccessUrl("/login?logout")
                                        .invalidateHttpSession(true)
                                        .deleteCookies("JSESSIONID")
                                        .permitAll())
                .sessionManagement(s -> s.sessionFixation(sf -> sf.migrateSession()));
        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg)
            throws Exception {
        return cfg.getAuthenticationManager();
    }

    /**
     * Disables Spring Boot's default servlet-level auto-registration of
     * {@link JwtAuthenticationFilter}. The filter runs solely inside the
     * {@link #apiChain(HttpSecurity, JwtAuthenticationFilter) API chain};
     * registering it twice would mean it executes on web-chain routes too
     * (harmless thanks to {@code OncePerRequestFilter}, but unnecessary).
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
