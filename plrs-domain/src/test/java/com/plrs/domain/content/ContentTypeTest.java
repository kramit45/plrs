package com.plrs.domain.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.DomainValidationException;
import org.junit.jupiter.api.Test;

class ContentTypeTest {

    @Test
    void valuesAreInDeclaredOrder() {
        assertThat(ContentType.values())
                .containsExactly(
                        ContentType.VIDEO,
                        ContentType.ARTICLE,
                        ContentType.EXERCISE,
                        ContentType.QUIZ);
    }

    @Test
    void fromNameReturnsMatchingValue() {
        assertThat(ContentType.fromName("QUIZ")).isEqualTo(ContentType.QUIZ);
        assertThat(ContentType.fromName("VIDEO")).isEqualTo(ContentType.VIDEO);
        assertThat(ContentType.fromName("ARTICLE")).isEqualTo(ContentType.ARTICLE);
        assertThat(ContentType.fromName("EXERCISE")).isEqualTo(ContentType.EXERCISE);
    }

    @Test
    void fromNameIsCaseSensitive() {
        assertThatThrownBy(() -> ContentType.fromName("quiz"))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("quiz")
                .hasMessageContaining("QUIZ");
    }

    @Test
    void fromNameRejectsNull() {
        assertThatThrownBy(() -> ContentType.fromName(null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    void fromNameRejectsUnknown() {
        assertThatThrownBy(() -> ContentType.fromName("PODCAST"))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("PODCAST");
    }
}
