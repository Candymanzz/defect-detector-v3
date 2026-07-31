package com.example.iml.orchestrator.integration.preview;

import com.example.iml.orchestrator.integration.capture.LineSynchronizedCaptureCoordinator;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class LivePreviewRuntimeContext {
    final AtomicBoolean closed = new AtomicBoolean(false);
    final AtomicBoolean cycleInProgress = new AtomicBoolean(false);
    final ConcurrentHashMap<Integer, AtomicBoolean> tickInProgressByCamera = new ConcurrentHashMap<>();
    final AtomicLong previewLineSequence = new AtomicLong(0L);
    final LivePreviewMetrics metrics = new LivePreviewMetrics();
    volatile LineSynchronizedCaptureCoordinator lineCaptureCoordinator;
}
