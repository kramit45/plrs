package com.plrs.web.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plrs.web.health.HealthController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Pins the two contractual behaviours of {@link RequestIdFilter}: the
 * filter always stamps an {@code X-Request-Id} on the response, and when
 * the caller supplies one it is echoed verbatim. Uses standalone
 * MockMvc rather than {@code @WebMvcTest} so the test has no opinion on
 * whatever security filter chain the web module configures — all we
 * want to exercise is this one filter against a bare controller.
 */
class RequestIdFilterTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.standaloneSetup(new HealthController())
                        .addFilters(new RequestIdFilter())
                        .build();
    }

    @Test
    void generatesRequestIdWhenInboundHeaderAbsent() throws Exception {
        MvcResult result =
                mockMvc.perform(get("/health"))
                        .andExpect(status().isOk())
                        .andExpect(header().exists("X-Request-Id"))
                        .andReturn();

        String generated = result.getResponse().getHeader("X-Request-Id");
        assertThat(generated).isNotBlank();
    }

    @Test
    void echoesInboundRequestIdHeader() throws Exception {
        String inbound = "11111111-2222-3333-4444-555555555555";

        mockMvc.perform(get("/health").header("X-Request-Id", inbound))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", inbound));
    }
}
