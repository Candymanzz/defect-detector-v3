package com.example.iml.positioning.runner;

import com.example.iml.positioning.analysis.BucketPositioningService;
import com.example.iml.positioning.opencv.OpenCvNativeLoader;
import com.example.iml.positioning.shm.ShmMatWriter;

import java.io.DataInputStream;
import java.io.DataOutputStream;

public final class PositioningRunnerMain {

    private PositioningRunnerMain() {
    }

    public static void main(String[] args) throws Exception {
        OpenCvNativeLoader.ensureLoaded();
        ShmMatWriter shmWriter = new ShmMatWriter();
        BucketPositioningService positioning = new BucketPositioningService(shmWriter);
        DataInputStream in = new DataInputStream(System.in);
        DataOutputStream out = new DataOutputStream(System.out);
        new StdioBinaryPositioningLoop(in, out, positioning, shmWriter).runForever();
    }
}
