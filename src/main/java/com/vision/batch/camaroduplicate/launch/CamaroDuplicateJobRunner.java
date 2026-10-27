package com.vision.batch.camaroduplicate.launch;

import com.vision.batch.camaroduplicate.config.CamaroDuplicateProperties;
import com.vision.batch.camaroduplicate.config.JobParams;
import com.vision.batch.camaroduplicate.model.CamaroFileType;
import com.vision.batch.camaroduplicate.notify.JobCompletionListener;
import com.vision.batch.camaroduplicate.source.InputFileSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * Entry point for a Kubernetes CronJob (one pod per run):
 *
 *   java -jar app.jar --spring.profiles.active=camaro-duplicate --camaro.fileType=DAILY
 *   java -jar app.jar ... --camaro.fileType=MONTHLY --camaro.sourceRef=camaro/in/x.csv   (manual re-run)
 *
 * For every file found by the {@link InputFileSource} (oldest first):
 *   fetch (download for S3) -> SHA-256 -> launch job -> release (delete local copy).
 * Archiving the source file is done by JobCompletionListener when the job completes.
 *
 * Process exit code = worst result:
 *   0 ok | 1 job failed | 3 db ok but report/mail failed | 4 no input file (TODO(business): is that an error?)
 *
 * TODO(vision): if Vision already launches jobs differently (scheduler, REST, shared
 * launcher), drop this class and pass the same parameters.
 */
@Component
@ConditionalOnProperty(prefix = "vision.batch.camaro-duplicate.runner", name = "enabled", havingValue = "true")
public class CamaroDuplicateJobRunner implements ApplicationRunner, ExitCodeGenerator {

    static final int EXIT_OK = 0;
    static final int EXIT_FAILED = 1;
    static final int EXIT_NOTIFICATION_FAILED = 3;
    static final int EXIT_NO_INPUT = 4;

    private static final Logger log = LoggerFactory.getLogger(CamaroDuplicateJobRunner.class);

    private final JobLauncher jobLauncher;
    private final Job camaroDuplicateJob;
    private final CamaroDuplicateProperties props;
    private final InputFileSource source;
    private int exitCode = EXIT_OK;

    public CamaroDuplicateJobRunner(JobLauncher jobLauncher, Job camaroDuplicateJob,
                                    CamaroDuplicateProperties props, InputFileSource camaroInputFileSource) {
        this.jobLauncher = jobLauncher;
        this.camaroDuplicateJob = camaroDuplicateJob;
        this.props = props;
        this.source = camaroInputFileSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        CamaroFileType type = CamaroFileType.valueOf(single(args, "camaro.fileType"));
        List<String> refs;
        try {
            refs = args.containsOption("camaro.sourceRef")
                    ? List.of(single(args, "camaro.sourceRef"))
                    : source.list(props.format(type).getFilePattern());
        } catch (RuntimeException e) {
            log.error("Cannot list {} Camaro duplicate files", type, e);
            exitCode = EXIT_FAILED;
            return;
        }

        if (refs.isEmpty()) {
            log.warn("No {} Camaro duplicate file found", type);
            exitCode = EXIT_NO_INPUT;
            return;
        }
        for (String ref : refs) {
            exitCode = Math.max(exitCode, process(type, ref));
        }
    }

    private int process(CamaroFileType type, String ref) {
        Path local = null;
        try {
            local = source.fetch(ref);
            return launch(type, ref, local);
        } catch (RuntimeException e) {
            log.error("Cannot fetch {}", source.describe(ref), e);
            return EXIT_FAILED;
        } finally {
            if (local != null) {
                source.release(ref, local);
            }
        }
    }

    private int launch(CamaroFileType type, String ref, Path local) {
        JobParameters params = new JobParametersBuilder()
                .addString(JobParams.FILE_TYPE, type.name(), true)
                .addString(JobParams.INPUT_CHECKSUM, InputFileResolver.sha256(local), true)
                .addString(JobParams.INPUT_FILE, local.toAbsolutePath().toString(), false)
                .addString(JobParams.SOURCE_REF, ref, false)
                .toJobParameters();
        String name = InputFileSource.fileName(ref);
        try {
            JobExecution execution = jobLauncher.run(camaroDuplicateJob, params);
            ExitStatus exit = execution.getExitStatus();
            log.info("{} -> {}", name, exit.getExitCode());
            if (JobCompletionListener.EXIT_NOTIFICATION_FAILED.equals(exit.getExitCode())) {
                return EXIT_NOTIFICATION_FAILED;
            }
            return ExitStatus.COMPLETED.getExitCode().equals(exit.getExitCode()) ? EXIT_OK : EXIT_FAILED;
        } catch (JobInstanceAlreadyCompleteException e) {
            // Same content already processed successfully: never apply it twice.
            // Moved aside so it is not picked up again. TODO(business): alert as well?
            log.warn("{} has already been processed (same checksum), not reprocessed", name);
            try {
                source.markAlreadyProcessed(ref);
            } catch (RuntimeException moveError) {
                log.error("Could not move {} aside", source.describe(ref), moveError);
            }
            return EXIT_OK;
        } catch (JobExecutionAlreadyRunningException e) {
            log.error("{} is being processed by another execution", name);
            return EXIT_FAILED;
        } catch (Exception e) {
            log.error("Cannot launch job for {}", name, e);
            return EXIT_FAILED;
        }
    }

    private static String single(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.size() != 1 || values.get(0).isBlank()) {
            throw new IllegalArgumentException("Missing or ambiguous argument --" + name);
        }
        return values.get(0);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
