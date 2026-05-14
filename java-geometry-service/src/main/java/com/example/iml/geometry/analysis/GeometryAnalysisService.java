package com.example.iml.geometry.analysis;

import com.example.iml.geometry.dto.InspectionRequest;
import com.example.iml.geometry.dto.InspectionResponse;

public interface GeometryAnalysisService {
    InspectionResponse inspect(InspectionRequest request);
}
