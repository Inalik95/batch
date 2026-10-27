package com.vision.batch.camaroduplicate.config;

import com.vision.batch.camaroduplicate.model.CamaroFileType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * All environment-specific values. Nothing here has a production default:
 * paths and recipients come from the environment (Helm values / ConfigMap),
 * SMTP credentials from Vision's existing secret mechanism via spring.mail.*.
 */
@Validated
@ConfigurationProperties(prefix = "vision.batch.camaro-duplicate")
public class CamaroDuplicateProperties {

    /** Where input files come from: local directory or IBM COS bucket. */
    @Valid
    private Source source = new Source();

    /** source.type=local only: directory where Camaro drops the files. */
    private Path inputDir;

    /** source.type=local only: processed files are moved here when the job completes. */
    private Path archiveDir;

    /** source.type=s3 only: local scratch directory for downloaded files (emptyDir in Kubernetes). */
    private Path workDir;

    /** CSV reports are written here, then attached to the mail (and uploaded if source.s3.report-prefix is set). */
    @NotNull
    private Path reportDir;

    /** Commit interval. */
    @Min(1)
    private int chunkSize = 200;

    /**
     * Max technical errors tolerated before the step fails. Business errors
     * (validation, not found, conflict) are outcomes, not skips, and do not count.
     */
    @Min(0)
    private int technicalSkipLimit = 10;

    @Valid
    private Map<CamaroFileType, FileFormat> formats = new EnumMap<>(CamaroFileType.class);

    @Valid
    private Mail mail = new Mail();

    public enum SourceType { LOCAL, S3 }

    public static class Source {
        @NotNull
        private SourceType type = SourceType.LOCAL;
        @Valid
        private S3 s3 = new S3();

        public SourceType getType() { return type; }
        public void setType(SourceType type) { this.type = type; }
        public S3 getS3() { return s3; }
        public void setS3(S3 s3) { this.s3 = s3; }
    }

    /**
     * IBM Cloud Object Storage through its S3-compatible API.
     * Credentials are the HMAC keys of a COS service credential (created with "Include HMAC Credential").
     * They must come from a Kubernetes Secret, never from this file.
     */
    public static class S3 {
        /** e.g. https://s3.direct.eu-de.cloud-object-storage.appdomain.cloud (private/direct endpoint from the cluster). */
        private java.net.URI endpoint;
        /** COS location, e.g. eu-de. Used for request signing only. */
        private String region;
        private String bucket;
        /** Prefix where Camaro drops files, e.g. "camaro/in/". Empty = bucket root. */
        private String inputPrefix = "";
        /** Prefix where handled files are moved, e.g. "camaro/archive/". Must differ from input-prefix. */
        private String archivePrefix;
        /** Prefix for re-delivered files. Defaults to archive-prefix. */
        private String alreadyProcessedPrefix;
        /** Optional: if set, CSV reports are uploaded there, e.g. "camaro/report/". */
        private String reportPrefix;
        /** HMAC access key id. If blank, the AWS default chain is used (AWS_ACCESS_KEY_ID env var...). */
        private String accessKeyId;
        private String secretAccessKey;
        /** COS supports both; path-style avoids DNS issues with dotted bucket names. */
        private boolean pathStyleAccess = true;
        private java.time.Duration apiCallTimeout = java.time.Duration.ofMinutes(5);

        public java.net.URI getEndpoint() { return endpoint; }
        public void setEndpoint(java.net.URI endpoint) { this.endpoint = endpoint; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }
        public String getInputPrefix() { return inputPrefix; }
        public void setInputPrefix(String inputPrefix) { this.inputPrefix = inputPrefix; }
        public String getArchivePrefix() { return archivePrefix; }
        public void setArchivePrefix(String archivePrefix) { this.archivePrefix = archivePrefix; }
        public String getAlreadyProcessedPrefix() { return alreadyProcessedPrefix; }
        public void setAlreadyProcessedPrefix(String alreadyProcessedPrefix) { this.alreadyProcessedPrefix = alreadyProcessedPrefix; }
        public String getReportPrefix() { return reportPrefix; }
        public void setReportPrefix(String reportPrefix) { this.reportPrefix = reportPrefix; }
        public String getAccessKeyId() { return accessKeyId; }
        public void setAccessKeyId(String accessKeyId) { this.accessKeyId = accessKeyId; }
        public String getSecretAccessKey() { return secretAccessKey; }
        public void setSecretAccessKey(String secretAccessKey) { this.secretAccessKey = secretAccessKey; }
        public boolean isPathStyleAccess() { return pathStyleAccess; }
        public void setPathStyleAccess(boolean pathStyleAccess) { this.pathStyleAccess = pathStyleAccess; }
        public java.time.Duration getApiCallTimeout() { return apiCallTimeout; }
        public void setApiCallTimeout(java.time.Duration apiCallTimeout) { this.apiCallTimeout = apiCallTimeout; }
    }

    public static class FileFormat {
        /** Glob matched against the file name (local dir or last segment of the object key). TODO(spec) */
        @NotBlank
        private String filePattern;
        private String delimiter = ";";
        private Charset encoding = StandardCharsets.UTF_8;
        /** Header lines to skip. */
        @Min(0)
        private int linesToSkip = 1;
        /** Expected tokens per line; lines with another count are VALIDATION_ERROR. */
        @Min(1)
        private int expectedColumns = 2;
        /** 0-based column index of the obsolete identifier. TODO(spec) */
        @Min(0)
        private int obsoleteIdColumn = 0;
        /** 0-based column index of the surviving identifier. TODO(spec) */
        @Min(0)
        private int survivorIdColumn = 1;
        /** Regex an identifier must match. TODO(spec): Camaro id format. */
        @NotBlank
        private String identifierPattern = "^[A-Za-z0-9]{1,32}$";

        public String getFilePattern() { return filePattern; }
        public void setFilePattern(String filePattern) { this.filePattern = filePattern; }
        public String getDelimiter() { return delimiter; }
        public void setDelimiter(String delimiter) { this.delimiter = delimiter; }
        public Charset getEncoding() { return encoding; }
        public void setEncoding(Charset encoding) { this.encoding = encoding; }
        public int getLinesToSkip() { return linesToSkip; }
        public void setLinesToSkip(int linesToSkip) { this.linesToSkip = linesToSkip; }
        public int getExpectedColumns() { return expectedColumns; }
        public void setExpectedColumns(int expectedColumns) { this.expectedColumns = expectedColumns; }
        public int getObsoleteIdColumn() { return obsoleteIdColumn; }
        public void setObsoleteIdColumn(int obsoleteIdColumn) { this.obsoleteIdColumn = obsoleteIdColumn; }
        public int getSurvivorIdColumn() { return survivorIdColumn; }
        public void setSurvivorIdColumn(int survivorIdColumn) { this.survivorIdColumn = survivorIdColumn; }
        public String getIdentifierPattern() { return identifierPattern; }
        public void setIdentifierPattern(String identifierPattern) { this.identifierPattern = identifierPattern; }
    }

    public static class Mail {
        private boolean enabled = true;
        private String from;
        /** TODO(business): recipient list per environment. */
        private List<String> to = new ArrayList<>();
        private List<String> cc = new ArrayList<>();
        /** Prefixed to every subject, e.g. "[VISION][QUAL1]". */
        private String subjectPrefix = "[VISION]";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }
        public List<String> getTo() { return to; }
        public void setTo(List<String> to) { this.to = to; }
        public List<String> getCc() { return cc; }
        public void setCc(List<String> cc) { this.cc = cc; }
        public String getSubjectPrefix() { return subjectPrefix; }
        public void setSubjectPrefix(String subjectPrefix) { this.subjectPrefix = subjectPrefix; }
    }

    public FileFormat format(CamaroFileType type) {
        FileFormat format = formats.get(type);
        if (format == null) {
            throw new IllegalStateException("No file format configured for " + type
                    + " (vision.batch.camaro-duplicate.formats." + type + ")");
        }
        return format;
    }

    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }
    public Path getWorkDir() { return workDir; }
    public void setWorkDir(Path workDir) { this.workDir = workDir; }
    public Path getInputDir() { return inputDir; }
    public void setInputDir(Path inputDir) { this.inputDir = inputDir; }
    public Path getArchiveDir() { return archiveDir; }
    public void setArchiveDir(Path archiveDir) { this.archiveDir = archiveDir; }
    public Path getReportDir() { return reportDir; }
    public void setReportDir(Path reportDir) { this.reportDir = reportDir; }
    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }
    public int getTechnicalSkipLimit() { return technicalSkipLimit; }
    public void setTechnicalSkipLimit(int technicalSkipLimit) { this.technicalSkipLimit = technicalSkipLimit; }
    public Map<CamaroFileType, FileFormat> getFormats() { return formats; }
    public void setFormats(Map<CamaroFileType, FileFormat> formats) { this.formats = formats; }
    public Mail getMail() { return mail; }
    public void setMail(Mail mail) { this.mail = mail; }
}
