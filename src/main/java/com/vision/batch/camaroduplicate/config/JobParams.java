package com.vision.batch.camaroduplicate.config;

/**
 * Job parameter names.
 *
 * fileType + inputChecksum are IDENTIFYING: Spring Batch refuses to start a
 * second instance for a file whose content already completed
 * (JobInstanceAlreadyCompleteException), which gives duplicate-file detection
 * for free, and restarts a FAILED instance from its last committed chunk.
 * This holds whatever the source: the checksum is computed on the downloaded bytes.
 */
public final class JobParams {

    public static final String JOB_NAME = "camaroDuplicateJob";

    /** DAILY | MONTHLY. Identifying. */
    public static final String FILE_TYPE = "fileType";
    /** SHA-256 of the input file content. Identifying. */
    public static final String INPUT_CHECKSUM = "inputChecksum";
    /** Local path the job reads (downloaded copy for S3). Non-identifying: may change on restart. */
    public static final String INPUT_FILE = "inputFile";
    /**
     * Source reference (object key for S3, path for local), used to archive the
     * original. Non-identifying. Optional: defaults to INPUT_FILE.
     */
    public static final String SOURCE_REF = "sourceRef";

    private JobParams() {
    }
}
