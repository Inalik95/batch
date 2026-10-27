# Camaro duplicate batch — template (socle)

Skeleton for the Camaro "#06 Fusion Tiers T tiers" job. Everything technical is in place.
The business action is a single interface, `DuplicateMergeHandler`, and it ships as a dry run.

**Assumed stack:** Spring Boot 3.3 / Spring Batch 5 / Java 17 / JdbcTemplate. Vision was not inspected.
Adapt the package, persistence style, mail service and chart to Vision's conventions.

**Not compiled or tested yet:** Maven Central was unreachable from the environment this was written in.
Run `mvn test` first. Tests are in `src/test`: two unit test classes and one H2 end-to-end test with 7 scenarios.

## Flow

```
CronJob (DAILY | MONTHLY)
 └─ CamaroDuplicateJobRunner     InputFileSource.list(glob), oldest first; per file:
                                 fetch (COS download) -> SHA-256 -> launch job -> release (delete local copy)
     └─ camaroDuplicateJob       params: fileType*, inputChecksum*, inputFile   (* identifying)
         ├─ preScanStep          tasklet, no DB write, re-runs on restart
         │                       -> ConflictRegistry (whole-file view)
         ├─ processStep          chunk(N), fault tolerant (skip DataAccessException only)
         │    reader             FlatFileItemReader, lenient mapper, no comment lines, restartable
         │    processor          validate -> repeated pair -> conflict -> DuplicateMergeHandler
         │    writer             CAMARO_DUPLICATE_RESULT rows, same tx as business update
         │    skipListener       TECHNICAL_ERROR row in REQUIRES_NEW
         └─ JobCompletionListener (always)
              summary -> CSV (from result table) -> upload CSV to COS -> mail
              -> archive source object (if completed)
```

## Input source: IBM Cloud Object Storage

`vision.batch.camaro-duplicate.source.type` picks the source. Use `s3` in the cluster. `local` is the mounted-directory mode, kept for local runs and the tests.

| | `s3` (IBM COS) | `local` |
|---|---|---|
| Class | `source/S3InputFileSource` | `source/LocalDirectoryInputFileSource` |
| Discovery | objects directly under `input-prefix` whose name matches the glob; sub-prefixes ignored | files in `input-dir` matching the glob |
| Read | downloaded to `work-dir` (`.part` + atomic move); the job reads the local copy | read in place |
| Archive (COMPLETED) | copy to `archive-prefix`, then delete source; skipped if already gone | move to `archive-dir` |
| Re-delivered file | moved to `<archive-prefix>/<name>.already-processed` | same, in `archive-dir` |
| FAILED | object stays in `input-prefix`; next run re-downloads, same checksum ⇒ restart | file stays |
| Report | uploaded to `report-prefix` if set (`S3ReportArchiver`) | stays in `report-dir` |

Client notes (`config/InputSourceConfig`):
- Uses AWS SDK v2 against the COS S3 endpoint, with path-style access.
- Authenticates with the **HMAC keys** of a COS service credential, injected from a Kubernetes Secret.
- Sets `requestChecksumCalculation(WHEN_REQUIRED)`. SDK 2.30+ otherwise sends CRC32 checksums that S3-compatible stores can reject.
- From IKS/ROKS, use the bucket location's *direct* endpoint.
- The credential needs the **Writer** role on the bucket: list, get, put, copy, delete.

If Vision already has a COS client, or requires IAM API-key auth, replace the `camaroS3Client` bean. API-key auth needs `com.ibm.cos:ibm-cos-java-sdk`. `S3InputFileSource` only depends on `S3Client`.

## Guarantees built in

| Concern | How |
|---|---|
| One job, two frequencies | `fileType` param selects a `FileFormat` (glob, delimiter, columns, id regex), configured in properties |
| No silent skip | Lenient parsing, every line gets one `ProcessingOutcome`; processor never returns null; `#` comments disabled |
| Conflicts across chunks | Pre-scan before any write: same obsolete id with 2 survivors, chains/cycles → `CONFLICT`; repeated pair → `DUPLICATE_LINE` |
| Same file twice | Identifying `inputChecksum` → `JobInstanceAlreadyCompleteException`, file moved aside |
| Restart | `FAILED` keeps the file in place; relaunch with same params resumes after last committed chunk; results keyed by job instance |
| Idempotency per line | Contract on `DuplicateMergeHandler`: check state, return `ALREADY_PROCESSED` |
| Atomicity | Business update + outcome row in one chunk transaction |
| Honest status | `SUCCESS` only if completed and every line is OK; dry run ⇒ `PARTIAL_SUCCESS` |
| Report/mail failure | Never changes `BatchStatus`; exit status `COMPLETED_NOTIFICATION_FAILED` → process exit 3 |
| PII | Only the two Camaro ids are persisted and reported; logs carry line numbers and exception classes |

Process exit codes: `0` ok/partial · `1` failed · `3` DB done but report/mail failed · `4` no input file.

## Extension points (what remains to write)

1. **`DuplicateMergeHandler`**: declare a `@Component` and it replaces the dry run automatically.
   Implement only the confirmed operation(s), as separate steps. The candidates are:
   - mark obsolete
   - store obsolete→survivor link
   - disable
   - flag for Portal
   - delete, only with explicit sign-off
2. **`ReportNotifier`**: declare a bean wrapping Vision's mail service to replace `MailReportNotifier`.
3. **`CamaroFileReaderFactory`**: only if the monthly layout is structurally different, not just different columns.
4. **`InputFileSource`**: implement it for another transport (SFTP, another bucket layout...).

## Integration into Vision

- Copy `src/main/java/.../camaroduplicate`, renaming the package.
  - Delete `CamaroDuplicateBatchApplication`.
  - Delete `pom.xml`, after checking batch/jdbc/mail are already dependencies.
- Move `db/camaro-duplicate/V1__...sql` into Vision's Flyway or Liquibase setup.
- Merge `application-camaro-duplicate.yml` into Vision's config.
- Merge `deploy/helm/*` into the existing chart. Then add the `camaroDuplicate` block to each `values-<env>.yaml` that ArgoCD tracks, for all seven environments.
- If Vision already runs Spring Batch jobs, align `CamaroDuplicateJobRunner` with the existing launcher instead.

## Open decisions (safe default in brackets)

| # | Question | Default in template |
|---|---|---|
| 1 | Table/column to update and exact operation(s) | dry run, DB untouched |
| 2 | Column positions, delimiter, encoding, header, id format (Excel sheet) | `;`, UTF-8, header, cols 0/1 |
| 3 | Is A→B, B→C resolvable (A→C) or a conflict? | conflict |
| 4 | Obsolete/survivor not found in Vision: warning or error? | warning (`*_NOT_FOUND`) |
| 5 | Portal: CSV handled by people, or API/flag? | CSV only |
| 6 | No file on a scheduled run: alert? | exit 4 |
| 7 | Same file re-delivered: alert? | moved aside, log warn |
| 8 | Recipients, wording, schedules, secrets per env | placeholders |
| 9 | Retention of reports, archives, result rows | none implemented. On COS, a bucket lifecycle rule on `archive-prefix` / `report-prefix` is the simplest option |
| 10 | COS per env: bucket, location/endpoint, prefixes, who writes `input-prefix` (Camaro directly or a transfer) | placeholders |
| 11 | Auth: HMAC keys (implemented) or IAM API key / trusted profile | HMAC |
