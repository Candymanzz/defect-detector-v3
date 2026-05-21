import { useEffect, useState } from "react";
import "../ModalWrapper/ModalWrapper.css";
import "./ReferenceSetup.css";
import { ServerWsMessage, WsConnectionStatus, orchestratorWs } from "../../shared/ws";
import { orchestratorApi } from "../../shared/api/orchestratorApi";

type ReferenceSetupProps = {
  onClose: () => void;
};

export function ReferenceSetup({ onClose }: ReferenceSetupProps) {
    const [status, setStatus] = useState<WsConnectionStatus>({
        state: "idle",
        reconnectAttempt: 0,
    });
    const [message, setMessage] = useState('waiting for message...');
    const [imageUrl, setImageUrl] = useState<string>();
    useEffect(() => {
        orchestratorWs.connect();
       const unsubscribeStatus = orchestratorWs.onStatus((status) => {
           setStatus(status);
        });
        const unsubscribeMessage = orchestratorWs.onMessage((message: ServerWsMessage) => {
            switch (message.type) {
                case "server.hello":
                    setMessage("Hello, world!");
                    break;
                case "server.state":
                    setMessage("State: " + message.payload.session_state + " " + message.payload.server_ts_ms);
                    break;
                    case "server.preview_frame":{
                        const cameraId = message.payload.camera_id;
                        const frameId = message.payload.frame_id;
                        const imagePath = message.payload.http_path ?? message.payload.current.http_path;
                    if (imagePath) {
                        setImageUrl(orchestratorApi.imageUrl(imagePath, frameId));
                    }
                        setMessage(`камера ${cameraId}, кадр ${frameId}, изображение: ${imagePath}`);
                    
                    }
                        break;
                default:
                    setMessage("Unknown message: " + message.type);
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

  return (
    <div className="modal-backdrop" onMouseDown={onClose}>
      <section
        aria-label="Задание эталона"
        aria-modal="true"
        className="modal reference-setup"
        role="dialog"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="modal__header">
          <h2>Задание эталона</h2>
          <button aria-label="Закрыть" className="modal__close" type="button" onClick={onClose}>
            x
          </button>
        </header>

        <div className="reference-setup__body">
          <p className="reference-setup__hint">
            Здесь будет настройка ракурсов и отправка пакета эталона на оркестратор.<br />
            Status: {status.state} <br />
            {message}
            
          </p>
          <div className="reference-setup__image-container">
          {imageUrl && <img src={imageUrl} alt="Preview frame" />}
          </div>
        </div>
      </section>
    </div>
  );
}
