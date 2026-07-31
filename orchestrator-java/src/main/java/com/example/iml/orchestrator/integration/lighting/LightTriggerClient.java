package com.example.iml.orchestrator.integration.lighting;

import com.example.iml.orchestrator.integration.lighting.client.CaptureLightingSession;
import com.example.iml.orchestrator.integration.lighting.client.LightBankCommander;
import com.example.iml.orchestrator.integration.lighting.client.LightBrightnessApplier;
import com.example.iml.orchestrator.integration.lighting.client.LightBrightnessMemory;
import com.example.iml.orchestrator.integration.lighting.client.LightConstantModeController;
import com.example.iml.orchestrator.integration.lighting.client.LightEngageCommander;
import com.example.iml.orchestrator.integration.lighting.client.LightEndpointReadinessPoller;
import com.example.iml.orchestrator.integration.lighting.client.LightServerHttpTransport;
import com.example.iml.orchestrator.integration.pipeline.spi.CaptureLightingPort;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * HTTP-триггер вспышек LightServer.v3: три типа URL — вкл, выкл, яркость ({@code /api/camera-flash/pair|single}).
 * Domain HTTP/memory/engage/session/bank live in {@code lighting.client.*}.
 */
public final class LightTriggerClient implements CaptureLightingPort {

    private static final Logger LOG = LogManager.getLogger(LightTriggerClient.class);

    private final boolean enabled;
    private final boolean holdMode;
    private final LightBrightnessMemory brightnessMemory;
    private final CaptureLightingSession captureSession;
    private final LightBrightnessApplier brightnessApplier;
    private final LightBankCommander bank;
    private final LightEndpointReadinessPoller readiness;
    private final LightConstantModeController mode;

    public static LightTriggerClient fromRootYaml(Map<String, Object> root) {
        return new LightTriggerClient(LightServersConfig.fromRootYaml(root));
    }

    public LightTriggerClient(LightServersConfig cfg) {
        this.enabled = cfg.enabled();
        this.holdMode = cfg.holdMode();
        LightServerHttpTransport transport = new LightServerHttpTransport(cfg, Math.max(100, cfg.timeoutMs()));
        this.brightnessMemory = new LightBrightnessMemory(cfg.brightnessPercent(), cfg.cameras());
        LightEngageCommander engage = new LightEngageCommander(
                LOG, transport, brightnessMemory, enabled, cfg.failOnError(), Math.max(0, cfg.settleDelayMs()));
        Object lightCommandLock = new Object();
        this.mode = new LightConstantModeController(
                LOG, engage, transport, lightCommandLock, enabled, cfg.failOnError(), holdMode);
        this.captureSession = new CaptureLightingSession(
                LOG, engage, lightCommandLock, enabled, holdMode, mode::isConstantLightingEngaged);
        this.brightnessApplier = new LightBrightnessApplier(
                LOG, engage, brightnessMemory, transport, lightCommandLock, enabled,
                mode::isConstantFlashMode, mode::markConstantLightingEngaged
        );
        this.bank = new LightBankCommander(
                LOG, transport, engage, brightnessMemory, lightCommandLock, enabled, cfg.failOnError(),
                brightnessMemory::brightnessByEndpoint
        );
        this.readiness = new LightEndpointReadinessPoller(LOG, transport, enabled);
        if (enabled) {
            LOG.info(
                    "light_servers: on={} off={} brightness_pair={} brightness_single={} bank={} cameras={} default_brightness_percent={} hold_mode={}",
                    transport.onUrl(), transport.offUrl(), transport.brightnessPairUrl(),
                    transport.brightnessSingleUrl(), transport.flashBankUrl(),
                    brightnessMemory.size(), brightnessMemory.brightnessPercent(), holdMode
            );
            for (LightServersConfig.CameraFlashSpec c : brightnessMemory.snapshot()) {
                LOG.info("  light camera id={} mode={} brightness_percent={}", c.cameraId(), c.mode(), c.brightnessPercent());
            }
        }
    }

    public int brightnessPercent() {
        return brightnessMemory.brightnessPercent();
    }

    public int brightnessPercent(String endpointId) {
        return brightnessMemory.brightnessPercent(endpointId);
    }

    public Map<String, Integer> brightnessByEndpoint() {
        return brightnessMemory.brightnessByEndpoint();
    }

    public List<String> endpointIds() {
        return brightnessMemory.endpointIds();
    }

    public int[] cameraIds(String endpointId) {
        return brightnessMemory.cameraIds(endpointId);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isHoldMode() {
        return holdMode;
    }

    public boolean isConstantFlashMode() {
        return mode.isConstantFlashMode();
    }

    public void setConstantFlashMode(boolean constant) {
        mode.setConstantFlashMode(constant);
    }

    public void startupEngage() {
        mode.startupEngage();
    }

    public void awaitEndpointsReady() {
        readiness.awaitReady();
    }

    public void setBrightnessPercent(int percent) {
        applyBrightnessUpdate(LightBrightnessUpdate.globalOnly(percent));
    }

    public void setBrightnessPercent(String endpointId, int percent) {
        applyBrightnessUpdate(new LightBrightnessUpdate(null, Map.of(endpointId, percent)));
    }

    public LightBrightnessApplyResult applyBrightnessUpdate(LightBrightnessUpdate update) {
        return brightnessApplier.apply(update);
    }

    public LightBrightnessApplyResult flushDeferredBrightness() {
        return brightnessApplier.flushDeferred();
    }

    public boolean hasDeferredHardwareBrightness() {
        return brightnessApplier.hasDeferredHardwareBrightness();
    }

    public void setAfterBrightnessApplied(Runnable hook) {
        brightnessApplier.setAfterBrightnessApplied(hook);
    }

    public void setCaptureLightingActive(BooleanSupplier captureLightingActive) {
        brightnessApplier.setCaptureLightingActive(captureLightingActive);
    }

    @Override
    @SuppressWarnings("unused")
    public void runCaptureWithLighting(
            int cameraId, long frameId, String phase, int flashLeadMs, CaptureLightingPort.CaptureStep captureStep
    ) throws LightingException {
        captureSession.run(cameraId, phase, flashLeadMs, captureStep);
    }

    public boolean lightOn(int cameraId, long frameId, String phase) {
        return bank.lightOn(cameraId, frameId, phase);
    }

    public boolean lightAllOn(String phase) {
        return bankAllOn(phase);
    }

    public boolean bankAllOn(String phase) {
        return bank.bankAllOn(phase);
    }

    public void bankAllOff() {
        bank.bankAllOff();
    }

    public void forceAllOff() {
        bank.forceAllOff();
    }

    public void shutdown() {
        forceAllOff();
    }
}
