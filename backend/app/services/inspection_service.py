import base64
from dataclasses import dataclass
from io import BytesIO
from typing import Dict, Optional, Tuple

import cv2
import numpy as np
from PIL import Image


@dataclass
class InspectionResult:
    product_type: str
    status: str
    anomaly_score: float
    threshold: float
    aligned_image_b64: str = ""
    diff_map_b64: str = ""
    heatmap_b64: str = ""
    segmentation_mask_b64: str = ""


class InspectionService:
    def __init__(self) -> None:
        self.references: Dict[str, np.ndarray] = {}
        self._orb = cv2.ORB_create(nfeatures=3000)
        self._matcher = cv2.BFMatcher(cv2.NORM_HAMMING, crossCheck=False)
        self._fallback_threshold = 0.25

        self._anomaly_engine = None
        self._load_anomalib_engine()

    def _load_anomalib_engine(self) -> None:
        try:
            from anomalib.deploy import OpenVINOInferencer  # type: ignore

            self._anomaly_engine = OpenVINOInferencer(
                path="models/patchcore/openvino/model.xml" # model path
            )
        except Exception:
            self._anomaly_engine = None

    def set_reference(self, product_type: str, image_bytes: bytes) -> None:
        image = self._decode_image(image_bytes)
        self.references[product_type] = image

    def get_reference(self, product_type: str) -> Optional[np.ndarray]:
        return self.references.get(product_type)

    def inspect(
        self,
        product_type: str,
        image_bytes: bytes,
        threshold: Optional[float] = None,
        include_visuals: bool = True,
    ) -> InspectionResult:
        reference = self.get_reference(product_type)
        if reference is None:
            raise ValueError(f"Reference for product_type '{product_type}' is not set")

        current = self._decode_image(image_bytes)
        aligned = self._align_to_reference(current, reference)
        diff_map = self._compute_advanced_difference(aligned, reference)

        anomaly_score, segmentation_mask = self._run_anomaly_model(diff_map)

        inspection_threshold = (
            threshold if threshold is not None else self._fallback_threshold
        )
        status = "БРАК" if anomaly_score >= inspection_threshold else "ГОДЕН"

        heatmap = self._build_heatmap(segmentation_mask) if include_visuals else None

        return InspectionResult(
            product_type=product_type,
            status=status,
            anomaly_score=anomaly_score,
            threshold=inspection_threshold,
            aligned_image_b64=self._encode_image(aligned) if include_visuals else "",
            diff_map_b64=self._encode_image(diff_map) if include_visuals else "",
            heatmap_b64=self._encode_image(heatmap) if include_visuals and heatmap is not None else "",
            segmentation_mask_b64=self._encode_image(segmentation_mask) if include_visuals else "",
        )

    def _decode_image(self, image_bytes: bytes) -> np.ndarray:
        data = np.frombuffer(image_bytes, dtype=np.uint8)
        image = cv2.imdecode(data, cv2.IMREAD_COLOR)
        if image is None:
            raise ValueError("Could not decode image")
        return image

    def _encode_image(self, image: np.ndarray) -> str:
        rgb_image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
        pil_image = Image.fromarray(rgb_image)
        buffer = BytesIO()
        pil_image.save(buffer, format="PNG")
        return base64.b64encode(buffer.getvalue()).decode("utf-8")

    def _align_to_reference(self, current: np.ndarray, reference: np.ndarray) -> np.ndarray:
        ref_gray = cv2.cvtColor(reference, cv2.COLOR_BGR2GRAY)
        cur_gray = cv2.cvtColor(current, cv2.COLOR_BGR2GRAY)

        kp_ref, des_ref = self._orb.detectAndCompute(ref_gray, None)
        kp_cur, des_cur = self._orb.detectAndCompute(cur_gray, None)
        if des_ref is None or des_cur is None or len(kp_ref) < 8 or len(kp_cur) < 8:
            return cv2.resize(current, (reference.shape[1], reference.shape[0]))

        matches = self._matcher.knnMatch(des_cur, des_ref, k=2)
        good_matches = []
        for pair in matches:
            if len(pair) < 2:
                continue
            m, n = pair
            if m.distance < 0.75 * n.distance:
                good_matches.append(m)

        if len(good_matches) < 8:
            return cv2.resize(current, (reference.shape[1], reference.shape[0]))

        src_pts = np.float32([kp_cur[m.queryIdx].pt for m in good_matches]).reshape(-1, 1, 2)
        dst_pts = np.float32([kp_ref[m.trainIdx].pt for m in good_matches]).reshape(-1, 1, 2)

        homography, mask = cv2.findHomography(src_pts, dst_pts, cv2.RANSAC, 5.0)
        if homography is None or mask is None:
            return cv2.resize(current, (reference.shape[1], reference.shape[0]))

        height, width = reference.shape[:2]
        return cv2.warpPerspective(current, homography, (width, height))

    def _compute_advanced_difference(
        self,
        aligned: np.ndarray,
        reference: np.ndarray,
    ) -> np.ndarray:
        if aligned.shape[:2] != reference.shape[:2]:
            aligned = cv2.resize(aligned, (reference.shape[1], reference.shape[0]))

        diff = cv2.absdiff(aligned, reference)
        return cv2.GaussianBlur(diff, (5, 5), 0)

    def _run_anomaly_model(self, diff_map: np.ndarray) -> Tuple[float, np.ndarray]:
        if self._anomaly_engine is not None:
            try:
                prediction = self._anomaly_engine.predict(image=diff_map)
                score = float(prediction.pred_score)
                mask = prediction.pred_mask.astype(np.uint8) * 255
                if len(mask.shape) == 2:
                    mask = cv2.cvtColor(mask, cv2.COLOR_GRAY2BGR)
                return score, mask
            except Exception:
                pass

        gray = cv2.cvtColor(diff_map, cv2.COLOR_BGR2GRAY)
        norm = cv2.normalize(gray, None, alpha=0, beta=255, norm_type=cv2.NORM_MINMAX)
        _, binary = cv2.threshold(norm, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
        score = float(np.mean(norm) / 255.0)
        mask = cv2.cvtColor(binary, cv2.COLOR_GRAY2BGR)
        return score, mask

    def _build_heatmap(self, mask: np.ndarray) -> np.ndarray:
        gray = cv2.cvtColor(mask, cv2.COLOR_BGR2GRAY)
        return cv2.applyColorMap(gray, cv2.COLORMAP_JET)
