import { useState } from "react";

type PreviewImageProps = {
  src?: string;
  alt: string;
  className?: string;
  placeholderClassName?: string;
  emptyLabel?: string;
};

export function PreviewImage({
  src,
  alt,
  className,
  placeholderClassName,
  emptyLabel = "Нет изображения",
}: PreviewImageProps) {
  const [failed, setFailed] = useState(false);

  if (!src || failed) {
    return <div className={placeholderClassName}>{emptyLabel}</div>;
  }

  return (
    <img
      alt={alt}
      className={className}
      src={src}
      onError={() => setFailed(true)}
    />
  );
}
