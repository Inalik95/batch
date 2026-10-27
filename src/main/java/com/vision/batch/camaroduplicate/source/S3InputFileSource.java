package com.vision.batch.camaroduplicate.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Camaro files delivered in an IBM Cloud Object Storage bucket (S3 API).
 * Ref = object key.
 *
 * <ul>
 *   <li>list    : objects directly under {@code inputPrefix} (sub-"folders" ignored, so an
 *                 archive prefix nested under the input prefix is never re-read)</li>
 *   <li>fetch   : download to {@code workDir/<file name>} via a .part file + atomic move</li>
 *   <li>archive : copy to {@code archivePrefix}, then delete the source (COS has no move).
 *                 Idempotent: a missing source means it was already archived.</li>
 *   <li>release : delete the local copy (the pod disk is not the system of record)</li>
 * </ul>
 *
 * Restart safety: a FAILED file stays under inputPrefix; the next run downloads it again,
 * gets the same checksum, and Spring Batch restarts the same job instance.
 *
 * Required IAM role on the bucket: Writer (list, get, put, delete).
 */
public class S3InputFileSource implements InputFileSource {

    private static final Logger log = LoggerFactory.getLogger(S3InputFileSource.class);

    private final S3Client s3;
    private final String bucket;
    private final String inputPrefix;
    private final String archivePrefix;
    private final String alreadyProcessedPrefix;
    private final Path workDir;

    public S3InputFileSource(S3Client s3, String bucket, String inputPrefix, String archivePrefix,
                             String alreadyProcessedPrefix, Path workDir) {
        this.s3 = Objects.requireNonNull(s3);
        this.bucket = requireText(bucket, "bucket");
        this.inputPrefix = normalizePrefix(inputPrefix);
        this.archivePrefix = normalizePrefix(requireText(archivePrefix, "archive-prefix"));
        this.alreadyProcessedPrefix = normalizePrefix(
                alreadyProcessedPrefix == null || alreadyProcessedPrefix.isBlank() ? archivePrefix : alreadyProcessedPrefix);
        this.workDir = Objects.requireNonNull(workDir, "work-dir");
        if (this.archivePrefix.equals(this.inputPrefix)) {
            throw new IllegalArgumentException("archive-prefix must differ from input-prefix");
        }
    }

    @Override
    public List<String> list(String fileNameGlob) {
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + fileNameGlob);
        List<String> keys = new ArrayList<>();
        String token = null;
        do {
            ListObjectsV2Response page = s3.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(inputPrefix)
                    .continuationToken(token)
                    .build());
            for (S3Object object : page.contents()) {
                String key = object.key();
                String relative = key.substring(inputPrefix.length());
                if (!relative.isEmpty() && relative.indexOf('/') < 0 && isSafeFileName(relative)
                        && matcher.matches(Path.of(relative))) {
                    keys.add(key);
                }
            }
            token = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
        } while (token != null);
        keys.sort(null);
        return keys;
    }

    @Override
    public Path fetch(String key) {
        String name = InputFileSource.fileName(key);
        if (!isSafeFileName(name)) {
            throw new IllegalArgumentException("Unsupported object name: " + key);
        }
        Path target = workDir.resolve(name);
        Path part = workDir.resolve(name + ".part");
        try {
            Files.createDirectories(workDir);
            Files.deleteIfExists(part);
            s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(), ResponseTransformer.toFile(part));
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("Downloaded {} ({} bytes)", describe(key), Files.size(target));
            return target;
        } catch (IOException e) {
            deleteQuietly(part);
            throw new UncheckedIOException("Cannot store download of " + describe(key) + " in " + workDir, e);
        } catch (RuntimeException e) {
            deleteQuietly(part);
            throw e;
        }
    }

    @Override
    public void archive(String key) {
        moveObject(key, archivePrefix + InputFileSource.fileName(key));
    }

    @Override
    public void markAlreadyProcessed(String key) {
        moveObject(key, alreadyProcessedPrefix + InputFileSource.fileName(key) + ".already-processed");
    }

    @Override
    public void release(String key, Path localFile) {
        deleteQuietly(localFile);
    }

    @Override
    public String describe(String key) {
        return "cos://" + bucket + "/" + key;
    }

    private void moveObject(String sourceKey, String targetKey) {
        if (!exists(sourceKey)) {
            log.info("{} no longer in input area, nothing to move", describe(sourceKey));
            return;
        }
        s3.copyObject(CopyObjectRequest.builder()
                .sourceBucket(bucket).sourceKey(sourceKey)
                .destinationBucket(bucket).destinationKey(targetKey)
                .build());
        // Delete only after a successful copy: a crash in between leaves two copies, never zero.
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(sourceKey).build());
        log.info("Moved {} to {}", describe(sourceKey), describe(targetKey));
    }

    private boolean exists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    /** "" stays "", "/a/b" becomes "a/b/". */
    public static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String p = prefix.strip();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p.isEmpty() || p.endsWith("/") ? p : p + "/";
    }

    private static boolean isSafeFileName(String name) {
        return !name.isBlank() && !name.equals(".") && !name.equals("..")
                && name.indexOf('/') < 0 && name.indexOf('\\') < 0 && name.indexOf('\0') < 0;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("vision.batch.camaro-duplicate.source.s3." + name + " is required");
        }
        return value;
    }

    private static void deleteQuietly(Path file) {
        try {
            if (file != null) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            log.warn("Could not delete local file {}", file, e);
        }
    }
}
