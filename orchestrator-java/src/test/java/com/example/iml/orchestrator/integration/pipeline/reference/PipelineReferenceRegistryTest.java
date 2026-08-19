package com.example.iml.orchestrator.integration.pipeline.reference;

import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;
import com.example.iml.orchestrator.integration.clientws.bundle.FpZoneNorm;
import com.example.iml.orchestrator.integration.clientws.bundle.PixelRoi;
import com.example.iml.orchestrator.integration.clientws.bundle.ReferenceBundleSnapshot;
import com.example.iml.orchestrator.integration.clientws.bundle.ReferenceViewSlot;
import com.example.iml.orchestrator.integration.clientws.bundle.ShmFrameRefData;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        assertEquals("/iml_ref_phase0_cam0", cam0.header().get("shm_name"));
        assertEquals("/iml_ref_phase0_cam7", cam7.header().get("shm_name"));
        assertEquals(0L, ((Number) cam0.header().get("shm_offset")).longValue());
        assertTrue(Files.isRegularFile(FrameJpegWriter.imlShmFilePath("iml_ref_phase0_cam0")));
        assertTrue(Files.isRegularFile(FrameJpegWriter.imlShmFilePath("iml_ref_phase0_cam7")));

        assertNotNull(cam3.header().get("interest_polygon_norm"));
        assertNotNull(cam3.header().get("joint_roi_norm"));
        assertNotNull(cam7.header().get("joint_roi_norm"));

        @SuppressWarnings("unchecked")
        Map<String, Object> jointNormBucketA = (Map<String, Object>) cam0.header().get("joint_roi_norm");
        @SuppressWarnings("unchecked")
        Map<String, Object> jointNormBucketB = (Map<String, Object>) cam7.header().get("joint_roi_norm");
        assertNotSame(jointNormBucketA, jointNormBucketB);
    }

    @Test
    void phaseOneDoesNotOverwritePhaseZeroForSameCamera() throws Exception {
        PipelineReferenceRegistry registry = new PipelineReferenceRegistry();
        ReferenceBundleSnapshot phase0 = bucketSnapshot(0, 2, "phase0");
        ReferenceBundleSnapshot legacyPhase1 = bucketSnapshot(0, 2, "phase1");
        ReferenceBundleSnapshot phase1 = new ReferenceBundleSnapshot(
                legacyPhase1.productType(), 1, 2, legacyPhase1.views(), legacyPhase1.jointViewIndex(),
                legacyPhase1.heatmapWidth(), legacyPhase1.heatmapHeight(), legacyPhase1.fpZones(),
                legacyPhase1.acceptedAtEpochMs()
        );

        registry.applyClientBundle(LogManager.getLogger(getClass()), phase0, id -> "det-" + id, List.of(), null);
        registry.applyClientBundle(LogManager.getLogger(getClass()), phase1, id -> "det-" + id, List.of(), null);

        assertEquals("/iml_ref_phase0_cam0", registry.get(0, 0).header().get("shm_name"));
        assertEquals("/iml_ref_phase1_cam0", registry.get(1, 0).header().get("shm_name"));
        assertEquals(registry.get(0, 0), registry.get(0));
        assertEquals("product-0#phase=1#cam=0", registry.get(1, 0).productType());
    }

    private static ReferenceBundleSnapshot bucketSnapshot(int firstCameraId, int jointCameraId, String shmPrefix)
            throws Exception {
        List<ReferenceViewSlot> views = new ArrayList<>(5);
        int jointViewIndex = jointCameraId - firstCameraId;
        int width = 4;
        int height = 2;
        int stride = width * 3;
        byte[] frameBytes = new byte[stride * height];
        for (int offset = 0; offset < 5; offset++) {
            int cameraId = firstCameraId + offset;
            boolean isJointView = offset == jointViewIndex;
            String shmName = shmPrefix + "-cam-" + cameraId;
            Path shmPath = FrameJpegWriter.imlShmFilePath(shmName);
            Path parent = shmPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(shmPath, frameBytes);
            views.add(new ReferenceViewSlot(
                    new ShmFrameRefData(
                            cameraId,
                            "frame-" + cameraId,
                            shmName,
                            width,
                            height,
                            stride,
                            0,
                            "BGR",
                            3,
                            null,
                            null,
                            null
                    ),
                    new PixelRoi(1, 1, 2, 1),
                    isJointView ? new PixelRoi(1, 1, 1, 1) : null,
                    List.of(
                            new FpZoneNorm.PointNorm(0.1, 0.1),
                            new FpZoneNorm.PointNorm(0.9, 0.1),
                            new FpZoneNorm.PointNorm(0.9, 0.9)
                    ),
                    isJointView
                            ? List.of(
                            new FpZoneNorm.PointNorm(0.2, 0.2),
                            new FpZoneNorm.PointNorm(0.4, 0.2),
                            new FpZoneNorm.PointNorm(0.4, 0.8),
                            new FpZoneNorm.PointNorm(0.2, 0.8)
                    )
                            : List.of()
            ));
        }
        return new ReferenceBundleSnapshot(
                "product-" + firstCameraId,
                List.copyOf(views),
                jointViewIndex,
                width,
                height,
                List.of(),
                System.currentTimeMillis()
        );
    }
}
