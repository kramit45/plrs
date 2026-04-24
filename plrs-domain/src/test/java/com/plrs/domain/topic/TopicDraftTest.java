package com.plrs.domain.topic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.AuditFields;
import com.plrs.domain.common.DomainInvariantException;
import com.plrs.domain.common.DomainValidationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TopicDraftTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-04-24T10:15:30Z"), ZoneOffset.UTC);

    private static AuditFields audit() {
        return AuditFields.initial("test", FIXED_CLOCK);
    }

    @Test
    void validRootDraftConstructs() {
        TopicDraft draft = new TopicDraft("Algebra", "Intro", Optional.empty(), audit());

        assertThat(draft.name()).isEqualTo("Algebra");
        assertThat(draft.description()).isEqualTo("Intro");
        assertThat(draft.parentTopicId()).isEmpty();
        assertThat(draft.audit()).isNotNull();
    }

    @Test
    void validChildDraftCarriesParent() {
        TopicId parent = TopicId.of(7L);

        TopicDraft draft = new TopicDraft("Linear Equations", null, Optional.of(parent), audit());

        assertThat(draft.parentTopicId()).contains(parent);
        assertThat(draft.description()).isNull();
    }

    @Test
    void compactConstructorTrimsName() {
        TopicDraft draft = new TopicDraft("  Algebra  ", null, Optional.empty(), audit());

        assertThat(draft.name()).isEqualTo("Algebra");
    }

    @Test
    void rejectsNullName() {
        assertThatThrownBy(() -> new TopicDraft(null, null, Optional.empty(), audit()))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("name");
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> new TopicDraft("   ", null, Optional.empty(), audit()))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rejectsNameOver120Chars() {
        String tooLong = "x".repeat(121);

        assertThatThrownBy(() -> new TopicDraft(tooLong, null, Optional.empty(), audit()))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("120");
    }

    @Test
    void rejectsDescriptionOver500Chars() {
        String tooLong = "x".repeat(501);

        assertThatThrownBy(() -> new TopicDraft("Algebra", tooLong, Optional.empty(), audit()))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("500");
    }

    @Test
    void rejectsNullParentTopicId() {
        assertThatThrownBy(() -> new TopicDraft("Algebra", null, null, audit()))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("parentTopicId");
    }

    @Test
    void rejectsNullAudit() {
        assertThatThrownBy(() -> new TopicDraft("Algebra", null, Optional.empty(), null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("audit");
    }
}
