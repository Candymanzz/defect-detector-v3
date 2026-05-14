package com.example.iml.geometry.codec;

import org.opencv.core.Mat;

public interface ImageCodec {
    Mat decodeBase64(String base64);

    String encodeBase64Png(Mat mat);
}
