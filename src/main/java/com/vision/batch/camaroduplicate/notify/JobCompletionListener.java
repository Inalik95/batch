package com.vision.batch.camaroduplicate.notify;

import com.vision.batch.camaroduplicate.config.JobParams;
import com.vision.batch.camaroduplicate.model.CamaroFileType;
import com.vision.batch.camaroduplicate.model.ExecutionSummary;
import com.vision.batch.camaroduplicate.report.CsvReportGenerator;
import com.vision.batch.camaroduplicate.report.ReportArchiver;
import com.vision.batch.camaroduplicate.result.ProcessingResultRepository;
import com.vision.batch.camaroduplicate.source.InputFileSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParameters;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Runs after every execution, whatever its status:
 * summary -> CSV -> store CSV -> mail -> archive source file (COMPLETED only).
 *
 * Each stage is isolated: a report, upload or mail failure is logged and surfaced
 * through the exit code, but never rewrites the database outcome (BatchStatus stays as is).
 *
 * Exit codes (mapped to process exit in CamaroDuplicateJobRunner):
 *   COMPLETED                     -> 0  (SUCCESS or PARTIAL_SUCCESS, details in the report)
 *   COMPLETED_NOTIFICATION_FAILED -> 3  (DB done, report / upload / mail missing: ops must act)
 *   FAILED                        -> 1
 */
public class JobCompletionListener implements JobExecutionListener {

    public static final String EXIT_NOTIFICATION_FAILED = "COMPLETED_NOTIFICATION_FAILED";

    private static final Logger log = LoggerFactory.getLogger(JobCompletionListener.class);

    private final ProcessingResultRepository repository;
    private final CsvReportGenerator reportGenerator;
    private final ReportArchiver reportArchiver;
    private final ReportNotifier notifier;
    private final InputFileSource inputFileSource;

    public JobCompletionListener(ProcessingResultRepository repository, CsvReportGenerator reportGenerator,
                                 ReportArchiver reportArchiver, ReportNotifier notifier,
                                 InputFileSource inputFileSource) {
        this.repository = repository;
        this.reportGenerator = reportGenerator;
        this.reportArchiver = reportArchiver;
        this.notifier = notifier;
        this.inputFileSource = inputFileSource;
    }

    @Override
    public void afterJob(JobExecution execution) {
        JobParameters params = execution.getJobParameters();
        String inputFile = params.getString(JobParams.INPUT_FILE);
        String sourceRef = params.getString(JobParams.SOURCE_REF, inputFile);
        ExecutionSummary summary = new ExecutionSummary(
                execution.getJobInstance().getInstanceId(),
                execution.getId(),
                InputFileSource.fileName(sourceRef),
                CamaroFileType.valueOf(params.getString(JobParams.FILE_TYPE)),
                execution.getStatus() == BatchStatus.COMPLETED,
                repository.countByOutcome(execution.getJobInstance().getInstanceId()));

        log.info("Camaro duplicate job instance={} execution={} source={} status={} counts={}",
                summary.jobInstanceId(), summary.jobExecutionId(), inputFileSource.describe(sourceRef),
                summary.status(), summary.countsByOutcome());

        Path report = null;
        String reportError = null;
        try {
            report = reportGenerator.generate(summary, Instant.now());
            log.info("Report written: {}", report);
        } catch (RuntimeException e) {
            reportError = e.getClass().getSimpleName();
            log.error("Report generation failed for instance {}", summary.jobInstanceId(), e);
        }

        boolean stored = report == null;   // nothing to store if generation failed (already counted)
        if (report != null) {
            try {
                log.info("Report stored: {}", reportArchiver.store(report));
                stored = true;
            } catch (RuntimeException e) {
                log.error("Report upload failed for instance {} (still attached to the mail)", summary.jobInstanceId(), e);
            }
        }

        boolean notified = true;
        try {
            notifier.notify(summary, report, reportError);
        } catch (RuntimeException e) {
            notified = false;
            log.error("Notification failed for instance {}", summary.jobInstanceId(), e);
        }

        if (summary.jobCompleted()) {
            archive(sourceRef);
            if (report == null || !stored || !notified) {
                execution.setExitStatus(new ExitStatus(EXIT_NOTIFICATION_FAILED,
                        "Database processing completed; report=" + (report != null)
                                + ", stored=" + stored + ", mail=" + notified));
            }
        }
        // FAILED: the source file stays in the input area so the same instance can be restarted.
    }

    private void archive(String sourceRef) {
        try {
            inputFileSource.archive(sourceRef);
        } catch (RuntimeException e) {
            // Not fatal: if the file is picked up again, the checksum identifying
            // parameter blocks reprocessing and the runner moves it aside.
            log.error("Could not archive {}", inputFileSource.describe(sourceRef), e);
        }
    }
}
