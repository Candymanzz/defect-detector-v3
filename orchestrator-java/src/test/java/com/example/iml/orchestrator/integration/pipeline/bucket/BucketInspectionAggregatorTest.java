package com.example.iml.orchestrator.integration.pipeline.bucket;

import com.example.iml.orchestrator.integration.fanout.BucketFanOutResult;
import com.example.iml.orchestrator.integration.fanout.BucketFanOutSink;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BucketInspectionAggregatorTest {

    private BucketInspectionAggregator aggregator;

    @AfterEach
    void tearDown() {
        if (aggregator != null) {
            aggregator.close();
        }
    }

    @Test
    void publishesBucketPassWhenAllFramesPass() {
        aggregator = new BucketInspectionAggregator(
                LogManager.getLogger(BucketInspectionAggregatorTest.class),
                new BucketInspectionConfig(
                        true,
                        List.of(new BucketGroup(0, List.of(0, 1))),
                        1000L,
                        1000L
                )
        );
        AtomicReference<BucketFanOutResult> published = new AtomicReference<>();
        BucketFanOutSink fanOut = fanOutSink(published);

        aggregator.recordFrameResult(10L, 0, decision(0, 100L, true), fanOut);
        assertNull(published.get());

        aggregator.recordFrameResult(10L, 1, decision(1, 101L, true), fanOut);

        BucketFanOutResult result = published.get();
        assertEquals(0, result.groupId());
        assertEquals(10L, result.triggerSequence());
        assertTrue(result.overallPass());
        assertEquals(2, result.frameDecisions().size());
    }

    @Test
    void publishesBucketRejectWhenAnyFrameFails() {
        aggregator = new BucketInspectionAggregator(
                LogManager.getLogger(BucketInspectionAggregatorTest.class),
                new BucketInspectionConfig(
                        true,
                        List.of(new BucketGroup(0, List.of(0, 1))),
                        1000L,
                        1000L
                )
        );
        AtomicReference<BucketFanOutResult> published = new AtomicReference<>();
        BucketFanOutSink fanOut = fanOutSink(published);

        aggregator.recordFrameResult(11L, 0, decision(0, 200L, true), fanOut);
        aggregator.recordFrameResult(11L, 1, decision(1, 201L, false), fanOut);

        BucketFanOutResult result = published.get();
        assertEquals(0, result.groupId());
        assertEquals(11L, result.triggerSequence());
        assertTrue(!result.overallPass());
    }

    @Test
    void twoGroupsPublishTogetherOnlyWhenBothBucketsReady() {
        aggregator = new BucketInspectionAggregator(
                LogManager.getLogger(BucketInspectionAggregatorTest.class),
                new BucketInspectionConfig(
                        true,
                        List.of(
                                new BucketGroup(0, List.of(0, 1)),
                                new BucketGroup(1, List.of(2, 3))
                        ),
                        1000L,
                        1000L
                )
        );
        List<BucketFanOutResult> published = new ArrayList<>();
        BucketFanOutSink fanOut = published::add;

        aggregator.recordFrameResult(20L, 0, decision(0, 300L, true), fanOut);
        aggregator.recordFrameResult(20L, 1, decision(1, 301L, false), fanOut);
        assertEquals(0, published.size(), "line1 must wait for line2 before FINS/UI fanout");

        aggregator.recordFrameResult(20L, 2, decision(2, 302L, true), fanOut);
        aggregator.recordFrameResult(20L, 3, decision(3, 303L, true), fanOut);
        assertEquals(2, published.size());
        assertEquals(0, published.get(0).groupId());
        assertTrue(!published.get(0).overallPass());
        assertEquals(1, published.get(1).groupId());
        assertTrue(published.get(1).overallPass());
    }

    @Test
    void phaseOnePairWaitsForPhaseZeroAndKeepsRawSequence() {
        aggregator = new BucketInspectionAggregator(
                LogManager.getLogger(BucketInspectionAggregatorTest.class),
                new BucketInspectionConfig(
                        true,
                        List.of(
                                new BucketGroup(0, 0, List.of(0)),
                                new BucketGroup(0, 1, List.of(1)),
                                new BucketGroup(1, 2, List.of(0)),
                                new BucketGroup(1, 3, List.of(1))
                        ),
                        1000L,
                        1000L
                )
        );
        List<BucketFanOutResult> published = new ArrayList<>();

        aggregator.recordFrameResult(101L, 100L, 1, 101L, 0, decision(0, 401L, true), published::add);
        aggregator.recordFrameResult(101L, 100L, 1, 101L, 1, decision(1, 402L, true), published::add);
        assertEquals(0, published.size(), "phase1 pair must remain held");

        aggregator.recordFrameResult(100L, 100L, 0, 100L, 1, decision(1, 403L, true), published::add);
        aggregator.recordFrameResult(100L, 100L, 0, 100L, 0, decision(0, 404L, true), published::add);

        assertEquals(List.of(0, 1, 2, 3), published.stream().map(BucketFanOutResult::groupId).toList());
        assertEquals(List.of(0, 0, 1, 1), published.stream().map(BucketFanOutResult::phaseId).toList());
        assertEquals(101L, published.get(2).rawTriggerSequence());
        assertEquals(100L, published.get(2).parentCycleId());
    }

    @Test
    void phaseTimeoutRejectsOnlyMissingGroup() throws Exception {
        aggregator = new BucketInspectionAggregator(
                LogManager.getLogger(BucketInspectionAggregatorTest.class),
                new BucketInspectionConfig(
                        true,
                        List.of(
                                new BucketGroup(0, 0, List.of(0)),
                                new BucketGroup(0, 1, List.of(1))
                        ),
                        60L,
                        1000L
                )
        );
        List<BucketFanOutResult> published = new java.util.concurrent.CopyOnWriteArrayList<>();
        aggregator.recordFrameResult(200L, 200L, 0, 200L, 0, decision(0, 500L, true), published::add);

        long deadline = System.currentTimeMillis() + 1000L;
        while (published.size() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }

        assertEquals(2, published.size());
        assertTrue(published.get(0).overallPass());
        assertEquals(0, published.get(0).groupId());
        assertTrue(!published.get(1).overallPass());
        assertEquals(1, published.get(1).groupId());
        assertTrue(published.get(1).frameDecisions().isEmpty());
    }

    @Test
    void jointPassIgnoresLowSiblingVisibilityStrictGate() {
        JointSeamPolicy policy = new JointSeamPolicy(0.25, 1.5, 0.8, 2.5);
        aggregator = new BucketInspectionAggregator(
                LogManager.getLogger(BucketInspectionAggregatorTest.class),
                new BucketInspectionConfig(
                        true,
                        List.of(new BucketGroup(0, List.of(0, 1))),
                        1000L,
                        1000L
                ),
                policy
        );
        AtomicReference<BucketFanOutResult> published = new AtomicReference<>();
        BucketFanOutSink fanOut = fanOutSink(published);

        // Joint прошёл обычные пороги (jointPass=true) — sibling strict не валит ведро,
        // даже если parallelism выше strict-лимита и sibling_vis низкая.
        aggregator.recordFrameResult(
                30L,
                0,
                seamDecision(0, 400L, true, true, true, 2.0, 1.2, 0.9),
                fanOut
        );
        aggregator.recordFrameResult(
                30L,
                1,
                seamDecision(1, 401L, true, false, true, 0.0, 0.0, 0.05),
                fanOut
        );

        BucketFanOutResult result = published.get();
        assertTrue(result.overallPass());
    }

    @Test
    void jointFailPlusLowSiblingVisibilityAppliesStrictAsExtraReject() {
        JointSeamPolicy policy = new JointSeamPolicy(0.25, 1.5, 0.8, 2.5);
        aggregator = new BucketInspectionAggregator(
                LogManager.getLogger(BucketInspectionAggregatorTest.class),
                new BucketInspectionConfig(
                        true,
                        List.of(new BucketGroup(0, List.of(0, 1))),
                        1000L,
                        1000L
                ),
                policy
        );
        AtomicReference<BucketFanOutResult> published = new AtomicReference<>();
        BucketFanOutSink fanOut = fanOutSink(published);

        // overallPass=true, но jointPass=false — доп. sibling-strict добивает ведро.
        aggregator.recordFrameResult(
                32L,
                0,
                seamDecision(0, 600L, true, true, false, 2.0, 0.3, 0.9),
                fanOut
        );
        aggregator.recordFrameResult(
                32L,
                1,
                seamDecision(1, 601L, true, false, true, 0.0, 0.0, 0.05),
                fanOut
        );

        BucketFanOutResult result = published.get();
        assertTrue(!result.overallPass());
    }

    @Test
    void highSiblingVisibilityKeepsNormalBucketPass() {
        JointSeamPolicy policy = new JointSeamPolicy(0.25, 1.5, 0.8, 2.5);
        aggregator = new BucketInspectionAggregator(
                LogManager.getLogger(BucketInspectionAggregatorTest.class),
                new BucketInspectionConfig(
                        true,
                        List.of(new BucketGroup(0, List.of(0, 1))),
                        1000L,
                        1000L
                ),
                policy
        );
        AtomicReference<BucketFanOutResult> published = new AtomicReference<>();
        BucketFanOutSink fanOut = fanOutSink(published);

        aggregator.recordFrameResult(
                31L,
                0,
                seamDecision(0, 500L, true, true, true, 2.0, 1.2, 0.9),
                fanOut
        );
        aggregator.recordFrameResult(
                31L,
                1,
                seamDecision(1, 501L, true, false, true, 0.0, 0.0, 0.8),
                fanOut
        );

        BucketFanOutResult result = published.get();
        assertTrue(result.overallPass());
    }

    private static BucketFanOutSink fanOutSink(AtomicReference<BucketFanOutResult> published) {
        return result -> published.set(result);
    }

    private static InspectionDecision decision(int cameraId, long frameId, boolean pass) {
        return InspectionDecision.simple(
                cameraId,
                frameId,
                pass,
                pass ? "ACCEPT" : "REJECT",
                0.1,
                "ГОДЕН",
                "PASS"
        );
    }

    private static InspectionDecision seamDecision(
            int cameraId,
            long frameId,
            boolean pass,
            boolean jointCamera,
            boolean jointPass,
            double parallelismDeg,
            double widthMm,
            double visibility
    ) {
        return new InspectionDecision(
                cameraId,
                frameId,
                pass,
                pass ? "ACCEPT" : "REJECT",
                0.1,
                "ГОДЕН",
                "PASS",
                jointCamera,
                parallelismDeg,
                widthMm,
                visibility,
                jointPass
        );
    }
}
