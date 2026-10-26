package com.vision.batch.camaroduplicate.model;

/**
 * One raw line of the Camaro file, mapped leniently: parsing never rejects a
 * line, validation does. That way every input line ends up in the report.
 *
 * TODO(spec): confirm against Fichier_Pérennité.xlsx (Data, col. G) which
 * columns carry the obsolete and the surviving identifier, and add any other
 * field the business rules need (merge date, Camaro event type, ...).
 *
 * @param lineNumber    1-based physical line number in the input file
 * @param obsoleteId    identifier of the duplicate account to retire (raw, may be blank)
 * @param survivorId    identifier of the account that survives the merge (raw, may be blank)
 * @param tokenCount    number of tokens found on the line, used to detect malformed lines
 */
public record DuplicateRecord(
        long lineNumber,
        String obsoleteId,
        String survivorId,
        int tokenCount) {

    /** Key used for in-file duplicate detection. */
    public String pairKey() {
        return obsoleteId + "->" + survivorId;
    }
}
