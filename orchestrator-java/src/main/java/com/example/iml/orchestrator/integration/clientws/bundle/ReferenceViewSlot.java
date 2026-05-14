package com.example.iml.orchestrator.integration.clientws.bundle;

/**
 * Один из пяти ракурсов эталона.
 */
public record ReferenceViewSlot(
        ShmFrameRefData frame,
        PixelRoi interestRoi,
        /** ROI стыка только для индекса {@code jointViewIndex}; иначе {@code null}. */
        PixelRoi jointRoi
) {
    public boolean hasJointRoi() {
        return jointRoi != null;
    }
}
