package com.vision.batch.camaroduplicate.action;

import com.vision.batch.camaroduplicate.model.DuplicateRecord;

/**
 * THE extension point for the unconfirmed business rule.
 *
 * Implement it once the target table/attribute and the expected operation are
 * confirmed. These are distinct operations; implement only what is decided:
 *   - mark the obsolete account as obsolete / merged
 *   - store the obsolete -> survivor relationship
 *   - disable the obsolete account
 *   - (physical delete: only if explicitly validated)
 *   - flag the account for Portal (or let the CSV report carry it)
 *
 * Contract:
 *  - Called inside the chunk transaction: use Vision's existing repositories/DAOs,
 *    do not open your own transaction, do not swallow DataAccessException
 *    (the step skips the line as TECHNICAL_ERROR and rolls the chunk back).
 *  - MUST be idempotent: check the current state first and return
 *    ALREADY_PROCESSED instead of applying the change twice.
 *  - Input has already been validated and checked for in-file conflicts.
 */
public interface DuplicateMergeHandler {

    MergeResult handle(DuplicateRecord rec);

    /** Short label written to the report, e.g. "DRY_RUN" or "DEACTIVATE_OBSOLETE". */
    String actionName();

    enum MergeStatus {
        APPLIED,
        ALREADY_PROCESSED,
        OBSOLETE_NOT_FOUND,
        SURVIVOR_NOT_FOUND,
        NOT_CONFIGURED
    }

    /** @param detail optional, non-personal detail for the report (no names, no e-mails) */
    record MergeResult(MergeStatus status, String detail) {
        public static MergeResult of(MergeStatus status) {
            return new MergeResult(status, null);
        }
    }
}
