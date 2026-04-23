package com.plrs.web.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plrs.web.health.HealthController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Slice test pinning the two contractual behaviors of {@link RequestIdFilter}:
 * the filter always stamps an {@code X-Request-Id} on the response, and when
 * the caller supplies one it is echoed verbatim. HealthController is the
 * target because it is the only controller in the module today and has no
 * dependencies that would drag in a full context.
 */
@WebMvcTest(HealthController.class)
@Import(RequestIdFilter.class)
class RequestIdFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void generatesRequestIdWhenInboundHeaderAbsent() throws Exception {
        MvcResult result = mockMvc.perform(get("/health"))
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
