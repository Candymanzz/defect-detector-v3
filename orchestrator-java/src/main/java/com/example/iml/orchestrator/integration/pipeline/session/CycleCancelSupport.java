package com.example.iml.orchestrator.integration.pipeline.session;

import com.example.iml.orchestrator.integration.pipeline.PipelineException;

import com.example.iml.orchestrator.integration.pipeline.InspectionPipelineServices;
import com.example.iml.orchestrator.integration.pipeline.PipelineState;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Cancel / poll helpers for async inspection cycles. */
final class CycleCancelSupport {

    static final long CANCEL_POLL_INTERVAL_MS = 50L;

    private CycleCancelSupport() {
    }

    static boolean awaitPythonOrCancel(
            CompletableFuture<PipelineState> captureFuture,
            CompletableFuture<PipelineState> geometryFuture,
            CompletableFuture<PipelineState> pythonFuture,
            CompletableFuture<Void> decisionFuture,
            long timeoutMs,
            PerCameraInspectionGate inspectionGate,
            int cameraId,
            InspectionPipelineServices svc
    ) throws TimeoutException, ExecutionException, InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            if (cancelIfRequested(
                    inspectionGate,
                    cameraId,
                    svc,
                    captureFuture,
                    geometryFuture,
                    pythonFuture,
                    decisionFuture
            )) {
                return false;
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new TimeoutException();
            }
            long waitMs = Math.min(
                    CANCEL_POLL_INTERVAL_MS,
                    Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos))
            );
            try {
                pythonFuture.get(waitMs, TimeUnit.MILLISECONDS);
                return true;
            } catch (TimeoutException ignored) {
                // Poll the cancellation flag until the stage completes or the SLA deadline expires.
            }
        }
    }

    static void awaitDecisionOrCancel(
            CompletableFuture<Void> decisionFuture,
            PerCameraInspectionGate inspectionGate,
            int cameraId,
            InspectionPipelineServices svc,
            CompletableFuture<?>... futures
    ) {
        while (!decisionFuture.isDone()) {
            if (cancelIfRequested(inspectionGate, cameraId, svc, futures)) {
                return;
            }
            try {
                decisionFuture.get(CANCEL_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
                return;
            } catch (TimeoutException ignored) {
                // Continue polling cancellation.
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new PipelineException(cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PipelineException(e);
            }
        }
        decisionFuture.join();
    }

    static boolean cancelIfRequested(
            PerCameraInspectionGate inspectionGate,
            int cameraId,
            InspectionPipelineServices svc,
            CompletableFuture<?>... futures
    ) {
        if (inspectionGate == null || !inspectionGate.isCancelRequested(cameraId)) {
            return false;
        }
        for (CompletableFuture<?> future : futures) {
            future.cancel(true);
        }
        svc.log().info("integration cam={}: inspection cycle cancelled by client", cameraId);
        return true;
    }

    static void cancelInspectionFutures(CompletableFuture<?>... futures) {
        for (CompletableFuture<?> future : futures) {
            future.cancel(true);
        }
    }
}
