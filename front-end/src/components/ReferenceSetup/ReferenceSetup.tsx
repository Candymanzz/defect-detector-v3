import "../ModalWrapper/ModalWrapper.css";
import "./ReferenceSetup.css";
import { useReferenceSetupController } from "./ReferenceController";

type ReferenceSetupProps = {
  onClose: () => void;
};

export function ReferenceSetup({ onClose }: ReferenceSetupProps) {
  const { status, message, imageUrl, canSendReference, handleSendReference } = useReferenceSetupController(onClose);

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
          <button type="button" disabled={!canSendReference} onClick={handleSendReference}>
            Задать эталон
          </button>

          <p className="reference-setup__hint">
            Здесь будет настройка ракурсов и отправка пакета эталона на оркестратор.
            <br />
            Статус: {status.state}
            <br />
            {message}
          </p>

          <div className="reference-setup__image-container">
            {imageUrl && <img src={imageUrl} alt="Кадр предпросмотра" />}
          </div>
        </div>
      </section>
    </div>
  );
}
