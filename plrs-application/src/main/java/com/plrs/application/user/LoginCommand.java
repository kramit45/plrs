package com.plrs.application.user;

/**
 * Input to {@link LoginUseCase}. Carries the raw credentials as strings;
 * the use case itself applies email normalisation and delegates password
 * comparison to the encoder.
 */
public record LoginCommand(String email, String rawPassword) {}
