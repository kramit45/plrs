package com.plrs.web.catalog;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plrs.application.content.AddPrerequisiteUseCase;
import com.plrs.application.content.CreateContentUseCase;
import com.plrs.application.security.TokenService;
import com.plrs.domain.common.AuditFields;
import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.content.ContentType;
import com.plrs.domain.content.Difficulty;
import com.plrs.domain.content.PrerequisiteRepository;
import com.plrs.domain.content.SearchPage;
import com.plrs.domain.topic.TopicId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = ContentController.class,
        includeFilters =
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = com.plrs.web.common.GlobalExceptionHandler.class))
@AutoConfigureMockMvc(addFilters = false)
@Import(ContentSearchTest.MethodSecurityTestConfig.class)
class ContentSearchTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-04-25T10:00:00Z"), ZoneOffset.UTC);

    @Autowired private MockMvc mockMvc;

    @MockBean private CreateContentUseCase createContentUseCase;
    @MockBean private AddPrerequisiteUseCase addPrereqUseCase;
    @MockBean private ContentRepository contentRepository;
    @MockBean private PrerequisiteRepository prereqRepository;
    @MockBean private TokenService tokenService;

    private static Content sample(long id, String title) {
        return Content.rehydrate(
                ContentId.of(id),
                TopicId.of(1L),
                title,
                ContentType.VIDEO,
                Difficulty.BEGINNER,
                10,
                "https://example.com/" + id,
                Optional.empty(),
                Set.of("warmup"),
                Optional.empty(),
                AuditFields.initial("system", CLOCK));
    }

    @Test
    @WithMockUser
    void getSearchWithQueryReturnsItems() throws Exception {
        when(contentRepository.search(eq("graph"), eq(20), eq(0)))
                .thenReturn(
                        new SearchPage(
                                List.of(sample(1L, "Graph Theory"), sample(2L, "Graph Search")),
                                0,
                                20,
                                2L,
                                1));

        mockMvc.perform(get("/api/content/search").param("q", "graph"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].title").value("Graph Theory"))
                .andExpect(jsonPath("$.items[0].ctype").value("VIDEO"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    @WithMockUser
    void getSearchBlankQueryReturnsEmptyPage() throws Exception {
        when(contentRepository.search(eq(""), eq(20), eq(0)))
                .thenReturn(new SearchPage(List.of(), 0, 20, 0L, 0));

        mockMvc.perform(get("/api/content/search").param("q", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @WithMockUser
    void getSearchPageSizeAboveMaxClampedTo100() throws Exception {
        when(contentRepository.search(eq("x"), eq(100), eq(0)))
                .thenReturn(new SearchPage(List.of(), 0, 100, 0L, 0));

        mockMvc.perform(
                        get("/api/content/search")
                                .param("q", "x")
                                .param("pageSize", "5000"))
                .andExpect(status().isOk());

        ArgumentCaptor<Integer> sizeCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(contentRepository).search(eq("x"), sizeCaptor.capture(), eq(0));
        org.assertj.core.api.Assertions.assertThat(sizeCaptor.getValue()).isEqualTo(100);
    }

    @Test
    @WithMockUser
    void getSearchPageSizeBelow1ClampedTo1() throws Exception {
        when(contentRepository.search(eq("x"), eq(1), eq(0)))
                .thenReturn(new SearchPage(List.of(), 0, 1, 0L, 0));

        mockMvc.perform(
                        get("/api/content/search")
                                .param("q", "x")
                                .param("pageSize", "0"))
                .andExpect(status().isOk());

        verify(contentRepository).search(eq("x"), eq(1), eq(0));
    }

    @Test
    @WithMockUser
    void getSearchNegativePageNumberClampedTo0() throws Exception {
        when(contentRepository.search(eq("x"), eq(20), eq(0)))
                .thenReturn(new SearchPage(List.of(), 0, 20, 0L, 0));

        mockMvc.perform(
                        get("/api/content/search")
                                .param("q", "x")
                                .param("pageNumber", "-5"))
                .andExpect(status().isOk());

        verify(contentRepository).search(eq("x"), eq(20), eq(0));
        verify(contentRepository, never()).search(eq("x"), eq(20), eq(-5));
    }

    @Test
    @WithMockUser
    void getSearchUsesDefaultPagination() throws Exception {
        // Slice tests with addFilters=false cannot reliably exercise the
        // unauthenticated 401 path (filter chain is bypassed); covering
        // default pagination behaviour instead. The 401 surface is
        // exercised by the SecurityConfig integration test.
        when(contentRepository.search(eq("x"), eq(20), eq(0)))
                .thenReturn(new SearchPage(List.of(), 0, 20, 0L, 0));

        mockMvc.perform(get("/api/content/search").param("q", "x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.pageNumber").value(0));
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    void getSearchAsStudent200() throws Exception {
        when(contentRepository.search(eq("x"), eq(20), eq(0)))
                .thenReturn(new SearchPage(List.of(), 0, 20, 0L, 0));

        mockMvc.perform(get("/api/content/search").param("q", "x"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "INSTRUCTOR")
    void getSearchAsInstructor200() throws Exception {
        when(contentRepository.search(eq("x"), eq(20), eq(0)))
                .thenReturn(new SearchPage(List.of(), 0, 20, 0L, 0));

        mockMvc.perform(get("/api/content/search").param("q", "x"))
                .andExpect(status().isOk());
    }

    @org.springframework.boot.test.context.TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class MethodSecurityTestConfig {}
}
