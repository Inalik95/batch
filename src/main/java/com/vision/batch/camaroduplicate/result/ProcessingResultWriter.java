package com.vision.batch.camaroduplicate.result;

import com.vision.batch.camaroduplicate.model.ProcessingResult;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

/**
 * Persists outcomes in the chunk transaction: the business change made by the
 * handler and its outcome row commit or roll back together.
 */
public class ProcessingResultWriter implements ItemWriter<ProcessingResult> {

    private final ProcessingResultRepository repository;

    public ProcessingResultWriter(ProcessingResultRepository repository) {
        this.repository = repository;
    }

    @Override
    public void write(Chunk<? extends ProcessingResult> chunk) {
        StepExecution step = currentStep();
        repository.insertAll(step.getJobExecution().getJobInstance().getInstanceId(),
                step.getJobExecutionId(), chunk.getItems());
    }

    static StepExecution currentStep() {
        var context = StepSynchronizationManager.getContext();
        if (context == null) {
            throw new IllegalStateException("No active step execution");
        }
        return context.getStepExecution();
    }
}
