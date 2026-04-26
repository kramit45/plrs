package com.plrs.web.catalog;

import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.topic.Topic;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.topic.TopicRepository;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * INSTRUCTOR/ADMIN can stream the catalogue as CSV (FR-11). Schema
 * matches what {@link ContentImportController} accepts so an export
 * → import round-trip is possible. Quizzes are deliberately
 * excluded — they can't be authored via the import path either
 * (ContentDraft refuses QUIZ ctype).
 *
 * <p>Memory: pulls the full non-quiz catalogue in one shot via
 * {@link ContentRepository#findAllNonQuiz(int)} with a generous cap.
 * The Iter 4 demo catalogue is small (≤ a few hundred items); a
 * future Iter 5 streaming adapter can swap this for a chunked
 * cursor without changing the response shape.
 *
 * <p>Traces to: FR-11.
 */
@RestController
@ConditionalOnProperty(name = "spring.datasource.url")
public class ContentExportController {

    /** Catalogue size cap for the export pull. */
    static final int EXPORT_LIMIT = 100_000;

    private final ContentRepository contentRepository;
    private final TopicRepository topicRepository;

    public ContentExportController(
            ContentRepository contentRepository, TopicRepository topicRepository) {
        this.contentRepository = contentRepository;
        this.topicRepository = topicRepository;
    }

    @GetMapping(value = "/api/content/export", produces = "text/csv")
    @PreAuthorize("hasAnyRole('INSTRUCTOR','ADMIN')")
    public void export(HttpServletResponse response) throws IOException {
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader(
                "Content-Disposition", "attachment; filename=\"plrs-content.csv\"");

        List<Content> all = contentRepository.findAllNonQuiz(EXPORT_LIMIT);
        Map<TopicId, String> topicNames = new HashMap<>();
        for (Content c : all) {
            topicNames.computeIfAbsent(
                    c.topicId(),
                    id -> topicRepository.findById(id).map(Topic::name).orElse("(unknown)"));
        }

        try (PrintWriter w = response.getWriter();
                CSVPrinter p =
                        new CSVPrinter(
                                w,
                                CSVFormat.DEFAULT
                                        .builder()
                                        .setHeader(
                                                "topic_name",
                                                "title",
                                                "ctype",
                                                "difficulty",
                                                "est_minutes",
                                                "url",
                                                "description",
                                                "tags")
                                        .build())) {
            for (Content c : all) {
                p.printRecord(
                        topicNames.get(c.topicId()),
                        c.title(),
                        c.ctype().name(),
                        c.difficulty().name(),
                        c.estMinutes(),
                        c.url(),
                        c.description().orElse(""),
                        String.join(";", c.tags()));
            }
        }
    }
}
