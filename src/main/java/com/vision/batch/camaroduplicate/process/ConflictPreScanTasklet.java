package com.vision.batch.camaroduplicate.process;

import com.vision.batch.camaroduplicate.config.CamaroDuplicateProperties.FileFormat;
import com.vision.batch.camaroduplicate.model.DuplicateRecord;
import com.vision.batch.camaroduplicate.read.CamaroFileReaderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.repeat.RepeatStatus;

import java.nio.file.Path;

/**
 * Step 1: read the whole file once, without touching the database, and build
 * the {@link ConflictRegistry}. Also fails fast on a missing/unreadable file.
 * Must be configured with allowStartIfComplete(true) so it runs again on restart.
 */
public class ConflictPreScanTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(ConflictPreScanTasklet.class);

    private final Path inputFile;
    private final FileFormat format;
    private final DuplicateRecordValidator validator;
    private final ConflictRegistry registry;

    public ConflictPreScanTasklet(Path inputFile, FileFormat format,
                                  DuplicateRecordValidator validator, ConflictRegistry registry) {
        this.inputFile = inputFile;
        this.format = format;
        this.validator = validator;
        this.registry = registry;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        FlatFileItemReader<DuplicateRecord> reader =
                CamaroFileReaderFactory.create("camaroDuplicatePreScanReader", inputFile, format);
        reader.setSaveState(false);
        long lines = 0;
        reader.open(new ExecutionContext());
        try {
            DuplicateRecord rec;
            while ((rec = reader.read()) != null) {
                lines++;
                if (validator.validate(rec).isEmpty()) {
                    registry.register(rec);
                }
            }
        } finally {
            reader.close();
        }
        registry.seal();
        log.info("Pre-scan done: {} data lines in {}", lines, inputFile.getFileName());
        return RepeatStatus.FINISHED;
    }
}
