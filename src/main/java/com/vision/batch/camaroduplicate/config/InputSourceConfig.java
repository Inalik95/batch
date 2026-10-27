package com.vision.batch.camaroduplicate.config;

import com.vision.batch.camaroduplicate.report.ReportArchiver;
import com.vision.batch.camaroduplicate.report.S3ReportArchiver;
import com.vision.batch.camaroduplicate.source.InputFileSource;
import com.vision.batch.camaroduplicate.source.LocalDirectoryInputFileSource;
import com.vision.batch.camaroduplicate.source.S3InputFileSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Selects the input source with vision.batch.camaro-duplicate.source.type:
 *   local (default) : mounted directory, previous behaviour
 *   s3              : IBM Cloud Object Storage bucket
 */
@Configuration
public class InputSourceConfig {

    private static final String TYPE = "vision.batch.camaro-duplicate.source.type";

    @Configuration
    @ConditionalOnProperty(name = TYPE, havingValue = "local", matchIfMissing = true)
    static class Local {

        @Bean
        InputFileSource camaroInputFileSource(CamaroDuplicateProperties props) {
            return new LocalDirectoryInputFileSource(props.getInputDir(), props.getArchiveDir());
        }

        @Bean
        ReportArchiver camaroReportArchiver() {
            return ReportArchiver.NONE;
        }
    }

    @Configuration
    @ConditionalOnProperty(name = TYPE, havingValue = "s3")
    static class CloudObjectStorage {

        /**
         * Plain AWS SDK v2 against the COS S3 endpoint with HMAC keys.
         * TODO(vision): if Vision already exposes an S3Client / COS client bean, inject it instead.
         * Alternative: IAM API key auth needs IBM's own SDK (com.ibm.cos:ibm-cos-java-sdk).
         */
        @Bean(destroyMethod = "close")
        S3Client camaroS3Client(CamaroDuplicateProperties props) {
            CamaroDuplicateProperties.S3 cfg = props.getSource().getS3();
            Objects.requireNonNull(cfg.getEndpoint(), "vision.batch.camaro-duplicate.source.s3.endpoint is required");
            Objects.requireNonNull(cfg.getRegion(), "vision.batch.camaro-duplicate.source.s3.region is required");
            return S3Client.builder()
                    .endpointOverride(cfg.getEndpoint())
                    .region(Region.of(cfg.getRegion()))
                    .credentialsProvider(credentials(cfg))
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(cfg.isPathStyleAccess())
                            .build())
                    // SDK >= 2.30 sends CRC32 checksums by default; S3-compatible stores
                    // such as COS may reject them. Only send/validate when the API requires it.
                    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                    .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                    .overrideConfiguration(o -> o.apiCallTimeout(cfg.getApiCallTimeout()))
                    .build();
        }

        @Bean
        InputFileSource camaroInputFileSource(S3Client camaroS3Client, CamaroDuplicateProperties props) {
            CamaroDuplicateProperties.S3 cfg = props.getSource().getS3();
            Path workDir = props.getWorkDir() != null
                    ? props.getWorkDir()
                    : Path.of(System.getProperty("java.io.tmpdir"), "camaro-duplicate");
            return new S3InputFileSource(camaroS3Client, cfg.getBucket(), cfg.getInputPrefix(),
                    cfg.getArchivePrefix(), cfg.getAlreadyProcessedPrefix(), workDir);
        }

        @Bean
        ReportArchiver camaroReportArchiver(S3Client camaroS3Client, CamaroDuplicateProperties props) {
            CamaroDuplicateProperties.S3 cfg = props.getSource().getS3();
            return cfg.getReportPrefix() == null || cfg.getReportPrefix().isBlank()
                    ? ReportArchiver.NONE
                    : new S3ReportArchiver(camaroS3Client, cfg.getBucket(), cfg.getReportPrefix());
        }

        private static AwsCredentialsProvider credentials(CamaroDuplicateProperties.S3 cfg) {
            boolean hasKey = cfg.getAccessKeyId() != null && !cfg.getAccessKeyId().isBlank();
            boolean hasSecret = cfg.getSecretAccessKey() != null && !cfg.getSecretAccessKey().isBlank();
            if (hasKey != hasSecret) {
                throw new IllegalStateException("Both source.s3.access-key-id and source.s3.secret-access-key must be set");
            }
            return hasKey
                    ? StaticCredentialsProvider.create(AwsBasicCredentials.create(cfg.getAccessKeyId(), cfg.getSecretAccessKey()))
                    : DefaultCredentialsProvider.create();
        }
    }
}
