package com.example.iml.orchestrator.integration.pipeline;

import java.util.Map;

/** Эталонный кадр в SHM для текущего product_type. */
public record ReferenceSnapshot(String productType, Map<String, Object> header) {
}
