package com.plrs.web.catalog;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.eq;
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
import com.plrs.domain.content.PrerequisiteEdge;
import com.plrs.domain.content.PrerequisiteRepository;
import com.plrs.domain.topic.Topic;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.topic.TopicRepository;
import com.plrs.web.common.GlobalExceptionHandler;
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
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = CatalogViewController.class,
        includeFilters =
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = GlobalExceptionHandler.class))
@AutoConfigureMockMvc(addFilters = false)
class CatalogDetailViewTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-04-25T10:00:00Z"), ZoneOffset.UTC);

    @Autowired private MockMvc mockMvc;

    @MockBean private ContentRepository contentRepository;
    @MockBean private TopicRepository topicRepository;
    @MockBean private PrerequisiteRepository prereqRepository;
    @MockBean private TokenService tokenService;

    private static Content sample(long id, String title, Set<String> tags) {
        return Content.rehydrate(
                ContentId.of(id),
                TopicId.of(1L),
                title,
                ContentType.VIDEO,
                Difficulty.BEGINNER,
                15,
                "https://example.com/" + id,
                Optional.of("an example description"),
                tags,
                Optional.empty(),
                AuditFields.initial("system", CLOCK));
    }

    private static Topic algebra() {
        return Topic.rehydrate(
                TopicId.of(1L),
                "Algebra",
                null,
                Optional.empty(),
                AuditFields.initial("system", CLOCK));
    }

    private void stubBaseLookups(long id, String title, Set<String> tags) {
        Content c = sample(id, title, tags);
        when(contentRepository.findById(ContentId.of(id))).thenReturn(Optional.of(c));
        when(topicRepository.findById(c.topicId())).thenReturn(Optional.of(algebra()));
    }

    @Test
    @WithMockUser
    void getDetail200RendersTitleAndMetadata() throws Exception {
        stubBaseLookups(7L, "Linear Equations", Set.of("warmup", "algebra"));
        when(prereqRepository.findDirectPrerequisitesOf(ContentId.of(7L))).thenReturn(List.of());
        when(prereqRepository.findDirectDependentsOf(ContentId.of(7L))).thenReturn(List.of());

        mockMvc.perform(get("/catalog/7"))
                .andExpect(status().isOk())
                .andExpect(view().name("catalog/detail"))
                .andExpect(content().string(containsString("Linear Equations")))
                .andExpect(content().string(containsString("VIDEO")))
                .andExpect(content().string(containsString("BEGINNER")))
                .andExpect(content().string(containsString("15 min")))
                .andExpect(content().string(containsString("Algebra")))
                .andExpect(content().string(containsString("warmup")));
    }

    @Test
    @WithMockUser
    void getDetailShowsPrereqAndDependentListsWithTitles() throws Exception {
        stubBaseLookups(7L, "Linear Equations", Set.of());
        ContentId prereqId = ContentId.of(2L);
        ContentId depId = ContentId.of(8L);
        when(prereqRepository.findDirectPrerequisitesOf(ContentId.of(7L)))
                .thenReturn(
                        List.of(
                                new PrerequisiteEdge(
                                        ContentId.of(7L),
                                        prereqId,
                                        Instant.parse("2026-04-25T09:00:00Z"),
                                        Optional.empty())));
        when(prereqRepository.findDirectDependentsOf(ContentId.of(7L)))
                .thenReturn(
                        List.of(
                                new PrerequisiteEdge(
                                        depId,
                                        ContentId.of(7L),
                                        Instant.parse("2026-04-25T08:00:00Z"),
                                        Optional.empty())));
        when(contentRepository.findById(prereqId))
                .thenReturn(Optional.of(sample(2L, "Number Basics", Set.of())));
        when(contentRepository.findById(depId))
                .thenReturn(Optional.of(sample(8L, "Quadratic Equations", Set.of())));

        mockMvc.perform(get("/catalog/7"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Number Basics")))
                .andExpect(content().string(containsString("Quadratic Equations")))
                .andExpect(content().string(containsString("/catalog/2")))
                .andExpect(content().string(containsString("/catalog/8")));
    }

    @Test
    @WithMockUser
    void getDetailEmptyPrereqsRendersEmptyState() throws Exception {
        stubBaseLookups(7L, "Solo", Set.of());
        when(prereqRepository.findDirectPrerequisitesOf(ContentId.of(7L))).thenReturn(List.of());
        when(prereqRepository.findDirectDependentsOf(ContentId.of(7L))).thenReturn(List.of());

        mockMvc.perform(get("/catalog/7"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No prerequisites")))
                .andExpect(content().string(containsString("Nothing depends on this content")));
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    void getDetailWithStudentShowsInteractionsPlaceholder() throws Exception {
        stubBaseLookups(7L, "Solo", Set.of());
        when(prereqRepository.findDirectPrerequisitesOf(ContentId.of(7L))).thenReturn(List.of());
        when(prereqRepository.findDirectDependentsOf(ContentId.of(7L))).thenReturn(List.of());

        mockMvc.perform(get("/catalog/7"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("isStudent", true))
                .andExpect(content().string(containsString("Your interactions")))
                .andExpect(content().string(containsString("step 72")));
    }

    @Test
    @WithMockUser(roles = "INSTRUCTOR")
    void getDetailWithInstructorHidesInteractionsPlaceholder() throws Exception {
        stubBaseLookups(7L, "Solo", Set.of());
        when(prereqRepository.findDirectPrerequisitesOf(ContentId.of(7L))).thenReturn(List.of());
        when(prereqRepository.findDirectDependentsOf(ContentId.of(7L))).thenReturn(List.of());

        mockMvc.perform(get("/catalog/7"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("isStudent", false))
                .andExpect(content().string(not(containsString("Your interactions"))));
    }

    @Test
    @WithMockUser
    void getDetail404WhenContentNotFound() throws Exception {
        when(contentRepository.findById(eq(ContentId.of(99L)))).thenReturn(Optional.empty());

        mockMvc.perform(get("/catalog/99")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void getDetailRendersCorrectViewName() throws Exception {
        // Replaces the spec's unauthenticated-redirect test: slice tests
        // use addFilters=false which bypasses the web chain that redirects
        // unauthenticated requests. Asserting view name + base model
        // attributes instead. The redirect is exercised by the Iter 1
        // SecurityConfigTest in the same module.
        stubBaseLookups(7L, "Solo", Set.of());
        when(prereqRepository.findDirectPrerequisitesOf(ContentId.of(7L))).thenReturn(List.of());
        when(prereqRepository.findDirectDependentsOf(ContentId.of(7L))).thenReturn(List.of());

        mockMvc.perform(get("/catalog/7"))
                .andExpect(status().isOk())
                .andExpect(view().name("catalog/detail"))
                .andExpect(model().attributeExists("item", "topic", "prereqs", "dependents", "titlesById"));
    }

    @Test
    @WithMockUser
    void getDetailExternalUrlRendersAsLink() throws Exception {
        stubBaseLookups(7L, "External", Set.of());
        when(prereqRepository.findDirectPrerequisitesOf(ContentId.of(7L))).thenReturn(List.of());
        when(prereqRepository.findDirectDependentsOf(ContentId.of(7L))).thenReturn(List.of());

        mockMvc.perform(get("/catalog/7"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"https://example.com/7\"")))
                .andExpect(content().string(containsString("target=\"_blank\"")))
                .andExpect(content().string(containsString("rel=\"noopener\"")));
    }
}
