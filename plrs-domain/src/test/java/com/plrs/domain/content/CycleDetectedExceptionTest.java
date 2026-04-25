package com.plrs.domain.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.DomainValidationException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CycleDetectedExceptionTest {

    @Test
    void cyclePathIsReturnedUnmodified() {
        ContentId a = ContentId.of(1L);
        ContentId b = ContentId.of(2L);
        ContentId c = ContentId.of(3L);
        List<ContentId> path = List.of(a, b, c, a);

        CycleDetectedException ex = new CycleDetectedException(a, c, path);

        assertThat(ex.cyclePath()).containsExactly(a, b, c, a);
        assertThat(ex.attempted()).isEqualTo(a);
        assertThat(ex.prereq()).isEqualTo(c);
    }

    @Test
    void cyclePathListIsUnmodifiable() {
        CycleDetectedException ex =
                new CycleDetectedException(
                        ContentId.of(1L), ContentId.of(2L), List.of(ContentId.of(1L)));

        assertThatThrownBy(() -> ex.cyclePath().add(ContentId.of(99L)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void compactConstructorDefensivelyCopiesPath() {
        ContentId a = ContentId.of(1L);
        ContentId b = ContentId.of(2L);
        List<ContentId> mutable = new ArrayList<>();
        mutable.add(a);
        mutable.add(b);
        mutable.add(a);

        CycleDetectedException ex = new CycleDetectedException(a, b, mutable);

        mutable.add(ContentId.of(999L));

        assertThat(ex.cyclePath()).containsExactly(a, b, a);
    }

    @Test
    void messageContainsRenderedPath() {
        CycleDetectedException ex =
                new CycleDetectedException(
                        ContentId.of(1L),
                        ContentId.of(2L),
                        List.of(ContentId.of(1L), ContentId.of(2L), ContentId.of(1L)));

        assertThat(ex.getMessage())
                .contains("ContentId(1)")
                .contains("ContentId(2)")
                .contains("->")
                .contains("would create a cycle");
    }

    @Test
    void extendsDomainValidationException() {
        CycleDetectedException ex =
                new CycleDetectedException(
                        ContentId.of(1L), ContentId.of(2L), List.of(ContentId.of(1L)));

        assertThat(ex).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void nullCyclePathProducesEmptyAccessorList() {
        CycleDetectedException ex =
                new CycleDetectedException(ContentId.of(1L), ContentId.of(2L), null);

        assertThat(ex.cyclePath()).isEmpty();
    }
}
