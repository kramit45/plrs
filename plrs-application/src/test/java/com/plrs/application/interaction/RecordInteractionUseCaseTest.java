package com.plrs.application.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.interaction.EventType;
import com.plrs.domain.interaction.InteractionEvent;
import com.plrs.domain.interaction.InteractionRepository;
import com.plrs.domain.user.UserId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecordInteractionUseCaseTest {

    private static final Instant T0 = Instant.parse("2026-04-25T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final UUID USER_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UserId USER = UserId.of(USER_UUID);
    private static final ContentId CONTENT = ContentId.of(42L);

    @Mock private InteractionRepository interactionRepo;

    private RecordInteractionUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new RecordInteractionUseCase(interactionRepo, CLOCK);
    }

    private static RecordInteractionCommand viewCmd() {
        return new RecordInteractionCommand(
                USER_UUID,
                CONTENT.value(),
                "VIEW",
                Optional.of(30),
                Optional.empty(),
                Optional.empty());
    }

    @Test
    void viewWithNoRecentViewReturnsRecordedAndSaves() {
        when(interactionRepo.existsViewSince(eq(USER), eq(CONTENT), any())).thenReturn(false);

        RecordInteractionResult result = useCase.handle(viewCmd());

        assertThat(result).isEqualTo(RecordInteractionResult.RECORDED);
        verify(interactionRepo).save(any(InteractionEvent.class));
    }

    @Test
    void viewWithinDebounceWindowReturnsDebouncedAndDoesNotSave() {
        when(interactionRepo.existsViewSince(eq(USER), eq(CONTENT), any())).thenReturn(true);

        RecordInteractionResult result = useCase.handle(viewCmd());

        assertThat(result).isEqualTo(RecordInteractionResult.DEBOUNCED);
        verify(interactionRepo, never()).save(any());
    }

    @Test
    void viewOutsideDebounceWindowReturnsRecorded() {
        // Adapter would say "no view newer than (now - 10min)" — debounce
        // does not fire, the new VIEW is persisted.
        when(interactionRepo.existsViewSince(eq(USER), eq(CONTENT), any())).thenReturn(false);

        assertThat(useCase.handle(viewCmd())).isEqualTo(RecordInteractionResult.RECORDED);
        verify(interactionRepo).save(any());
    }

    @Test
    void completeIsNeverDebounced() {
        useCase.handle(
                new RecordInteractionCommand(
                        USER_UUID,
                        CONTENT.value(),
                        "COMPLETE",
                        Optional.of(120),
                        Optional.empty(),
                        Optional.empty()));

        verify(interactionRepo, never()).existsViewSince(any(), any(), any());
        verify(interactionRepo).save(any());
    }

    @Test
    void bookmarkIsNeverDebounced() {
        useCase.handle(
                new RecordInteractionCommand(
                        USER_UUID,
                        CONTENT.value(),
                        "BOOKMARK",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));

        verify(interactionRepo, never()).existsViewSince(any(), any(), any());
        verify(interactionRepo).save(any());
    }

    @Test
    void rateWithoutRatingThrows() {
        RecordInteractionCommand cmd =
                new RecordInteractionCommand(
                        USER_UUID,
                        CONTENT.value(),
                        "RATE",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty());

        assertThatThrownBy(() -> useCase.handle(cmd))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("RATE requires rating");

        verify(interactionRepo, never()).save(any());
    }

    @Test
    void rateWithRatingSaves() {
        RecordInteractionCommand cmd =
                new RecordInteractionCommand(
                        USER_UUID,
                        CONTENT.value(),
                        "RATE",
                        Optional.empty(),
                        Optional.of(4),
                        Optional.empty());

        RecordInteractionResult result = useCase.handle(cmd);

        assertThat(result).isEqualTo(RecordInteractionResult.RECORDED);
        ArgumentCaptor<InteractionEvent> evt = ArgumentCaptor.forClass(InteractionEvent.class);
        verify(interactionRepo).save(evt.capture());
        assertThat(evt.getValue().eventType()).isEqualTo(EventType.RATE);
        assertThat(evt.getValue().rating()).isPresent();
        assertThat(evt.getValue().rating().get().value()).isEqualTo(4);
    }

    @Test
    void invalidEventTypeStringThrows() {
        RecordInteractionCommand cmd =
                new RecordInteractionCommand(
                        USER_UUID,
                        CONTENT.value(),
                        "SHARE",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty());

        assertThatThrownBy(() -> useCase.handle(cmd))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("SHARE");

        verify(interactionRepo, never()).save(any());
    }

    @Test
    void debounceWindowIsExactlyTenMinutes() {
        when(interactionRepo.existsViewSince(any(), any(), any())).thenReturn(false);

        useCase.handle(viewCmd());

        ArgumentCaptor<Instant> sinceCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(interactionRepo).existsViewSince(eq(USER), eq(CONTENT), sinceCaptor.capture());
        // Boundary semantics: the use case asks "any VIEW newer than
        // (now - 10min)?" — anything strictly newer debounces, anything
        // exactly 10 minutes old or older does not.
        assertThat(sinceCaptor.getValue()).isEqualTo(T0.minus(Duration.ofMinutes(10)));
    }

    @Test
    void serverStampsOccurredAtFromClockNotFromCommand() {
        when(interactionRepo.existsViewSince(any(), any(), any())).thenReturn(false);

        useCase.handle(viewCmd());

        ArgumentCaptor<InteractionEvent> evt = ArgumentCaptor.forClass(InteractionEvent.class);
        verify(interactionRepo).save(evt.capture());
        assertThat(evt.getValue().occurredAt()).isEqualTo(T0);
    }
}
