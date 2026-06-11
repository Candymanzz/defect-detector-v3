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
  const [failedSrc, setFailedSrc] = useState<string>();
  const failed = Boolean(src && failedSrc === src);

  if (!src) {
    return <div className={placeholderClassName}>{emptyLabel}</div>;
  }

  return (
    <>
      <img
        alt={alt}
        className={className}
        hidden={failed}
        src={src}
        onError={() => setFailedSrc(src)}
        onLoad={() => setFailedSrc((previousFailedSrc) => (previousFailedSrc === src ? undefined : previousFailedSrc))}
      />
      {failed && <div className={placeholderClassName}>{emptyLabel}</div>}
    </>
  );
}
