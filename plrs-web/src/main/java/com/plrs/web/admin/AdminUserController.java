package com.plrs.web.admin;

import com.plrs.application.audit.Auditable;
import com.plrs.domain.user.UserId;
import com.plrs.domain.user.UserRepository;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN-only user-management endpoints. Currently exposes the FR-06
 * unlock action; future iterations will add role assignment, password
 * force-reset, etc.
 *
 * <p>Traces to: FR-06.
 */
@RestController
@RequestMapping("/api/admin/users")
@ConditionalOnProperty(name = "spring.datasource.url")
public class AdminUserController {

    private final UserRepository users;

    public AdminUserController(UserRepository users) {
        this.users = users;
    }

    @PostMapping("/{userId}/unlock")
    @PreAuthorize("hasRole('ADMIN')")
    @Auditable(action = "USER_UNLOCKED", entityType = "user")
    public ResponseEntity<Void> unlock(@PathVariable UUID userId) {
        users.unlock(UserId.of(userId));
        return ResponseEntity.noContent().build();
    }
}
