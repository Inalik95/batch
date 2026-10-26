package com.vision.batch.camaroduplicate.process;

/** Stable error codes written to the CSV report. Never rename once in production. */
public final class ErrorCodes {

    public static final String BAD_COLUMN_COUNT = "E001_BAD_COLUMN_COUNT";
    public static final String MISSING_OBSOLETE_ID = "E002_MISSING_OBSOLETE_ID";
    public static final String MISSING_SURVIVOR_ID = "E003_MISSING_SURVIVOR_ID";
    public static final String INVALID_OBSOLETE_ID = "E004_INVALID_OBSOLETE_ID";
    public static final String INVALID_SURVIVOR_ID = "E005_INVALID_SURVIVOR_ID";
    public static final String SELF_MERGE = "E006_SELF_MERGE";

    public static final String MULTIPLE_SURVIVORS = "C001_MULTIPLE_SURVIVORS";
    public static final String MERGE_CHAIN = "C002_MERGE_CHAIN";

    public static final String OBSOLETE_NOT_FOUND = "N001_OBSOLETE_NOT_FOUND";
    public static final String SURVIVOR_NOT_FOUND = "N002_SURVIVOR_NOT_FOUND";

    public static final String TECHNICAL = "T001_TECHNICAL_ERROR";

    private ErrorCodes() {
    }
}
