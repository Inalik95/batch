package com.vision.batch.camaroduplicate.result;

import com.vision.batch.camaroduplicate.model.DuplicateRecord;
import com.vision.batch.camaroduplicate.model.ProcessingOutcome;
import com.vision.batch.camaroduplicate.model.ProcessingResult;
import com.vision.batch.camaroduplicate.process.ErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Records a TECHNICAL_ERROR row for each line skipped after a technical failure.
 * Runs in REQUIRES_NEW so the row survives the chunk rollback; the upsert keeps
 * it correct if the chunk is replayed after a crash.
 * Only the exception class is reported: messages may contain personal data or SQL.
 */
public class TechnicalErrorSkipListener implements SkipListener<DuplicateRecord, ProcessingResult> {

    private static final Logger log = LoggerFactory.getLogger(TechnicalErrorSkipListener.class);

    private final ProcessingResultRepository repository;
    private final TransactionTemplate requiresNew;

    public TechnicalErrorSkipListener(ProcessingResultRepository repository, PlatformTransactionManager txManager) {
        this.repository = repository;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void onSkipInProcess(DuplicateRecord item, Throwable t) {
        log.error("Technical error on line {} ({}), line skipped", item.lineNumber(), t.getClass().getSimpleName(), t);
        record(new ProcessingResult(item.lineNumber(), item.obsoleteId(), item.survivorId(),
                ProcessingOutcome.TECHNICAL_ERROR, null, ErrorCodes.TECHNICAL, t.getClass().getSimpleName(),
                java.time.Instant.now()));
    }

    @Override
    public void onSkipInWrite(ProcessingResult item, Throwable t) {
        log.error("Write failed for line {} ({}), line skipped", item.lineNumber(), t.getClass().getSimpleName(), t);
        record(new ProcessingResult(item.lineNumber(), item.obsoleteId(), item.survivorId(),
                ProcessingOutcome.TECHNICAL_ERROR, null, ErrorCodes.TECHNICAL, t.getClass().getSimpleName(),
                item.processedAt()));
    }

    private void record(ProcessingResult result) {
        StepExecution step = ProcessingResultWriter.currentStep();
        long instanceId = step.getJobExecution().getJobInstance().getInstanceId();
        requiresNew.executeWithoutResult(s -> repository.save(instanceId, step.getJobExecutionId(), result));
    }
}
