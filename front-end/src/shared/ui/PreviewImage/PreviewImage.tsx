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
  const [loadedSrc, setLoadedSrc] = useState(src);
  const failed = Boolean(src && failedSrc === src);

  if (!src) {
    return <div className={placeholderClassName}>{emptyLabel}</div>;
  }

  const visibleSrc = loadedSrc ?? src;
  const isLoadingNextSrc = src !== visibleSrc && !failed;

  return (
    <>
      <img
        alt={alt}
        className={className}
        decoding={decoding}
        fetchPriority={fetchPriority}
        hidden={failed && src === visibleSrc}
        src={visibleSrc}
        onError={() => setFailedSrc(visibleSrc)}
        onLoad={(event) => {
          setFailedSrc((previousFailedSrc) => (previousFailedSrc === visibleSrc ? undefined : previousFailedSrc));
          setLoadedSrc(visibleSrc);
          onLoad?.(event);
        }}
      />
      {isLoadingNextSrc && (
        <img
          alt=""
          aria-hidden="true"
          decoding={decoding}
          fetchPriority={fetchPriority}
          hidden
          src={src}
          onError={() => setFailedSrc(src)}
          onLoad={(event) => {
            setFailedSrc((previousFailedSrc) => (previousFailedSrc === src ? undefined : previousFailedSrc));
            setLoadedSrc(src);
            onLoad?.(event);
          }}
        />
      )}
      {failed && src === visibleSrc && <div className={placeholderClassName}>{emptyLabel}</div>}
    </>
  );
}
