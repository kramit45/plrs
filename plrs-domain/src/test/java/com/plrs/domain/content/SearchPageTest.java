package com.plrs.domain.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.DomainValidationException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchPageTest {

    @Test
    void validEmptyPageConstructs() {
        SearchPage page = new SearchPage(List.of(), 0, 20, 0L, 0);

        assertThat(page.items()).isEmpty();
        assertThat(page.pageNumber()).isZero();
        assertThat(page.pageSize()).isEqualTo(20);
        assertThat(page.totalElements()).isZero();
        assertThat(page.totalPages()).isZero();
    }

    @Test
    void compactConstructorDefensivelyCopiesItems() {
        List<Content> mutable = new ArrayList<>();

        SearchPage page = new SearchPage(mutable, 0, 10, 0L, 0);

        assertThatThrownBy(() -> page.items().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNullItems() {
        assertThatThrownBy(() -> new SearchPage(null, 0, 10, 0L, 0))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("items");
    }

    @Test
    void rejectsNegativePageNumber() {
        assertThatThrownBy(() -> new SearchPage(List.of(), -1, 10, 0L, 0))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("pageNumber");
    }

    @Test
    void rejectsZeroPageSize() {
        assertThatThrownBy(() -> new SearchPage(List.of(), 0, 0, 0L, 0))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("pageSize");
    }

    @Test
    void rejectsNegativePageSize() {
        assertThatThrownBy(() -> new SearchPage(List.of(), 0, -1, 0L, 0))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("pageSize");
    }

    @Test
    void rejectsNegativeTotalElements() {
        assertThatThrownBy(() -> new SearchPage(List.of(), 0, 10, -1L, 0))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("totalElements");
    }

    @Test
    void rejectsNegativeTotalPages() {
        assertThatThrownBy(() -> new SearchPage(List.of(), 0, 10, 0L, -1))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("totalPages");
    }
}
