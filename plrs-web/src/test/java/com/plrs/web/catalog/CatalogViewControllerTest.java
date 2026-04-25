package com.plrs.web.catalog;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.plrs.application.security.TokenService;
import com.plrs.domain.common.AuditFields;
import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.content.ContentType;
import com.plrs.domain.content.Difficulty;
import com.plrs.domain.content.SearchPage;
import com.plrs.domain.topic.Topic;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.topic.TopicRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = CatalogViewController.class)
@AutoConfigureMockMvc(addFilters = false)
class CatalogViewControllerTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-04-25T10:00:00Z"), ZoneOffset.UTC);

    @Autowired private MockMvc mockMvc;

    @MockBean private ContentRepository contentRepository;
    @MockBean private TopicRepository topicRepository;
    @MockBean private TokenService tokenService;

    private static Content sample(long id, String title) {
        return Content.rehydrate(
                ContentId.of(id),
                TopicId.of(1L),
                title,
                ContentType.VIDEO,
                Difficulty.BEGINNER,
                15,
                "https://example.com/" + id,
                Optional.empty(),
                Set.of("warmup"),
                Optional.empty(),
                AuditFields.initial("system", CLOCK));
    }

    private static Topic rootTopic(long id, String name) {
        return Topic.rehydrate(
                TopicId.of(id),
                name,
                "intro to " + name,
                Optional.empty(),
                AuditFields.initial("system", CLOCK));
    }

    @Test
    @WithMockUser
    void getCatalogNoQueryRendersRootTopicsAndEmptyResultsSection() throws Exception {
        when(contentRepository.search(eq(""), eq(20), eq(0)))
                .thenReturn(new SearchPage(List.of(), 0, 20, 0L, 0));
        when(topicRepository.findRootTopics())
                .thenReturn(List.of(rootTopic(1L, "Algebra"), rootTopic(2L, "Calculus")));

        mockMvc.perform(get("/catalog"))
                .andExpect(status().isOk())
                .andExpect(view().name("catalog/browse"))
                .andExpect(model().attributeExists("page", "rootTopics", "q"))
                .andExpect(content().string(containsString("Browse by topic")))
                .andExpect(content().string(containsString("Algebra")))
                .andExpect(content().string(containsString("Calculus")));
    }

    @Test
    @WithMockUser
    void getCatalogWithQueryRendersResultTable() throws Exception {
        when(contentRepository.search(eq("graph"), eq(20), eq(0)))
                .thenReturn(
                        new SearchPage(
                                List.of(sample(11L, "Graph Theory"), sample(12L, "Graph Search")),
                                0,
                                20,
                                2L,
                                1));
        when(topicRepository.findRootTopics()).thenReturn(List.of());

        mockMvc.perform(get("/catalog").param("q", "graph"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Graph Theory")))
                .andExpect(content().string(containsString("Graph Search")))
                .andExpect(content().string(containsString("VIDEO")))
                .andExpect(content().string(containsString("warmup")));
    }

    @Test
    @WithMockUser
    void getCatalogPaginationPreservesQParameter() throws Exception {
        when(contentRepository.search(eq("graph"), eq(2), eq(0)))
                .thenReturn(
                        new SearchPage(
                                List.of(sample(11L, "Graph Theory"), sample(12L, "Graph Search")),
                                0,
                                2,
                                10L,
                                5));
        when(topicRepository.findRootTopics()).thenReturn(List.of());

        mockMvc.perform(get("/catalog").param("q", "graph").param("pageSize", "2"))
                .andExpect(status().isOk())
                // Pagination links must round-trip the q parameter so the user
                // doesn't lose their search when paging.
                .andExpect(content().string(containsString("q=graph")))
                .andExpect(content().string(containsString("pageNumber=1")));
    }

    @Test
    @WithMockUser
    void getCatalogNoResultsShowsHelpfulEmptyState() throws Exception {
        when(contentRepository.search(eq("zzzz"), eq(20), eq(0)))
                .thenReturn(new SearchPage(List.of(), 0, 20, 0L, 0));
        when(topicRepository.findRootTopics()).thenReturn(List.of());

        mockMvc.perform(get("/catalog").param("q", "zzzz"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No results for")))
                .andExpect(content().string(containsString("zzzz")));
    }

    @Test
    @WithMockUser
    void getCatalogReturnsExpectedViewAndModelAttributes() throws Exception {
        // Replaces the spec's unauthenticated-redirect test: slice tests
        // bypass the filter chain via addFilters=false, so the
        // /catalog → /login redirect cannot be exercised here. The web
        // chain's auth requirement is asserted by the SecurityConfig
        // integration test in the same module.
        when(contentRepository.search(eq(""), eq(20), eq(0)))
                .thenReturn(new SearchPage(List.of(), 0, 20, 0L, 0));
        when(topicRepository.findRootTopics()).thenReturn(List.of());

        mockMvc.perform(get("/catalog"))
                .andExpect(status().isOk())
                .andExpect(view().name("catalog/browse"))
                .andExpect(model().attribute("pageSize", 20))
                .andExpect(model().attribute("pageNumber", 0))
                .andExpect(model().attributeExists("page", "rootTopics", "q"));
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    void getCatalogAsStudent200() throws Exception {
        when(contentRepository.search(eq(""), eq(20), eq(0)))
                .thenReturn(new SearchPage(List.of(), 0, 20, 0L, 0));
        when(topicRepository.findRootTopics()).thenReturn(List.of());

        mockMvc.perform(get("/catalog"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser
    void getCatalogClampsPageSizeAndPageNumber() throws Exception {
        when(contentRepository.search(eq(""), eq(100), eq(0)))
                .thenReturn(new SearchPage(List.of(), 0, 100, 0L, 0));
        when(topicRepository.findRootTopics()).thenReturn(List.of());

        mockMvc.perform(
                        get("/catalog").param("pageSize", "5000").param("pageNumber", "-3"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("pageSize", 100))
                .andExpect(model().attribute("pageNumber", 0));

        verify(contentRepository).search(eq(""), eq(100), eq(0));
    }
}
