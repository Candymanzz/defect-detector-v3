import { useCallback, useEffect, useRef, useState } from "react";
import { DEFAULT_STATE } from "../../app/initialState";
import { computeContainBox } from "../lib/imageBox";

const getLoadedImageSize = (imageElement) => ({
  width: imageElement.naturalWidth,
  height: imageElement.naturalHeight
});

export function useImageBoxes({ referencePreview, heatmapPreview }) {
  const referenceContainerRef = useRef(null);
  const heatmapContainerRef = useRef(null);
  const [referenceImageSize, setReferenceImageSize] = useState(() => ({
    ...DEFAULT_STATE.imageSize
  }));
  const [referenceBox, setReferenceBox] = useState(() => ({ ...DEFAULT_STATE.imageBox }));
  const [heatmapImageSize, setHeatmapImageSize] = useState(() => ({
    ...DEFAULT_STATE.imageSize
  }));
  const [heatmapBox, setHeatmapBox] = useState(() => ({ ...DEFAULT_STATE.imageBox }));

  const updateReferenceBox = useCallback((imageSize) => {
    setReferenceBox(computeContainBox(referenceContainerRef.current, imageSize));
  }, []);

  const updateHeatmapBox = useCallback((imageSize) => {
    setHeatmapBox(computeContainBox(heatmapContainerRef.current, imageSize));
  }, []);

  const handleReferenceImageLoad = useCallback(
    (event) => {
      const nextImageSize = getLoadedImageSize(event.currentTarget);
      setReferenceImageSize(nextImageSize);
      requestAnimationFrame(() => updateReferenceBox(nextImageSize));
    },
    [updateReferenceBox]
  );

  const handleHeatmapImageLoad = useCallback(
    (event) => {
      const nextImageSize = getLoadedImageSize(event.currentTarget);
      setHeatmapImageSize(nextImageSize);
      requestAnimationFrame(() => updateHeatmapBox(nextImageSize));
    },
    [updateHeatmapBox]
  );

  useEffect(() => {
    updateReferenceBox(referenceImageSize);
    updateHeatmapBox(heatmapImageSize);

    const handleResize = () => {
      updateReferenceBox(referenceImageSize);
      updateHeatmapBox(heatmapImageSize);
    };

    window.addEventListener("resize", handleResize);
    return () => window.removeEventListener("resize", handleResize);
  }, [
    referenceImageSize,
    referencePreview,
    heatmapImageSize,
    heatmapPreview,
    updateReferenceBox,
    updateHeatmapBox
  ]);

  return {
    referenceContainerRef,
    heatmapContainerRef,
    referenceBox,
    heatmapBox,
    heatmapImageSize,
    handleReferenceImageLoad,
    handleHeatmapImageLoad
  };
}
