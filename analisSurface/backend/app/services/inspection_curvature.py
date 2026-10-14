"""Bounded surface geometry inferred from a rounded ROI cap and its reference.

The cap establishes a circular cross-section hypothesis, not a physical radius
in millimetres or a reconstruction of the bottom fillet. Its chord constrains
the ellipse, avoiding extrapolation of five free parameters from a short arc.
No current inspection image is used to estimate geometry.
"""

from dataclasses import asdict, dataclass

import cv2
import numpy as np

from app.services.inspection_geometry import polygon_mask_from_norm_points


@dataclass(frozen=True)
class CurvatureModel:
    version: int
    frame_size: tuple[int, int]
    transverse: tuple[float, float]
    sides: tuple[tuple[float, float], tuple[float, float]]
    cap_center: tuple[float, float]
    cap_radii: tuple[float, float]
    confidence: float
    source: str
    fit_error: float

    def snapshot(self) -> dict:
        return asdict(self)

    @classmethod
    def restore(cls, data):
        """Unknown or damaged historical models must fall back to neutral geometry."""
        if not isinstance(data, dict) or data.get("version") != 1:
            return None
        try:
            model = cls(
                version=1, frame_size=tuple(data["frame_size"]),
                transverse=tuple(data["transverse"]),
                sides=tuple(tuple(p) for p in data["sides"]),
                cap_center=tuple(data["cap_center"]), cap_radii=tuple(data["cap_radii"]),
                confidence=float(data["confidence"]), source=str(data["source"]),
                fit_error=float(data["fit_error"]),
            )
            if (len(model.frame_size) != 2 or len(model.transverse) != 2
                    or len(model.cap_center) != 2 or len(model.cap_radii) != 2
                    or len(model.sides) != 2 or any(len(p) != 2 for p in model.sides)):
                return None
            numeric = np.asarray([*model.frame_size, *model.transverse,
                                  *model.sides[0], *model.sides[1], *model.cap_center,
                                  *model.cap_radii, model.confidence, model.fit_error])
            if (min(model.frame_size) < 2 or max(model.frame_size) > 32768
                    or not np.all(np.isfinite(numeric))
                    or abs(np.linalg.norm(model.transverse) - 1) > .001
                    or min(model.cap_radii) <= 0 or not 0 < model.confidence <= 1
                    or max(model.cap_radii) > max(model.frame_size)
                    or not 0 <= model.fit_error <= .16
                    or any(abs(p[0]) > 2 or abs(p[1]) > 4 * max(model.frame_size) for p in model.sides)
                    or any(not 0 <= c <= s for c, s in zip(model.cap_center, model.frame_size))
                    or model.source not in ("roi", "reference-refined")):
                return None
            return model
        except (KeyError, TypeError, ValueError, IndexError):
            return None

    def weights(self, width, height, polygon):
        """Bound a circular wall and elliptical rounded end; outside ROI is neutral.

        The end uses a dome approximation. A silhouette cannot identify the
        actual fillet depth, so the same confidence/cap applies there as on the
        wall. No separate edge intensity multiplier is introduced.
        """
        source_width, source_height = self.frame_size
        # Evaluate in the fit's coordinates even when inspection is downscaled.
        yy, xx = np.mgrid[:height, :width].astype(np.float32)
        xx *= (source_width - 1) / max(1, width - 1)
        yy *= (source_height - 1) / max(1, height - 1)
        ux, uy = self.transverse
        u = xx * ux + yy * uy
        v = xx * -uy + yy * ux
        left = self.sides[0][0] * v + self.sides[0][1]
        right = self.sides[1][0] * v + self.sides[1][1]
        span = right - left
        wall_radial = (2 * u - left - right) / np.maximum(span, 1)
        cx, cy = self.cap_center
        cap_u, cap_v = cx * ux + cy * uy, cx * -uy + cy * ux
        across = (u - cap_u) / self.cap_radii[0]
        along = np.clip((v - cap_v) / self.cap_radii[1], 0, 1)
        cap_radial_squared = np.where(along > 0, across * across + along * along, 0.)
        radial_squared = np.minimum(1., np.maximum(wall_radial * wall_radial, cap_radial_squared))
        # Circular cross-section: du/ds = cos(theta). Never invert the singular
        # silhouette; missing optical samples cannot be recovered by weighting.
        stretch = np.minimum(2., 1. / np.sqrt(np.maximum(1. - radial_squared, .25)))
        stretch = (1. + self.confidence * (stretch - 1.)).astype(np.float32)
        roi = polygon_mask_from_norm_points(width, height, polygon) > 0
        stretch[~roi | (span <= 1)] = 1.
        stretch.setflags(write=False)
        direction = np.array([ux * (width - 1) / (source_width - 1),
                              uy * (height - 1) / (source_height - 1)])
        direction /= np.linalg.norm(direction)
        return stretch, tuple(float(v) for v in direction)


@dataclass(frozen=True)
class SurfaceWeights:
    """Two legacy perspective maps plus optional frozen transverse geometry.

    Iteration/indexing retain the existing (far, width_scale) interface. Crops
    and padding must use the methods below to retain the surface model.
    """
    far: np.ndarray
    width_scale: np.ndarray
    curvature: np.ndarray
    transverse: tuple[float, float]
    model: CurvatureModel
    frame_size: tuple[int, int] | None = None
    origin: tuple[int, int] = (0, 0)

    def __iter__(self):
        return iter((self.far, self.width_scale))

    def __getitem__(self, index):
        return (self.far, self.width_scale)[index]

    def crop(self, bbox):
        x, y, width, height = bbox
        return SurfaceWeights(*(a[y:y + height, x:x + width]
                                for a in (self.far, self.width_scale, self.curvature)),
                              self.transverse, self.model, self.frame_size,
                              (self.origin[0] + x, self.origin[1] + y))

    def pad(self, padding):
        return SurfaceWeights(np.pad(self.far, padding, constant_values=0.),
                              np.pad(self.width_scale, padding, constant_values=1.),
                              np.pad(self.curvature, padding, constant_values=1.),
                              self.transverse, self.model, self.frame_size,
                              (self.origin[0] - padding[1][0], self.origin[1] - padding[0][0]))

    def direction_at(self, x, y):
        """Local compression direction in full-frame coordinates, also in FP crops."""
        if self.frame_size is None:
            return self.transverse
        width, height = self.frame_size
        source_width, source_height = self.model.frame_size
        source_x = (x + self.origin[0]) * (source_width - 1) / max(1, width - 1)
        source_y = (y + self.origin[1]) * (source_height - 1) / max(1, height - 1)
        ux, uy = self.model.transverse
        u = source_x * ux + source_y * uy
        v = source_x * -uy + source_y * ux
        cx, cy = self.model.cap_center
        across = (u - cx * ux - cy * uy) / self.model.cap_radii[0]
        along = np.clip((v + cx * uy - cy * ux) / self.model.cap_radii[1], 0, 1)
        left = self.model.sides[0][0] * v + self.model.sides[0][1]
        right = self.model.sides[1][0] * v + self.model.sides[1][1]
        wall_radial = (2 * u - left - right) / max(1, right - left)
        if along <= 0 or across * across + along * along <= wall_radial * wall_radial:
            return self.transverse
        # Ellipse gradient follows the normal to the rounded ROI arc. A local
        # component uses its centre direction; the image itself is never warped.
        normal = (across / self.model.cap_radii[0] * np.array([ux, uy])
                  + along / self.model.cap_radii[1] * np.array([-uy, ux]))
        normal *= [(width - 1) / (source_width - 1), (height - 1) / (source_height - 1)]
        normal /= np.linalg.norm(normal)
        return tuple(normal)


GeometryWeights = SurfaceWeights | tuple[np.ndarray, np.ndarray]


def with_curvature(perspective, model, width, height, polygon):
    if perspective is None or model is None:
        return perspective
    stretch, direction = model.weights(width, height, polygon)
    return SurfaceWeights(*perspective, stretch, direction, model, (width, height))


def crop_weights(weights, bbox):
    if weights is None:
        return None
    if isinstance(weights, SurfaceWeights):
        return weights.crop(bbox)
    x, y, width, height = bbox
    return tuple(a[y:y + height, x:x + width] for a in weights)


def pad_weights(weights, padding):
    if isinstance(weights, SurfaceWeights):
        return weights.pad(padding)
    return (np.pad(weights[0], padding, constant_values=0.),
            np.pad(weights[1], padding, constant_values=1.))


def _reference_arc(reference, cap, transverse, radius, center):
    """Seek coherent silhouette gradients in a narrow band around the ROI arc.

    Interpolation only locates reference samples; it does not manufacture new
    geometric observations. Sparse/printed contours leave the ROI fit intact.
    """
    gray = cv2.cvtColor(reference, cv2.COLOR_BGR2GRAY) if reference.ndim == 3 else reference
    gx = cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3) / 8.
    gy = cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3) / 8.
    samples = []
    for left, right in zip(cap[:-1], cap[1:]):
        n = max(2, int(np.linalg.norm(right - left) / 8))
        samples.extend(left + (right - left) * t for t in np.linspace(0, 1, n, endpoint=False))
    samples = np.asarray(samples, np.float32)
    longitudinal = np.array([-transverse[1], transverse[0]])
    relative = samples - center
    normals = ((relative @ transverse / radius[0] ** 2)[:, None] * transverse
               + (relative @ longitudinal / radius[1] ** 2)[:, None] * longitudinal)
    normals /= np.maximum(np.linalg.norm(normals, axis=1)[:, None], .001)
    band = max(4, min(16, round(radius[0] * .05)))
    offsets = np.arange(-band, band + 1, dtype=np.float32)
    locations = (samples[:, None, :] + normals[:, None, :] * offsets[None, :, None]).astype(np.float32)
    x, y = locations[..., 0], locations[..., 1]
    grad_x = cv2.remap(gx, x, y, cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT)
    grad_y = cv2.remap(gy, x, y, cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT)
    normal_gradient = np.abs(grad_x * normals[:, 0, None] + grad_y * normals[:, 1, None])
    magnitude = np.sqrt(grad_x * grad_x + grad_y * grad_y)
    credible = (normal_gradient >= 12.) & (normal_gradient >= .7 * magnitude)
    ranked = np.where(credible, normal_gradient * np.exp(-.5 * (offsets / band) ** 2), 0)
    best = np.argmax(ranked, axis=1)
    supported = ranked[np.arange(len(samples)), best] > 0
    if np.mean(supported) < .7:
        return None
    displacement = offsets[best][supported]
    if np.median(np.abs(displacement - np.median(displacement))) > max(3, band * .4):
        return None
    return locations[np.arange(len(samples)), best][supported]


def estimate_curvature(reference, polygon, line):
    """Fit a constrained half ellipse; reject flat, incomplete or irregular caps."""
    if reference is None or not polygon or not line or len(polygon) < 7:
        return None
    height, width = reference.shape[:2]
    points = np.asarray(polygon, np.float64) * [width - 1, height - 1]
    near, far = np.asarray(line, np.float64) * [width - 1, height - 1]
    axis = far - near
    if not np.all(np.isfinite(points)) or np.linalg.norm(axis) < 10:
        return None
    axis /= np.linalg.norm(axis)
    edges = np.roll(points, -1, axis=0) - points
    lengths = np.linalg.norm(edges, axis=1)
    candidates = np.flatnonzero((lengths > 20) & (np.abs(edges @ axis) >= .65 * lengths))
    if len(candidates) < 2:
        return None
    i, j = sorted(candidates[np.argsort(lengths[candidates])[-2:]].tolist())
    arcs = [points[i + 1:j + 1], np.vstack((points[j + 1:], points[:i + 1]))]
    cap = max(arcs, key=lambda p: float(np.mean(p @ axis)))
    if len(cap) < 5:
        return None
    chord = cap[-1] - cap[0]
    chord_width = np.linalg.norm(chord)
    if chord_width < 40:
        return None
    transverse = chord / chord_width
    longitudinal = np.array([-transverse[1], transverse[0]])
    if longitudinal @ axis < 0:
        transverse = -transverse
        longitudinal = -longitudinal
    if longitudinal @ axis < .7:
        return None
    center = (cap[0] + cap[-1]) * .5
    radius = chord_width * .5
    u = (cap - center) @ transverse / radius
    v = (cap - center) @ longitudinal
    if np.any(np.abs(u) > 1.04) or np.min(v) < -radius * .04:
        return None
    profile = np.sqrt(np.maximum(0, 1 - u * u))
    interior = profile > .25
    if np.count_nonzero(interior) < 3:
        return None
    depth = float(np.median(v[interior] / profile[interior]))
    if not .12 * radius <= depth <= 1.2 * radius:
        return None
    error = float(np.median(np.abs(v[interior] - depth * profile[interior])) / depth)
    if error > .16 or np.percentile(np.abs(v - depth * profile), 90) > .35 * depth:
        return None
    # Both sides must extend well beyond the cap; a circle-only/bottom-view ROI
    # cannot establish the cylindrical wall's transverse metric.
    side_lines = []
    for index in (i, j):
        segment = points[[index, (index + 1) % len(points)]]
        along = segment @ longitudinal
        across = segment @ transverse
        if abs(along[1] - along[0]) < max(radius * .8, depth * 1.5):
            return None
        slope = float((across[1] - across[0]) / (along[1] - along[0]))
        side_lines.append((slope, float(across[0] - slope * along[0])))
    mid_v = float(np.mean(points @ longitudinal))
    side_lines.sort(key=lambda p: p[0] * mid_v + p[1])
    probe = points @ longitudinal
    spans = (side_lines[1][0] - side_lines[0][0]) * probe + side_lines[1][1] - side_lines[0][1]
    if np.min(spans) < radius or np.max(spans) > radius * 6:
        return None
    refined = _reference_arc(reference, cap, transverse, (radius, depth), center)
    confidence = .6
    source = "roi"
    if refined is not None:
        ru = (refined - center) @ transverse / radius
        rv = (refined - center) @ longitudinal
        rp = np.sqrt(np.maximum(0, 1 - ru * ru))
        keep = (np.abs(ru) < .95) & (rp > .25)
        if np.count_nonzero(keep) >= 10:
            reference_depth = float(np.median(rv[keep] / rp[keep]))
            reference_error = float(np.median(np.abs(rv[keep] - reference_depth * rp[keep])) / max(1, reference_depth))
            if abs(reference_depth - depth) <= max(8, .15 * depth) and reference_error <= .12:
                depth = reference_depth
                confidence = 1.
                source = "reference-refined"
    confidence *= max(.7, 1 - 1.5 * error)
    return CurvatureModel(1, (width, height), tuple(transverse), tuple(side_lines),
                          tuple(center), (radius, depth), confidence, source, error)
