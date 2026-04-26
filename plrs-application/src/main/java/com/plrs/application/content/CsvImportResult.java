package com.plrs.application.content;

import java.util.List;

/**
 * Outcome of an {@link ImportContentCsvUseCase} invocation. Successful
 * rows are persisted regardless of whether other rows errored — FR-10
 * "successful rows persist" semantic.
 */
public record CsvImportResult(int saved, List<ImportRowError> errors) {

    public CsvImportResult {
        if (errors == null) {
            errors = List.of();
        } else {
            errors = List.copyOf(errors);
        }
    }
}
