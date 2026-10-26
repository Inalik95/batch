package com.vision.batch.camaroduplicate.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionSummaryTest {

    private static ExecutionSummary summary(boolean completed, Map<ProcessingOutcome, Long> counts) {
        return new ExecutionSummary(1, 1, "f.csv", CamaroFileType.DAILY, completed, counts);
    }

    @Test
    void allOk_isSuccess() {
        assertThat(summary(true, Map.of(ProcessingOutcome.APPLIED, 3L, ProcessingOutcome.ALREADY_PROCESSED, 1L))
                .status()).isEqualTo(ExecutionSummary.Status.SUCCESS);
    }

    @Test
    void emptyFile_completed_isSuccess() {
        assertThat(summary(true, Map.of()).status()).isEqualTo(ExecutionSummary.Status.SUCCESS);
    }

    @Test
    void anyWarningOrError_isPartial() {
        assertThat(summary(true, Map.of(ProcessingOutcome.APPLIED, 3L, ProcessingOutcome.OBSOLETE_USER_NOT_FOUND, 1L))
                .status()).isEqualTo(ExecutionSummary.Status.PARTIAL_SUCCESS);
        assertThat(summary(true, Map.of(ProcessingOutcome.ACTION_NOT_CONFIGURED, 1L))
                .status()).isEqualTo(ExecutionSummary.Status.PARTIAL_SUCCESS);
    }

    @Test
    void jobNotCompleted_isFailed_evenIfLinesOk() {
        assertThat(summary(false, Map.of(ProcessingOutcome.APPLIED, 3L)).status())
                .isEqualTo(ExecutionSummary.Status.FAILED);
    }
}
