package com.vision.batch.camaroduplicate.model;

/**
 * Per-line result. Every input line gets exactly one outcome; nothing is
 * skipped silently.
 */
public enum ProcessingOutcome {

    /** The configured action changed the Vision database. */
    APPLIED(Severity.OK),
    /** The target state already existed (re-delivery or re-run). No change. */
    ALREADY_PROCESSED(Severity.OK),
    /** Same obsolete -> survivor pair already seen earlier in this file. */
    DUPLICATE_LINE(Severity.OK),
    /** No business action is configured yet (default dry-run handler). */
    ACTION_NOT_CONFIGURED(Severity.WARNING),

    OBSOLETE_USER_NOT_FOUND(Severity.WARNING),
    SURVIVOR_USER_NOT_FOUND(Severity.WARNING),

    /** Structure or identifier is invalid. */
    VALIDATION_ERROR(Severity.ERROR),
    /** The file contains contradicting instructions for this identifier. */
    CONFLICT(Severity.ERROR),
    /** Unexpected failure while applying the action. Chunk-level rollback happened. */
    TECHNICAL_ERROR(Severity.ERROR);

    public enum Severity { OK, WARNING, ERROR }

    private final Severity severity;

    ProcessingOutcome(Severity severity) {
        this.severity = severity;
    }

    public Severity severity() {
        return severity;
    }
}
