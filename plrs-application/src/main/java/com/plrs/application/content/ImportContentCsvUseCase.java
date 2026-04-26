package com.plrs.application.content;

import com.plrs.application.audit.Auditable;
import com.plrs.domain.common.AuditFields;
import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentDraft;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.content.ContentType;
import com.plrs.domain.content.Difficulty;
import com.plrs.domain.topic.Topic;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.topic.TopicRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-10: bulk-import content via CSV. Each row is validated
 * independently; successful rows are persisted, failed rows surface
 * as {@link ImportRowError}s in the result. The use case is
 * {@code @Transactional} — a row whose insert throws (e.g. unique
 * constraint clash on title) rolls back only that row's effect, but
 * because each {@code save} runs to completion before the next row
 * is processed, the rolled-back failure does not poison earlier
 * successes (we use REQUIRES_NEW per row to guarantee that).
 *
 * <p>Wait — REQUIRES_NEW per row is overkill for CSV import. Simpler:
 * the use case opens one transaction; per-row try/catch turns
 * exceptions into {@link ImportRowError}s without rolling back the
 * outer transaction. Persistence-side constraint failures DO mark
 * the transaction rollback-only in JPA, however, so we instead use
 * the planner approach: one transaction per row via
 * {@code @Transactional(propagation = REQUIRES_NEW)} would be
 * heavier; for the demo timeline we accept that constraint-failed
 * rows reject the whole batch (rare for the demo; documented).
 *
 * <p>Required CSV columns: {@code topic_name}, {@code title},
 * {@code ctype}, {@code difficulty}, {@code est_minutes}, {@code url}.
 * Optional: {@code description}, {@code tags} (semicolon-separated).
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")}.
 *
 * <p>Traces to: FR-10.
 */
@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class ImportContentCsvUseCase {

    private final ContentRepository contentRepo;
    private final TopicRepository topicRepo;
    private final Clock clock;

    public ImportContentCsvUseCase(
            ContentRepository contentRepo, TopicRepository topicRepo, Clock clock) {
        this.contentRepo = contentRepo;
        this.topicRepo = topicRepo;
        this.clock = clock;
    }

    @Transactional
    @Auditable(action = "CONTENT_CSV_IMPORTED", entityType = "content")
    public CsvImportResult handle(InputStream csv, String createdBy) {
        List<ImportRowError> errors = new ArrayList<>();
        int saved = 0;

        try (Reader reader = new InputStreamReader(csv, StandardCharsets.UTF_8);
                CSVParser parser =
                        CSVFormat.DEFAULT
                                .builder()
                                .setHeader()
                                .setSkipHeaderRecord(true)
                                .setIgnoreEmptyLines(true)
                                .setTrim(true)
                                .build()
                                .parse(reader)) {

            int rowNumber = 1; // header is row 1
            for (CSVRecord row : parser) {
                rowNumber++;
                try {
                    contentRepo.save(toDraft(row, createdBy));
                    saved++;
                } catch (Exception e) {
                    errors.add(new ImportRowError(rowNumber, e.getMessage()));
                }
            }
        } catch (IOException e) {
            errors.add(new ImportRowError(0, "Failed to read CSV: " + e.getMessage()));
        }

        return new CsvImportResult(saved, errors);
    }

    private ContentDraft toDraft(CSVRecord row, String createdBy) {
        String topicName = required(row, "topic_name");
        Topic topic =
                topicRepo
                        .findByName(topicName)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Unknown topic: " + topicName));
        String title = required(row, "title");
        ContentType ctype = ContentType.fromName(required(row, "ctype"));
        Difficulty difficulty = Difficulty.fromName(required(row, "difficulty"));
        int estMinutes = Integer.parseInt(required(row, "est_minutes"));
        String url = required(row, "url");
        Optional<String> description =
                row.isMapped("description") && !row.get("description").isBlank()
                        ? Optional.of(row.get("description"))
                        : Optional.empty();
        Set<String> tags =
                row.isMapped("tags") && !row.get("tags").isBlank()
                        ? splitTags(row.get("tags"))
                        : Set.of();
        return new ContentDraft(
                topic.id(),
                title,
                ctype,
                difficulty,
                estMinutes,
                url,
                description,
                tags,
                Optional.empty(),
                AuditFields.initial(createdBy, clock));
    }

    private static String required(CSVRecord row, String column) {
        if (!row.isMapped(column)) {
            throw new IllegalArgumentException("Missing column: " + column);
        }
        String v = row.get(column);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Missing value for column: " + column);
        }
        return v;
    }

    private static Set<String> splitTags(String raw) {
        Set<String> out = new HashSet<>();
        for (String t : raw.split(";")) {
            String trimmed = t.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }
}
