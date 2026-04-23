package com.plrs.web.common;

import com.plrs.application.user.EmailAlreadyRegisteredException;
import com.plrs.domain.common.DomainValidationException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
