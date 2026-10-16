package com.example.iml.orchestrator.integration.pipeline.bucket;

import com.example.iml.orchestrator.integration.fanout.BucketFanOutResult;
import com.example.iml.orchestrator.integration.fanout.BucketFanOutSink;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.pipeline.session.PerCameraInspectionGate;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BucketInspectionAggregatorTest {

    @Test
    void deliversBothGroupsAsBatchBeforeCameraUi() {
        aggregator = new BucketInspectionAggregator(LogManager.getLogger(getClass()),
                new BucketInspectionConfig(true, List.of(
                        new BucketGroup(0, List.of(0)), new BucketGroup(1, List.of(1))), 1000L, 1000L));
        List<String> events = new ArrayList<>();
        BucketFanOutSink sink = new BucketFanOutSink() {
            public void publishBucket(BucketFanOutResult result) {
                throw new AssertionError("multi-group delivery must use a batch");
            }
            public void publishBuckets(List<BucketFanOutResult> results) {
                assertEquals(List.of(0, 1), results.stream().map(BucketFanOutResult::groupId).toList());
                events.add("DO3");
                events.add("DO4");
            }
        };
        aggregator.recordFrameResult(1L, 0, decision(0, 1L, true), sink, () -> events.add("UI0"));
        assertTrue(events.isEmpty());
        aggregator.recordFrameResult(1L, 1, decision(1, 1L, true), sink, () -> events.add("UI1"));
        assertEquals(List.of("DO3", "DO4", "UI0", "UI1"), events);
    }

    @Test
    void captureWithoutReferencePassesAndNeverOffersEarlyReject() {
        aggregator = new BucketInspectionAggregator(LogManager.getLogger(getClass()),
                new BucketInspectionConfig(true, List.of(new BucketGroup(0, List.of(0))), 1000L, 1000L));
        List<BucketFanOutResult> published = new ArrayList<>();
        AtomicInteger earlyRejects = new AtomicInteger();
        BucketFanOutSink sink = new BucketFanOutSink() {
            public void publishBucket(BucketFanOutResult result) { published.add(result); }
            public boolean publishEarlyPlasticHandleReject(long sequence, int cameraId) {
                earlyRejects.incrementAndGet(); return true;
            }
        };
        aggregator.recordFrameResult(1L, 0, InspectionDecision.captureOnly(0, 1L), sink);
        assertEquals(0, earlyRejects.get());
        assertEquals(1, published.size());
        assertTrue(published.get(0).overallPass());
    }

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
    void firstRejectedFrameOffersImmediatePlasticHandleRejectBeforeBucketsComplete() {
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
        AtomicInteger earlyRejects = new AtomicInteger();
        List<BucketFanOutResult> published = new ArrayList<>();
        BucketFanOutSink fanOut = new BucketFanOutSink() {
            @Override
            public void publishBucket(BucketFanOutResult result) {
                published.add(result);
            }

            @Override
            public boolean publishEarlyPlasticHandleReject(long triggerSequence, int cameraId) {
                assertEquals(21L, triggerSequence);
                assertEquals(0, cameraId);
                earlyRejects.incrementAndGet();
                return true;
            }
        };

        aggregator.recordFrameResult(21L, 0, decision(0, 310L, false), fanOut);

        assertEquals(1, earlyRejects.get());
        assertEquals(0, published.size(), "normal bucket/UI verdict still waits for all frames");
    }

    @Test
    void earlyRejectIsRoutedOnlyToItsLineWhileOtherLineKeepsWaiting() {
        aggregator = new BucketInspectionAggregator(
                LogManager.getLogger(getClass()),
                new BucketInspectionConfig(true, List.of(
                        new BucketGroup(0, List.of(0, 1)), new BucketGroup(1, List.of(2, 3))), 1000L, 1000L));
        List<Integer> earlyGroups = new ArrayList<>();
        List<BucketFanOutResult> published = new ArrayList<>();
        BucketFanOutSink sink = new BucketFanOutSink() {
            public void publishBucket(BucketFanOutResult result) { published.add(result); }
            public boolean publishEarlyBucketReject(long sequence, int groupId, int cameraId) {
                earlyGroups.add(groupId);
                return true;
            }
        };

        aggregator.recordFrameResult(22L, 0, decision(0, 320L, false), sink);
        assertEquals(List.of(0), earlyGroups);
        assertTrue(published.isEmpty(), "normal bucket/UI results still wait for both complete line summaries");

        aggregator.recordFrameResult(22L, 1, decision(1, 321L, true), sink);
        aggregator.recordFrameResult(22L, 2, decision(2, 322L, true), sink);
        aggregator.recordFrameResult(22L, 3, decision(3, 323L, true), sink);
        assertEquals(2, published.size());
        assertTrue(!published.get(0).overallPass());
        assertTrue(published.get(1).overallPass());
    }

    @Test
    void disabledCameraCannotTriggerEarlyReject() {
        var gate = PerCameraInspectionGate.fromCameras(List.of(Map.of("id", 0), Map.of("id", 1)));
        gate.setInspectionEnabled(1, false);
        aggregator = new BucketInspectionAggregator(LogManager.getLogger(getClass()),
                new BucketInspectionConfig(true, List.of(new BucketGroup(0, List.of(0, 1))), 1000L, 1000L),
                JointSeamPolicy.defaults(), gate);
        List<BucketFanOutResult> published = new ArrayList<>();
        AtomicInteger early = new AtomicInteger();
        BucketFanOutSink sink = new BucketFanOutSink() {
            public void publishBucket(BucketFanOutResult result) { published.add(result); }
            public boolean publishEarlyBucketReject(long sequence, int groupId, int cameraId) {
                early.incrementAndGet(); return true;
            }
        };
        aggregator.recordFrameResult(23L, 1, decision(1, 330L, false), sink);
        aggregator.recordFrameResult(23L, 0, decision(0, 331L, true), sink);
        assertEquals(0, early.get());
        assertTrue(published.get(0).overallPass());
    }

    @Test
    void earlyRejectIsSentOnceAndStaysInFinalResultAfterDisablingFailedCameras() {
        var gate = PerCameraInspectionGate.fromCameras(List.of(Map.of("id", 0), Map.of("id", 1), Map.of("id", 2)));
        aggregator = new BucketInspectionAggregator(LogManager.getLogger(getClass()),
                new BucketInspectionConfig(true, List.of(new BucketGroup(0, List.of(0, 1, 2))), 1000L, 1000L),
                JointSeamPolicy.defaults(), gate);
        List<BucketFanOutResult> published = new ArrayList<>();
        AtomicInteger early = new AtomicInteger();
        BucketFanOutSink sink = new BucketFanOutSink() {
            public void publishBucket(BucketFanOutResult result) { published.add(result); }
            public boolean publishEarlyBucketReject(long sequence, int groupId, int cameraId) {
                early.incrementAndGet(); return true;
            }
        };
        aggregator.recordFrameResult(24L, 0, decision(0, 340L, false), sink);
        aggregator.recordFrameResult(24L, 1, decision(1, 341L, false), sink);
        assertEquals(1, early.get(), "further failing cameras must not issue redundant early DO writes");
        gate.setInspectionEnabled(0, false);
        gate.setInspectionEnabled(1, false);
        aggregator.recordFrameResult(24L, 2, decision(2, 342L, true), sink);
        assertTrue(!published.get(0).overallPass(), "UI verdict must agree with the already delivered reject");
    }

    @Test
    void failedEarlyDeliveryStillCollectsFramesAndRetriesInFinalDelivery() {
        aggregator = new BucketInspectionAggregator(LogManager.getLogger(getClass()),
                new BucketInspectionConfig(true, List.of(new BucketGroup(0, List.of(0, 1))), 1000L, 1000L));
        List<BucketFanOutResult> published = new ArrayList<>();
        BucketFanOutSink sink = new BucketFanOutSink() {
            public void publishBucket(BucketFanOutResult result) { published.add(result); }
            public boolean publishEarlyBucketReject(long sequence, int groupId, int cameraId) {
                throw new IllegalStateException("SDK write failed");
            }
        };
        aggregator.recordFrameResult(25L, 0, decision(0, 350L, false), sink);
        aggregator.recordFrameResult(25L, 1, decision(1, 351L, true), sink);
        assertEquals(1, published.size());
        assertTrue(!published.get(0).overallPass());
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

    @Test
    void softStoppedCameraIsNotRequiredForBucketPass() {
        PerCameraInspectionGate gate = PerCameraInspectionGate.fromCameras(List.of(
                Map.of("id", 0),
                Map.of("id", 1),
                Map.of("id", 2)
        ));
        gate.setInspectionEnabled(1, false);
        aggregator = new BucketInspectionAggregator(
                LogManager.getLogger(BucketInspectionAggregatorTest.class),
                new BucketInspectionConfig(
                        true,
                        List.of(new BucketGroup(0, List.of(0, 1, 2))),
                        1000L,
                        1000L
                ),
                JointSeamPolicy.defaults(),
                gate
        );
        AtomicReference<BucketFanOutResult> published = new AtomicReference<>();
        BucketFanOutSink fanOut = fanOutSink(published);

        aggregator.recordFrameResult(40L, 0, decision(0, 600L, true), fanOut);
        assertNull(published.get());
        aggregator.recordFrameResult(40L, 2, decision(2, 602L, true), fanOut);

        BucketFanOutResult result = published.get();
        assertTrue(result.overallPass());
        assertEquals(2, result.frameDecisions().size());
    }

    @Test
    void disablingCameraCompletesOpenBucketWithoutWaiting() {
        PerCameraInspectionGate gate = PerCameraInspectionGate.fromCameras(List.of(
                Map.of("id", 0),
                Map.of("id", 1),
                Map.of("id", 2)
        ));
        aggregator = new BucketInspectionAggregator(
                LogManager.getLogger(BucketInspectionAggregatorTest.class),
                new BucketInspectionConfig(
                        true,
                        List.of(new BucketGroup(0, List.of(0, 1, 2))),
                        1000L,
                        1000L
                ),
                JointSeamPolicy.defaults(),
                gate
        );
        AtomicReference<BucketFanOutResult> published = new AtomicReference<>();
        BucketFanOutSink fanOut = fanOutSink(published);

        aggregator.recordFrameResult(41L, 0, decision(0, 700L, true), fanOut);
        aggregator.recordFrameResult(41L, 2, decision(2, 702L, true), fanOut);
        assertNull(published.get(), "still waiting for cam 1");

        gate.setInspectionEnabled(1, false);
        aggregator.reevaluateOpenBucketsAfterGateChange();

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
                jointPass,
                Map.of()
        );
    }
}
