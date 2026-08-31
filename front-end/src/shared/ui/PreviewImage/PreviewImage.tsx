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
  const displayedSrcRef = useRef<string | undefined>(undefined);
  const latestSrcRef = useRef(src);
  const loadingSrcRef = useRef<string | undefined>(undefined);
  const activeImageRef = useRef<HTMLImageElement | undefined>(undefined);
  const mountedRef = useRef(false);
  const retainPreviousRef = useRef(retainPreviousWhileLoading);
  const loadLatestRef = useRef<() => void>(() => undefined);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      if (activeImageRef.current) {
        activeImageRef.current.onload = null;
        activeImageRef.current.onerror = null;
      }
      activeImageRef.current = undefined;
      loadingSrcRef.current = undefined;
    };
  }, []);

  useEffect(() => {
    latestSrcRef.current = src;
    retainPreviousRef.current = retainPreviousWhileLoading;
    loadLatestRef.current = () => {
      const candidateSrc = latestSrcRef.current;
      if (
        !mountedRef.current ||
        !retainPreviousRef.current ||
        !candidateSrc ||
        candidateSrc === displayedSrcRef.current ||
        loadingSrcRef.current
      ) {
        return;
      }

      const nextImage = new Image();
      loadingSrcRef.current = candidateSrc;
      activeImageRef.current = nextImage;
      nextImage.decoding = decoding;
      nextImage.fetchPriority = fetchPriority;

      const finish = () => {
        if (activeImageRef.current === nextImage) {
          activeImageRef.current = undefined;
        }
        loadingSrcRef.current = undefined;
      };
      const showLoadedImage = () => {
        finish();
        if (!mountedRef.current || !retainPreviousRef.current) {
          return;
        }
        displayedSrcRef.current = candidateSrc;
        setFailedSrc(undefined);
        setDisplayedSrc(candidateSrc);
        // src may have advanced while this image was loading. Keep one request in
        // flight and immediately continue with the newest frame instead of
        // cancelling every request and leaving the old image on screen forever.
        loadLatestRef.current();
      };

      nextImage.onload = () => {
        if (typeof nextImage.decode !== "function") {
          showLoadedImage();
          return;
        }
        void nextImage.decode().then(showLoadedImage, showLoadedImage);
      };
      nextImage.onerror = () => {
        finish();
        if (!mountedRef.current || !retainPreviousRef.current) {
          return;
        }
        setFailedSrc(candidateSrc);
        if (latestSrcRef.current !== candidateSrc) {
          loadLatestRef.current();
        }
      };
      nextImage.src = candidateSrc;
    };
    loadLatestRef.current();
  }, [decoding, fetchPriority, retainPreviousWhileLoading, src]);

  const effectiveSrc = retainPreviousWhileLoading ? (displayedSrc ?? src) : src;
  const failed = Boolean(src && failedSrc === src && (!retainPreviousWhileLoading || !effectiveSrc));

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
            displayedSrcRef.current = undefined;
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
