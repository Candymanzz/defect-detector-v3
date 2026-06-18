import { useState } from "react";

type PreviewImageProps = {
  src?: string;
  alt: string;
  className?: string;
  placeholderClassName?: string;
  emptyLabel?: string;
  decoding?: "async" | "sync" | "auto";
  fetchPriority?: "high" | "low" | "auto";
};

export function PreviewImage({
  src,
  alt,
  className,
  placeholderClassName,
  emptyLabel = "Нет изображения",
  decoding = "async",
  fetchPriority = "auto",
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
        decoding={decoding}
        fetchPriority={fetchPriority}
        hidden={failed}
        src={src}
        onError={() => setFailedSrc(src)}
        onLoad={() => setFailedSrc((previousFailedSrc) => (previousFailedSrc === src ? undefined : previousFailedSrc))}
      />
      {failed && <div className={placeholderClassName}>{emptyLabel}</div>}
    </>
  );
}
