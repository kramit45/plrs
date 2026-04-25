package com.plrs.application.topic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.plrs.domain.common.AuditFields;
import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.topic.Topic;
import com.plrs.domain.topic.TopicDraft;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.topic.TopicRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CreateTopicUseCaseTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-04-25T10:00:00Z"), ZoneOffset.UTC);

    @Mock private TopicRepository topicRepository;

    private CreateTopicUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new CreateTopicUseCase(topicRepository, CLOCK);
    }

    private static Topic persisted(long id, String name) {
        return Topic.rehydrate(
                TopicId.of(id),
                name,
                null,
                Optional.empty(),
                AuditFields.initial("system", CLOCK));
    }

    @Test
    void happyPathRootTopicSavedAndIdReturned() {
        when(topicRepository.existsByName("Algebra")).thenReturn(false);
        when(topicRepository.save(any(TopicDraft.class))).thenReturn(persisted(42L, "Algebra"));

        TopicId id =
                useCase.handle(
                        new CreateTopicCommand(
                                "Algebra", "intro", Optional.empty(), "admin-ui"));

        assertThat(id).isEqualTo(TopicId.of(42L));
        ArgumentCaptor<TopicDraft> draft = ArgumentCaptor.forClass(TopicDraft.class);
        verify(topicRepository).save(draft.capture());
        assertThat(draft.getValue().name()).isEqualTo("Algebra");
        assertThat(draft.getValue().parentTopicId()).isEmpty();
        assertThat(draft.getValue().audit().createdBy()).isEqualTo("admin-ui");
    }

    @Test
    void happyPathChildTopicSavedWithParentReference() {
        TopicId parent = TopicId.of(7L);
        when(topicRepository.existsByName("Linear Equations")).thenReturn(false);
        when(topicRepository.findById(parent)).thenReturn(Optional.of(persisted(7L, "Algebra")));
        when(topicRepository.save(any(TopicDraft.class)))
                .thenReturn(persisted(99L, "Linear Equations"));

        TopicId id =
                useCase.handle(
                        new CreateTopicCommand(
                                "Linear Equations", null, Optional.of(parent), "admin-ui"));

        assertThat(id).isEqualTo(TopicId.of(99L));
        ArgumentCaptor<TopicDraft> draft = ArgumentCaptor.forClass(TopicDraft.class);
        verify(topicRepository).save(draft.capture());
        assertThat(draft.getValue().parentTopicId()).contains(parent);
    }

    @Test
    void duplicateNameThrowsTopicAlreadyExistsWithoutCallingSave() {
        when(topicRepository.existsByName("Algebra")).thenReturn(true);

        assertThatThrownBy(
                        () ->
                                useCase.handle(
                                        new CreateTopicCommand(
                                                "Algebra",
                                                null,
                                                Optional.empty(),
                                                "admin-ui")))
                .isInstanceOf(TopicAlreadyExistsException.class)
                .hasMessageContaining("Algebra");

        verify(topicRepository, never()).save(any());
    }

    @Test
    void unknownParentThrowsTopicNotFoundException() {
        TopicId parent = TopicId.of(7L);
        when(topicRepository.existsByName("Linear Equations")).thenReturn(false);
        when(topicRepository.findById(parent)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                useCase.handle(
                                        new CreateTopicCommand(
                                                "Linear Equations",
                                                null,
                                                Optional.of(parent),
                                                "admin-ui")))
                .isInstanceOf(TopicNotFoundException.class)
                .satisfies(
                        e ->
                                assertThat(((TopicNotFoundException) e).topicId())
                                        .isEqualTo(parent));

        verify(topicRepository, never()).save(any());
    }

    @Test
    void blankNameValidationBubblesUp() {
        when(topicRepository.existsByName("   ")).thenReturn(false);

        assertThatThrownBy(
                        () ->
                                useCase.handle(
                                        new CreateTopicCommand(
                                                "   ",
                                                null,
                                                Optional.empty(),
                                                "admin-ui")))
                .isInstanceOf(DomainValidationException.class);

        verify(topicRepository, never()).save(any());
    }
}
