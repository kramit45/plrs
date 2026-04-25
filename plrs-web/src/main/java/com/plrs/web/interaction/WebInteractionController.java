package com.plrs.web.interaction;

import com.plrs.application.interaction.RecordInteractionCommand;
import com.plrs.application.interaction.RecordInteractionResult;
import com.plrs.application.interaction.RecordInteractionUseCase;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session-authenticated HTTP surface for interaction logging at
 * {@code POST /web-api/interactions}. Mounted under {@code /web-api/**}
 * (not {@code /api/**}) so it falls under Iter 1's web security chain
 * (session cookie + CSRF) rather than the JWT-only API chain.
 *
 * <p>Mirrors {@link InteractionController}'s contract: 201 Created on
 * RECORDED, 200 OK on DEBOUNCED. Restricted to {@code STUDENT}.
 *
 * <p>The browser-side beacon in {@code catalog/detail.html} POSTs here
 * so form-login STUDENTs can log view events without first acquiring a
 * JWT.
 *
 * <p>Traces to: FR-15 / FR-16 / FR-17.
 */
@RestController
@RequestMapping("/web-api/interactions")
@ConditionalOnProperty(name = "spring.datasource.url")
public class WebInteractionController {

    private final RecordInteractionUseCase useCase;

    public WebInteractionController(RecordInteractionUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<Map<String, Object>> record(
            @Valid @RequestBody RecordInteractionRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UUID userUuid = UUID.fromString(auth.getName());
        RecordInteractionCommand cmd =
                new RecordInteractionCommand(
                        userUuid,
                        req.contentId(),
                        req.eventType(),
                        Optional.ofNullable(req.dwellSec()),
                        Optional.ofNullable(req.rating()),
                        Optional.ofNullable(req.clientInfo()));
        RecordInteractionResult r = useCase.handle(cmd);
        int status = r == RecordInteractionResult.RECORDED ? 201 : 200;
        return ResponseEntity.status(status).body(Map.of("result", r.name()));
    }
}
