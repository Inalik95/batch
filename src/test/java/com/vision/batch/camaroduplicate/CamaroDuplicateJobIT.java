package com.vision.batch.camaroduplicate;

import com.vision.batch.camaroduplicate.action.DuplicateMergeHandler;
import com.vision.batch.camaroduplicate.action.DuplicateMergeHandler.MergeResult;
import com.vision.batch.camaroduplicate.action.DuplicateMergeHandler.MergeStatus;
import com.vision.batch.camaroduplicate.config.JobParams;
import com.vision.batch.camaroduplicate.launch.InputFileResolver;
import com.vision.batch.camaroduplicate.model.CamaroFileType;
import com.vision.batch.camaroduplicate.model.ExecutionSummary;
import com.vision.batch.camaroduplicate.model.ProcessingOutcome;
import com.vision.batch.camaroduplicate.notify.JobCompletionListener;
import com.vision.batch.camaroduplicate.notify.ReportNotifier;
import com.vision.batch.camaroduplicate.result.ProcessingResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBatchTest
@SpringBootTest
@ActiveProfiles("test")
class CamaroDuplicateJobIT {

    private static final Path ROOT;

    static {
        try {
            ROOT = Files.createTempDirectory("camaro-it");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry registry) {
        registry.add("vision.batch.camaro-duplicate.input-dir", () -> ROOT.resolve("in").toString());
        registry.add("vision.batch.camaro-duplicate.archive-dir", () -> ROOT.resolve("archive").toString());
        registry.add("vision.batch.camaro-duplicate.report-dir", () -> ROOT.resolve("report").toString());
    }

    @Autowired
    JobLauncherTestUtils launcher;
    @Autowired
    ProcessingResultRepository results;

    @MockBean
    DuplicateMergeHandler handler;
    @MockBean
    ReportNotifier notifier;

    @BeforeEach
    void setUp() throws IOException {
        reset(handler, notifier);
        when(handler.actionName()).thenReturn("TEST_ACTION");
        when(handler.handle(any())).thenReturn(MergeResult.of(MergeStatus.APPLIED));
        Files.createDirectories(ROOT.resolve("in"));
    }

    /** Lines are written with ';' and converted to '|' for monthly files. Random ids keep checksums unique. */
    private Path file(String name, String... lines) throws IOException {
        Path f = ROOT.resolve("in").resolve(name);
        StringBuilder sb = new StringBuilder("OBSOLETE;SURVIVOR\n");
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        String content = name.contains("monthly") ? sb.toString().replace(';', '|') : sb.toString();
        Files.writeString(f, content, StandardCharsets.UTF_8);
        return f;
    }

    private static JobParameters params(CamaroFileType type, Path f) {
        return new JobParametersBuilder()
                .addString(JobParams.FILE_TYPE, type.name(), true)
                .addString(JobParams.INPUT_CHECKSUM, InputFileResolver.sha256(f), true)
                .addString(JobParams.INPUT_FILE, f.toString(), false)
                .toJobParameters();
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    private ExecutionSummary lastSummary() {
        ArgumentCaptor<ExecutionSummary> captor = ArgumentCaptor.forClass(ExecutionSummary.class);
        verify(notifier).notify(captor.capture(), any(), any());
        return captor.getValue();
    }

    @Test
    void dailyFile_everyLineGetsAnOutcome_reportSent_inputArchived() throws Exception {
        String a = id(), b = id(), c = id(), d = id(), e = id();
        Path f = file("duplicates_daily_" + id() + ".csv",
                a + ";" + b,              // APPLIED
                a + ";" + b,              // DUPLICATE_LINE
                c + ";" + d,              // conflict: c has two survivors
                c + ";" + e,              // conflict
                "only-one-column",        // VALIDATION_ERROR
                "",                       // VALIDATION_ERROR (blank line is not skipped)
                "#" + id() + ";" + id()); // VALIDATION_ERROR (not treated as a comment)

        JobExecution exec = launcher.launchJob(params(CamaroFileType.DAILY, f));

        assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(exec.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
        var counts = results.countByOutcome(exec.getJobInstance().getInstanceId());
        assertThat(counts).containsEntry(ProcessingOutcome.APPLIED, 1L)
                .containsEntry(ProcessingOutcome.DUPLICATE_LINE, 1L)
                .containsEntry(ProcessingOutcome.CONFLICT, 2L)
                .containsEntry(ProcessingOutcome.VALIDATION_ERROR, 3L);

        ArgumentCaptor<Path> report = ArgumentCaptor.forClass(Path.class);
        verify(notifier).notify(argThat(s -> s.status() == ExecutionSummary.Status.PARTIAL_SUCCESS),
                report.capture(), isNull());
        List<String> csv = Files.readAllLines(report.getValue(), StandardCharsets.UTF_8);
        assertThat(csv).hasSize(1 + 7);
        assertThat(Files.exists(f)).isFalse();
        assertThat(ROOT.resolve("archive").resolve(f.getFileName())).exists();
    }

    @Test
    void monthlyFile_sameJob_otherFormat() throws Exception {
        Path f = file("duplicates_monthly_" + id() + ".csv", id() + ";" + id(), id() + ";" + id());

        JobExecution exec = launcher.launchJob(params(CamaroFileType.MONTHLY, f));

        assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(lastSummary().status()).isEqualTo(ExecutionSummary.Status.SUCCESS);
        assertThat(lastSummary().countsByOutcome()).containsEntry(ProcessingOutcome.APPLIED, 2L);
    }

    @Test
    void sameContentTwice_isRefused() throws Exception {
        Path f = file("duplicates_daily_" + id() + ".csv", id() + ";" + id());
        JobParameters p = params(CamaroFileType.DAILY, f);
        Path copy = Files.copy(f, ROOT.resolve(f.getFileName() + ".copy"));
        launcher.launchJob(p);

        JobParameters again = new JobParametersBuilder(p)
                .addString(JobParams.INPUT_FILE, copy.toString(), false).toJobParameters();
        assertThatThrownBy(() -> launcher.launchJob(again)).isInstanceOf(JobInstanceAlreadyCompleteException.class);
    }

    @Test
    void technicalErrorOnOneLine_isReported_othersCommitted() throws Exception {
        String bad = id();
        when(handler.handle(argThat(r -> r != null && bad.equals(r.obsoleteId()))))
                .thenThrow(new DataIntegrityViolationException("boom"));
        Path f = file("duplicates_daily_" + id() + ".csv", id() + ";" + id(), bad + ";" + id(), id() + ";" + id());

        JobExecution exec = launcher.launchJob(params(CamaroFileType.DAILY, f));

        assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        var counts = results.countByOutcome(exec.getJobInstance().getInstanceId());
        assertThat(counts).containsEntry(ProcessingOutcome.APPLIED, 2L)
                .containsEntry(ProcessingOutcome.TECHNICAL_ERROR, 1L);
    }

    @Test
    void failure_thenRestart_resumesWithoutDuplicates() throws Exception {
        String crash = id();
        when(handler.handle(argThat(r -> r != null && crash.equals(r.obsoleteId()))))
                .thenThrow(new IllegalStateException("not skippable"));
        // chunk-size 2: lines 1-2 commit, line 3 crashes the step
        Path f = file("duplicates_daily_" + id() + ".csv",
                id() + ";" + id(), id() + ";" + id(), crash + ";" + id(), id() + ";" + id());
        JobParameters p = params(CamaroFileType.DAILY, f);

        JobExecution first = launcher.launchJob(p);
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(f).exists(); // kept for restart
        verify(notifier).notify(argThat(s -> s.status() == ExecutionSummary.Status.FAILED), any(), any());

        reset(handler);
        when(handler.actionName()).thenReturn("TEST_ACTION");
        when(handler.handle(any())).thenReturn(MergeResult.of(MergeStatus.APPLIED));
        JobExecution restart = launcher.launchJob(p);

        assertThat(restart.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(restart.getJobInstance().getInstanceId()).isEqualTo(first.getJobInstance().getInstanceId());
        assertThat(results.countByOutcome(restart.getJobInstance().getInstanceId()))
                .containsEntry(ProcessingOutcome.APPLIED, 4L).hasSize(1);
    }

    @Test
    void missingFile_jobFailed_andStillNotified() {
        Path missing = ROOT.resolve("in").resolve("does-not-exist-" + id() + ".csv");
        JobParameters p = new JobParametersBuilder()
                .addString(JobParams.FILE_TYPE, "DAILY", true)
                .addString(JobParams.INPUT_CHECKSUM, id(), true)
                .addString(JobParams.INPUT_FILE, missing.toString(), false)
                .toJobParameters();

        JobExecution exec;
        try {
            exec = launcher.launchJob(p);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertThat(exec.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(lastSummary().status()).isEqualTo(ExecutionSummary.Status.FAILED);
    }

    @Test
    void mailFailure_doesNotChangeDbOutcome_butExitCodeSignalsIt() throws Exception {
        doThrow(new RuntimeException("smtp down")).when(notifier).notify(any(), any(), any());
        Path f = file("duplicates_daily_" + id() + ".csv", id() + ";" + id());

        JobExecution exec = launcher.launchJob(params(CamaroFileType.DAILY, f));

        assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(exec.getExitStatus().getExitCode()).isEqualTo(JobCompletionListener.EXIT_NOTIFICATION_FAILED);
        assertThat(results.countByOutcome(exec.getJobInstance().getInstanceId()))
                .containsEntry(ProcessingOutcome.APPLIED, 1L);
    }
}
