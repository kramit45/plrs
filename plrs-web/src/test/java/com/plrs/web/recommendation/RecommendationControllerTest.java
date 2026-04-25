package com.plrs.web.recommendation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plrs.application.recommendation.GenerateRecommendationsUseCase;
import com.plrs.application.security.TokenService;
import com.plrs.domain.common.AuditFields;
import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.content.ContentType;
import com.plrs.domain.content.Difficulty;
import com.plrs.domain.recommendation.Recommendation;
import com.plrs.domain.recommendation.RecommendationReason;
import com.plrs.domain.recommendation.RecommendationScore;
import com.plrs.domain.topic.Topic;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.topic.TopicRepository;
import com.plrs.domain.user.UserId;
import com.plrs.web.common.PerUserRateLimiter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = RecommendationController.class,
        includeFilters =
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = com.plrs.web.common.GlobalExceptionHandler.class))
@AutoConfigureMockMvc(addFilters = false)
@Import(RecommendationControllerTest.MethodSecurityTestConfig.class)
class RecommendationControllerTest {

    private static final String STUDENT_UUID = "11111111-2222-3333-4444-555555555555";
    private static final String OTHER_UUID = "22222222-3333-4444-5555-666666666666";
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-04-25T10:00:00Z"), ZoneOffset.UTC);
    private static final TopicId TOPIC = TopicId.of(7L);

    @Autowired private MockMvc mockMvc;
    @Autowired private PerUserRateLimiter rateLimiter;

    @MockBean private GenerateRecommendationsUseCase useCase;
    @MockBean private ContentRepository contentRepository;
    @MockBean private TopicRepository topicRepository;
    @MockBean private TokenService tokenService;

    @BeforeEach
    void clearLimiter() {
        rateLimiter.clear();
    }

    private static Content content(long id, String title) {
        return Content.rehydrate(
                ContentId.of(id),
                TOPIC,
                title,
                ContentType.VIDEO,
                Difficulty.BEGINNER,
                10,
                "https://x.y/" + id,
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                AuditFields.initial("system", CLOCK));
    }

    private static Topic topic() {
        return Topic.rehydrate(
                TOPIC, "Algebra", "desc", Optional.empty(),
                AuditFields.initial("system", CLOCK));
    }

    private static Recommendation rec(long contentId, int rank, double score) {
        return Recommendation.rehydrate(
                UserId.of(UUID.fromString(STUDENT_UUID)),
                ContentId.of(contentId),
                Instant.parse("2026-04-25T10:00:00Z"),
                RecommendationScore.of(score),
                rank,
                new RecommendationReason("Popular among learners exploring Algebra"),
                "popularity_v1",
                Optional.empty(),
                Optional.empty());
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void getRecommendationsHappyPath() throws Exception {
        when(useCase.handle(any(), anyInt()))
                .thenReturn(List.of(rec(101L, 1, 0.9), rec(102L, 2, 0.7)));
        when(contentRepository.findById(ContentId.of(101L)))
                .thenReturn(Optional.of(content(101L, "Vid 101")));
        when(contentRepository.findById(ContentId.of(102L)))
                .thenReturn(Optional.of(content(102L, "Vid 102")));
        when(topicRepository.findById(TOPIC)).thenReturn(Optional.of(topic()));

        mockMvc.perform(get("/api/recommendations").param("k", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].contentId").value(101))
                .andExpect(jsonPath("$[0].title").value("Vid 101"))
                .andExpect(jsonPath("$[0].topic").value("Algebra"))
                .andExpect(jsonPath("$[0].rank").value(1))
                .andExpect(jsonPath("$[0].score").value(0.9));
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void rejectsKBelowOne() throws Exception {
        mockMvc.perform(get("/api/recommendations").param("k", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void rejectsKAboveFifty() throws Exception {
        mockMvc.perform(get("/api/recommendations").param("k", "51"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void studentQueryingAnotherUsersRecsForbidden() throws Exception {
        mockMvc.perform(
                        get("/api/recommendations")
                                .param("k", "5")
                                .param("userId", OTHER_UUID))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "ADMIN")
    void adminQueryingAnotherUsersRecsAllowed() throws Exception {
        when(useCase.handle(any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(
                        get("/api/recommendations")
                                .param("k", "5")
                                .param("userId", OTHER_UUID))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void rateLimit429AfterTwentyOneRequestsInOneWindow() throws Exception {
        when(useCase.handle(any(), anyInt())).thenReturn(List.of());

        for (int i = 0; i < PerUserRateLimiter.LIMIT_PER_WINDOW; i++) {
            mockMvc.perform(get("/api/recommendations").param("k", "1"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/recommendations").param("k", "1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class MethodSecurityTestConfig {

        @Bean
        Clock testClock() {
            return CLOCK;
        }

        @Bean
        PerUserRateLimiter rateLimiter(Clock clock) {
            return new PerUserRateLimiter(clock);
        }
    }
}
