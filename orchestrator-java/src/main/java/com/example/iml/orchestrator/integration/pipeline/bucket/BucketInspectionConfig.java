package com.example.iml.orchestrator.integration.pipeline.bucket;

import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Конфигурация агрегации результатов инспекции по независимым «ведрам».
 * Источник истины — {@code groups} в YAML; {@code mode} — необязательный preset, если groups не заданы.
 */
public record BucketInspectionConfig(
        boolean enabled,
        List<BucketGroup> groups,
        long timeoutMs,
        long lineBroadcastIntervalMs
) {
    public static final int DEFAULT_CAMERAS_PER_PRESET_GROUP = 5;

    public static BucketInspectionConfig disabled() {
        return new BucketInspectionConfig(false, List.of(), 4000L, 5000L);
    }

    public List<Integer> allCameraIds() {
        return groups.stream()
                .flatMap(group -> group.cameraIds().stream())
                .distinct()
                .sorted()
                .toList();
    }

    @SuppressWarnings("unchecked")
    public static BucketInspectionConfig parse(Map<String, Object> integration, Collection<Integer> enabledCameraIds) {
        if (integration == null) {
            return disabled();
        }
        Object raw = integration.get("inspection_bucket");
        if (!(raw instanceof Map<?, ?> m)) {
            return disabled();
        }
        boolean enabled = YamlScalars.toBool(m.get("enabled"), false);
        if (!enabled) {
            return disabled();
        }
        List<BucketGroup> groups = parseGroups(m, enabledCameraIds);
        long timeoutMs = resolveTimeoutMs(m, groups);
        long lineBroadcastIntervalMs = Math.max(
                500L,
                YamlScalars.toLong(m.get("line_broadcast_interval_ms"), 5000L)
        );
        return new BucketInspectionConfig(enabled, groups, timeoutMs, lineBroadcastIntervalMs);
    }

    private static long resolveTimeoutMs(Map<?, ?> m, List<BucketGroup> groups) {
        long configured = YamlScalars.toLong(m.get("timeout_ms"), -1L);
        if (configured > 0) {
            return Math.max(500L, configured);
        }
        int cameraCount = groups.stream().mapToInt(group -> group.cameraIds().size()).sum();
        return cameraCount > DEFAULT_CAMERAS_PER_PRESET_GROUP ? 6000L : 4000L;
    }

    @SuppressWarnings("unchecked")
    private static List<BucketGroup> parseGroups(Map<?, ?> m, Collection<Integer> enabledCameraIds) {
        Object rawGroups = m.get("groups");
        if (rawGroups instanceof List<?> list && !list.isEmpty()) {
            return parseExplicitGroups(list);
        }
        List<Integer> cameraIds = parseFlatCameraIds(m.get("camera_ids"));
        InspectionOperationMode mode = resolveOperationMode(
                m.get("mode") == null ? null : String.valueOf(m.get("mode")),
                cameraIds.isEmpty() ? enabledCameraIds : cameraIds
        );
        if (!cameraIds.isEmpty()) {
            return splitIntoPresetGroups(mode, cameraIds);
        }
        return resolvePresetGroupsForMode(mode, enabledCameraIds);
    }

    @SuppressWarnings("unchecked")
    private static List<BucketGroup> parseExplicitGroups(List<?> list) {
        List<BucketGroup> groups = new ArrayList<>();
        Set<Integer> seenGroupIds = new HashSet<>();
        Set<Integer> seenCameraIds = new HashSet<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> rawGroup)) {
                continue;
            }
            Map<String, Object> groupMap = (Map<String, Object>) rawGroup;
            int groupId = YamlScalars.toInt(groupMap.get("id"), groups.size());
            if (!seenGroupIds.add(groupId)) {
                throw new IllegalStateException("inspection_bucket.groups: duplicate group id=" + groupId);
            }
            List<Integer> cameraIds = parseFlatCameraIds(groupMap.get("camera_ids"));
            if (cameraIds.isEmpty()) {
                throw new IllegalStateException(
                        "inspection_bucket.groups: group id=" + groupId + " requires non-empty camera_ids"
                );
            }
            for (Integer cameraId : cameraIds) {
                if (!seenCameraIds.add(cameraId)) {
                    throw new IllegalStateException(
                            "inspection_bucket.groups: camera " + cameraId + " appears in more than one group"
                    );
                }
            }
            groups.add(new BucketGroup(groupId, cameraIds));
        }
        if (groups.isEmpty()) {
            throw new IllegalStateException("inspection_bucket.groups must contain at least one group");
        }
        return List.copyOf(groups);
    }

    private static List<Integer> parseFlatCameraIds(Object rawCameraIds) {
        if (!(rawCameraIds instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Integer> ids = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Number n) {
                ids.add(n.intValue());
            }
        }
        ids.sort(Comparator.naturalOrder());
        return List.copyOf(ids);
    }

    static List<BucketGroup> splitIntoPresetGroups(InspectionOperationMode mode, List<Integer> sortedCameraIds) {
        if (mode == InspectionOperationMode.FIVE_CAMERAS) {
            if (sortedCameraIds.size() < DEFAULT_CAMERAS_PER_PRESET_GROUP) {
                throw new IllegalStateException(
                        "inspection_bucket.mode=five_cameras requires at least "
                                + DEFAULT_CAMERAS_PER_PRESET_GROUP + " camera_ids, found " + sortedCameraIds.size()
                );
            }
            return List.of(new BucketGroup(0, sortedCameraIds.subList(0, DEFAULT_CAMERAS_PER_PRESET_GROUP)));
        }
        if (sortedCameraIds.size() < DEFAULT_CAMERAS_PER_PRESET_GROUP * 2) {
            throw new IllegalStateException(
                    "inspection_bucket.mode=ten_cameras requires at least "
                            + (DEFAULT_CAMERAS_PER_PRESET_GROUP * 2) + " camera_ids, found " + sortedCameraIds.size()
            );
        }
        return List.of(
                new BucketGroup(0, sortedCameraIds.subList(0, DEFAULT_CAMERAS_PER_PRESET_GROUP)),
                new BucketGroup(1, sortedCameraIds.subList(
                        DEFAULT_CAMERAS_PER_PRESET_GROUP,
                        DEFAULT_CAMERAS_PER_PRESET_GROUP * 2
                ))
        );
    }

    static List<BucketGroup> resolvePresetGroupsForMode(
            InspectionOperationMode mode,
            Collection<Integer> enabledCameraIds
    ) {
        List<Integer> sorted = enabledCameraIds == null
                ? List.of()
                : enabledCameraIds.stream()
                        .filter(id -> id != null && id >= 0)
                        .sorted()
                        .distinct()
                        .toList();
        return splitIntoPresetGroups(mode, sorted);
    }

    private static InspectionOperationMode resolveOperationMode(String rawMode, Collection<Integer> cameraIds) {
        if (rawMode != null && !rawMode.isBlank()) {
            return InspectionOperationMode.fromConfig(rawMode);
        }
        int cameraCount = cameraIds == null ? 0 : (int) cameraIds.stream().filter(id -> id != null && id >= 0).count();
        return cameraCount >= DEFAULT_CAMERAS_PER_PRESET_GROUP * 2
                ? InspectionOperationMode.TEN_CAMERAS
                : InspectionOperationMode.FIVE_CAMERAS;
    }
}
