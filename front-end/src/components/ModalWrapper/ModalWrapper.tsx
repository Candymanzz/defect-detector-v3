<<<<<<< HEAD
import { useEffect, useSyncExternalStore } from "react";
import { getReferenceImageUrl, subscribeReferenceImages } from "../../shared/referenceImages";
=======
import { useEffect } from "react";
>>>>>>> window
import { PreviewImage } from "../../shared/ui/PreviewImage";
import "./ModalWrapper.css";
type ModalWrapperProps = {
  isOpen: boolean;
  title: string;
<<<<<<< HEAD
  cameraId?: number;
=======
>>>>>>> window
  cameraImageUrl?: string;
  referenceImageUrl?: string;
  onClose: () => void;
};

export function ModalWrapper({
  isOpen,
  title,
<<<<<<< HEAD
  cameraId,
=======
>>>>>>> window
  cameraImageUrl,
  referenceImageUrl,
  onClose,
}: ModalWrapperProps) {
<<<<<<< HEAD
  const storedReferenceImageUrl = useSyncExternalStore(
    subscribeReferenceImages,
    () => getReferenceImageUrl(cameraId),
    () => undefined,
  );
  const displayedReferenceImageUrl = referenceImageUrl ?? storedReferenceImageUrl;

=======
>>>>>>> window
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
<<<<<<< HEAD
          <ImagePanel imageUrl={displayedReferenceImageUrl} label="Эталон" />
=======
          <ImagePanel imageUrl={referenceImageUrl} label="Эталон" />
>>>>>>> window
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
