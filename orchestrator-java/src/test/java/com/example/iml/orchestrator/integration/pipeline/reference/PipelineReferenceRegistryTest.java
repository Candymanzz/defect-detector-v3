package com.example.iml.orchestrator.integration.pipeline.reference;

import com.example.iml.orchestrator.integration.clientws.bundle.FpZoneNorm;
import com.example.iml.orchestrator.integration.clientws.bundle.PixelRoi;
import com.example.iml.orchestrator.integration.clientws.bundle.ReferenceBundleSnapshot;
import com.example.iml.orchestrator.integration.clientws.bundle.ReferenceViewSlot;
import com.example.iml.orchestrator.integration.clientws.bundle.ShmFrameRefData;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class PipelineReferenceRegistryTest {

    @Test
    void applyClientBundleStoresPerCameraReferenceForEachBucket() throws Exception {
        PipelineReferenceRegistry registry = new PipelineReferenceRegistry();

        registry.applyClientBundle(
                LogManager.getLogger(getClass()),
                bucketSnapshot(0, 2, "bucket-a"),
                cameraId -> "det-" + cameraId,
                List.of(),
                null
        );
        registry.applyClientBundle(
                LogManager.getLogger(getClass()),
                bucketSnapshot(5, 7, "bucket-b"),
                cameraId -> "det-" + cameraId,
                List.of(),
                null
        );

        ReferenceSnapshot cam0 = registry.get(0);
        ReferenceSnapshot cam3 = registry.get(3);
        ReferenceSnapshot cam7 = registry.get(7);

        assertNotNull(cam0);
        assertNotNull(cam3);
        assertNotNull(cam7);
        assertNotSame(cam0.header().get("shm_name"), cam7.header().get("shm_name"));

        assertEquals(0, cam0.header().get("bucket_group_id"));
        assertEquals(1, cam7.header().get("bucket_group_id"));
        assertEquals(2, cam0.header().get("joint_camera_id"));
        assertEquals(7, cam7.header().get("joint_camera_id"));

        assertNotNull(cam3.header().get("interest_polygon_norm"));
        assertNotNull(cam3.header().get("joint_roi_norm"));
        assertNotNull(cam7.header().get("joint_roi_norm"));

        @SuppressWarnings("unchecked")
        Map<String, Object> jointNormBucketA = (Map<String, Object>) cam0.header().get("joint_roi_norm");
        @SuppressWarnings("unchecked")
        Map<String, Object> jointNormBucketB = (Map<String, Object>) cam7.header().get("joint_roi_norm");
        assertNotSame(jointNormBucketA, jointNormBucketB);
    }

    private static ReferenceBundleSnapshot bucketSnapshot(int firstCameraId, int jointCameraId, String shmPrefix) {
        List<ReferenceViewSlot> views = new ArrayList<>(5);
        int jointViewIndex = jointCameraId - firstCameraId;
        for (int offset = 0; offset < 5; offset++) {
            int cameraId = firstCameraId + offset;
            boolean isJointView = offset == jointViewIndex;
            views.add(new ReferenceViewSlot(
                    new ShmFrameRefData(
                            cameraId,
                            "frame-" + cameraId,
                            shmPrefix + "-cam-" + cameraId,
                            2448,
                            2048,
                            7344,
                            cameraId * 10_000,
                            "BGR",
                            3,
                            null,
                            null,
                            null
                    ),
                    new PixelRoi(100, 100, 800, 800),
                    isJointView ? new PixelRoi(200, 200, 120, 120) : null,
                    List.of(
                            new FpZoneNorm.PointNorm(0.1, 0.1),
                            new FpZoneNorm.PointNorm(0.9, 0.1),
                            new FpZoneNorm.PointNorm(0.9, 0.9)
                    )
            ));
        }
        return new ReferenceBundleSnapshot(
                "product-" + firstCameraId,
                List.copyOf(views),
                jointViewIndex,
                2448,
                2048,
                List.of(),
                System.currentTimeMillis()
        );
    }
}
