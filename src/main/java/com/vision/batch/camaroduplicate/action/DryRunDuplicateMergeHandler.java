package com.vision.batch.camaroduplicate.action;

import com.vision.batch.camaroduplicate.model.DuplicateRecord;

/**
 * Default handler, active until a real one is declared as a bean.
 * Writes nothing; every valid line is reported as ACTION_NOT_CONFIGURED,
 * which makes the run PARTIAL_SUCCESS, so a missing implementation can never
 * look like a successful synchronisation.
 */
public class DryRunDuplicateMergeHandler implements DuplicateMergeHandler {

    @Override
    public MergeResult handle(DuplicateRecord rec) {
        return MergeResult.of(MergeStatus.NOT_CONFIGURED);
    }

    @Override
    public String actionName() {
        return "DRY_RUN";
    }
}
