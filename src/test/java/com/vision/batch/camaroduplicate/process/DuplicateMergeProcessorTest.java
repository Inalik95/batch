package com.vision.batch.camaroduplicate.process;

import com.vision.batch.camaroduplicate.action.DryRunDuplicateMergeHandler;
import com.vision.batch.camaroduplicate.config.CamaroDuplicateProperties.FileFormat;
import com.vision.batch.camaroduplicate.model.DuplicateRecord;
import com.vision.batch.camaroduplicate.model.ProcessingOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateMergeProcessorTest {

    private FileFormat format;

    @BeforeEach
    void setUp() {
        format = new FileFormat();
        format.setFilePattern("*.csv");
    }

    private DuplicateMergeProcessor processorFor(List<DuplicateRecord> file) {
        DuplicateRecordValidator validator = new DuplicateRecordValidator(format);
        ConflictRegistry registry = new ConflictRegistry();
        file.stream().filter(r -> validator.validate(r).isEmpty()).forEach(registry::register);
        registry.seal();
        return new DuplicateMergeProcessor(validator, registry, new DryRunDuplicateMergeHandler());
    }

    private static DuplicateRecord rec(long line, String obsolete, String survivor) {
        return new DuplicateRecord(line, obsolete, survivor, 2);
    }

    @Test
    void validLine_goesToHandler() {
        var a = rec(2, "A1", "B1");
        var result = processorFor(List.of(a)).process(a);
        assertThat(result.outcome()).isEqualTo(ProcessingOutcome.ACTION_NOT_CONFIGURED);
        assertThat(result.actionPerformed()).isEqualTo("DRY_RUN");
    }

    @Test
    void malformedAndInvalidLines_areReportedNotSkipped() {
        var p = processorFor(List.of());
        assertThat(p.process(new DuplicateRecord(2, "A1", null, 1)).errorCode()).isEqualTo(ErrorCodes.BAD_COLUMN_COUNT);
        assertThat(p.process(rec(3, " ", "B1")).errorCode()).isEqualTo(ErrorCodes.MISSING_OBSOLETE_ID);
        assertThat(p.process(rec(4, "A-1", "B1")).errorCode()).isEqualTo(ErrorCodes.INVALID_OBSOLETE_ID);
        assertThat(p.process(rec(5, "A1", "A1")).errorCode()).isEqualTo(ErrorCodes.SELF_MERGE);
    }

    @Test
    void repeatedPair_firstProcessed_othersDuplicateLine() {
        var first = rec(2, "A1", "B1");
        var again = rec(7, "A1", "B1");
        var p = processorFor(List.of(first, again));
        assertThat(p.process(first).outcome()).isEqualTo(ProcessingOutcome.ACTION_NOT_CONFIGURED);
        assertThat(p.process(again).outcome()).isEqualTo(ProcessingOutcome.DUPLICATE_LINE);
    }

    @Test
    void sameObsoleteWithTwoSurvivors_isConflictOnBothLines() {
        var l1 = rec(2, "A1", "B1");
        var l2 = rec(3, "A1", "C1");
        var p = processorFor(List.of(l1, l2));
        assertThat(p.process(l1).errorCode()).isEqualTo(ErrorCodes.MULTIPLE_SURVIVORS);
        assertThat(p.process(l2).errorCode()).isEqualTo(ErrorCodes.MULTIPLE_SURVIVORS);
    }

    @Test
    void chain_isConflictOnEveryLineInvolved() {
        var l1 = rec(2, "A1", "B1");
        var l2 = rec(3, "B1", "C1");
        var unrelated = rec(4, "X1", "Y1");
        var p = processorFor(List.of(l1, l2, unrelated));
        assertThat(p.process(l1).errorCode()).isEqualTo(ErrorCodes.MERGE_CHAIN);
        assertThat(p.process(l2).errorCode()).isEqualTo(ErrorCodes.MERGE_CHAIN);
        assertThat(p.process(unrelated).outcome()).isEqualTo(ProcessingOutcome.ACTION_NOT_CONFIGURED);
    }
}
