package com.vision.batch.camaroduplicate.report;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.file.Path;

/** Uploads the CSV report to {@code bucket/reportPrefix/<report file name>}. */
public class S3ReportArchiver implements ReportArchiver {

    private final S3Client s3;
    private final String bucket;
    private final String reportPrefix;

    public S3ReportArchiver(S3Client s3, String bucket, String reportPrefix) {
        this.s3 = s3;
        this.bucket = bucket;
        this.reportPrefix = com.vision.batch.camaroduplicate.source.S3InputFileSource.normalizePrefix(reportPrefix);
    }

    @Override
    public String store(Path report) {
        String key = reportPrefix + report.getFileName();
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType("text/csv; charset=UTF-8")
                        .build(),
                RequestBody.fromFile(report));
        return "cos://" + bucket + "/" + key;
    }
}
