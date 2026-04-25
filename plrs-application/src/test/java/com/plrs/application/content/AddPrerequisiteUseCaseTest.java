package com.plrs.application.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.plrs.domain.common.AuditFields;
import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.content.ContentType;
import com.plrs.domain.content.CycleDetectedException;
import com.plrs.domain.content.Difficulty;
import com.plrs.domain.content.PrerequisiteEdge;
import com.plrs.domain.content.PrerequisiteRepository;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class AddPrerequisiteUseCaseTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-04-25T10:00:00Z"), ZoneOffset.UTC);

    private static final ContentId C1 = ContentId.of(1L);
    private static final ContentId C2 = ContentId.of(2L);

    @Mock private ContentRepository contentRepository;
    @Mock private PrerequisiteRepository prereqRepository;

    private AddPrerequisiteUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new AddPrerequisiteUseCase(contentRepository, prereqRepository, CLOCK);
    }

    private static Content content(ContentId id) {
        return Content.rehydrate(
                id,
                TopicId.of(1L),
                "title-" + id.value(),
                ContentType.VIDEO,
                Difficulty.BEGINNER,
                10,
                "https://x.y",
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                AuditFields.initial("system", CLOCK));
    }

    @Test
    void happyPathSavesEdgeAfterCycleCheck() {
        when(contentRepository.findById(C1)).thenReturn(Optional.of(content(C1)));
        when(contentRepository.findById(C2)).thenReturn(Optional.of(content(C2)));
        when(prereqRepository.exists(C1, C2)).thenReturn(false);
        when(prereqRepository.findCyclePath(C1, C2)).thenReturn(List.of());

        useCase.handle(new AddPrerequisiteCommand(C1, C2, Optional.empty()));

        verify(prereqRepository).save(any(PrerequisiteEdge.class));
        verify(prereqRepository).findCyclePath(C1, C2);
    }

    @Test
    void contentNotFoundThrowsBeforeTouchingPrereqRepo() {
        when(contentRepository.findById(C1)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () -> useCase.handle(
                                new AddPrerequisiteCommand(C1, C2, Optional.empty())))
                .isInstanceOf(ContentNotFoundException.class)
                .satisfies(
                        e ->
                                assertThat(((ContentNotFoundException) e).contentId())
                                        .isEqualTo(C1));

        verify(prereqRepository, never()).exists(any(), any());
        verify(prereqRepository, never()).save(any());
    }

    @Test
    void prereqNotFoundThrowsBeforeCycleCheck() {
        when(contentRepository.findById(C1)).thenReturn(Optional.of(content(C1)));
        when(contentRepository.findById(C2)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () -> useCase.handle(
                                new AddPrerequisiteCommand(C1, C2, Optional.empty())))
                .isInstanceOf(ContentNotFoundException.class)
                .satisfies(
                        e ->
                                assertThat(((ContentNotFoundException) e).contentId())
                                        .isEqualTo(C2));

        verify(prereqRepository, never()).findCyclePath(any(), any());
        verify(prereqRepository, never()).save(any());
    }

    @Test
    void existingEdgeIsIdempotentNoop() {
        when(contentRepository.findById(C1)).thenReturn(Optional.of(content(C1)));
        when(contentRepository.findById(C2)).thenReturn(Optional.of(content(C2)));
        when(prereqRepository.exists(C1, C2)).thenReturn(true);

        useCase.handle(new AddPrerequisiteCommand(C1, C2, Optional.empty()));

        verify(prereqRepository, never()).findCyclePath(any(), any());
        verify(prereqRepository, never()).save(any());
    }

    @Test
    void selfEdgePropagatesCycleDetectedException() {
        when(contentRepository.findById(C1)).thenReturn(Optional.of(content(C1)));
        when(prereqRepository.exists(C1, C1)).thenReturn(false);

        assertThatThrownBy(
                        () -> useCase.handle(
                                new AddPrerequisiteCommand(C1, C1, Optional.empty())))
                .isInstanceOf(CycleDetectedException.class)
                .satisfies(
                        e -> {
                            CycleDetectedException ce = (CycleDetectedException) e;
                            assertThat(ce.cyclePath()).containsExactly(C1);
                        });

        verify(prereqRepository, never()).save(any());
    }

    @Test
    void cyclePropagatesCycleDetectedExceptionWithPath() {
        when(contentRepository.findById(C1)).thenReturn(Optional.of(content(C1)));
        when(contentRepository.findById(C2)).thenReturn(Optional.of(content(C2)));
        when(prereqRepository.exists(C1, C2)).thenReturn(false);
        List<ContentId> reportedPath = List.of(C2, ContentId.of(3L), C1);
        when(prereqRepository.findCyclePath(C1, C2)).thenReturn(reportedPath);

        assertThatThrownBy(
                        () -> useCase.handle(
                                new AddPrerequisiteCommand(C1, C2, Optional.empty())))
                .isInstanceOf(CycleDetectedException.class)
                .satisfies(
                        e -> {
                            CycleDetectedException ce = (CycleDetectedException) e;
                            assertThat(ce.cyclePath()).containsExactlyElementsOf(reportedPath);
                        });

        verify(prereqRepository, never()).save(any());
    }

    @Test
    void retriesOnceOnCannotAcquireLockExceptionThenSucceeds() {
        when(contentRepository.findById(C1)).thenReturn(Optional.of(content(C1)));
        when(contentRepository.findById(C2)).thenReturn(Optional.of(content(C2)));
        when(prereqRepository.exists(C1, C2)).thenReturn(false);
        when(prereqRepository.findCyclePath(C1, C2)).thenReturn(List.of());
        doThrow(new CannotAcquireLockException("first attempt"))
                .doAnswer(inv -> inv.getArgument(0))
                .when(prereqRepository)
                .save(any(PrerequisiteEdge.class));

        useCase.handle(new AddPrerequisiteCommand(C1, C2, Optional.empty()));

        verify(prereqRepository, times(2)).save(any(PrerequisiteEdge.class));
    }

    @Test
    void retriesOnceOnDataIntegrityViolationThenPropagatesOnSecondFailure() {
        when(contentRepository.findById(C1)).thenReturn(Optional.of(content(C1)));
        when(contentRepository.findById(C2)).thenReturn(Optional.of(content(C2)));
        when(prereqRepository.exists(C1, C2)).thenReturn(false);
        when(prereqRepository.findCyclePath(C1, C2)).thenReturn(List.of());
        doThrow(new DataIntegrityViolationException("first"))
                .doThrow(new DataIntegrityViolationException("second"))
                .when(prereqRepository)
                .save(any(PrerequisiteEdge.class));

        assertThatThrownBy(
                        () -> useCase.handle(
                                new AddPrerequisiteCommand(C1, C2, Optional.empty())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("second");

        verify(prereqRepository, times(2)).save(any(PrerequisiteEdge.class));
    }

    @Test
    void doesNotRetryOnContentNotFound() {
        when(contentRepository.findById(C1)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () -> useCase.handle(
                                new AddPrerequisiteCommand(C1, C2, Optional.empty())))
                .isInstanceOf(ContentNotFoundException.class);

        // Single lookup attempt; no retry around a non-retryable failure.
        verify(contentRepository, times(1)).findById(C1);
    }

    @Test
    void doesNotRetryOnCycleDetected() {
        when(contentRepository.findById(C1)).thenReturn(Optional.of(content(C1)));
        when(contentRepository.findById(C2)).thenReturn(Optional.of(content(C2)));
        when(prereqRepository.exists(C1, C2)).thenReturn(false);
        when(prereqRepository.findCyclePath(C1, C2)).thenReturn(List.of(C2, C1));

        assertThatThrownBy(
                        () -> useCase.handle(
                                new AddPrerequisiteCommand(C1, C2, Optional.empty())))
                .isInstanceOf(CycleDetectedException.class);

        // exists() called once: cycle check failed before save, no retry.
        verify(prereqRepository, times(1)).exists(eq(C1), eq(C2));
        verify(prereqRepository, never()).save(any());
    }

    @Test
    void edgeCarriesAddedByFromCommand() {
        UserId author = UserId.newId();
        when(contentRepository.findById(C1)).thenReturn(Optional.of(content(C1)));
        when(contentRepository.findById(C2)).thenReturn(Optional.of(content(C2)));
        when(prereqRepository.exists(C1, C2)).thenReturn(false);
        when(prereqRepository.findCyclePath(C1, C2)).thenReturn(List.of());

        useCase.handle(new AddPrerequisiteCommand(C1, C2, Optional.of(author)));

        verify(prereqRepository)
                .save(
                        org.mockito.ArgumentMatchers.argThat(
                                edge -> edge.addedBy().equals(Optional.of(author))));
    }

    @Test
    void doHandleIsAnnotatedSerializable() throws NoSuchMethodException {
        Method m =
                AddPrerequisiteUseCase.class.getDeclaredMethod(
                        "doHandle", AddPrerequisiteCommand.class);

        Transactional t = m.getAnnotation(Transactional.class);

        assertThat(t).as("doHandle must carry @Transactional").isNotNull();
        assertThat(t.isolation()).isEqualTo(Isolation.SERIALIZABLE);
    }
}
