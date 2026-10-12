"""Local evidence for compact residuals after registration and light compensation.

Scores are heuristic evidence, not calibrated defect probabilities. Accepted
regions remain the responsibility of learned normals and FP mini-etalons.
"""

import cv2
import numpy as np

from app.services.analysis_settings import AnalysisSettings
from app.services.inspection_curvature import GeometryWeights


def _compact_candidates(connected: np.ndarray, geometry=None):
    """Keep normal components; separate thick islands from attached edge traces.

    Opening is restricted to rejected parents. It never enlarges a region or
    converts the entire boundary into defect support.
    """
    count, labels, stats, _ = cv2.connectedComponentsWithStats(connected, connectivity=8)
    rejected = np.zeros_like(connected)
    for label in range(1, count):
        x, y, width, height, area = (int(v) for v in stats[label])
        component = labels[y:y + height, x:x + width] == label
        stretch = _local_curvature(geometry, x, y, width, height, component)
        if max(width / height, height / width) <= 3.0 * stretch and area / (width * height) >= 0.5:
            yield x, y, width, height, area, component, False
        else:
            rejected[y:y + height, x:x + width][component] = 255
    if not np.any(rejected):
        return
    islands = cv2.morphologyEx(rejected, cv2.MORPH_OPEN, np.ones((9, 9), np.uint8))
    count, labels, stats, _ = cv2.connectedComponentsWithStats(islands, connectivity=8)
    for label in range(1, count):
        x, y, width, height, area = (int(v) for v in stats[label])
        component = labels[y:y + height, x:x + width] == label
        stretch = _local_curvature(geometry, x, y, width, height, component)
        if max(width / height, height / width) <= 3.0 * stretch and area / (width * height) >= 0.5:
            yield x, y, width, height, area, labels[y:y + height, x:x + width] == label, True


def _local_curvature(geometry, x, y, width, height, component):
    curvature = getattr(geometry, "curvature", None)
    if curvature is None:
        return 1.
    return float(np.mean(curvature[y:y + height, x:x + width][component]))


def _surface_coordinates(supported, geometry, stretch, x, y):
    coordinates = np.column_stack(np.nonzero(supported)).astype(np.float64)
    if stretch > 1.001:
        # np.nonzero returns (y, x), while the reference metric uses (x, y).
        center = np.mean(coordinates, axis=0)
        direction = np.asarray(geometry.direction_at(x + center[1], y + center[0]))[::-1]
        coordinates -= center
        coordinates += (stretch - 1.) * (coordinates @ direction)[:, None] * direction
    return coordinates


def compact_residual_evidence(
    gray: np.ndarray,
    blurred: np.ndarray,
    settings: AnalysisSettings,
    perspective_weights: GeometryWeights | None = None,
    *,
    allow_boundary_separation: bool = True,
    minimum_score: float = 0.0,
    minimum_source_support: int | None = None,
    perspective_area: bool = True,
) -> tuple[float, np.ndarray]:
    """Compare connected compact differences to the surrounding residual noise.

    A lower seed threshold only proposes regions. Robust local noise, actual
    source-pixel support and shape must confirm them before they enter the mask.
    The existing strong-signal/scratch branch is evaluated independently.
    Terminal gain uses a smaller support requirement only for amplification;
    ordinary detection retains the default support floor.
    """
    mask = np.zeros_like(gray)
    seed_floor = max(4.0, settings.min_diff_signal * 0.4)
    if float(np.max(blurred)) <= seed_floor:
        return 0.0, mask
    seeds = (blurred > seed_floor).astype(np.uint8) * 255
    seeds = cv2.medianBlur(seeds, 3)
    connected = cv2.morphologyEx(seeds, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))
    min_support = (
        max(24, settings.min_defect_area * 4)
        if minimum_source_support is None else max(6, minimum_source_support)
    )
    score = 0.0
    frame_height, frame_width = gray.shape
    for x, y, width, height, area, component, separated in _compact_candidates(connected, perspective_weights):
        if separated and not allow_boundary_separation:
            continue
        # Long boundary traces are common after an article shift. They must be
        # handled by the original scratch/edge logic, not this compact branch.
        stretch = _local_curvature(perspective_weights, x, y, width, height, component)
        # Geometry can recover projected support, but never count blur pixels
        # as optical samples. Keep twelve real pixels and a three-pixel width.
        required_support = min_support
        if minimum_source_support is None and stretch > 1.001:
            required_support = max(12, int(np.ceil(min_support / stretch)))
        if area < required_support or min(width, height) < (3 if stretch > 1.001 else 4):
            continue
        source = gray[y:y + height, x:x + width]
        supported = component & (source > seed_floor)
        support = int(np.count_nonzero(supported))
        if support < required_support or support / area < 0.65:
            continue
        stretch = _local_curvature(perspective_weights, x, y, width, height, supported)
        # Use a ring separated from the component by a three-pixel blur halo.
        # Other residuals in the ring are retained: excluding all candidates
        # would underestimate noise on printing and textured surfaces.
        padding = max(8, min(24, max(width, height) // 2))
        left, top = max(0, x - padding), max(0, y - padding)
        right, bottom = min(frame_width, x + width + padding), min(frame_height, y + height + padding)
        ring = np.ones((bottom - top, right - left), dtype=bool)
        ring[max(0, y - top - 3):min(bottom - top, y + height - top + 3),
             max(0, x - left - 3):min(right - left, x + width - left + 3)] = False
        background = blurred[top:bottom, left:right][ring]
        if background.size < max(32, support // 2):
            continue
        baseline = float(np.median(background))
        deviation = np.abs(background.astype(np.float32) - baseline)
        robust_noise = 1.4826 * float(np.median(deviation))
        # MAD alone is zero for sparse printing residuals. Retain their energy
        # with a winsorized RMS, while ignoring the largest sensor outliers.
        clipped = np.minimum(deviation, np.percentile(deviation, 99))
        rms_noise = float(np.sqrt(np.mean(clipped * clipped)))
        # A zero difference map is not a noiseless camera: envelope filtering,
        # ROI masking and quantization censor small differences. Preserve the
        # operator's noise tolerance as a lower bound on that uncertainty.
        noise = max(1.5, settings.min_diff_signal * 0.25, robust_noise, rms_noise)
        values = blurred[y:y + height, x:x + width][supported]
        # A separated island contains a weak skirt inherited from the rim.
        # Confirm a sustained core, not a single peak, before using its signal.
        signal = float(np.percentile(values, 75) if separated else np.median(values))
        # Boundary islands need stronger corroboration than an isolated spot:
        # at least a quarter of their source support must clear four noise
        # scales. This keeps a thicker normal rim from becoming a new defect.
        if separated and np.count_nonzero(values >= baseline + 4.0 * noise) < max(min_support, support // 4):
            continue
        snr = (signal - baseline) / noise
        if snr <= 3.0 or signal <= float(np.percentile(background, 90)):
            continue

        # An axis-aligned box can make a diagonal boundary strip look compact.
        # Check its principal axes as well, independently of camera rotation.
        coordinates = _surface_coordinates(supported, perspective_weights, stretch, x, y)
        eigenvalues = np.linalg.eigvalsh(np.cov(coordinates, rowvar=False))
        if eigenvalues[-1] > 9.0 * max(float(eigenvalues[0]), 0.01):
            continue
        if stretch > 1.001 and np.min(np.ptp(coordinates, axis=0) + 1) < 4:
            continue

        effective_area = float(support)
        if perspective_weights is not None:
            far, scale = perspective_weights
            # Width is only a proxy for surface scale; use the same bounded
            # linear correction as the scratch branch, not squared area gain.
            local_scale = np.ones(support, dtype=np.float32)
            if perspective_area:
                local_scale += far[y:y + height, x:x + width][supported] * (
                    scale[y:y + height, x:x + width][supported] - 1.0
                )
            curvature = getattr(perspective_weights, "curvature", None)
            if curvature is not None:
                local_scale *= curvature[y:y + height, x:x + width][supported]
            effective_area *= float(np.mean(np.clip(local_scale, 1.0, 2.5)))
        # Gaussian smoothing correlates neighbours: count roughly one sample
        # per 3x3 block. Log SNR avoids making raw contrast dominate the score;
        # area grows smoothly instead of saturating at sixteen pixels.
        evidence = np.sqrt(effective_area / 9.0) * np.log1p(snr - 3.0)
        local_score = float(0.65 * -np.expm1(-evidence / 7.0))
        if local_score < minimum_score:
            continue
        score = max(score, local_score)
        mask[y:y + height, x:x + width][component] = 255
    return score, mask


def pre_normalization_compact_signal(reference_gray, current_gray, settings, geometry=None):
    """Retain compact, locally supported raw structure before light removal.

    A local envelope tolerates registration jitter. Broad smooth fields are
    removed at a fixed pixel scale; a sharp boundary must support the island.
    The output joins the ordinary difference map and follows the same learning,
    FP, review and sub-ROI lifecycle, without separate hidden camera state.
    """
    reference = cv2.GaussianBlur(reference_gray, (3, 3), 0)
    current = cv2.GaussianBlur(current_gray, (3, 3), 0)
    envelope = np.ones((5, 5), np.uint8)
    raw = cv2.max(cv2.subtract(current, cv2.dilate(reference, envelope)),
                  cv2.subtract(cv2.erode(reference, envelope), current))
    local = cv2.morphologyEx(raw, cv2.MORPH_TOPHAT,
                             cv2.getStructuringElement(cv2.MORPH_RECT, (31, 31)))
    # This raw branch historically has no longitudinal area gain. Carry only
    # curvature here, preserving its existing light/registration thresholds.
    raw_geometry = geometry if getattr(geometry, "curvature", None) is not None else None
    _, candidates = compact_residual_evidence(
        local, cv2.GaussianBlur(local, (3, 3), 0), settings, raw_geometry,
        allow_boundary_separation=False, minimum_score=0.25, perspective_area=False,
    )
    output = np.zeros_like(raw)
    if not np.any(candidates):
        return output
    detail = cv2.absdiff(raw, cv2.GaussianBlur(raw, (9, 9), 0))
    count, labels, stats, _ = cv2.connectedComponentsWithStats(candidates, connectivity=8)
    boundary_floor = max(3.0, settings.min_diff_signal * 0.25)
    for label in range(1, count):
        x, y, width, height, area = (int(v) for v in stats[label])
        component = labels[y:y + height, x:x + width] == label
        edge_support = detail[y:y + height, x:x + width][component] >= boundary_floor
        if np.count_nonzero(edge_support) < max(4, int(area * 0.03)):
            continue
        output[y:y + height, x:x + width][component] = local[y:y + height, x:x + width][component]
    return output
