package com.plrs.domain.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.DomainValidationException;
import org.junit.jupiter.api.Test;

class EventTypeTest {

    @Test
    void valuesAreInDeclaredOrder() {
        assertThat(EventType.values())
                .containsExactly(
                        EventType.VIEW,
                        EventType.COMPLETE,
                        EventType.BOOKMARK,
                        EventType.LIKE,
                        EventType.RATE);
    }

    @Test
    void allowsDwellOnlyForViewAndComplete() {
        assertThat(EventType.VIEW.allowsDwell()).isTrue();
        assertThat(EventType.COMPLETE.allowsDwell()).isTrue();
        assertThat(EventType.BOOKMARK.allowsDwell()).isFalse();
        assertThat(EventType.LIKE.allowsDwell()).isFalse();
        assertThat(EventType.RATE.allowsDwell()).isFalse();
    }

    @Test
    void requiresRatingOnlyForRate() {
        assertThat(EventType.RATE.requiresRating()).isTrue();
        assertThat(EventType.VIEW.requiresRating()).isFalse();
        assertThat(EventType.COMPLETE.requiresRating()).isFalse();
        assertThat(EventType.BOOKMARK.requiresRating()).isFalse();
        assertThat(EventType.LIKE.requiresRating()).isFalse();
    }

    @Test
    void fromNameReturnsMatchingValue() {
        assertThat(EventType.fromName("VIEW")).isEqualTo(EventType.VIEW);
        assertThat(EventType.fromName("RATE")).isEqualTo(EventType.RATE);
    }

    @Test
    void fromNameIsCaseSensitive() {
        assertThatThrownBy(() -> EventType.fromName("view"))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("view")
                .hasMessageContaining("VIEW");
    }

    @Test
    void fromNameRejectsNull() {
        assertThatThrownBy(() -> EventType.fromName(null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    void fromNameRejectsUnknown() {
        assertThatThrownBy(() -> EventType.fromName("SHARE"))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("SHARE");
    }
}
