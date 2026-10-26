package com.vision.batch.camaroduplicate.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Aggregated result of one job instance, used for the e-mail subject and body.
 * The status is derived from facts only: the Spring Batch status and the
 * per-line outcome counts.
 */
public record ExecutionSummary(
        long jobInstanceId,
        long jobExecutionId,
        String inputFileName,
        CamaroFileType fileType,
        boolean jobCompleted,
        Map<ProcessingOutcome, Long> countsByOutcome) {

    public enum Status { SUCCESS, PARTIAL_SUCCESS, FAILED }

    public ExecutionSummary {
        EnumMap<ProcessingOutcome, Long> copy = new EnumMap<>(ProcessingOutcome.class);
        copy.putAll(countsByOutcome);
        countsByOutcome = Collections.unmodifiableMap(copy);
    }

    public long total() {
        return countsByOutcome.values().stream().mapToLong(Long::longValue).sum();
    }

    public long count(ProcessingOutcome.Severity severity) {
        return countsByOutcome.entrySet().stream()
                .filter(e -> e.getKey().severity() == severity)
                .mapToLong(Map.Entry::getValue)
                .sum();
    }

    /**
     * FAILED          : the job did not complete (lines may be unprocessed).
     * PARTIAL_SUCCESS : the job completed but at least one line is WARNING or ERROR.
     * SUCCESS         : the job completed and every line is OK.
     */
    public Status status() {
        if (!jobCompleted) {
            return Status.FAILED;
        }
        boolean clean = count(ProcessingOutcome.Severity.WARNING) == 0
                && count(ProcessingOutcome.Severity.ERROR) == 0;
        return clean ? Status.SUCCESS : Status.PARTIAL_SUCCESS;
    }
}
