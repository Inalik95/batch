package com.vision.batch.camaroduplicate.report;

import java.nio.file.Path;

/**
 * Keeps a durable copy of the CSV report. With source.type=s3 the pod disk is
 * ephemeral, so reports are uploaded to the bucket (source.s3.report-prefix).
 */
public interface ReportArchiver {

    /** @return where the report was stored, for logs */
    String store(Path report);

    /** Default: the report stays in report-dir only. */
    ReportArchiver NONE = report -> report.toString();
}
