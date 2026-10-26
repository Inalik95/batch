package com.vision.batch.camaroduplicate.result;

import com.vision.batch.camaroduplicate.model.ProcessingOutcome;
import com.vision.batch.camaroduplicate.model.ProcessingResult;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Per-line outcomes, keyed by (job_instance_id, line_number).
 * Keyed by INSTANCE, not execution: after a restart the report still covers
 * the whole file. DDL: src/main/resources/db/camaro-duplicate/V1__camaro_duplicate_result.sql
 *
 * Replace JdbcTemplate with Vision's persistence style if it differs.
 */
public class ProcessingResultRepository {

    private static final String INSERT = """
            INSERT INTO CAMARO_DUPLICATE_RESULT
              (JOB_INSTANCE_ID, LINE_NUMBER, JOB_EXECUTION_ID, OBSOLETE_ID, SURVIVOR_ID,
               OUTCOME, ACTION_PERFORMED, ERROR_CODE, ERROR_DESCRIPTION, PROCESSED_AT)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String UPDATE = """
            UPDATE CAMARO_DUPLICATE_RESULT
               SET JOB_EXECUTION_ID = ?, OBSOLETE_ID = ?, SURVIVOR_ID = ?, OUTCOME = ?,
                   ACTION_PERFORMED = ?, ERROR_CODE = ?, ERROR_DESCRIPTION = ?, PROCESSED_AT = ?
             WHERE JOB_INSTANCE_ID = ? AND LINE_NUMBER = ?""";

    private final JdbcTemplate jdbc;

    public ProcessingResultRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Batch insert from the chunk writer (same transaction as the business update). */
    public void insertAll(long jobInstanceId, long jobExecutionId, List<? extends ProcessingResult> results) {
        jdbc.batchUpdate(INSERT, results, results.size(), (ps, r) -> {
            ps.setLong(1, jobInstanceId);
            ps.setLong(2, r.lineNumber());
            ps.setLong(3, jobExecutionId);
            ps.setString(4, truncate(r.obsoleteId(), 64));
            ps.setString(5, truncate(r.survivorId(), 64));
            ps.setString(6, r.outcome().name());
            ps.setString(7, r.actionPerformed());
            ps.setString(8, r.errorCode());
            ps.setString(9, truncate(r.errorDescription(), 500));
            ps.setTimestamp(10, Timestamp.from(r.processedAt()));
        });
    }

    /** Upsert, used outside the chunk transaction (skip listener), safe on restart. */
    public void save(long jobInstanceId, long jobExecutionId, ProcessingResult r) {
        int updated = jdbc.update(UPDATE, jobExecutionId, truncate(r.obsoleteId(), 64), truncate(r.survivorId(), 64),
                r.outcome().name(), r.actionPerformed(), r.errorCode(), truncate(r.errorDescription(), 500),
                Timestamp.from(r.processedAt()), jobInstanceId, r.lineNumber());
        if (updated == 0) {
            insertAll(jobInstanceId, jobExecutionId, List.of(r));
        }
    }

    public Map<ProcessingOutcome, Long> countByOutcome(long jobInstanceId) {
        Map<ProcessingOutcome, Long> counts = new EnumMap<>(ProcessingOutcome.class);
        jdbc.query("SELECT OUTCOME, COUNT(*) FROM CAMARO_DUPLICATE_RESULT WHERE JOB_INSTANCE_ID = ? GROUP BY OUTCOME",
                rs -> {
                    counts.put(ProcessingOutcome.valueOf(rs.getString(1)), rs.getLong(2));
                }, jobInstanceId);
        return counts;
    }

    /** Streams results in file order, without loading them all in memory. */
    public void forEach(long jobInstanceId, Consumer<ProcessingResult> consumer) {
        jdbc.query("""
                SELECT LINE_NUMBER, OBSOLETE_ID, SURVIVOR_ID, OUTCOME, ACTION_PERFORMED,
                       ERROR_CODE, ERROR_DESCRIPTION, PROCESSED_AT
                  FROM CAMARO_DUPLICATE_RESULT
                 WHERE JOB_INSTANCE_ID = ?
                 ORDER BY LINE_NUMBER""",
                rs -> {
                    consumer.accept(new ProcessingResult(
                            rs.getLong(1), rs.getString(2), rs.getString(3),
                            ProcessingOutcome.valueOf(rs.getString(4)), rs.getString(5),
                            rs.getString(6), rs.getString(7), rs.getTimestamp(8).toInstant()));
                }, jobInstanceId);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
