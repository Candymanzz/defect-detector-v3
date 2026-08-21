import { useEffect, useRef, useState } from "react";
import type { SyntheticEvent } from "react";

type PreviewImageProps = {
  src?: string;
  alt: string;
  className?: string;
  placeholderClassName?: string;
  emptyLabel?: string;
  decoding?: "async" | "sync" | "auto";
  fetchPriority?: "high" | "low" | "auto";
  retainPreviousWhileLoading?: boolean;
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
  retainPreviousWhileLoading = false,
  onLoad,
}: PreviewImageProps) {
  const [displayedSrc, setDisplayedSrc] = useState<string>();
  const [failedSrc, setFailedSrc] = useState<string>();
  const requestRef = useRef(0);

  useEffect(() => {
    const requestId = ++requestRef.current;
    if (!retainPreviousWhileLoading || !src || src === displayedSrc) {
      return;
    }

    const nextImage = new Image();
    nextImage.decoding = decoding;
    nextImage.fetchPriority = fetchPriority;

    const showLoadedImage = () => {
      if (requestRef.current !== requestId) {
        return;
      }
      setFailedSrc(undefined);
      setDisplayedSrc(src);
    };

    nextImage.onload = () => {
      if (typeof nextImage.decode !== "function") {
        showLoadedImage();
        return;
      }
      void nextImage.decode().then(showLoadedImage, showLoadedImage);
    };
    nextImage.onerror = () => {
      if (requestRef.current === requestId) {
        setFailedSrc(src);
      }
    };
    nextImage.src = src;

    return () => {
      nextImage.onload = null;
      nextImage.onerror = null;
    };
  }, [decoding, displayedSrc, fetchPriority, retainPreviousWhileLoading, src]);

  const effectiveSrc = retainPreviousWhileLoading ? displayedSrc : src;
  const failed = Boolean(
    src && failedSrc === src && (!retainPreviousWhileLoading || !effectiveSrc),
  );

  if (!effectiveSrc && (!src || failed)) {
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
        src={effectiveSrc}
        onError={() => {
          if (retainPreviousWhileLoading && effectiveSrc === src) {
            setDisplayedSrc(undefined);
          }
          setFailedSrc(src);
        }}
        onLoad={(event) => {
          setFailedSrc(undefined);
          onLoad?.(event);
        }}
      />
      {failed && <div className={placeholderClassName}>{emptyLabel}</div>}
    </>
  );
}
