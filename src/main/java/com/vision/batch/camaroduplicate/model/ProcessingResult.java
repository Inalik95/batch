package com.vision.batch.camaroduplicate.model;

import java.time.Instant;

/**
 * Outcome of one input line, persisted in CAMARO_DUPLICATE_RESULT and used as
 * the single source for the CSV report (the report never re-derives anything).
 */
public record ProcessingResult(
        long lineNumber,
        String obsoleteId,
        String survivorId,
        ProcessingOutcome outcome,
        String actionPerformed,
        String errorCode,
        String errorDescription,
        Instant processedAt) {

    public static ProcessingResult of(DuplicateRecord rec, ProcessingOutcome outcome,
                                      String action, String errorCode, String errorDescription) {
        return new ProcessingResult(rec.lineNumber(), rec.obsoleteId(), rec.survivorId(),
                outcome, action, errorCode, errorDescription, Instant.now());
    }

    public static ProcessingResult ok(DuplicateRecord rec, ProcessingOutcome outcome, String action) {
        return of(rec, outcome, action, null, null);
    }

    public static ProcessingResult error(DuplicateRecord rec, ProcessingOutcome outcome,
                                         String errorCode, String errorDescription) {
        return of(rec, outcome, null, errorCode, errorDescription);
    }
}
