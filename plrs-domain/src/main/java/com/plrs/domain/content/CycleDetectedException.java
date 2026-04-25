package com.plrs.domain.content;

import com.plrs.domain.common.DomainValidationException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown when an attempt to add a prerequisite edge would create a cycle
 * in the prerequisite DAG. Carries the cycle path so the caller (use
 * case, web layer) can show a useful error rather than just "no, you
 * can't do that".
 *
 * <p>Subclasses {@link DomainValidationException} so a catch-all
 * "the domain said no" handler keeps working.
 *
 * <p>Traces to: §3.b.2.3 (no-cycle invariant), FR-09 (prerequisite tracking).
 */
public class CycleDetectedException extends DomainValidationException {

    private final ContentId attempted;
    private final ContentId prereq;
    private final List<ContentId> cyclePath;

    public CycleDetectedException(
            ContentId attempted, ContentId prereq, List<ContentId> cyclePath) {
        super(buildMessage(attempted, prereq, cyclePath));
        this.attempted = attempted;
        this.prereq = prereq;
        this.cyclePath =
                cyclePath == null ? List.of() : Collections.unmodifiableList(List.copyOf(cyclePath));
    }

    public ContentId attempted() {
        return attempted;
    }

    public ContentId prereq() {
        return prereq;
    }

    public List<ContentId> cyclePath() {
        return cyclePath;
    }

    private static String buildMessage(
            ContentId attempted, ContentId prereq, List<ContentId> cyclePath) {
        String renderedPath =
                cyclePath == null || cyclePath.isEmpty()
                        ? "[]"
                        : cyclePath.stream()
                                .map(ContentId::toString)
                                .collect(Collectors.joining(" -> ", "[", "]"));
        return "adding prereq " + prereq + " to " + attempted
                + " would create a cycle via " + renderedPath;
    }
}
