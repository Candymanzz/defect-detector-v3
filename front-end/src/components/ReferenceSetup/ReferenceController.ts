import { useEffect, useState } from "react";
import { orchestratorApi } from "../../shared/api/orchestratorApi";
import { orchestratorWs } from "../../shared/ws";
import type {
  ClientReferenceBundlePayload,
  PixelRoi,
  PreviewFramePayload,
  ReferenceViewSlot,
  ServerWsMessage,
  WsConnectionStatus,
} from "../../shared/ws";

const INITIAL_STATUS: WsConnectionStatus = {
  state: "idle",
  reconnectAttempt: 0,
};

export function useReferenceSetupController(onClose: () => void) {
  const [status, setStatus] = useState<WsConnectionStatus>(INITIAL_STATUS);
  const [message, setMessage] = useState("Ожидание сообщения...");
  const [imageUrl, setImageUrl] = useState<string>();
  const [lastPreviewFrame, setLastPreviewFrame] = useState<PreviewFramePayload>();

  useEffect(() => {
    orchestratorWs.connect();

    const unsubscribeStatus = orchestratorWs.onStatus(setStatus);
    const unsubscribeMessage = orchestratorWs.onMessage((message: ServerWsMessage) => {
      switch (message.type) {
        case "server.hello":
          setMessage("Соединение установлено");
          break;
        case "server.state":
          setMessage(`Состояние: ${message.payload.session_state} ${message.payload.server_ts_ms}`);
          break;
        case "server.preview_frame": {
          const nextPreviewFrame = message.payload;
          const imagePath = nextPreviewFrame.http_path ?? nextPreviewFrame.current.http_path;

          setLastPreviewFrame(nextPreviewFrame);

          if (imagePath) {
            setImageUrl(orchestratorApi.imageUrl(imagePath, nextPreviewFrame.frame_id));
          }

          setMessage(
            `Камера ${nextPreviewFrame.camera_id}, кадр ${nextPreviewFrame.frame_id}, изображение: ${
              imagePath ?? "нет"
            }`,
          );
          break;
        }
        case "server.reference_bundle_ack":
          setMessage(message.payload.ok ? "Пакет эталона принят" : "Пакет эталона отклонен");
          break;
        case "server.error":
          setMessage(`${message.payload.code}: ${message.payload.message}`);
          break;
        default:
          setMessage(`Неизвестное сообщение: ${message.type}`);
          break;
      }
    });

    return () => {
      unsubscribeStatus();
      unsubscribeMessage();
    };
  }, []);

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      }
    };

    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [onClose]);

  const handleSendReference = () => {
    if (!lastPreviewFrame) {
      setMessage("Кадр еще не получен");
      return;
    }

    try {
      const payload = createOneFrameReferenceBundle(lastPreviewFrame);
      orchestratorWs.sendReferenceBundle(payload);
      setMessage("Пакет эталона отправлен");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : String(error));
    }
  };

  return {
    status,
    message,
    imageUrl,
    canSendReference: Boolean(lastPreviewFrame && status.state === "open"),
    handleSendReference,
  };
}

export function createOneFrameReferenceBundle(previewFrame: PreviewFramePayload): ClientReferenceBundlePayload {
  const roi = createFullRoi(previewFrame);
  const jointViewIndex = clampViewIndex(previewFrame.camera_id);
  const productType = previewFrame.detector.product_type || `camera-${previewFrame.camera_id}`;
  const views = Array.from({ length: 5 }, (_, index) =>
    createReferenceView(previewFrame, roi, index === jointViewIndex ? roi : null),
  ) as ClientReferenceBundlePayload["views"];

  return {
    product_type: productType,
    joint_view_index: jointViewIndex,
    heatmap_width: previewFrame.current.width,
    heatmap_height: previewFrame.current.height,
    views,
    fp_zones: [],
  };
}

export function createFullRoi(frame: PreviewFramePayload): PixelRoi {
  return {
    x: 0,
    y: 0,
    width: frame.current.width,
    height: frame.current.height,
  };
}

function createReferenceView(
  previewFrame: PreviewFramePayload,
  interestRoi: PixelRoi,
  jointRoi: PixelRoi | null,
): ReferenceViewSlot {
  return {
    frame: previewFrame.current,
    interest_roi: interestRoi,
    joint_roi: jointRoi,
  };
}

function clampViewIndex(cameraId: number) {
  return Math.min(Math.max(cameraId, 0), 4);
}
