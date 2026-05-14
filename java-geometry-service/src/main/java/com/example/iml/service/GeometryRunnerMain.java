package com.example.iml.service;

/**
 * Совместимость с конфигами и shaded JAR: точка входа прежнего FQCN.
 */
public final class GeometryRunnerMain {

    private GeometryRunnerMain() {
    }

    public static void main(String[] args) throws Exception {
        com.example.iml.geometry.runner.GeometryRunnerMain.main(args);
    }
}
