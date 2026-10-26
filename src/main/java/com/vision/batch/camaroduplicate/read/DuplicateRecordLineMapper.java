package com.vision.batch.camaroduplicate.read;

import com.vision.batch.camaroduplicate.config.CamaroDuplicateProperties.FileFormat;
import com.vision.batch.camaroduplicate.model.DuplicateRecord;
import org.springframework.batch.item.file.LineMapper;
import org.springframework.batch.item.file.transform.DelimitedLineTokenizer;
import org.springframework.batch.item.file.transform.FieldSet;

/**
 * Lenient mapping: never throws on content, so malformed lines reach the
 * validator and appear in the report instead of being skipped by the reader.
 * Daily and monthly differ only by the FileFormat passed in.
 */
public class DuplicateRecordLineMapper implements LineMapper<DuplicateRecord> {

    private final DelimitedLineTokenizer tokenizer;
    private final FileFormat format;

    public DuplicateRecordLineMapper(FileFormat format) {
        this.format = format;
        this.tokenizer = new DelimitedLineTokenizer(format.getDelimiter());
        this.tokenizer.setStrict(false);
    }

    @Override
    public DuplicateRecord mapLine(String line, int lineNumber) {
        FieldSet fields = tokenizer.tokenize(line);
        int count = fields.getFieldCount();
        return new DuplicateRecord(
                lineNumber,
                token(fields, format.getObsoleteIdColumn(), count),
                token(fields, format.getSurvivorIdColumn(), count),
                count);
    }

    private static String token(FieldSet fields, int index, int count) {
        return index < count ? fields.readString(index) : null;
    }
}
