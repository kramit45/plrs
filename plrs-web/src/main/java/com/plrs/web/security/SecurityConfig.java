package com.plrs.web.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Two-chain security configuration for the dual-surface Iter 1 API.
 *
 * <p>{@link #apiChain(HttpSecurity, JwtAuthenticationFilter, CorsConfigurationSource)}
 * (order 1) covers {@code /api/**}: stateless, CSRF disabled (clients
 * send tokens not cookies, so there is no CSRF surface to protect),
 * CORS on with its configuration sourced from {@link CorsConfig}, and
 * Bearer-token authentication via {@link JwtAuthenticationFilter}.
 * {@code /api/auth/register} and {@code /api/auth/login} are public;
 * every other {@code /api} route requires an authenticated principal.
 *
 * <p>{@link #webChain(HttpSecurity)} (order 2) covers everything else —
 * the server-rendered Thymeleaf views. Session-backed authentication
 * with Spring's built-in form login, CSRF enabled with a cookie-based
 * repository ({@code XSRF-TOKEN} cookie, {@code _csrf} form parameter)
 * so SPAs calling into the same origin can read the token via
 * JavaScript if needed, session fixation migrated on login, and
 * {@code /logout} handled by Spring Security's filter (invalidates the
 * HTTP session and clears {@code JSESSIONID}).
 *
 * <p>Both chains apply a baseline set of security response headers —
 * HSTS (1 year + includeSubDomains), {@code X-Frame-Options: DENY},
 * {@code X-Content-Type-Options: nosniff}, a {@code Referrer-Policy} of
 * {@code strict-origin-when-cross-origin}, a {@code Permissions-Policy}
 * that disables geolocation / microphone / camera / payment, and a
 * strict CSP that allows the jsDelivr Bootstrap CDN referenced by
 * {@code layout.html}.
 *
 * <p>Traces to: §7 (dual auth + security controls: CSRF, HSTS, CSP,
 * X-Frame-Options, CORS), §3.a.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain apiChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtFilter,
            CorsConfigurationSource corsSource)
            throws Exception {
        http.securityMatcher("/api/**")
                .csrf(c -> c.disable())
                .cors(cors -> cors.configurationSource(corsSource))
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
        applyHeaders(http);
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain webChain(HttpSecurity http) throws Exception {
        http.csrf(
                        c ->
                                c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                                        .csrfTokenRequestHandler(
                                                new CsrfTokenRequestAttributeHandler()))
                .authorizeHttpRequests(
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
        applyHeaders(http);
        return http.build();
    }

    private static void applyHeaders(HttpSecurity http) throws Exception {
        http.headers(
                h ->
                        h.httpStrictTransportSecurity(
                                        hsts ->
                                                hsts.includeSubDomains(true)
                                                        .maxAgeInSeconds(31_536_000))
                                .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                                .contentTypeOptions(Customizer.withDefaults())
                                .referrerPolicy(
                                        rp ->
                                                rp.policy(
                                                        ReferrerPolicyHeaderWriter.ReferrerPolicy
                                                                .STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                                .addHeaderWriter(
                                        new StaticHeadersWriter(
                                                "Permissions-Policy",
                                                "geolocation=(), microphone=(), camera=(),"
                                                        + " payment=()"))
                                .contentSecurityPolicy(
                                        csp ->
                                                csp.policyDirectives(
                                                        "default-src 'self'; "
                                                                + "style-src 'self'"
                                                                + " https://cdn.jsdelivr.net"
                                                                + " 'unsafe-inline'; "
                                                                + "script-src 'self'"
                                                                + " https://cdn.jsdelivr.net; "
                                                                + "img-src 'self' data:; "
                                                                + "font-src 'self'"
                                                                + " https://cdn.jsdelivr.net; "
                                                                + "frame-ancestors 'none'")));
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg)
            throws Exception {
        return cfg.getAuthenticationManager();
    }

    /**
     * Disables Spring Boot's default servlet-level auto-registration of
     * {@link JwtAuthenticationFilter}. The filter runs solely inside the
     * API chain; registering it twice would mean it executes on web-chain
     * routes too (harmless thanks to {@code OncePerRequestFilter}, but
     * unnecessary).
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
