package com.plrs.web.catalog;

import com.plrs.application.audit.Auditable;
import com.plrs.application.content.CsvImportResult;
import com.plrs.application.content.ImportContentCsvUseCase;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * INSTRUCTOR/ADMIN can POST a CSV file to bulk-import content
 * (FR-10). The use case persists successful rows even when others
 * fail; the response carries the count of saved rows plus a per-row
 * error list for UI display.
 *
 * <p>Traces to: FR-10.
 */
@RestController
@ConditionalOnProperty(name = "spring.datasource.url")
public class ContentImportController {

    private final ImportContentCsvUseCase useCase;

    public ContentImportController(ImportContentCsvUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping("/api/content/import")
    @PreAuthorize("hasAnyRole('INSTRUCTOR','ADMIN')")
    @Auditable(action = "CONTENT_CSV_IMPORTED", entityType = "content")
    public CsvImportResult importCsv(
            @RequestParam("file") MultipartFile file, Authentication auth) throws IOException {
        try (InputStream is = file.getInputStream()) {
            return useCase.handle(is, auth != null ? auth.getName() : "anonymous");
        }
    }
}
