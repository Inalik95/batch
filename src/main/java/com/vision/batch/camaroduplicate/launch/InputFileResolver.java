package com.vision.batch.camaroduplicate.launch;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Finds candidate files and fingerprints them.
 * TODO(infra): if Camaro files arrive through an existing Vision transfer
 * component (SFTP poller, S3, shared volume...), resolve them through it instead.
 */
public final class InputFileResolver {

    private InputFileResolver() {
    }

    /** Regular, readable files matching the glob, oldest name first (names are expected to carry a date). */
    public static List<Path> find(Path dir, String glob) {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, glob)) {
            for (Path p : stream) {
                if (Files.isRegularFile(p) && Files.isReadable(p)) {
                    files.add(p.toAbsolutePath());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list input directory " + dir, e);
        }
        files.sort(null);
        return files;
    }

    public static String sha256(Path file) {
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
            byte[] buffer = new byte[64 * 1024];
            while (in.read(buffer) != -1) {
                // digest is updated while reading
            }
            return HexFormat.of().formatHex(((DigestInputStream) in).getMessageDigest().digest());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
