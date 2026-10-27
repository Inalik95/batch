package com.vision.batch.camaroduplicate.launch;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Content fingerprint of a local file. Computed on the downloaded bytes (not the
 * S3 ETag, which is not a content hash for multipart uploads), so re-delivery
 * detection works the same for every source.
 * File discovery lives in {@link com.vision.batch.camaroduplicate.source.InputFileSource}.
 */
public final class InputFileResolver {

    private InputFileResolver() {
    }

    public static String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
                byte[] buffer = new byte[64 * 1024];
                while (in.read(buffer) != -1) {
                    // digest is updated while reading
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
