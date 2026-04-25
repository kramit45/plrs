package com.plrs.domain.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.AuditFields;
import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.topic.TopicId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ContentCanAddPrereqTest {

    private static final Instant T0 = Instant.parse("2026-04-25T10:00:00Z");

    private static Content content(long id) {
        return Content.rehydrate(
                ContentId.of(id),
                TopicId.of(1L),
                "title-" + id,
                ContentType.VIDEO,
                Difficulty.BEGINNER,
                10,
                "https://x.y",
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                AuditFields.initial("system", T0));
    }

    @Test
    void selfPrereqThrowsCycleDetectedWithSelfPath() {
        Content c = content(7L);
        PrerequisiteCheckingRepository unused =
                (a, b) -> {
                    throw new AssertionError("repo must not be consulted for self-edge");
                };

        assertThatThrownBy(() -> c.canAddPrerequisite(c.id(), unused))
                .isInstanceOf(CycleDetectedException.class)
                .satisfies(
                        ex -> {
                            CycleDetectedException ce = (CycleDetectedException) ex;
                            assertThat(ce.cyclePath()).containsExactly(c.id());
                        });
    }

    @Test
    void emptyCyclePathFromRepoMeansEdgeIsSafe() {
        Content c = content(7L);
        PrerequisiteCheckingRepository safe = (a, b) -> List.of();

        assertThatCode(() -> c.canAddPrerequisite(ContentId.of(8L), safe))
                .doesNotThrowAnyException();
    }

    @Test
    void nonEmptyCyclePathFromRepoRaisesCycleDetected() {
        Content c = content(7L);
        ContentId prereq = ContentId.of(9L);
        List<ContentId> reportedPath =
                List.of(c.id(), prereq, ContentId.of(10L), c.id());
        PrerequisiteCheckingRepository cyclic = (a, b) -> reportedPath;

        assertThatThrownBy(() -> c.canAddPrerequisite(prereq, cyclic))
                .isInstanceOf(CycleDetectedException.class)
                .satisfies(
                        ex -> {
                            CycleDetectedException ce = (CycleDetectedException) ex;
                            assertThat(ce.cyclePath()).containsExactlyElementsOf(reportedPath);
                            assertThat(ce.attempted()).isEqualTo(c.id());
                            assertThat(ce.prereq()).isEqualTo(prereq);
                        });
    }

    @Test
    void nullPrereqThrowsDomainValidationNotCycle() {
        Content c = content(7L);
        PrerequisiteCheckingRepository unused = (a, b) -> List.of();

        assertThatThrownBy(() -> c.canAddPrerequisite(null, unused))
                .isInstanceOf(DomainValidationException.class)
                .isNotInstanceOf(CycleDetectedException.class)
                .hasMessageContaining("prereq");
    }

    @Test
    void nullRepositoryThrowsDomainValidation() {
        Content c = content(7L);

        assertThatThrownBy(() -> c.canAddPrerequisite(ContentId.of(8L), null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("checkingRepo");
    }
}
