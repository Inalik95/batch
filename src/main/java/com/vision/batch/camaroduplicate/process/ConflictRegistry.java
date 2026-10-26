package com.vision.batch.camaroduplicate.process;

import com.vision.batch.camaroduplicate.model.DuplicateRecord;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Whole-file view, built by a pre-scan before any database change, so that
 * contradicting lines are rejected even when they fall into different chunks.
 *
 * Rules (safe defaults, TODO(business) confirm):
 *  - same obsolete id with two different survivors      -> CONFLICT on all its lines
 *  - an id that is obsolete on one line and survivor on
 *    another (chain A->B, B->C, or cycle)               -> CONFLICT on all lines involved
 *  - exact same pair repeated                           -> first line processed, others DUPLICATE_LINE
 *
 * Deterministic for a given file, so a rebuild on restart yields the same result.
 * Memory is O(distinct identifiers); fine for typical volumes, revisit if the
 * monthly file holds millions of lines.
 */
public class ConflictRegistry {

    private final Map<String, Set<String>> survivorsByObsolete = new HashMap<>();
    private final Set<String> survivorIds = new HashSet<>();
    private final Map<String, Long> firstLineByPair = new HashMap<>();
    private boolean sealed;

    /** Called by the pre-scan for every structurally valid record. */
    public void register(DuplicateRecord rec) {
        if (sealed) {
            throw new IllegalStateException("Registry already sealed");
        }
        survivorsByObsolete.computeIfAbsent(rec.obsoleteId(), k -> new LinkedHashSet<>()).add(rec.survivorId());
        survivorIds.add(rec.survivorId());
        firstLineByPair.merge(rec.pairKey(), rec.lineNumber(), Math::min);
    }

    public void seal() {
        sealed = true;
    }

    public boolean isSealed() {
        return sealed;
    }

    public Optional<DuplicateRecordValidator.Violation> conflictFor(DuplicateRecord rec) {
        requireSealed();
        Set<String> survivors = survivorsByObsolete.getOrDefault(rec.obsoleteId(), Set.of());
        if (survivors.size() > 1) {
            return Optional.of(new DuplicateRecordValidator.Violation(ErrorCodes.MULTIPLE_SURVIVORS,
                    "Obsolete identifier has " + survivors.size() + " different survivors in this file"));
        }
        if (survivorIds.contains(rec.obsoleteId()) || survivorsByObsolete.containsKey(rec.survivorId())) {
            return Optional.of(new DuplicateRecordValidator.Violation(ErrorCodes.MERGE_CHAIN,
                    "Identifier is both obsolete and surviving in this file (chain or cycle)"));
        }
        return Optional.empty();
    }

    public boolean isRepeatedLine(DuplicateRecord rec) {
        requireSealed();
        Long first = firstLineByPair.get(rec.pairKey());
        return first != null && first < rec.lineNumber();
    }

    private void requireSealed() {
        if (!sealed) {
            throw new IllegalStateException("Conflict pre-scan has not run");
        }
    }
}
