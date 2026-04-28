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
        self.rois: Dict[str, Tuple[float, float, float, float]] = {}
        self.roi_polygons: Dict[str, list[Tuple[float, float]]] = {}
        self._orb = cv2.ORB_create(nfeatures=1800)
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

    def set_roi(self, product_type: str, x: float, y: float, w: float, h: float) -> None:
        for name, value in {"x": x, "y": y, "w": w, "h": h}.items():
            if value < 0 or value > 1:
                raise ValueError(f"ROI field '{name}' must be in [0, 1]")
        if w <= 0 or h <= 0:
            raise ValueError("ROI width/height must be > 0")
        if x + w > 1 or y + h > 1:
            raise ValueError("ROI rectangle must be fully inside image bounds")
        self.rois[product_type] = (x, y, w, h)

    def get_roi(self, product_type: str) -> Optional[Tuple[float, float, float, float]]:
        return self.rois.get(product_type)

    def set_roi_polygon(self, product_type: str, points: list[Tuple[float, float]]) -> None:
        if len(points) < 3:
            raise ValueError("ROI polygon must contain at least 3 points")
        normalized_points: list[Tuple[float, float]] = []
        for idx, (x, y) in enumerate(points):
            if x < 0 or x > 1 or y < 0 or y > 1:
                raise ValueError(f"ROI polygon point #{idx + 1} must be inside [0, 1]")
            normalized_points.append((float(x), float(y)))
        self.roi_polygons[product_type] = normalized_points

    def get_roi_polygon(self, product_type: str) -> Optional[list[Tuple[float, float]]]:
        return self.roi_polygons.get(product_type)

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

        polygon = self.get_roi_polygon(product_type)
        if polygon is not None:
            aligned, reference = self._mask_to_polygon(aligned, reference, polygon)

        roi = self.get_roi(product_type)
        if roi is not None:
            aligned, reference = self._crop_to_roi(aligned, reference, roi)

        diff_map = self._compute_advanced_difference(aligned, reference)

        anomaly_score, segmentation_mask = self._run_anomaly_model(diff_map)

        inspection_threshold = (
            threshold if threshold is not None else self._fallback_threshold
        )
        status = "БРАК" if anomaly_score >= inspection_threshold else "ГОДЕН"

        heatmap = self._build_heatmap(segmentation_mask, diff_map) if include_visuals else None

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

        homography, mask = cv2.findHomography(src_pts, dst_pts, cv2.RANSAC, 1.0)
        if homography is None or mask is None:
            return cv2.resize(current, (reference.shape[1], reference.shape[0]))

        height, width = reference.shape[:2]
        aligned = cv2.warpPerspective(current, homography, (width, height))
        return self._refine_alignment_ecc(aligned, reference)

    def _compute_advanced_difference(
        self,
        aligned: np.ndarray,
        reference: np.ndarray,
    ) -> np.ndarray:
        if aligned.shape[:2] != reference.shape[:2]:
            aligned = cv2.resize(aligned, (reference.shape[1], reference.shape[0]))

        # Shift-tolerant difference:
        # allow small local displacement by comparing aligned intensity
        # against local min/max envelope of reference.
        ref_gray = cv2.cvtColor(reference, cv2.COLOR_BGR2GRAY)
        cur_gray = cv2.cvtColor(aligned, cv2.COLOR_BGR2GRAY)

        # CLAHE can over-amplify texture noise on smooth frames, so apply it only
        # when the frame has enough contrast/variance.
        if float(np.std(cur_gray)) > 5.0:
            clahe = cv2.createCLAHE(clipLimit=1.2, tileGridSize=(8, 8))
            ref_gray = clahe.apply(ref_gray)
            cur_gray = clahe.apply(cur_gray)

        kernel = np.ones((5, 5), dtype=np.uint8)
        ref_min = cv2.erode(ref_gray, kernel, iterations=1)
        ref_max = cv2.dilate(ref_gray, kernel, iterations=1)

        over = cv2.subtract(cur_gray, ref_max)
        under = cv2.subtract(ref_min, cur_gray)
        robust_gray = cv2.max(over, under)

        # Bilateral filter better preserves thin scratch edges than Gaussian blur.
        robust_gray = cv2.bilateralFilter(robust_gray, d=7, sigmaColor=35, sigmaSpace=35)

        # Light Gaussian denoising before Black Hat suppresses sensor speckles
        # that can otherwise be boosted as false micro-defects.
        robust_gray = cv2.GaussianBlur(robust_gray, (3, 3), 0)

        # Black Hat highlights narrow dark structures on top of larger bright regions
        # (e.g., thin scratches over bucket texture/text).
        blackhat = cv2.morphologyEx(
            robust_gray,
            cv2.MORPH_BLACKHAT,
            np.ones((15, 15), dtype=np.uint8),
        )
        robust_gray = cv2.addWeighted(robust_gray, 0.7, blackhat, 0.3, 0.0)

        # Edge suppression on strong static reference edges (lid/border/text bounds):
        # reduce anomaly response in a small tolerance band around those edges.
        edges_ref = cv2.Canny(ref_gray, 80, 160)
        edges_zone = cv2.dilate(edges_ref, np.ones((3, 3), dtype=np.uint8), iterations=2)
        edge_mask = edges_zone > 0
        robust_gray = robust_gray.astype(np.float32)
        robust_gray[edge_mask] *= 0.2
        robust_gray = np.clip(robust_gray, 0, 255).astype(np.uint8)

        # Structural masking for text-heavy regions:
        # where reference has dense structure, require stronger local contrast
        # to treat response as anomaly.
        structure_mask = cv2.Sobel(ref_gray, cv2.CV_8U, 1, 1, ksize=3)
        text_like_zone = structure_mask > 30
        if np.any(text_like_zone):
            text_vals = robust_gray[text_like_zone]
            robust_gray[text_like_zone] = np.where(text_vals >= 55, text_vals, 0).astype(np.uint8)

        # Median blur removes salt-like speckles without erasing thin linear defects.
        robust_gray = cv2.medianBlur(robust_gray, 3)
        return cv2.cvtColor(robust_gray, cv2.COLOR_GRAY2BGR)

    def _refine_alignment_ecc(self, aligned: np.ndarray, reference: np.ndarray) -> np.ndarray:
        ref_gray = cv2.cvtColor(reference, cv2.COLOR_BGR2GRAY)
        aligned_gray = cv2.cvtColor(aligned, cv2.COLOR_BGR2GRAY)

        # Pyramid ECC (4 levels) with phase-correlation bootstrap.
        # This is more stable for tiny shifts on text-heavy surfaces.
        levels = 4
        ref_pyr = [ref_gray]
        aligned_pyr = [aligned_gray]
        for _ in range(1, levels):
            ref_pyr.append(cv2.pyrDown(ref_pyr[-1]))
            aligned_pyr.append(cv2.pyrDown(aligned_pyr[-1]))

        warp_aff = np.eye(2, 3, dtype=np.float32)
        criteria = (cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 50, 1e-4)
        try:
            for level in reversed(range(levels)):
                ref_lvl = ref_pyr[level]
                cur_lvl = aligned_pyr[level]

                if level < levels - 1:
                    warp_aff[0, 2] *= 2.0
                    warp_aff[1, 2] *= 2.0

                # Coarse translational bootstrap from phase correlation.
                shift, _ = cv2.phaseCorrelate(
                    np.float32(ref_lvl),
                    np.float32(cur_lvl),
                )
                warp_aff[0, 2] += float(shift[0])
                warp_aff[1, 2] += float(shift[1])

                cv2.findTransformECC(
                    ref_lvl,
                    cur_lvl,
                    warp_aff,
                    cv2.MOTION_AFFINE,
                    criteria,
                    None,
                    5,
                )

            h, w = reference.shape[:2]
            return cv2.warpAffine(
                aligned,
                warp_aff,
                (w, h),
                flags=cv2.INTER_LINEAR | cv2.WARP_INVERSE_MAP,
                borderMode=cv2.BORDER_REPLICATE,
            )
        except Exception:
            return aligned

    def _run_anomaly_model(self, diff_map: np.ndarray) -> Tuple[float, np.ndarray]:
        # Heuristic fallback score with emphasis on strong local differences.
        # This helps thin/high-contrast defects (e.g. scratches) score higher than
        # broad low-contrast texture/background changes.
        gray = cv2.cvtColor(diff_map, cv2.COLOR_BGR2GRAY)
        gray_blur = cv2.GaussianBlur(gray, (5, 5), 0)
        strong_threshold = float(np.percentile(gray_blur, 95))
        if float(np.max(gray_blur)) < 20:
            zero = np.zeros_like(gray_blur, dtype=np.uint8)
            return 0.0, cv2.cvtColor(zero, cv2.COLOR_GRAY2BGR)
        _, binary = cv2.threshold(gray_blur, strong_threshold, 255, cv2.THRESH_BINARY)

        # Suppress speckle noise but keep thin structures (scratches).
        cleaned = cv2.medianBlur(binary, 3)
        cleaned = cv2.morphologyEx(
            cleaned,
            cv2.MORPH_CLOSE,
            np.ones((3, 3), dtype=np.uint8),
            iterations=1,
        )

        num_labels, labels, stats, _ = cv2.connectedComponentsWithStats(cleaned, connectivity=8)
        filtered = np.zeros_like(cleaned)
        min_area = 15
        max_aspect = 0.0
        for label_idx in range(1, num_labels):
            area = int(stats[label_idx, cv2.CC_STAT_AREA])
            w = max(1, int(stats[label_idx, cv2.CC_STAT_WIDTH]))
            h = max(1, int(stats[label_idx, cv2.CC_STAT_HEIGHT]))
            aspect = max(w / h, h / w)
            max_aspect = max(max_aspect, float(aspect))

            # Keep any object above minimum area; elongated contours are always critical.
            if area >= min_area or aspect > 4.0:
                filtered[labels == label_idx] = 255

        changed_ratio = float(np.count_nonzero(filtered)) / float(filtered.size)

        # Brightest tail statistics: more robust for narrow defects than plain mean.
        flat = gray_blur.reshape(-1)
        k = max(1, int(flat.size * 0.01))  # top 1% brightest pixels
        top_mean = float(np.mean(np.partition(flat, -k)[-k:])) / 255.0

        # If even the brightest tail is too weak, treat it as background noise.
        if top_mean < 0.05:
            zero = np.zeros_like(filtered)
            return 0.0, cv2.cvtColor(zero, cv2.COLOR_GRAY2BGR)

        # Score bias toward strong thin scratches:
        # 70% from brightest 1% intensity, 30% from contour elongation.
        aspect_term = float(np.clip(max_aspect / 10.0, 0.0, 1.0))
        heuristic_score = float(np.clip(0.70 * top_mean + 0.30 * aspect_term, 0.0, 1.0))
        if max_aspect > 5.0:
            heuristic_score = max(heuristic_score, 0.4)
        heuristic_mask = cv2.cvtColor(filtered, cv2.COLOR_GRAY2BGR)

        if self._anomaly_engine is not None:
            try:
                prediction = self._anomaly_engine.predict(image=diff_map)
                model_score = float(prediction.pred_score)
                mask = prediction.pred_mask.astype(np.uint8) * 255
                if len(mask.shape) == 2:
                    mask = cv2.cvtColor(mask, cv2.COLOR_GRAY2BGR)
                # Merge model mask with heuristic mask so thin scratches seen in diff_map
                # are not lost when model mask is conservative on textured surfaces.
                merged_mask = cv2.bitwise_or(mask, heuristic_mask)
                # Use the larger score to avoid missing obvious defects when model score is conservative.
                return max(model_score, heuristic_score), merged_mask
            except Exception:
                pass

        return heuristic_score, heuristic_mask

    def _build_heatmap(self, mask: np.ndarray, diff_map: Optional[np.ndarray] = None) -> np.ndarray:
        mask_gray = cv2.cvtColor(mask, cv2.COLOR_BGR2GRAY)
        if diff_map is None:
            return cv2.applyColorMap(mask_gray, cv2.COLORMAP_JET)

        # Make heatmap explainable: combine model/anomaly mask with raw difference energy.
        # This guarantees that thin scratches visible on diff_map are also visible on heatmap.
        diff_gray = cv2.cvtColor(diff_map, cv2.COLOR_BGR2GRAY)
        diff_norm = cv2.normalize(diff_gray, None, 0, 255, cv2.NORM_MINMAX)
        combined = cv2.max(mask_gray, diff_norm)
        heatmap = cv2.applyColorMap(combined, cv2.COLORMAP_JET)

        # Brighten detected defects so they pop in red while background remains darker.
        mask_float = (mask_gray.astype(np.float32) / 255.0)[..., np.newaxis]
        boosted = heatmap.astype(np.float32) * (1.0 + 0.5 * mask_float)
        return np.clip(boosted, 0, 255).astype(np.uint8)

    def _crop_to_roi(
        self,
        aligned: np.ndarray,
        reference: np.ndarray,
        roi: Tuple[float, float, float, float],
    ) -> Tuple[np.ndarray, np.ndarray]:
        x_ratio, y_ratio, w_ratio, h_ratio = roi
        height, width = reference.shape[:2]
        x1 = int(round(x_ratio * width))
        y1 = int(round(y_ratio * height))
        x2 = int(round((x_ratio + w_ratio) * width))
        y2 = int(round((y_ratio + h_ratio) * height))

        x1 = max(0, min(x1, width - 1))
        y1 = max(0, min(y1, height - 1))
        x2 = max(x1 + 1, min(x2, width))
        y2 = max(y1 + 1, min(y2, height))

        return aligned[y1:y2, x1:x2], reference[y1:y2, x1:x2]

    def _mask_to_polygon(
        self,
        aligned: np.ndarray,
        reference: np.ndarray,
        polygon: list[Tuple[float, float]],
    ) -> Tuple[np.ndarray, np.ndarray]:
        height, width = reference.shape[:2]
        pts = np.array(
            [[int(round(x * (width - 1))), int(round(y * (height - 1)))] for x, y in polygon],
            dtype=np.int32,
        )
        mask = np.zeros((height, width), dtype=np.uint8)
        cv2.fillPoly(mask, [pts], 255)

        aligned_masked = cv2.bitwise_and(aligned, aligned, mask=mask)
        reference_masked = cv2.bitwise_and(reference, reference, mask=mask)
        return aligned_masked, reference_masked
