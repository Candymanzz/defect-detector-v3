package com.example.iml.geometry.runner;

import com.example.iml.geometry.analysis.OpenCvGeometryAnalysisService;
import com.example.iml.geometry.calibration.CalibrationService;
import com.example.iml.geometry.codec.OpenCvImageCodec;
import com.example.iml.geometry.opencv.OpenCvNativeLoader;

import java.io.DataInputStream;
import java.io.DataOutputStream;

public final class GeometryRunnerMain {

    private GeometryRunnerMain() {
    }

    public static void main(String[] args) throws Exception {
        OpenCvNativeLoader.ensureLoaded();
        OpenCvGeometryAnalysisService inspection = new OpenCvGeometryAnalysisService(
                new OpenCvImageCodec(),
                new CalibrationService(),
                false,
                true
        );

        DataInputStream in = new DataInputStream(System.in);
        DataOutputStream out = new DataOutputStream(System.out);
        new StdioBinaryGeometryLoop(in, out, inspection).runForever();
    }
}
