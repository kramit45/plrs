package com.plrs.application.user;

/**
 * Input to {@link RegisterUserUseCase}. Kept as a record of raw strings
 * because the use case itself runs the domain validations; the command's
 * only job is to carry the caller's intent across the port boundary.
 *
 * <p>{@code registrantContext} fills the audit {@code createdBy} slot —
 * {@code "self-registration"} for public sign-ups, an admin identifier
 * for operator-created accounts, and so on.
 */
public record RegisterUserCommand(String email, String rawPassword, String registrantContext) {}
