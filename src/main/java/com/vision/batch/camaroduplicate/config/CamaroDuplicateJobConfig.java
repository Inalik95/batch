package com.vision.batch.camaroduplicate.config;

import com.vision.batch.camaroduplicate.action.DryRunDuplicateMergeHandler;
import com.vision.batch.camaroduplicate.action.DuplicateMergeHandler;
import com.vision.batch.camaroduplicate.model.CamaroFileType;
import com.vision.batch.camaroduplicate.model.DuplicateRecord;
import com.vision.batch.camaroduplicate.model.ProcessingResult;
import com.vision.batch.camaroduplicate.notify.JobCompletionListener;
import com.vision.batch.camaroduplicate.notify.MailReportNotifier;
import com.vision.batch.camaroduplicate.notify.ReportNotifier;
import com.vision.batch.camaroduplicate.process.ConflictPreScanTasklet;
import com.vision.batch.camaroduplicate.process.ConflictRegistry;
import com.vision.batch.camaroduplicate.process.DuplicateMergeProcessor;
import com.vision.batch.camaroduplicate.process.DuplicateRecordValidator;
import com.vision.batch.camaroduplicate.read.CamaroFileReaderFactory;
import com.vision.batch.camaroduplicate.report.CsvReportGenerator;
import com.vision.batch.camaroduplicate.result.ProcessingResultRepository;
import com.vision.batch.camaroduplicate.result.ProcessingResultWriter;
import com.vision.batch.camaroduplicate.result.TechnicalErrorSkipListener;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.DefaultJobParametersValidator;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Path;

/**
 * camaroDuplicateJob
 *
 *   preScanStep  (tasklet, re-runs on restart) : read whole file, build ConflictRegistry, no DB write
 *   processStep  (chunk, fault tolerant)       : validate -> conflicts -> DuplicateMergeHandler -> result rows
 *   listener     (always)                      : summary -> CSV -> mail -> archive
 *
 * One job for DAILY and MONTHLY: the fileType parameter only selects the FileFormat.
 */
@Configuration
@EnableConfigurationProperties(CamaroDuplicateProperties.class)
public class CamaroDuplicateJobConfig {

    // ---------------------------------------------------------------- job

    @Bean
    public Job camaroDuplicateJob(JobRepository jobRepository, Step camaroDuplicatePreScanStep,
                                  Step camaroDuplicateProcessStep, JobCompletionListener camaroDuplicateCompletionListener) {
        DefaultJobParametersValidator params = new DefaultJobParametersValidator(
                new String[]{JobParams.FILE_TYPE, JobParams.INPUT_CHECKSUM, JobParams.INPUT_FILE}, new String[0]);
        return new JobBuilder(JobParams.JOB_NAME, jobRepository)
                .validator(params)
                .start(camaroDuplicatePreScanStep)
                .next(camaroDuplicateProcessStep)
                .listener(camaroDuplicateCompletionListener)
                .build();
    }

    // ---------------------------------------------------------------- steps

    @Bean
    public Step camaroDuplicatePreScanStep(JobRepository jobRepository, PlatformTransactionManager txManager,
                                           ConflictPreScanTasklet camaroDuplicatePreScanTasklet) {
        return new StepBuilder("camaroDuplicatePreScanStep", jobRepository)
                .tasklet(camaroDuplicatePreScanTasklet, txManager)
                .allowStartIfComplete(true)   // registry is job-scoped: rebuild it on restart
                .build();
    }

    @Bean
    public Step camaroDuplicateProcessStep(JobRepository jobRepository, PlatformTransactionManager txManager,
                                           CamaroDuplicateProperties props,
                                           FlatFileItemReader<DuplicateRecord> camaroDuplicateReader,
                                           DuplicateMergeProcessor camaroDuplicateProcessor,
                                           ProcessingResultWriter camaroDuplicateWriter,
                                           TechnicalErrorSkipListener camaroDuplicateSkipListener) {
        return new StepBuilder("camaroDuplicateProcessStep", jobRepository)
                .<DuplicateRecord, ProcessingResult>chunk(props.getChunkSize(), txManager)
                .reader(camaroDuplicateReader)
                .processor(camaroDuplicateProcessor)
                .writer(camaroDuplicateWriter)
                .faultTolerant()
                // Only technical DB failures are skippable; business errors are outcomes, not exceptions.
                .skip(DataAccessException.class)
                .skipLimit(props.getTechnicalSkipLimit())
                .listener(camaroDuplicateSkipListener)
                .build();
    }

    // ---------------------------------------------------------------- step-scoped components

    @Bean
    @JobScope
    public ConflictRegistry camaroDuplicateConflictRegistry() {
        return new ConflictRegistry();
    }

    @Bean
    @StepScope
    public ConflictPreScanTasklet camaroDuplicatePreScanTasklet(
            CamaroDuplicateProperties props, ConflictRegistry camaroDuplicateConflictRegistry,
            @Value("#{jobParameters['" + JobParams.INPUT_FILE + "']}") String inputFile,
            @Value("#{jobParameters['" + JobParams.FILE_TYPE + "']}") String fileType) {
        var format = props.format(CamaroFileType.valueOf(fileType));
        return new ConflictPreScanTasklet(Path.of(inputFile), format,
                new DuplicateRecordValidator(format), camaroDuplicateConflictRegistry);
    }

    @Bean
    @StepScope
    public FlatFileItemReader<DuplicateRecord> camaroDuplicateReader(
            CamaroDuplicateProperties props,
            @Value("#{jobParameters['" + JobParams.INPUT_FILE + "']}") String inputFile,
            @Value("#{jobParameters['" + JobParams.FILE_TYPE + "']}") String fileType) {
        return CamaroFileReaderFactory.create("camaroDuplicateReader", Path.of(inputFile),
                props.format(CamaroFileType.valueOf(fileType)));
    }

    @Bean
    @StepScope
    public DuplicateMergeProcessor camaroDuplicateProcessor(
            CamaroDuplicateProperties props, ConflictRegistry camaroDuplicateConflictRegistry,
            ObjectProvider<DuplicateMergeHandler> handlers,
            @Value("#{jobParameters['" + JobParams.FILE_TYPE + "']}") String fileType) {
        // The real handler is simply a DuplicateMergeHandler @Component in Vision.
        // Until it exists, the dry-run handler is used and the run is reported as PARTIAL.
        return new DuplicateMergeProcessor(
                new DuplicateRecordValidator(props.format(CamaroFileType.valueOf(fileType))),
                camaroDuplicateConflictRegistry, handlers.getIfAvailable(DryRunDuplicateMergeHandler::new));
    }

    // ---------------------------------------------------------------- singletons

    @Bean
    public ProcessingResultRepository camaroDuplicateResultRepository(JdbcTemplate jdbcTemplate) {
        return new ProcessingResultRepository(jdbcTemplate);
    }

    @Bean
    public ProcessingResultWriter camaroDuplicateWriter(ProcessingResultRepository repository) {
        return new ProcessingResultWriter(repository);
    }

    @Bean
    public TechnicalErrorSkipListener camaroDuplicateSkipListener(ProcessingResultRepository repository,
                                                                  PlatformTransactionManager txManager) {
        return new TechnicalErrorSkipListener(repository, txManager);
    }

    @Bean
    public JobCompletionListener camaroDuplicateCompletionListener(ProcessingResultRepository repository,
                                                                   ObjectProvider<ReportNotifier> notifiers,
                                                                   ObjectProvider<JavaMailSender> mailSender,
                                                                   CamaroDuplicateProperties props) {
        // A ReportNotifier bean (e.g. wrapping Vision's mail service) wins over the default mail one.
        ReportNotifier notifier = notifiers.getIfAvailable(
                () -> new MailReportNotifier(mailSender.getObject(), props.getMail()));
        return new JobCompletionListener(repository,
                new CsvReportGenerator(repository, props.getReportDir()), notifier, props.getArchiveDir());
    }
}
