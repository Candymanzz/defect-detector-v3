import mmap
import os
import sys
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator, Optional

import numpy as np


@dataclass(frozen=True)
class ShmImageOutputInfo:
    path: str
    width: int
    height: int
    stride: int
    channels: int
    dtype: str = "uint8"


def _windows_iml_shm_dir() -> Path:
    local_app = os.environ.get("LOCALAPPDATA") or os.environ.get("TEMP") or "."
    return Path(local_app) / "iml_shm"


def resolve_shm_path(shm_name: str) -> Path:
    normalized = shm_name.strip()
    if not normalized:
        raise ValueError("shm_name must not be empty")

    if sys.platform == "win32":
        candidate = Path(normalized)
        if candidate.is_absolute():
            if candidate.is_file():
                return candidate
            # camera-worker may report logs/*.bin while data lives in %LOCALAPPDATA%\iml_shm
            name = candidate.name
            if name.endswith(".bin"):
                alt = _windows_iml_shm_dir() / name.replace(".bin", "")
                if alt.is_file():
                    return alt
            stem = candidate.stem
            if stem.startswith("iml_cam_"):
                alt = _windows_iml_shm_dir() / stem
                if alt.is_file():
                    return alt
            return candidate

        base = normalized.lstrip("/").replace("/", "_")
        path = _windows_iml_shm_dir() / base
        if path.is_file():
            return path
        return path

    if normalized.startswith("/dev/shm/"):
        path = Path(normalized)
    else:
        path = Path("/dev/shm") / normalized.lstrip("/")

    resolved = path.resolve(strict=False)
    shm_root = Path("/dev/shm").resolve(strict=False)
    if shm_root not in (resolved, *resolved.parents):
        raise ValueError("shm_name must resolve inside /dev/shm")
    return resolved


@contextmanager
def open_bgr_shm_frame(
    shm_name: str,
    width: int,
    height: int,
    stride: Optional[int] = None,
    shm_offset: int = 0,
) -> Iterator[np.ndarray]:
    if width <= 0 or height <= 0:
        raise ValueError("width and height must be positive")
    if shm_offset < 0:
        raise ValueError("shm_offset must be >= 0")

    row_stride = stride if stride is not None else width * 3
    min_stride = width * 3
    if row_stride < min_stride:
        raise ValueError(f"stride must be at least width * 3 ({min_stride})")

    required_end = shm_offset + (height - 1) * row_stride + min_stride
    shm_path = resolve_shm_path(shm_name)

    if not shm_path.is_file():
        raise FileNotFoundError(f"shared memory file not found: {shm_path}")

    fd = os.open(shm_path, os.O_RDONLY)
    shm_map = None
    frame: Optional[np.ndarray] = None
    try:
        shm_size = os.fstat(fd).st_size
        if required_end > shm_size:
            raise ValueError(
                f"shared memory payload is too small: need end offset {required_end}, "
                f"but {shm_path} has {shm_size} bytes"
            )

        shm_map = mmap.mmap(fd, 0, access=mmap.ACCESS_READ)
        frame = np.ndarray(
            shape=(height, width, 3),
            dtype=np.uint8,
            buffer=shm_map,
            offset=shm_offset,
            strides=(row_stride, 3, 1),
        )
        yield frame
    finally:
        frame = None
        if shm_map is not None:
            shm_map.close()
        os.close(fd)


def write_u8_image_to_shm(output_path: str, image: np.ndarray) -> ShmImageOutputInfo:
    if image.dtype != np.uint8:
        image = np.clip(image, 0, 255).astype(np.uint8)

    contiguous = np.ascontiguousarray(image)
    if contiguous.ndim == 2:
        height, width = contiguous.shape
        channels = 1
        stride = width
    elif contiguous.ndim == 3:
        height, width, channels = contiguous.shape
        stride = width * channels
    else:
        raise ValueError("visual output must be a 2D or 3D uint8 image")

    shm_path = resolve_shm_path(output_path)
    data = contiguous.tobytes()
    fd = os.open(shm_path, os.O_CREAT | os.O_RDWR, 0o666)
    try:
        os.ftruncate(fd, len(data))
        with mmap.mmap(fd, len(data), access=mmap.ACCESS_WRITE) as output_map:
            output_map.write(data)
            output_map.flush()
    finally:
        os.close(fd)

    return ShmImageOutputInfo(
        path=str(shm_path),
        width=width,
        height=height,
        stride=stride,
        channels=channels,
    )
