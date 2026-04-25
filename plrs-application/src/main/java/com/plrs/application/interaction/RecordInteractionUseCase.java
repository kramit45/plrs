package com.plrs.application.interaction;

import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.interaction.EventType;
import com.plrs.domain.interaction.InteractionEvent;
import com.plrs.domain.interaction.InteractionRepository;
import com.plrs.domain.interaction.Rating;
import com.plrs.domain.user.UserId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records an interaction event with the FR-15 10-minute debounce for
 * VIEW: a second VIEW from the same learner for the same content within
 * the window is <i>not</i> persisted; the use case returns
 * {@link RecordInteractionResult#DEBOUNCED} so the caller can short-circuit.
 *
 * <p>Other event types ({@code COMPLETE}, {@code BOOKMARK}, {@code LIKE},
 * {@code RATE}) are not debounced — they're discrete actions, and the
 * composite PK {@code (user_id, content_id, occurred_at)} is the
 * second-line guard against accidental double-submits.
 *
 * <p>{@code occurredAt} is server-stamped from the injected {@link Clock}
 * so clients can't backdate to slip past the debounce window.
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")} so
 * the bean is not created when {@code PlrsApplicationTests} runs its
 * no-DB smoke test.
 *
 * <p>Traces to: FR-15 (VIEW 10-min debounce), FR-16 (interaction events),
 * FR-17 (rating).
 */
@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public final class RecordInteractionUseCase {

    static final Duration VIEW_DEBOUNCE_WINDOW = Duration.ofMinutes(10);

    private final InteractionRepository interactionRepository;
    private final Clock clock;

    public RecordInteractionUseCase(InteractionRepository interactionRepository, Clock clock) {
        this.interactionRepository = interactionRepository;
        this.clock = clock;
    }

    @Transactional
    public RecordInteractionResult handle(RecordInteractionCommand cmd) {
        EventType type = EventType.fromName(cmd.eventType());
        UserId userId = UserId.of(cmd.userId());
        ContentId contentId = ContentId.of(cmd.contentId());
        Instant now = Instant.now(clock);

        if (type == EventType.VIEW) {
            Instant since = now.minus(VIEW_DEBOUNCE_WINDOW);
            if (interactionRepository.existsViewSince(userId, contentId, since)) {
                return RecordInteractionResult.DEBOUNCED;
            }
        }

        InteractionEvent event =
                switch (type) {
                    case VIEW ->
                            InteractionEvent.view(
                                    userId, contentId, now, cmd.dwellSec(), cmd.clientInfo());
                    case COMPLETE ->
                            InteractionEvent.complete(
                                    userId, contentId, now, cmd.dwellSec(), cmd.clientInfo());
                    case BOOKMARK ->
                            InteractionEvent.bookmark(userId, contentId, now, cmd.clientInfo());
                    case LIKE ->
                            InteractionEvent.like(userId, contentId, now, cmd.clientInfo());
                    case RATE ->
                            InteractionEvent.rate(
                                    userId,
                                    contentId,
                                    now,
                                    Rating.of(
                                            cmd.rating()
                                                    .orElseThrow(
                                                            () ->
                                                                    new DomainValidationException(
                                                                            "RATE requires rating"))),
                                    cmd.clientInfo());
                };
        interactionRepository.save(event);
        return RecordInteractionResult.RECORDED;
    }
}
