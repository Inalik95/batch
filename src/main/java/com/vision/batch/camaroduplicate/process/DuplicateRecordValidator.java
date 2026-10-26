package com.vision.batch.camaroduplicate.process;

import com.vision.batch.camaroduplicate.config.CamaroDuplicateProperties.FileFormat;
import com.vision.batch.camaroduplicate.model.DuplicateRecord;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Structural and identifier validation, no database access.
 * Returns the first violation found, as an error code from {@link ErrorCodes}.
 */
public class DuplicateRecordValidator {

    public record Violation(String code, String description) {
    }

    private final int expectedColumns;
    private final Pattern identifierPattern;

    public DuplicateRecordValidator(FileFormat format) {
        this.expectedColumns = format.getExpectedColumns();
        this.identifierPattern = Pattern.compile(format.getIdentifierPattern());
    }

    public Optional<Violation> validate(DuplicateRecord rec) {
        if (rec.tokenCount() != expectedColumns) {
            return violation(ErrorCodes.BAD_COLUMN_COUNT,
                    "Expected " + expectedColumns + " columns, found " + rec.tokenCount());
        }
        if (isBlank(rec.obsoleteId())) {
            return violation(ErrorCodes.MISSING_OBSOLETE_ID, "Obsolete identifier is empty");
        }
        if (isBlank(rec.survivorId())) {
            return violation(ErrorCodes.MISSING_SURVIVOR_ID, "Surviving identifier is empty");
        }
        if (!identifierPattern.matcher(rec.obsoleteId()).matches()) {
            return violation(ErrorCodes.INVALID_OBSOLETE_ID, "Obsolete identifier has an invalid format");
        }
        if (!identifierPattern.matcher(rec.survivorId()).matches()) {
            return violation(ErrorCodes.INVALID_SURVIVOR_ID, "Surviving identifier has an invalid format");
        }
        if (rec.obsoleteId().equals(rec.survivorId())) {
            return violation(ErrorCodes.SELF_MERGE, "Obsolete and surviving identifiers are identical");
        }
        return Optional.empty();
    }

    private static Optional<Violation> violation(String code, String description) {
        return Optional.of(new Violation(code, description));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
