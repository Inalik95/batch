package com.vision.batch.camaroduplicate.read;

import com.vision.batch.camaroduplicate.config.CamaroDuplicateProperties.FileFormat;
import com.vision.batch.camaroduplicate.model.DuplicateRecord;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Path;

/**
 * Single place that knows how to open a Camaro file. Used by both the
 * conflict pre-scan and the processing step, so they always read the same way.
 *
 * TODO(spec): if the monthly file turns out to be structurally different
 * (not just other columns), introduce a strategy per CamaroFileType here;
 * the rest of the job does not change.
 */
public final class CamaroFileReaderFactory {

    private CamaroFileReaderFactory() {
    }

    public static FlatFileItemReader<DuplicateRecord> create(String name, Path file, FileFormat format) {
        FlatFileItemReader<DuplicateRecord> reader = new FlatFileItemReaderBuilder<DuplicateRecord>()
                .name(name)
                .resource(new FileSystemResource(file))
                .encoding(format.getEncoding().name())
                .linesToSkip(format.getLinesToSkip())
                .lineMapper(new DuplicateRecordLineMapper(format))
                .strict(true)          // missing file = failure, never an empty success
                .saveState(true)       // restart from last committed chunk
                .build();
        // Default comment prefix "#" would drop lines silently: disable it.
        reader.setComments(new String[0]);
        return reader;
    }
}
