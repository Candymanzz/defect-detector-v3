import { useEffect } from "react";
import { PreviewImage } from "../../shared/ui/PreviewImage";
import "./ModalWrapper.css";
type ModalWrapperProps = {
  isOpen: boolean;
  title: string;
  cameraImageUrl?: string;
  referenceImageUrl?: string;
  onClose: () => void;
};

export function ModalWrapper({
  isOpen,
  title,
  cameraImageUrl,
  referenceImageUrl,
  onClose,
}: ModalWrapperProps) {
  useEffect(() => {
    if (!isOpen) {
      return;
    }

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      }
    };

    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [isOpen, onClose]);

  if (!isOpen) {
    return null;
  }

  return (
    <div className="modal-backdrop" onMouseDown={onClose}>
      <section
        aria-label={title}
        aria-modal="true"
        className="modal"
        role="dialog"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="modal__header">
          <h2>{title}</h2>
          <button aria-label="Close modal" className="modal__close" type="button" onClick={onClose}>
            x
          </button>
        </header>

        <div className="modal__images">
          <ImagePanel imageUrl={referenceImageUrl} label="Эталон" />
          <ImagePanel imageUrl={cameraImageUrl} label="Проверка камеры" />
        </div>
      </section>
    </div>
  );
}

function ImagePanel({ label, imageUrl }: { label: string; imageUrl?: string }) {
  return (
    <figure className="modal-image-panel">
      <div className="modal-image-panel__image-wrap">
        <PreviewImage
          key={imageUrl ?? `${label}-offline`}
          alt={label}
          className="modal-image-panel__image"
          placeholderClassName="modal-image-panel__placeholder"
          src={imageUrl}
        />
      </div>
      <figcaption>{label}</figcaption>
    </figure>
  );
}
