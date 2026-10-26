package com.vision.batch.camaroduplicate.config;

/**
 * Job parameter names.
 *
 * fileType + inputChecksum are IDENTIFYING: Spring Batch refuses to start a
 * second instance for a file whose content already completed
 * (JobInstanceAlreadyCompleteException), which gives duplicate-file detection
 * for free, and restarts a FAILED instance from its last committed chunk.
 */
public final class JobParams {

    public static final String JOB_NAME = "camaroDuplicateJob";

    /** DAILY | MONTHLY. Identifying. */
    public static final String FILE_TYPE = "fileType";
    /** SHA-256 of the input file content. Identifying. */
    public static final String INPUT_CHECKSUM = "inputChecksum";
    /** Absolute path of the input file. Non-identifying (the file can be moved). */
    public static final String INPUT_FILE = "inputFile";

    private JobParams() {
    }
}
