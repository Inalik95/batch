package com.vision.batch.camaroduplicate.notify;

import com.vision.batch.camaroduplicate.model.ExecutionSummary;

import java.nio.file.Path;

/**
 * Sends the execution result. Implementations must not alter the job outcome:
 * they report it.
 *
 * @param report       the CSV, or null if its generation failed
 * @param reportError  why the CSV is missing, or null
 */
public interface ReportNotifier {

    void notify(ExecutionSummary summary, Path report, String reportError);
}
