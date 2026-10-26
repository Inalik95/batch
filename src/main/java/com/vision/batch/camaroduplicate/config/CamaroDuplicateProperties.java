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

    /** Directory where Camaro drops the files. */
    @NotNull
    private Path inputDir;

    /** Processed files are moved here when the job completes. */
    @NotNull
    private Path archiveDir;

    /** CSV reports are written here (kept for audit, also attached to the mail). */
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

    public static class FileFormat {
        /** Glob used to find the file in inputDir, e.g. "FUSION_TIERS_J_*.csv". TODO(spec) */
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
