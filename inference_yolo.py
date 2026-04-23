from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np
from ultralytics import YOLO


@dataclass
class InferenceConfig:
    model_path: Path
    confidence_threshold: float = 0.3
    crop_ratio: float = 0.8
    imgsz: int = 1024
    contour_color: tuple[int, int, int] = (0, 0, 255)
    contour_thickness: int = 2


class ScratchDetector:
    def __init__(self, config: InferenceConfig) -> None:
        self.config = config
        if not self.config.model_path.exists():
            raise FileNotFoundError(f"Model not found: {self.config.model_path}")
        self.model = YOLO(str(self.config.model_path))

    @staticmethod
    def _read_image(image: np.ndarray | str | Path) -> np.ndarray:
        if isinstance(image, np.ndarray):
            return image.copy()
        image_path = Path(image)
        frame = cv2.imread(str(image_path))
        if frame is None:
            raise ValueError(f"Не удалось прочитать изображение: {image_path}")
        return frame

    def _center_crop(self, image: np.ndarray) -> tuple[np.ndarray, tuple[int, int, int, int]]:
        h, w = image.shape[:2]
        ratio = min(max(self.config.crop_ratio, 0.1), 1.0)
        crop_w = int(w * ratio)
        crop_h = int(h * ratio)

        x1 = (w - crop_w) // 2
        y1 = (h - crop_h) // 2
        x2 = x1 + crop_w
        y2 = y1 + crop_h
        return image[y1:y2, x1:x2], (x1, y1, x2, y2)

    def predict(self, image: np.ndarray | str | Path) -> tuple[list[np.ndarray], list[float], np.ndarray]:
        source = self._read_image(image)
        crop, (x1, y1, x2, y2) = self._center_crop(source)

        result = self.model.predict(
            source=crop,
            imgsz=self.config.imgsz,
            conf=self.config.confidence_threshold,
            verbose=False,
        )[0]

        masks_out: list[np.ndarray] = []
        confs_out: list[float] = []
        visualized = source.copy()

        if result.masks is None or result.boxes is None:
            return masks_out, confs_out, visualized

        mask_data = result.masks.data.cpu().numpy()
        box_conf = result.boxes.conf.cpu().numpy()

        for mask, conf in zip(mask_data, box_conf):
            conf_value = float(conf)
            if conf_value < self.config.confidence_threshold:
                continue

            mask_uint8 = (mask > 0.5).astype(np.uint8)
            if mask_uint8.shape[:2] != crop.shape[:2]:
                mask_uint8 = cv2.resize(
                    mask_uint8,
                    (crop.shape[1], crop.shape[0]),
                    interpolation=cv2.INTER_NEAREST,
                )

            full_mask = np.zeros(source.shape[:2], dtype=np.uint8)
            full_mask[y1:y2, x1:x2] = mask_uint8

            contours, _ = cv2.findContours(full_mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            cv2.drawContours(
                visualized,
                contours,
                contourIdx=-1,
                color=self.config.contour_color,
                thickness=self.config.contour_thickness,
            )

            masks_out.append(full_mask)
            confs_out.append(conf_value)

        return masks_out, confs_out, visualized
