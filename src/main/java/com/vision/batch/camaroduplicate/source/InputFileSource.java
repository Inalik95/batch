package com.vision.batch.camaroduplicate.source;

import java.nio.file.Path;
import java.util.List;

/**
 * Where Camaro files come from and where they go once handled.
 *
 * A file is identified by an opaque {@code ref} (an S3 object key, or an
 * absolute local path). The job itself only ever reads a local file:
 * {@link #fetch} materialises it, {@link #release} cleans up afterwards.
 *
 * Lifecycle driven by CamaroDuplicateJobRunner / JobCompletionListener:
 * <pre>
 *   list -> fetch -> [job] -> archive (COMPLETED) | nothing (FAILED, kept for restart) -> release
 *                          -> markAlreadyProcessed (same checksum already COMPLETED)
 * </pre>
 *
 * Implementations: {@link LocalDirectoryInputFileSource} (source.type=local),
 * {@link S3InputFileSource} (source.type=s3, IBM Cloud Object Storage).
 */
public interface InputFileSource {

    /** Refs of the files matching the glob (applied to the file name), oldest name first. */
    List<String> list(String fileNameGlob);

    /** Makes the file available locally and returns its path. Fails if it does not exist. */
    Path fetch(String ref);

    /** Moves the source file out of the input area after a COMPLETED execution. Must be idempotent. */
    void archive(String ref);

    /** Moves a re-delivered file (same content already COMPLETED) out of the input area. Must be idempotent. */
    void markAlreadyProcessed(String ref);

    /** Frees local resources created by {@link #fetch}. Never touches the source file. */
    void release(String ref, Path localFile);

    /** Human-readable location, for logs and the e-mail (no credentials). */
    String describe(String ref);

    /** Last path segment of a ref, used as the input file name in reports. */
    static String fileName(String ref) {
        int slash = ref.lastIndexOf('/');
        return slash < 0 ? ref : ref.substring(slash + 1);
    }
}
