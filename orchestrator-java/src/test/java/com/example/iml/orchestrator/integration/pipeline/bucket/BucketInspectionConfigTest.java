package com.example.iml.orchestrator.integration.pipeline.bucket;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BucketInspectionConfigTest {

    @Test
    void parsesExplicitGroupsFromYaml() {
        BucketInspectionConfig config = BucketInspectionConfig.parse(
                Map.of(
                        "inspection_bucket",
                        Map.of(
                                "enabled", true,
                                "groups", List.of(
                                        Map.of("id", 0, "camera_ids", List.of(0, 1, 2, 3, 4)),
                                        Map.of("id", 1, "camera_ids", List.of(5, 6, 7, 8, 9))
                                )
                        )
                ),
                Set.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        );

        assertEquals(
                List.of(
                        new BucketGroup(0, List.of(0, 1, 2, 3, 4)),
                        new BucketGroup(1, List.of(5, 6, 7, 8, 9))
                ),
                config.groups()
        );
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), config.allCameraIds());
        assertEquals(6000L, config.timeoutMs());
    }

    @Test
    void resolvesFiveCameraPresetFromEnabledCameras() {
        BucketInspectionConfig config = BucketInspectionConfig.parse(
                Map.of(
                        "inspection_bucket",
                        Map.of("enabled", true, "mode", "five_cameras")
                ),
                Set.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        );

        assertEquals(List.of(new BucketGroup(0, List.of(0, 1, 2, 3, 4))), config.groups());
        assertEquals(4000L, config.timeoutMs());
    }

    @Test
    void resolvesTenCameraPresetAsTwoIndependentGroups() {
        BucketInspectionConfig config = BucketInspectionConfig.parse(
                Map.of(
                        "inspection_bucket",
                        Map.of("enabled", true, "mode", "ten_cameras")
                ),
                new LinkedHashSet<>(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9))
        );

        assertEquals(
                List.of(
                        new BucketGroup(0, List.of(0, 1, 2, 3, 4)),
                        new BucketGroup(1, List.of(5, 6, 7, 8, 9))
                ),
                config.groups()
        );
        assertEquals(6000L, config.timeoutMs());
    }

    @Test
    void explicitCameraIdsWithPresetMode() {
        BucketInspectionConfig config = BucketInspectionConfig.parse(
                Map.of(
                        "inspection_bucket",
                        Map.of(
                                "enabled", true,
                                "mode", "five_cameras",
                                "camera_ids", List.of(2, 3, 4, 5, 6)
                        )
                ),
                Set.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        );

        assertEquals(List.of(new BucketGroup(0, List.of(2, 3, 4, 5, 6))), config.groups());
    }

    @Test
    void failsWhenCameraAppearsInMultipleGroups() {
        assertThrows(
                IllegalStateException.class,
                () -> BucketInspectionConfig.parse(
                        Map.of(
                                "inspection_bucket",
                                Map.of(
                                        "enabled", true,
                                        "groups", List.of(
                                                Map.of("id", 0, "camera_ids", List.of(0, 1, 2)),
                                                Map.of("id", 1, "camera_ids", List.of(2, 3, 4))
                                        )
                                )
                        ),
                        Set.of(0, 1, 2, 3, 4)
                )
        );
    }

    @Test
    void failsWhenNotEnoughCamerasForPresetMode() {
        assertThrows(
                IllegalStateException.class,
                () -> BucketInspectionConfig.parse(
                        Map.of(
                                "inspection_bucket",
                                Map.of("enabled", true, "mode", "ten_cameras")
                        ),
                        Set.of(0, 1, 2, 3, 4)
                )
        );
    }
}
