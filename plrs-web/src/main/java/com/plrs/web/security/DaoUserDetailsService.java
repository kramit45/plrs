package com.plrs.web.security;

import com.plrs.domain.user.Email;
import com.plrs.domain.user.User;
import com.plrs.domain.user.UserRepository;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Supplies {@link UserDetails} for the form-login flow on the web chain.
 * The JWT API chain bypasses this entirely — it authenticates via
 * {@link JwtAuthenticationFilter} rather than Spring's
 * {@code DaoAuthenticationProvider}.
 *
 * <p>Gated with {@link ConditionalOnProperty} on {@code spring.datasource.url}
 * so the no-DB {@code PlrsApplicationTests} smoke test does not require a
 * {@link UserRepository} bean.
 */
@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class DaoUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public DaoUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        Email email;
        try {
            email = Email.of(username);
        } catch (RuntimeException e) {
            throw new UsernameNotFoundException("User not found: " + username, e);
        }
        User user =
                users.findByEmail(email)
                        .orElseThrow(
                                () -> new UsernameNotFoundException("User not found: " + username));
        List<SimpleGrantedAuthority> authorities =
                user.roles().stream()
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                        .toList();
        return org.springframework.security.core.userdetails.User.withUsername(
                        user.email().value())
                .password(user.passwordHash().value())
                .authorities(authorities)
                .build();
    }
}
