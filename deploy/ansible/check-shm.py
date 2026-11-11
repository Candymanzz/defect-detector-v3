#!/usr/bin/env python3
"""Verify readable camera SHM without starting acquisition or changing settings."""
import json
import os
from pathlib import Path
import sys
import urllib.request


def check_buffers(cameras, directory):
    if not isinstance(cameras, list) or not cameras:
        raise ValueError("Camera list is empty or invalid")
    result = []
    for camera in cameras:
        if type(camera) is not int or camera < 0:
            raise ValueError("Invalid camera ID")
        path = directory / ("iml_cam_" + str(camera) + "_frame")
        with path.open("rb") as stream:
            size = os.fstat(stream.fileno()).st_size
            if size <= 0 or not stream.read(1):
                raise ValueError("Empty SHM buffer: " + str(path))
        result.append(camera)
    return result


def main():
    endpoint = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8099/api/cameras"
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(endpoint, timeout=5) as response:
        cameras = json.load(response)["cameras"]
    ids = check_buffers(cameras, Path("/dev/shm"))
    print("Readable camera SHM buffers: " + ",".join(map(str, ids)))


if __name__ == "__main__":
    main()
