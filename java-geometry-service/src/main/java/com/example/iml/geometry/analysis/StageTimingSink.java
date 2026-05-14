package com.example.iml.geometry.analysis;

@FunctionalInterface
public interface StageTimingSink {
    void record(String stage, long durationNs);
}
