package com.example.iml.orchestrator.integration.pipeline.bucket;

import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;

import java.util.Map;

/**
 * Evaluates joint-seam strict gate when sibling cameras have low seam visibility.
 */
final class BucketSeamStrictGate {

    private final boolean strictActive;
    private final boolean forceReject;
    private final double siblingVisibility;
    private final InspectionDecision jointDecision;

    private BucketSeamStrictGate(
            boolean strictActive,
            boolean forceReject,
            double siblingVisibility,
            InspectionDecision jointDecision
    ) {
        this.strictActive = strictActive;
        this.forceReject = forceReject;
        this.siblingVisibility = siblingVisibility;
        this.jointDecision = jointDecision;
    }

    static BucketSeamStrictGate inactive() {
        return new BucketSeamStrictGate(false, false, 1.0, null);
    }

    static BucketSeamStrictGate evaluate(
            Map<Integer, InspectionDecision> decisions,
            JointSeamPolicy jointSeamPolicy
    ) {
        InspectionDecision joint = null;
        double siblingSum = 0.0;
        int siblingCount = 0;
        for (InspectionDecision decision : decisions.values()) {
            if (decision == null) {
                continue;
            }
            if (decision.jointCamera()) {
                joint = decision;
            } else if (decision.jointVisibility() > 0.0 || !"CAPTURE".equals(decision.action())) {
                siblingSum += decision.jointVisibility();
                siblingCount++;
            }
        }
        if (joint == null) {
            return inactive();
        }
        double siblingVisibility = siblingCount == 0 ? 1.0 : siblingSum / siblingCount;
        boolean strictActive = siblingVisibility < jointSeamPolicy.siblingMinVisibility();
        if (!strictActive) {
            return new BucketSeamStrictGate(false, false, siblingVisibility, joint);
        }
        boolean strictPass = jointSeamPolicy.passesStrict(joint.jointParallelismDeg(), joint.jointWidthMm());
        return new BucketSeamStrictGate(true, !strictPass, siblingVisibility, joint);
    }

    boolean strictActive() {
        return strictActive;
    }

    boolean forceReject() {
        return forceReject;
    }

    double siblingVisibility() {
        return siblingVisibility;
    }

    InspectionDecision jointDecision() {
        return jointDecision;
    }
}
