package com.example.iml.orchestrator.integration.clientws.bundle;

import java.util.List;

/**
 * Один из пяти ракурсов эталона.
 */
public record ReferenceViewSlot(
        ShmFrameRefData frame,
        PixelRoi interestRoi,
        /** ROI стыка только для индекса {@code jointViewIndex}; иначе {@code null}. */
        PixelRoi jointRoi,
        /**
         * Область интереса как многоугольник в норм. координатах кадра [0,1].
         * Пустой список — при inspect строится из {@link #interestRoi()}.
         */
        List<FpZoneNorm.PointNorm> interestPolygonNorm,
        /**
         * Полигон шва в норм. координатах кадра [0,1]; только на joint-view.
         * Пустой список — маска не применяется (только bbox {@link #jointRoi()}).
         */
        List<FpZoneNorm.PointNorm> jointPolygonNorm
) {
    public boolean hasJointRoi() {
        return jointRoi != null;
    }

    public boolean hasInterestPolygonNorm() {
        return interestPolygonNorm != null && interestPolygonNorm.size() >= 3;
    }

    public boolean hasJointPolygonNorm() {
        return jointPolygonNorm != null && jointPolygonNorm.size() >= 3;
    }
}
