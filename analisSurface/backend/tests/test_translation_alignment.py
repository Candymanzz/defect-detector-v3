import cv2
import numpy as np
import pytest

from app.services.translation_alignment import TranslationAligner
from app.services.inspection_service import InspectionService


def bucket(dx: int = 0, dy: int = 0) -> np.ndarray:
    image = np.full((512, 512, 3), 18, dtype=np.uint8)
    # The machine fixture stays in place while only the bucket oscillates.
    cv2.rectangle(image, (70, 70), (95, 420), (180, 180, 180), 3)
    cv2.circle(image, (256 + dx, 256 + dy), 155, (85, 105, 135), -1)
    cv2.circle(image, (256 + dx, 256 + dy), 155, (200, 205, 210), 5)
    cv2.circle(image, (256 + dx, 256 + dy), 118, (45, 60, 80), 3)
    cv2.rectangle(image, (170 + dx, 205 + dy), (340 + dx, 300 + dy), (180, 210, 225), 3)
    cv2.putText(image, "BUCKET 25", (176 + dx, 260 + dy),
                cv2.FONT_HERSHEY_SIMPLEX, 0.75, (245, 245, 245), 2)
    return image


def test_only_bucket_moves_and_brightness_changes() -> None:
    reference = bucket()
    current = cv2.convertScaleAbs(bucket(9, -7), alpha=1.10, beta=12)
    result = TranslationAligner().align(
        current,
        reference,
        product_type="bucket",
        reference_token="reference-1",
        roi_polygon=[(0.13, 0.13), (0.88, 0.13), (0.88, 0.87), (0.13, 0.87)],
    )

    assert result.method != "unchanged"
    assert result.dx == pytest.approx(-9, abs=2)
    assert result.dy == pytest.approx(7, abs=2)
    assert result.edge_after < result.edge_before * 0.5
    before = np.abs(current[100:410, 110:410].astype(np.int16)
                    - reference[100:410, 110:410].astype(np.int16)).mean()
    after = np.abs(result.image[100:410, 110:410].astype(np.int16)
                   - reference[100:410, 110:410].astype(np.int16)).mean()
    assert after < before * 0.8


def test_lighting_change_does_not_move_aligned_bucket() -> None:
    reference = bucket()
    current = cv2.convertScaleAbs(reference, alpha=1.10, beta=12)
    cv2.line(current, (205, 185), (310, 185), (255, 255, 255), 3)
    result = TranslationAligner().align(
        current, reference, product_type="bucket", reference_token="reference-1"
    )
    assert result.method == "unchanged"
    assert np.array_equal(result.image, current)


def test_two_pixel_bucket_wobble_is_corrected() -> None:
    reference = bucket()
    current = bucket(2, -1)
    result = TranslationAligner().align(
        current,
        reference,
        product_type="bucket",
        reference_token="reference-1",
        roi_polygon=[(0.13, 0.13), (0.88, 0.13), (0.88, 0.87), (0.13, 0.87)],
    )
    assert result.method != "unchanged"
    assert result.dx == pytest.approx(-2, abs=1)
    assert result.dy == pytest.approx(1, abs=1)


def test_no_features_does_not_invent_translation() -> None:
    reference = np.full((200, 260, 3), 80, dtype=np.uint8)
    current = np.full_like(reference, 125)
    result = TranslationAligner().align(
        current, reference, product_type="blank", reference_token="reference-1"
    )
    assert result.method == "unchanged"
    assert np.array_equal(result.image, current)


def test_inspection_compares_shifted_bucket_in_reference_coordinates(
    inspection_service: InspectionService,
) -> None:
    reference = bucket()
    current = bucket(9, -7)
    inspection_service.set_reference_frame("oscillating-bucket", reference)
    inspection_service.set_roi_polygon(
        "oscillating-bucket",
        [(0.13, 0.13), (0.88, 0.13), (0.88, 0.87), (0.13, 0.87)],
    )
    inspection_service.inspect_frame(
        "oscillating-bucket", current,
        include_visuals=False,
        store_learning_review=False,
    )
    aligned = inspection_service._last_aligned["oscillating-bucket"]
    crop = np.s_[100:410, 110:410]
    raw_difference = np.abs(current[crop].astype(np.int16) - reference[crop].astype(np.int16)).mean()
    aligned_difference = np.abs(aligned[crop].astype(np.int16) - reference[crop].astype(np.int16)).mean()
    assert aligned_difference < raw_difference * 0.5
