package com.vision.batch.camaroduplicate.notify;

import com.vision.batch.camaroduplicate.config.JobParams;
import com.vision.batch.camaroduplicate.model.CamaroFileType;
import com.vision.batch.camaroduplicate.model.ExecutionSummary;
import com.vision.batch.camaroduplicate.report.CsvReportGenerator;
import com.vision.batch.camaroduplicate.result.ProcessingResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/**
 * Runs after every execution, whatever its status: summary -> CSV -> mail -> archive.
 *
 * Each stage is isolated: a report or mail failure is logged and surfaced through
 * the exit code, but never rewrites the database outcome (BatchStatus stays as is).
 *
 * Exit codes (mapped to process exit in CamaroDuplicateJobRunner):
 *   COMPLETED                     -> 0  (SUCCESS or PARTIAL_SUCCESS, details in the report)
 *   COMPLETED_NOTIFICATION_FAILED -> 3  (DB done, report or mail missing: ops must act)
 *   FAILED                        -> 1
 */
public class JobCompletionListener implements JobExecutionListener {

    public static final String EXIT_NOTIFICATION_FAILED = "COMPLETED_NOTIFICATION_FAILED";

    private static final Logger log = LoggerFactory.getLogger(JobCompletionListener.class);

    private final ProcessingResultRepository repository;
    private final CsvReportGenerator reportGenerator;
    private final ReportNotifier notifier;
    private final Path archiveDir;

    public JobCompletionListener(ProcessingResultRepository repository, CsvReportGenerator reportGenerator,
                                 ReportNotifier notifier, Path archiveDir) {
        this.repository = repository;
        this.reportGenerator = reportGenerator;
        this.notifier = notifier;
        this.archiveDir = archiveDir;
    }

    @Override
    public void afterJob(JobExecution execution) {
        Path inputFile = Path.of(execution.getJobParameters().getString(JobParams.INPUT_FILE));
        ExecutionSummary summary = new ExecutionSummary(
                execution.getJobInstance().getInstanceId(),
                execution.getId(),
                inputFile.getFileName().toString(),
                CamaroFileType.valueOf(execution.getJobParameters().getString(JobParams.FILE_TYPE)),
                execution.getStatus() == BatchStatus.COMPLETED,
                repository.countByOutcome(execution.getJobInstance().getInstanceId()));

        log.info("Camaro duplicate job instance={} execution={} status={} counts={}",
                summary.jobInstanceId(), summary.jobExecutionId(), summary.status(), summary.countsByOutcome());

        Path report = null;
        String reportError = null;
        try {
            report = reportGenerator.generate(summary, Instant.now());
            log.info("Report written: {}", report);
        } catch (RuntimeException e) {
            reportError = e.getClass().getSimpleName();
            log.error("Report generation failed for instance {}", summary.jobInstanceId(), e);
        }

        boolean notified = true;
        try {
            notifier.notify(summary, report, reportError);
        } catch (RuntimeException e) {
            notified = false;
            log.error("Notification failed for instance {}", summary.jobInstanceId(), e);
        }

        if (summary.jobCompleted()) {
            archive(inputFile);
            if (report == null || !notified) {
                execution.setExitStatus(new ExitStatus(EXIT_NOTIFICATION_FAILED,
                        "Database processing completed; report=" + (report != null) + ", mail=" + notified));
            }
        }
        // FAILED: the file stays in inputDir so the same instance can be restarted.
    }

    private void archive(Path inputFile) {
        try {
            Files.createDirectories(archiveDir);
            Path target = archiveDir.resolve(inputFile.getFileName());
            Files.move(inputFile, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("Input archived to {}", target);
        } catch (IOException e) {
            // Not fatal: re-ingestion is blocked by the checksum identifying parameter.
            log.error("Could not archive {}", inputFile, e);
        }
    }
}
