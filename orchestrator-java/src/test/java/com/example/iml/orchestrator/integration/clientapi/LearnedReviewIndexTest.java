package com.example.iml.orchestrator.integration.clientapi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LearnedReviewIndexTest {

    @Test
    void lookupPrefersCameraFrameThenScopedProduct() {
        LearnedReviewIndex.remember(2, 1042L, "bucket#cam=2", "uuid-cam-2");
        LearnedReviewIndex.remember(3, 1042L, "bucket#cam=3", "uuid-cam-3");

        assertEquals("uuid-cam-2", LearnedReviewIndex.lookup(2, 1042L, "bucket"));
        assertEquals("uuid-cam-3", LearnedReviewIndex.lookup(null, 1042L, "bucket#cam=3"));
        assertNull(LearnedReviewIndex.lookup(9, 1042L, "other"));
    }

    @Test
    void scopedProductTypeAppendsCameraOnce() {
        assertEquals("bucket#phase=0#cam=2", LearnedReviewIndex.scopedProductType("bucket", 2));
        assertEquals("bucket#phase=0#cam=9", LearnedReviewIndex.scopedProductType("bucket#cam=2", 9));
        assertEquals("bucket#phase=1#cam=2", LearnedReviewIndex.scopedProductType("bucket#phase=1#cam=9", 1, 2));
        assertEquals("bucket", LearnedReviewIndex.scopedProductType("bucket", null));
    }
}
