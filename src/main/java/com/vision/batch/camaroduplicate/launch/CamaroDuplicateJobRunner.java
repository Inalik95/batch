package com.vision.batch.camaroduplicate.launch;

import com.vision.batch.camaroduplicate.config.CamaroDuplicateProperties;
import com.vision.batch.camaroduplicate.config.JobParams;
import com.vision.batch.camaroduplicate.model.CamaroFileType;
import com.vision.batch.camaroduplicate.notify.JobCompletionListener;
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
 *   java -jar app.jar ... --camaro.fileType=MONTHLY --camaro.inputFile=/data/in/x.csv   (manual re-run)
 *
 * Processes every matching file, oldest first. Process exit code = worst result:
 *   0 ok | 1 job failed | 3 db ok but report/mail failed | 4 no input file (TODO(business): is that an error?)
 *
 * TODO(vision): if Vision already launches jobs differently (scheduler, REST, shared
 * launcher), drop this class and pass the same three parameters.
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
    private int exitCode = EXIT_OK;

    public CamaroDuplicateJobRunner(JobLauncher jobLauncher, Job camaroDuplicateJob, CamaroDuplicateProperties props) {
        this.jobLauncher = jobLauncher;
        this.camaroDuplicateJob = camaroDuplicateJob;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        CamaroFileType type = CamaroFileType.valueOf(single(args, "camaro.fileType"));
        List<Path> files = args.containsOption("camaro.inputFile")
                ? List.of(Path.of(single(args, "camaro.inputFile")).toAbsolutePath())
                : InputFileResolver.find(props.getInputDir(), props.format(type).getFilePattern());

        if (files.isEmpty()) {
            log.warn("No {} Camaro duplicate file in {}", type, props.getInputDir());
            exitCode = EXIT_NO_INPUT;
            return;
        }
        for (Path file : files) {
            exitCode = Math.max(exitCode, launch(type, file));
        }
    }

    private int launch(CamaroFileType type, Path file) {
        JobParameters params = new JobParametersBuilder()
                .addString(JobParams.FILE_TYPE, type.name(), true)
                .addString(JobParams.INPUT_CHECKSUM, InputFileResolver.sha256(file), true)
                .addString(JobParams.INPUT_FILE, file.toString(), false)
                .toJobParameters();
        try {
            JobExecution execution = jobLauncher.run(camaroDuplicateJob, params);
            ExitStatus exit = execution.getExitStatus();
            log.info("{} -> {}", file.getFileName(), exit.getExitCode());
            if (JobCompletionListener.EXIT_NOTIFICATION_FAILED.equals(exit.getExitCode())) {
                return EXIT_NOTIFICATION_FAILED;
            }
            return ExitStatus.COMPLETED.getExitCode().equals(exit.getExitCode()) ? EXIT_OK : EXIT_FAILED;
        } catch (JobInstanceAlreadyCompleteException e) {
            // Same content already processed successfully: never apply it twice.
            // Moved aside so it is not picked up again. TODO(business): alert as well?
            log.warn("{} has already been processed (same checksum), not reprocessed", file.getFileName());
            moveAside(file);
            return EXIT_OK;
        } catch (JobExecutionAlreadyRunningException e) {
            log.error("{} is being processed by another execution", file.getFileName());
            return EXIT_FAILED;
        } catch (Exception e) {
            log.error("Cannot launch job for {}", file.getFileName(), e);
            return EXIT_FAILED;
        }
    }

    private void moveAside(Path file) {
        try {
            java.nio.file.Files.createDirectories(props.getArchiveDir());
            java.nio.file.Files.move(file, props.getArchiveDir().resolve(file.getFileName() + ".already-processed"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            log.error("Could not move {} aside", file.getFileName(), e);
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
