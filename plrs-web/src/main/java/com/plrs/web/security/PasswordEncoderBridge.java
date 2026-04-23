package com.plrs.web.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Registers a Spring Security {@link PasswordEncoder} bean at cost 12 so
 * the default {@code DaoAuthenticationProvider} (wired behind form login
 * via {@link DaoUserDetailsService}) can compare submitted passwords
 * against stored BCrypt hashes.
 *
 * <p>The application-layer
 * {@link com.plrs.application.security.PasswordEncoder} port and its
 * {@code BCryptPasswordEncoderAdapter} are left untouched — they serve
 * the registration and login use cases through the JSON API. This bean
 * exists only for the Spring Security infrastructure that the web form
 * chain depends on; the two encoders produce identical hashes because
 * both use cost 12.
 */
@Configuration
public class PasswordEncoderBridge {

    @Bean
    public PasswordEncoder springSecurityPasswordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
