package com.plrs.application.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.plrs.application.topic.TopicNotFoundException;
import com.plrs.domain.common.AuditFields;
import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentDraft;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.content.ContentType;
import com.plrs.domain.content.Difficulty;
import com.plrs.domain.topic.Topic;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.topic.TopicRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CreateContentUseCaseTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-04-25T10:00:00Z"), ZoneOffset.UTC);
    private static final TopicId TOPIC = TopicId.of(7L);

    @Mock private ContentRepository contentRepository;
    @Mock private TopicRepository topicRepository;

    private CreateContentUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new CreateContentUseCase(contentRepository, topicRepository, CLOCK);
    }

    private static Topic persistedTopic(TopicId id) {
        return Topic.rehydrate(
                id,
                "topic-" + id.value(),
                null,
                Optional.empty(),
                AuditFields.initial("system", CLOCK));
    }

    private static Content persistedContent(long id, TopicId topicId, String title, ContentType ctype) {
        return Content.rehydrate(
                ContentId.of(id),
                topicId,
                title,
                ctype,
                Difficulty.BEGINNER,
                10,
                "https://example.com/" + id,
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                AuditFields.initial("system", CLOCK));
    }

    private CreateContentCommand commandFor(ContentType ctype, String title) {
        return new CreateContentCommand(
                TOPIC,
                title,
                ctype,
                Difficulty.BEGINNER,
                15,
                "https://example.com/x",
                Optional.empty(),
                Set.of("warmup"),
                Optional.empty(),
                "admin-ui");
    }

    @ParameterizedTest
    @EnumSource(value = ContentType.class, names = {"VIDEO", "ARTICLE", "EXERCISE"})
    void happyPathSavesNonQuizContent(ContentType ctype) {
        when(topicRepository.findById(TOPIC)).thenReturn(Optional.of(persistedTopic(TOPIC)));
        when(contentRepository.existsByTopicIdAndTitle(TOPIC, "Intro")).thenReturn(false);
        when(contentRepository.save(any(ContentDraft.class)))
                .thenReturn(persistedContent(42L, TOPIC, "Intro", ctype));

        ContentId id = useCase.handle(commandFor(ctype, "Intro"));

        assertThat(id).isEqualTo(ContentId.of(42L));
        verify(contentRepository).save(any(ContentDraft.class));
    }

    @Test
    void quizCtypeThrowsWithPointerToAuthorQuizUseCase() {
        when(topicRepository.findById(TOPIC)).thenReturn(Optional.of(persistedTopic(TOPIC)));

        assertThatThrownBy(() -> useCase.handle(commandFor(ContentType.QUIZ, "Quiz1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AuthorQuizUseCase")
                .hasMessageContaining("step 81");

        verify(contentRepository, never()).existsByTopicIdAndTitle(any(), any());
        verify(contentRepository, never()).save(any());
    }

    @Test
    void unknownTopicThrowsTopicNotFoundException() {
        when(topicRepository.findById(TOPIC)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.handle(commandFor(ContentType.VIDEO, "Intro")))
                .isInstanceOf(TopicNotFoundException.class)
                .satisfies(
                        e ->
                                assertThat(((TopicNotFoundException) e).topicId())
                                        .isEqualTo(TOPIC));

        verify(contentRepository, never()).save(any());
    }

    @Test
    void duplicateTitleInSameTopicThrowsContentTitleNotUnique() {
        when(topicRepository.findById(TOPIC)).thenReturn(Optional.of(persistedTopic(TOPIC)));
        when(contentRepository.existsByTopicIdAndTitle(TOPIC, "Intro")).thenReturn(true);

        assertThatThrownBy(() -> useCase.handle(commandFor(ContentType.VIDEO, "Intro")))
                .isInstanceOf(ContentTitleNotUniqueException.class)
                .satisfies(
                        e -> {
                            ContentTitleNotUniqueException ex = (ContentTitleNotUniqueException) e;
                            assertThat(ex.topicId()).isEqualTo(TOPIC);
                            assertThat(ex.title()).isEqualTo("Intro");
                        });

        verify(contentRepository, never()).save(any());
    }

    @Test
    void duplicateTitleAcrossDifferentTopicsSucceeds() {
        TopicId topicA = TopicId.of(7L);
        TopicId topicB = TopicId.of(8L);
        when(topicRepository.findById(topicA)).thenReturn(Optional.of(persistedTopic(topicA)));
        when(topicRepository.findById(topicB)).thenReturn(Optional.of(persistedTopic(topicB)));
        when(contentRepository.existsByTopicIdAndTitle(topicA, "Shared")).thenReturn(false);
        when(contentRepository.existsByTopicIdAndTitle(topicB, "Shared")).thenReturn(false);
        when(contentRepository.save(any(ContentDraft.class)))
                .thenReturn(persistedContent(1L, topicA, "Shared", ContentType.VIDEO))
                .thenReturn(persistedContent(2L, topicB, "Shared", ContentType.VIDEO));

        ContentId idA =
                useCase.handle(
                        new CreateContentCommand(
                                topicA,
                                "Shared",
                                ContentType.VIDEO,
                                Difficulty.BEGINNER,
                                10,
                                "https://x.y/a",
                                Optional.empty(),
                                Set.of(),
                                Optional.empty(),
                                "admin-ui"));
        ContentId idB =
                useCase.handle(
                        new CreateContentCommand(
                                topicB,
                                "Shared",
                                ContentType.VIDEO,
                                Difficulty.BEGINNER,
                                10,
                                "https://x.y/b",
                                Optional.empty(),
                                Set.of(),
                                Optional.empty(),
                                "admin-ui"));

        assertThat(idA).isNotNull();
        assertThat(idB).isNotNull();
        assertThat(idA).isNotEqualTo(idB);
    }

    @Test
    void invalidUrlSchemeBubblesUpAsDomainValidation() {
        when(topicRepository.findById(TOPIC)).thenReturn(Optional.of(persistedTopic(TOPIC)));
        when(contentRepository.existsByTopicIdAndTitle(TOPIC, "Intro")).thenReturn(false);

        assertThatThrownBy(
                        () ->
                                useCase.handle(
                                        new CreateContentCommand(
                                                TOPIC,
                                                "Intro",
                                                ContentType.VIDEO,
                                                Difficulty.BEGINNER,
                                                10,
                                                "ftp://example.com/file",
                                                Optional.empty(),
                                                Set.of(),
                                                Optional.empty(),
                                                "admin-ui")))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("http");

        verify(contentRepository, never()).save(any());
    }
}
