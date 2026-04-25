package com.plrs.web.common;

import com.plrs.application.content.ContentNotFoundException;
import com.plrs.application.content.ContentTitleNotUniqueException;
import com.plrs.application.security.InvalidTokenException;
import com.plrs.application.topic.TopicAlreadyExistsException;
import com.plrs.application.topic.TopicNotFoundException;
import com.plrs.application.user.EmailAlreadyRegisteredException;
import com.plrs.application.user.InvalidCredentialsException;
import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.CycleDetectedException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain-layer, application-layer, and Spring-surface exceptions to
 * RFC 7807 {@link ProblemDetail} responses so HTTP clients see a
 * consistent error shape. Each response carries the correlation id from
 * MDC (populated by {@code RequestIdFilter} in step 15), which lets a
 * client report an error by id and the server can grep server logs for
 * the full context.
 *
 * <p>Status mapping:
 *
 * <ul>
 *   <li>{@link DomainValidationException} → 400 — value-object or policy
 *       rule breach (invalid email format, weak password, UUID parse
 *       failure, etc.).
 *   <li>{@link EmailAlreadyRegisteredException} → 409 — uniqueness clash
 *       detected by the use case.
 *   <li>{@link InvalidCredentialsException} → 401 — unknown email or wrong
 *       password; the response body is identical for both to prevent
 *       account enumeration, and we log at WARN with only the request id
 *       (never the attempted email).
 *   <li>{@link InvalidTokenException} → 401 — refresh/access JWT failed
 *       verification (bad signature, expired, wrong issuer, tampered, or
 *       the token value itself was missing from the logout/refresh
 *       request); logged at WARN with only the request id, never the
 *       token value.
 *   <li>{@link MethodArgumentNotValidException} → 400 — bean-validation
 *       failures on request records (e.g. {@code @NotBlank}). The detail
 *       body includes a per-field error map.
 *   <li>{@link HttpMessageNotReadableException} → 400 — malformed JSON
 *       body; caught explicitly so the catch-all below does not wrap it
 *       as a 500.
 *   <li>Any other {@link Exception} → 500, logged at ERROR with stack
 *       trace. The client sees a generic detail; the server retains the
 *       cause.
 * </ul>
 *
 * <p>Traces to: §5.b (error handling standards).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String PROBLEM_BASE_URI = "https://plrs.example/problems/";

    @ExceptionHandler(DomainValidationException.class)
    public ProblemDetail handleDomainValidation(DomainValidationException e) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "domain-validation",
                "Validation failed",
                e.getMessage());
    }

    @ExceptionHandler(TopicAlreadyExistsException.class)
    public ProblemDetail handleTopicAlreadyExists(TopicAlreadyExistsException e) {
        ProblemDetail p =
                problem(
                        HttpStatus.CONFLICT,
                        "topic-already-exists",
                        "Topic already exists",
                        e.getMessage());
        p.setProperty("name", e.name());
        return p;
    }

    @ExceptionHandler(TopicNotFoundException.class)
    public ProblemDetail handleTopicNotFound(TopicNotFoundException e) {
        ProblemDetail p =
                problem(
                        HttpStatus.NOT_FOUND,
                        "topic-not-found",
                        "Topic not found",
                        e.getMessage());
        if (e.topicId() != null) {
            p.setProperty("topicId", e.topicId().value());
        }
        return p;
    }

    @ExceptionHandler(ContentNotFoundException.class)
    public ProblemDetail handleContentNotFound(ContentNotFoundException e) {
        ProblemDetail p =
                problem(
                        HttpStatus.NOT_FOUND,
                        "content-not-found",
                        "Content not found",
                        e.getMessage());
        if (e.contentId() != null) {
            p.setProperty("contentId", e.contentId().value());
        }
        return p;
    }

    @ExceptionHandler(ContentTitleNotUniqueException.class)
    public ProblemDetail handleContentTitleNotUnique(ContentTitleNotUniqueException e) {
        ProblemDetail p =
                problem(
                        HttpStatus.CONFLICT,
                        "content-title-not-unique",
                        "Content title not unique within topic",
                        e.getMessage());
        if (e.topicId() != null) {
            p.setProperty("topicId", e.topicId().value());
        }
        // Property is named "contentTitle" (not "title") to avoid colliding
        // with ProblemDetail's standard title field on serialisation.
        p.setProperty("contentTitle", e.title());
        return p;
    }

    @ExceptionHandler(CycleDetectedException.class)
    public ProblemDetail handleCycleDetected(CycleDetectedException e) {
        ProblemDetail p =
                problem(
                        HttpStatus.CONFLICT,
                        "cycle-detected",
                        "Prerequisite cycle detected",
                        e.getMessage());
        List<Long> path = e.cyclePath().stream().map(ContentId::value).toList();
        p.setProperty("cyclePath", path);
        return p;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException e) {
        return problem(
                HttpStatus.FORBIDDEN,
                "access-denied",
                "Forbidden",
                "You do not have permission to perform this action");
    }

    /**
     * Catches {@code AuthenticationCredentialsNotFoundException} and other
     * Spring Security {@link AuthenticationException}s thrown from the
     * method-security interceptor when there is no authenticated principal
     * (e.g. a slice test where the filter chain is bypassed, or a request
     * that reaches a method-secured endpoint without going through the
     * authentication filter).
     */
    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthenticationException(AuthenticationException e) {
        return problem(
                HttpStatus.UNAUTHORIZED,
                "authentication-required",
                "Unauthorized",
                "Authentication is required to access this resource");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "invalid-argument",
                "Invalid argument",
                e.getMessage());
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ProblemDetail handleDuplicateEmail(EmailAlreadyRegisteredException e) {
        ProblemDetail p =
                problem(
                        HttpStatus.CONFLICT,
                        "email-already-registered",
                        "Email already registered",
                        e.getMessage());
        p.setProperty("email", e.email());
        return p;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleBeanValidation(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fe.getField(), fe.getDefaultMessage());
        }
        ProblemDetail p =
                problem(
                        HttpStatus.BAD_REQUEST,
                        "invalid-request",
                        "Invalid request",
                        "One or more fields are invalid");
        p.setProperty("errors", fieldErrors);
        return p;
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail handleInvalidCredentials(InvalidCredentialsException e) {
        log.warn("login rejected (requestId={})", MDC.get("requestId"));
        return problem(
                HttpStatus.UNAUTHORIZED,
                "invalid-credentials",
                "Unauthorized",
                "Invalid email or password");
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ProblemDetail handleInvalidToken(InvalidTokenException e) {
        log.warn("token rejected (requestId={})", MDC.get("requestId"));
        return problem(
                HttpStatus.UNAUTHORIZED,
                "invalid-token",
                "Unauthorized",
                "Invalid or expired token");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException e) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "unreadable-request",
                "Malformed request body",
                "The request body could not be parsed as JSON");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleAnyOther(Exception e) {
        log.error("Unhandled exception mapped to 500", e);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal-error",
                "Internal error",
                "An unexpected error occurred");
    }

    private static ProblemDetail problem(
            HttpStatusCode status, String slug, String title, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create(PROBLEM_BASE_URI + slug));
        p.setTitle(title);
        String requestId = MDC.get("requestId");
        if (requestId != null) {
            p.setProperty("requestId", requestId);
        }
        return p;
    }
}
