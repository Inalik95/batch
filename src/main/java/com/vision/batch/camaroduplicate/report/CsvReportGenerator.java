package com.vision.batch.camaroduplicate.report;

import com.vision.batch.camaroduplicate.model.ExecutionSummary;
import com.vision.batch.camaroduplicate.model.ProcessingResult;
import com.vision.batch.camaroduplicate.result.ProcessingResultRepository;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Builds the CSV from persisted outcomes only (what really happened, including
 * lines from a previous failed execution of the same instance).
 * Written to a .tmp file then moved atomically: no half-written report is ever visible.
 *
 * Format: ';' separator, UTF-8 with BOM (opens correctly in French Excel),
 * RFC 4180 quoting. Personal data is limited to the two Camaro identifiers.
 */
public class CsvReportGenerator {

    static final String[] HEADER = {
            "JOB_INSTANCE_ID", "INPUT_FILE", "FILE_TYPE", "LINE_NUMBER",
            "OBSOLETE_ID", "SURVIVOR_ID", "OUTCOME", "ACTION_PERFORMED",
            "ERROR_CODE", "ERROR_DESCRIPTION", "PROCESSED_AT"};

    private static final char SEP = ';';
    private static final DateTimeFormatter NAME_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.of("Europe/Paris"));

    private final ProcessingResultRepository repository;
    private final Path reportDir;

    public CsvReportGenerator(ProcessingResultRepository repository, Path reportDir) {
        this.repository = repository;
        this.reportDir = reportDir;
    }

    public Path generate(ExecutionSummary summary, java.time.Instant now) {
        String fileName = "vision_camaro_duplicates_%s_%d_%s.csv".formatted(
                summary.fileType().name().toLowerCase(), summary.jobInstanceId(), NAME_TS.format(now));
        Path target = reportDir.resolve(fileName);
        Path tmp = reportDir.resolve(fileName + ".tmp");
        try {
            Files.createDirectories(reportDir);
            try (BufferedWriter out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                out.write('﻿');
                writeRow(out, HEADER);
                IOException[] failure = new IOException[1];
                repository.forEach(summary.jobInstanceId(), r -> {
                    if (failure[0] == null) {
                        try {
                            writeRow(out, toRow(summary, r));
                        } catch (IOException e) {
                            failure[0] = e;
                        }
                    }
                });
                if (failure[0] != null) {
                    throw failure[0];
                }
            }
            return Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort
            }
            throw new UncheckedIOException("CSV report generation failed: " + target, e);
        }
    }

    private static String[] toRow(ExecutionSummary s, ProcessingResult r) {
        return new String[]{
                String.valueOf(s.jobInstanceId()), s.inputFileName(), s.fileType().name(),
                String.valueOf(r.lineNumber()), r.obsoleteId(), r.survivorId(), r.outcome().name(),
                r.actionPerformed(), r.errorCode(), r.errorDescription(), r.processedAt().toString()};
    }

    static void writeRow(BufferedWriter out, String[] values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                out.write(SEP);
            }
            out.write(escape(values[i]));
        }
        out.write("\r\n");
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        // Neutralise spreadsheet formula injection.
        String v = !value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        boolean quote = v.indexOf(SEP) >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
        return quote ? '"' + v.replace("\"", "\"\"") + '"' : v;
    }
}
