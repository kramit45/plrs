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
 * JWT-authenticated HTTP surface for interaction logging at
 * {@code POST /api/interactions}. Restricted to {@code STUDENT}: only
 * learners produce these events. Returns 201 Created when the event was
 * persisted, 200 OK when the use case debounced the event (FR-15
 * 10-minute window).
 *
 * <p>The session-authenticated browser-side counterpart lives at
 * {@code /web-api/interactions} ({@link WebInteractionController}) so
 * Iter 2 form-login users can fire view beacons without juggling JWTs
 * in the browser.
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")} so
 * the bean is not created when {@code PlrsApplicationTests} runs its
 * no-DB smoke test.
 *
 * <p>Traces to: FR-15 (VIEW debounce), FR-16 (interactions), FR-17
 * (rating).
 */
@RestController
@RequestMapping("/api/interactions")
@ConditionalOnProperty(name = "spring.datasource.url")
public class InteractionController {

    private final RecordInteractionUseCase useCase;

    public InteractionController(RecordInteractionUseCase useCase) {
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
