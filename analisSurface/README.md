## Backend (inspection API)

```bash
cd backend
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
python -m uvicorn app.main:app --reload --port 8000
```

## Stdio-детектор для Java-оркестратора (IMLB)

Оркестратор из корня репозитория поднимает процесс с бинарным протоколом по stdin/stdout (как бывший `python-detectors`).

1. Виртуальное окружение **в каталоге `backend/`** (пути в `config/config.yaml` ждут именно его):

```bash
cd backend
python3 -m venv .venv
source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt
```

2. Команда в конфиге: `analisSurface/backend/.venv/.../python` + `analisSurface/run_stdio_worker.py` (обёртка добавляет `backend` в `PYTHONPATH`). Реализация: `app/orchestrator_stdio_runner.py`.

3. Ручная проверка из **корня репозитория** (после сборки venv): процесс сразу выйдет на EOF stdin — это нормально.

```bash
analisSurface/backend/.venv/bin/python analisSurface/run_stdio_worker.py
```

## Python camera server (RTSP/USB capture)

```bash
cd backend
source .venv/bin/activate
# Configure camera source explicitly (required)
# USB camera: export CAMERA_SOURCE="0"
# RTSP camera: export CAMERA_SOURCE="rtsp://user:password@169.254.82.219:554/Streaming/Channels/101"
python -m uvicorn camera_server.main:app --reload --port 8001
```

## Frontend

```bash
cd frontend
npm install
npm run dev
```

## C camera HTTP server (Aravis + JPEG)

This server is implemented in `c_capture/camera_server.c`.

- It opens the first detected GigE Vision/GenICam camera through Aravis.
- On each HTTP request to `http://127.0.0.1:8080`, it grabs one frame and returns JPEG.
- If frame capture fails, it returns HTTP `503`.

### Install dependencies (macOS, Homebrew)

```bash
brew install pkgconf aravis libmicrohttpd jpeg-turbo
```

### Build and run

```bash
cd /defect-detector-v3
cc c_capture/camera_server.c -o c_capture/camera_server \
  $(pkg-config --cflags --libs aravis-0.8 libmicrohttpd libjpeg)

cd c_capture
./camera_server
```

Expected startup logs:

- `Camera connected: ...`
- `HTTP server running on http://localhost:8080`

### Get a photo

From another terminal:

```bash
curl http://127.0.0.1:8080 -o frame.jpg
open frame.jpg
```

## Network troubleshooting for GigE camera on macOS

If camera works on another computer but not on this Mac:

1. Check link on camera NIC (`en6` in examples):

```bash
ifconfig en6
```

2. Verify route points to the camera interface:

```bash
route -n get 169.254.82.219
```

3. If route is wrong (for example on `en0`), fix it:

```bash
sudo route -n delete -host 169.254.82.219
sudo route -n add -host 169.254.82.219 -interface en6
```

4. Clear ARP and test connectivity:

```bash
sudo arp -d 169.254.82.219
ping -c 5 169.254.82.219
```

5. Confirm Aravis discovery:

```bash
arv-tool-0.8 --gv-discovery-interface=en6
```

If needed, temporarily disable Wi-Fi to avoid route conflicts with `169.254.*`:

```bash
networksetup -setairportpower en0 off
# Re-enable later:
networksetup -setairportpower en0 on
```

## Black image troubleshooting (C server)

If `curl` saves a valid JPEG but image is fully black:

- Most likely exposure/gain are too low or auto modes are off.
- Stop `./camera_server` before changing camera features (otherwise writes may fail with `access-denied`).

Set manual values:

```bash
arv-tool-0.8 control ExposureAuto=Off GainAuto=Off ExposureTime=30000 Gain=8
arv-tool-0.8 control ExposureAuto ExposureTime GainAuto Gain PixelFormat
```

Then start the C server again and capture a new frame.

Notes:

- `PixelFormat=Mono8` is expected in current C implementation.
- If feature writes fail with `GigEVision write_register error (access-denied)`, close other clients (including running capture apps) and retry.
