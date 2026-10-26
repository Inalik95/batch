package com.vision.batch.camaroduplicate.process;

import com.vision.batch.camaroduplicate.action.DuplicateMergeHandler;
import com.vision.batch.camaroduplicate.action.DuplicateMergeHandler.MergeResult;
import com.vision.batch.camaroduplicate.model.DuplicateRecord;
import com.vision.batch.camaroduplicate.model.ProcessingOutcome;
import com.vision.batch.camaroduplicate.model.ProcessingResult;
import org.springframework.batch.item.ItemProcessor;

/**
 * Decides the outcome of one line. Never returns null (null would filter the
 * line out of the writer, i.e. a silent skip).
 *
 * Order: structure -> in-file duplicate -> in-file conflict -> business action.
 * Technical exceptions from the handler propagate: the step skips the line,
 * rolls back the chunk, and the skip listener records TECHNICAL_ERROR.
 */
public class DuplicateMergeProcessor implements ItemProcessor<DuplicateRecord, ProcessingResult> {

    private final DuplicateRecordValidator validator;
    private final ConflictRegistry conflicts;
    private final DuplicateMergeHandler handler;

    public DuplicateMergeProcessor(DuplicateRecordValidator validator, ConflictRegistry conflicts,
                                   DuplicateMergeHandler handler) {
        this.validator = validator;
        this.conflicts = conflicts;
        this.handler = handler;
    }

    @Override
    public ProcessingResult process(DuplicateRecord rec) {
        var violation = validator.validate(rec);
        if (violation.isPresent()) {
            return ProcessingResult.error(rec, ProcessingOutcome.VALIDATION_ERROR,
                    violation.get().code(), violation.get().description());
        }
        if (conflicts.isRepeatedLine(rec)) {
            return ProcessingResult.ok(rec, ProcessingOutcome.DUPLICATE_LINE, "NONE");
        }
        var conflict = conflicts.conflictFor(rec);
        if (conflict.isPresent()) {
            return ProcessingResult.error(rec, ProcessingOutcome.CONFLICT,
                    conflict.get().code(), conflict.get().description());
        }
        return toResult(rec, handler.handle(rec));
    }

    private ProcessingResult toResult(DuplicateRecord rec, MergeResult result) {
        String action = handler.actionName();
        return switch (result.status()) {
            case APPLIED -> ProcessingResult.of(rec, ProcessingOutcome.APPLIED, action, null, result.detail());
            case ALREADY_PROCESSED -> ProcessingResult.of(rec, ProcessingOutcome.ALREADY_PROCESSED, "NONE", null, result.detail());
            case NOT_CONFIGURED -> ProcessingResult.of(rec, ProcessingOutcome.ACTION_NOT_CONFIGURED, action, null,
                    "No business action configured, database untouched");
            case OBSOLETE_NOT_FOUND -> ProcessingResult.error(rec, ProcessingOutcome.OBSOLETE_USER_NOT_FOUND,
                    ErrorCodes.OBSOLETE_NOT_FOUND, "Obsolete user not found in Vision");
            case SURVIVOR_NOT_FOUND -> ProcessingResult.error(rec, ProcessingOutcome.SURVIVOR_USER_NOT_FOUND,
                    ErrorCodes.SURVIVOR_NOT_FOUND, "Surviving user not found in Vision");
        };
    }
}
