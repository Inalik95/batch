package com.vision.batch.camaroduplicate.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Files dropped in a local / mounted directory (source.type=local).
 * Ref = absolute path. Kept for local runs, tests and as a fallback.
 */
public class LocalDirectoryInputFileSource implements InputFileSource {

    private static final Logger log = LoggerFactory.getLogger(LocalDirectoryInputFileSource.class);

    private final Path inputDir;
    private final Path archiveDir;

    public LocalDirectoryInputFileSource(Path inputDir, Path archiveDir) {
        this.inputDir = Objects.requireNonNull(inputDir, "vision.batch.camaro-duplicate.input-dir is required for source.type=local");
        this.archiveDir = Objects.requireNonNull(archiveDir, "vision.batch.camaro-duplicate.archive-dir is required for source.type=local");
    }

    @Override
    public List<String> list(String fileNameGlob) {
        List<String> refs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(inputDir, fileNameGlob)) {
            for (Path p : stream) {
                if (Files.isRegularFile(p) && Files.isReadable(p)) {
                    refs.add(p.toAbsolutePath().toString());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list input directory " + inputDir, e);
        }
        refs.sort(null);
        return refs;
    }

    @Override
    public Path fetch(String ref) {
        return Path.of(ref);
    }

    @Override
    public void archive(String ref) {
        move(Path.of(ref), Path.of(ref).getFileName().toString());
    }

    @Override
    public void markAlreadyProcessed(String ref) {
        move(Path.of(ref), Path.of(ref).getFileName() + ".already-processed");
    }

    @Override
    public void release(String ref, Path localFile) {
        // The local file IS the source: nothing to clean.
    }

    @Override
    public String describe(String ref) {
        return ref;
    }

    private void move(Path file, String targetName) {
        if (!Files.exists(file)) {
            return; // already moved (idempotent)
        }
        try {
            Files.createDirectories(archiveDir);
            Path target = archiveDir.resolve(targetName);
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("Input moved to {}", target);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot move " + file + " to " + archiveDir, e);
        }
    }
}
