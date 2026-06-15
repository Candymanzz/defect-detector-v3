package com.example.iml.orchestrator.integration.pipeline.bucket;

import com.example.iml.orchestrator.integration.fanout.BucketFanOutResult;
import com.example.iml.orchestrator.integration.fanout.BucketFanOutSink;
import com.example.iml.orchestrator.integration.fanout.FanOutEvent;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.pipeline.fanoutbridge.InspectionDecisionToFanOutEvent;
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

        aggregator.recordFrameResult(10L, 0, decision(0, 100L, true), fanOut, new InspectionDecisionToFanOutEvent());
        assertNull(published.get());

        aggregator.recordFrameResult(10L, 1, decision(1, 101L, true), fanOut, new InspectionDecisionToFanOutEvent());

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

        aggregator.recordFrameResult(11L, 0, decision(0, 200L, true), fanOut, new InspectionDecisionToFanOutEvent());
        aggregator.recordFrameResult(11L, 1, decision(1, 201L, false), fanOut, new InspectionDecisionToFanOutEvent());

        BucketFanOutResult result = published.get();
        assertEquals(0, result.groupId());
        assertEquals(11L, result.triggerSequence());
        assertTrue(!result.overallPass());
    }

    @Test
    void independentGroupsPublishSeparateBucketSignals() {
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
        BucketFanOutSink fanOut = new BucketFanOutSink() {
            @Override
            public void publishPerFrame(FanOutEvent event) {
            }

            @Override
            public void publishBucket(BucketFanOutResult result) {
                published.add(result);
            }
        };

        aggregator.recordFrameResult(20L, 0, decision(0, 300L, true), fanOut, new InspectionDecisionToFanOutEvent());
        aggregator.recordFrameResult(20L, 1, decision(1, 301L, false), fanOut, new InspectionDecisionToFanOutEvent());
        assertEquals(1, published.size());
        assertEquals(0, published.get(0).groupId());
        assertTrue(!published.get(0).overallPass());

        aggregator.recordFrameResult(20L, 2, decision(2, 302L, true), fanOut, new InspectionDecisionToFanOutEvent());
        aggregator.recordFrameResult(20L, 3, decision(3, 303L, true), fanOut, new InspectionDecisionToFanOutEvent());
        assertEquals(2, published.size());
        assertEquals(1, published.get(1).groupId());
        assertTrue(published.get(1).overallPass());
    }

    private static BucketFanOutSink fanOutSink(AtomicReference<BucketFanOutResult> published) {
        return new BucketFanOutSink() {
            @Override
            public void publishPerFrame(FanOutEvent event) {
            }

            @Override
            public void publishBucket(BucketFanOutResult result) {
                published.set(result);
            }
        };
    }

    private static InspectionDecision decision(int cameraId, long frameId, boolean pass) {
        return new InspectionDecision(cameraId, frameId, pass, pass ? "ACCEPT" : "REJECT", 0.1, "ГОДЕН", "PASS");
    }
}
