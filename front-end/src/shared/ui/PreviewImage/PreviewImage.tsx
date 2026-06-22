import { useState } from "react";
import type { SyntheticEvent } from "react";

type PreviewImageProps = {
  src?: string;
  alt: string;
  className?: string;
  placeholderClassName?: string;
  emptyLabel?: string;
  decoding?: "async" | "sync" | "auto";
  fetchPriority?: "high" | "low" | "auto";
  onLoad?: (event: SyntheticEvent<HTMLImageElement>) => void;
};

export function PreviewImage({
  src,
  alt,
  className,
  placeholderClassName,
  emptyLabel = "Нет изображения",
  decoding = "async",
  fetchPriority = "auto",
  onLoad,
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
        onLoad={(event) => {
          setFailedSrc((previousFailedSrc) => (previousFailedSrc === src ? undefined : previousFailedSrc));
          onLoad?.(event);
        }}
      />
      {failed && <div className={placeholderClassName}>{emptyLabel}</div>}
    </>
  );
}
