package com.plrs.web.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plrs.application.security.TokenService;
import com.plrs.domain.common.AuditFields;
import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.content.ContentType;
import com.plrs.domain.content.Difficulty;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ContentExportController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ContentExportControllerTest.MethodSecurityTestConfig.class)
class ContentExportControllerTest {

    private static final TopicId TOPIC = TopicId.of(1L);
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-04-26T10:00:00Z"), ZoneOffset.UTC);

    @Autowired private MockMvc mockMvc;

    @MockBean private ContentRepository contentRepository;
    @MockBean private TopicRepository topicRepository;
    @MockBean private TokenService tokenService;

    private static Content content(long id, String title, String tag) {
        return Content.rehydrate(
                ContentId.of(id),
                TOPIC,
                title,
                ContentType.VIDEO,
                Difficulty.BEGINNER,
                10,
                "https://x.y/" + id,
                Optional.of("desc-" + id),
                Set.of(tag),
                Optional.empty(),
                AuditFields.initial("system", CLOCK));
    }

    private static Topic topic() {
        return Topic.rehydrate(
                TOPIC, "Algebra", "desc", Optional.empty(), AuditFields.initial("system", CLOCK));
    }

    @Test
    @WithMockUser(roles = "INSTRUCTOR")
    void instructorGetsCsv() throws Exception {
        when(contentRepository.findAllNonQuiz(anyInt()))
                .thenReturn(List.of(content(1L, "Lesson A", "intro")));
        when(topicRepository.findById(TOPIC)).thenReturn(Optional.of(topic()));

        var result =
                mockMvc.perform(get("/api/content/export"))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Content-Type", "text/csv; charset=UTF-8"))
                        .andExpect(header().string(
                                "Content-Disposition",
                                "attachment; filename=\"plrs-content.csv\""))
                        .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("topic_name,title,ctype,difficulty,est_minutes,url,description,tags");
        assertThat(body).contains("Algebra,Lesson A,VIDEO,BEGINNER,10,https://x.y/1,desc-1,intro");
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    void studentForbidden() throws Exception {
        mockMvc.perform(get("/api/content/export")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "INSTRUCTOR")
    void emptyCatalogueExportsHeaderRowOnly() throws Exception {
        when(contentRepository.findAllNonQuiz(anyInt())).thenReturn(List.of());

        var result = mockMvc.perform(get("/api/content/export")).andExpect(status().isOk()).andReturn();
        String body = result.getResponse().getContentAsString();
        // Header line only — newline after header, nothing else.
        assertThat(body.trim())
                .isEqualTo("topic_name,title,ctype,difficulty,est_minutes,url,description,tags");
    }

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class MethodSecurityTestConfig {}
}
