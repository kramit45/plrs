package com.plrs.domain.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.user.UserId;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PrerequisiteEdgeTest {

    private static final Instant T0 = Instant.parse("2026-04-25T10:00:00Z");

    @Test
    void validEdgeConstructs() {
        ContentId a = ContentId.of(1L);
        ContentId b = ContentId.of(2L);
        UserId author = UserId.newId();

        PrerequisiteEdge edge = new PrerequisiteEdge(a, b, T0, Optional.of(author));

        assertThat(edge.contentId()).isEqualTo(a);
        assertThat(edge.prereqContentId()).isEqualTo(b);
        assertThat(edge.addedAt()).isEqualTo(T0);
        assertThat(edge.addedBy()).contains(author);
    }

    @Test
    void edgeWithEmptyAddedByConstructs() {
        PrerequisiteEdge edge =
                new PrerequisiteEdge(ContentId.of(1L), ContentId.of(2L), T0, Optional.empty());

        assertThat(edge.addedBy()).isEmpty();
    }

    @Test
    void selfEdgeThrows() {
        ContentId same = ContentId.of(7L);

        assertThatThrownBy(() -> new PrerequisiteEdge(same, same, T0, Optional.empty()))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("self-referential")
                .hasMessageContaining("ContentId(7)");
    }

    @Test
    void rejectsNullContentId() {
        assertThatThrownBy(
                        () ->
                                new PrerequisiteEdge(
                                        null, ContentId.of(2L), T0, Optional.empty()))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("contentId");
    }

    @Test
    void rejectsNullPrereqContentId() {
        assertThatThrownBy(
                        () ->
                                new PrerequisiteEdge(
                                        ContentId.of(1L), null, T0, Optional.empty()))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("prereqContentId");
    }

    @Test
    void rejectsNullAddedAt() {
        assertThatThrownBy(
                        () ->
                                new PrerequisiteEdge(
                                        ContentId.of(1L),
                                        ContentId.of(2L),
                                        null,
                                        Optional.empty()))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("addedAt");
    }

    @Test
    void rejectsNullAddedBy() {
        assertThatThrownBy(
                        () ->
                                new PrerequisiteEdge(
                                        ContentId.of(1L), ContentId.of(2L), T0, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("addedBy");
    }
}
