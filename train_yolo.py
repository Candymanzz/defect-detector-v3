from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path

from ultralytics import YOLO


@dataclass
class TrainConfig:
    data_yaml: Path
    model_name: str = "yolo11n-seg.pt"
    imgsz: int = 1024
    epochs: int = 100
    batch: int = 8
    project: Path = Path("runs")
    name: str = "scratch-seg"
    device: str | int | None = None
    mosaic: float = 1.0
    mixup: float = 0.1
    copy_paste: float = 0.1
    export_onnx: bool = True


def _resolve_best_weights(model: YOLO, train_result: object) -> Path:
    candidates: list[Path] = []

    best_attr = getattr(getattr(model, "trainer", None), "best", None)
    if best_attr:
        candidates.append(Path(str(best_attr)))

    save_dir = getattr(train_result, "save_dir", None)
    if save_dir:
        candidates.append(Path(str(save_dir)) / "weights" / "best.pt")

    candidates.extend(
        [
            Path("runs") / "segment" / "train" / "weights" / "best.pt",
            Path("runs") / "segment" / "train1" / "weights" / "best.pt",
        ]
    )

    for candidate in candidates:
        if candidate.exists():
            return candidate.resolve()

    raise FileNotFoundError("Не удалось найти best.pt после обучения.")


def train(config: TrainConfig) -> tuple[Path, Path | None]:
    if not config.data_yaml.exists():
        raise FileNotFoundError(f"Файл датасета не найден: {config.data_yaml}")

    model = YOLO(config.model_name)
    train_result = model.train(
        data=str(config.data_yaml),
        imgsz=config.imgsz,
        epochs=config.epochs,
        batch=config.batch,
        project=str(config.project),
        name=config.name,
        device=config.device,
        mosaic=config.mosaic,
        mixup=config.mixup,
        copy_paste=config.copy_paste,
    )

    best_weights = _resolve_best_weights(model, train_result)
    onnx_path: Path | None = None
    if config.export_onnx:
        best_model = YOLO(str(best_weights))
        exported = best_model.export(format="onnx")
        onnx_path = Path(str(exported)).resolve()

    return best_weights, onnx_path


def parse_args() -> TrainConfig:
    parser = argparse.ArgumentParser(description="Training script for YOLO11 segmentation.")
    parser.add_argument("--data", type=Path, required=True, help="Path to data.yaml")
    parser.add_argument("--epochs", type=int, default=100)
    parser.add_argument("--batch", type=int, default=8)
    parser.add_argument("--imgsz", type=int, default=1024)
    parser.add_argument("--project", type=Path, default=Path("runs"))
    parser.add_argument("--name", type=str, default="scratch-seg")
    parser.add_argument("--device", type=str, default=None)
    args = parser.parse_args()

    return TrainConfig(
        data_yaml=args.data,
        epochs=args.epochs,
        batch=args.batch,
        imgsz=args.imgsz,
        project=args.project,
        name=args.name,
        device=args.device,
    )


if __name__ == "__main__":
    cfg = parse_args()
    best_pt, best_onnx = train(cfg)
    print(f"Best weights: {best_pt}")
    if best_onnx:
        print(f"Exported ONNX: {best_onnx}")
