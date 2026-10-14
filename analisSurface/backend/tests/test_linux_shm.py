import sys
import numpy as np
import pytest
from app.services import shm_io

pytestmark = pytest.mark.skipif(sys.platform != "linux", reason="Linux shared memory contract")


def test_posix_names_and_traversal():
    assert shm_io.resolve_shm_path("/iml_cam_test") == shm_io.resolve_shm_path("/dev/shm/iml_cam_test")
    with pytest.raises(ValueError, match="inside /dev/shm"):
        shm_io.resolve_shm_path("../tmp/frame")


def test_frame_offset_stride_and_heatmap(tmp_path, monkeypatch):
    frame = np.arange(36, dtype=np.uint8).reshape(3, 4, 3)
    padded = np.zeros((3, 16), dtype=np.uint8)
    padded[:, :12] = frame.reshape(3, 12)
    (tmp_path / "frame").write_bytes(b"header!!" + padded.tobytes())
    monkeypatch.setattr(shm_io, "resolve_shm_path", lambda name: tmp_path / name)
    with shm_io.open_bgr_shm_frame("frame", width=4, height=3, stride=16, shm_offset=8) as mapped:
        np.testing.assert_array_equal(mapped, frame)
        heatmap = mapped[:, :, 0].copy()
        del mapped
    info = shm_io.write_u8_image_to_shm("heatmap", heatmap)
    assert (info.width, info.height, info.stride) == (4, 3, 4)
    assert (tmp_path / "heatmap").read_bytes() == heatmap.tobytes()
