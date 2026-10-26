-- Per-line outcome of the Camaro duplicate job. Source of the CSV report and of the audit trail.
-- Proposal: rename / move to Vision's migration tool (Flyway or Liquibase), adapt types to the RDBMS.
-- Retention: TODO(business) purge rows older than N months (contains Camaro identifiers).

CREATE TABLE CAMARO_DUPLICATE_RESULT (
    JOB_INSTANCE_ID    BIGINT        NOT NULL,   -- BATCH_JOB_INSTANCE.JOB_INSTANCE_ID
    LINE_NUMBER        BIGINT        NOT NULL,   -- physical line in the input file
    JOB_EXECUTION_ID   BIGINT        NOT NULL,   -- execution that produced this row (restart traceability)
    OBSOLETE_ID        VARCHAR(64),
    SURVIVOR_ID        VARCHAR(64),
    OUTCOME            VARCHAR(32)   NOT NULL,   -- ProcessingOutcome
    ACTION_PERFORMED   VARCHAR(64),
    ERROR_CODE         VARCHAR(40),
    ERROR_DESCRIPTION  VARCHAR(500),
    PROCESSED_AT       TIMESTAMP     NOT NULL,
    CONSTRAINT PK_CAMARO_DUPLICATE_RESULT PRIMARY KEY (JOB_INSTANCE_ID, LINE_NUMBER)
);

CREATE INDEX IX_CAMARO_DUP_RESULT_OBSOLETE ON CAMARO_DUPLICATE_RESULT (OBSOLETE_ID);
