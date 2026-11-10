export type ExclusionViewport = { zoom: number; x: number; y: number };

/** Keep the same image point under the zoom anchor, without exposing empty margins. */
export function zoomExclusionView(view: ExclusionViewport, nextZoom: number, width: number, height: number,
  anchorX = 0, anchorY = 0): ExclusionViewport {
  const zoom = Math.max(1, Math.min(8, nextZoom));
  if (zoom === 1) return { zoom: 1, x: 0, y: 0 };
  const ratio = zoom / view.zoom;
  const maxX = width * (zoom - 1) / 2, maxY = height * (zoom - 1) / 2;
  return { zoom,
    x: Math.max(-maxX, Math.min(maxX, anchorX - (anchorX - view.x) * ratio)),
    y: Math.max(-maxY, Math.min(maxY, anchorY - (anchorY - view.y) * ratio)),
  };
}
