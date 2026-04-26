package com.plrs.web.catalog;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plrs.application.content.CsvImportResult;
import com.plrs.application.content.ImportContentCsvUseCase;
import com.plrs.application.security.TokenService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ContentImportController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ContentImportControllerTest.MethodSecurityTestConfig.class)
class ContentImportControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private ImportContentCsvUseCase useCase;
    @MockBean private TokenService tokenService;

    @Test
    @WithMockUser(roles = "INSTRUCTOR")
    void instructorImportSucceeds() throws Exception {
        when(useCase.handle(any(), anyString())).thenReturn(new CsvImportResult(2, List.of()));

        MockMultipartFile file =
                new MockMultipartFile(
                        "file", "x.csv", "text/csv", "header\nrow1\n".getBytes());
        mockMvc.perform(multipart("/api/content/import").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(2));
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    void studentImportForbidden() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile("file", "x.csv", "text/csv", "h\nr\n".getBytes());
        mockMvc.perform(multipart("/api/content/import").file(file))
                .andExpect(status().isForbidden());
        verify(useCase, never()).handle(any(), anyString());
    }

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class MethodSecurityTestConfig {}
}
