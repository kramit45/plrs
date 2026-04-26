package com.plrs.application.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.plrs.domain.common.AuditFields;
import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentDraft;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.content.ContentType;
import com.plrs.domain.content.Difficulty;
import com.plrs.domain.topic.Topic;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.topic.TopicRepository;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ImportContentCsvUseCaseTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-04-26T10:00:00Z"), ZoneOffset.UTC);
    private static final TopicId TOPIC = TopicId.of(7L);

    @Mock private ContentRepository contentRepo;
    @Mock private TopicRepository topicRepo;

    private ImportContentCsvUseCase useCase() {
        return new ImportContentCsvUseCase(contentRepo, topicRepo, CLOCK);
    }

    private Topic topic() {
        return Topic.rehydrate(
                TOPIC, "Algebra", "desc", Optional.empty(), AuditFields.initial("system", CLOCK));
    }

    private Content stubContent(long id) {
        return Content.rehydrate(
                ContentId.of(id),
                TOPIC,
                "T",
                ContentType.VIDEO,
                Difficulty.BEGINNER,
                5,
                "https://x.y",
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                AuditFields.initial("system", CLOCK));
    }

    private CsvImportResult run(String csv) {
        return useCase()
                .handle(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), "tester");
    }

    @Test
    void happyPathSavesAllRows() {
        when(topicRepo.findByName("Algebra")).thenReturn(Optional.of(topic()));
        when(contentRepo.save(any())).thenReturn(stubContent(1L));

        String csv =
                "topic_name,title,ctype,difficulty,est_minutes,url\n"
                        + "Algebra,Lesson A,VIDEO,BEGINNER,5,https://example.com/a\n"
                        + "Algebra,Lesson B,ARTICLE,INTERMEDIATE,10,https://example.com/b\n"
                        + "Algebra,Lesson C,EXERCISE,ADVANCED,15,https://example.com/c\n";
        CsvImportResult result = run(csv);

        assertThat(result.saved()).isEqualTo(3);
        assertThat(result.errors()).isEmpty();
        verify(contentRepo, times(3)).save(any(ContentDraft.class));
    }

    @Test
    void unknownTopicCollectedAsRowError() {
        when(topicRepo.findByName("Algebra")).thenReturn(Optional.of(topic()));
        when(topicRepo.findByName("Unknown")).thenReturn(Optional.empty());
        when(contentRepo.save(any())).thenReturn(stubContent(1L));

        String csv =
                "topic_name,title,ctype,difficulty,est_minutes,url\n"
                        + "Algebra,OK,VIDEO,BEGINNER,5,https://example.com/ok\n"
                        + "Unknown,Bad,VIDEO,BEGINNER,5,https://example.com/bad\n";

        CsvImportResult result = run(csv);

        assertThat(result.saved()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).rowNumber()).isEqualTo(3);
        assertThat(result.errors().get(0).message()).contains("Unknown topic");
    }

    @Test
    void invalidCtypeCollectedAsRowError() {
        when(topicRepo.findByName("Algebra")).thenReturn(Optional.of(topic()));

        String csv =
                "topic_name,title,ctype,difficulty,est_minutes,url\n"
                        + "Algebra,Bad,DOCUMENT,BEGINNER,5,https://example.com/x\n";
        CsvImportResult result = run(csv);

        assertThat(result.saved()).isZero();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).message()).contains("DOCUMENT");
    }

    @Test
    void blankCsvProducesEmptyResult() {
        String csv = "topic_name,title,ctype,difficulty,est_minutes,url\n";
        CsvImportResult result = run(csv);

        assertThat(result.saved()).isZero();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void semicolonSeparatedTagsAreSplit() {
        when(topicRepo.findByName("Algebra")).thenReturn(Optional.of(topic()));
        when(contentRepo.save(any())).thenReturn(stubContent(1L));

        String csv =
                "topic_name,title,ctype,difficulty,est_minutes,url,tags\n"
                        + "Algebra,T,VIDEO,BEGINNER,5,https://x.y,beginner;intro;math\n";
        CsvImportResult result = run(csv);

        assertThat(result.saved()).isEqualTo(1);
        org.mockito.ArgumentCaptor<ContentDraft> cap =
                org.mockito.ArgumentCaptor.forClass(ContentDraft.class);
        verify(contentRepo).save(cap.capture());
        assertThat(cap.getValue().tags()).containsExactlyInAnyOrder("beginner", "intro", "math");
    }

    @Test
    void mixedValidAndInvalidPersistsValidOnes() {
        when(topicRepo.findByName("Algebra")).thenReturn(Optional.of(topic()));
        when(contentRepo.save(any())).thenReturn(stubContent(1L));

        String csv =
                "topic_name,title,ctype,difficulty,est_minutes,url\n"
                        + "Algebra,OK,VIDEO,BEGINNER,5,https://x.y/1\n"
                        + "Algebra,Bad,WHATEVER,BEGINNER,5,https://x.y/2\n"
                        + "Algebra,OK2,ARTICLE,INTERMEDIATE,7,https://x.y/3\n";
        CsvImportResult result = run(csv);

        assertThat(result.saved()).isEqualTo(2);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).rowNumber()).isEqualTo(3);
    }
}
