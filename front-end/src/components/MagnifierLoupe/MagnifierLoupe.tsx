import { useLayoutEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import { LOUPE_MAX_ZOOM, LOUPE_MIN_ZOOM } from "./useLoupeSettings";
import type { LoupeSettings } from "./useLoupeSettings";
import "./MagnifierLoupe.css";

const LOUPE_WIDTH = 180;
const LOUPE_HEIGHT = 180;
const OFFSET = 22;

type NormPoint = { x: number; y: number };

type MagnifierLoupeProps = {
  imageUrl: string;
  /** Положение курсора в нормализованных координатах изображения; null — лупа скрыта. */
  cursor: NormPoint | null;
  zoom: number;
  /** Класс SVG-оверлея редактора: на нём держатся стили контуров и маркеров. */
  svgClassName?: string;
  /** Те же фигуры, что нарисованы в редакторе (без курсора). */
  children?: ReactNode;
};

/**
 * Окно с увеличенным участком под курсором. Располагается справа от курсора
 * (слева, если справа нет места) и не перехватывает события мыши.
 * Размещается внутри позиционированного контейнера с изображением.
 */
export function MagnifierLoupe({ imageUrl, cursor, zoom, svgClassName, children }: MagnifierLoupeProps) {
  const rootRef = useRef<HTMLDivElement>(null);
  const [box, setBox] = useState<{ width: number; height: number } | null>(null);

  useLayoutEffect(() => {
    const parent = rootRef.current?.parentElement;
    if (!parent || !cursor) return;
    const rect = parent.getBoundingClientRect();
    setBox((current) =>
      current && current.width === rect.width && current.height === rect.height
        ? current
        : { width: rect.width, height: rect.height },
    );
  }, [cursor]);

  let content: ReactNode = null;
  if (cursor && box && box.width > 0 && box.height > 0) {
    const cursorX = cursor.x * box.width;
    const cursorY = cursor.y * box.height;
    const fitsRight = cursorX + OFFSET + LOUPE_WIDTH <= box.width;
    const left = fitsRight ? cursorX + OFFSET : Math.max(0, cursorX - OFFSET - LOUPE_WIDTH);
    const top = Math.min(Math.max(0, cursorY - LOUPE_HEIGHT / 2), Math.max(0, box.height - LOUPE_HEIGHT));

    // Видимая область в нормализованных единицах: zoom = во сколько раз больше пикселей экрана на пиксель окна.
    const viewWidth = LOUPE_WIDTH / (box.width * zoom);
    const viewHeight = LOUPE_HEIGHT / (box.height * zoom);
    const viewBox = `${cursor.x - viewWidth / 2} ${cursor.y - viewHeight / 2} ${viewWidth} ${viewHeight}`;

    content = (
      <div className="magnifier-loupe__window" style={{ left, top, width: LOUPE_WIDTH, height: LOUPE_HEIGHT }}>
        <svg
          className={svgClassName}
          viewBox={viewBox}
          preserveAspectRatio="none"
          width={LOUPE_WIDTH}
          height={LOUPE_HEIGHT}
        >
          <image href={imageUrl} x="0" y="0" width="1" height="1" preserveAspectRatio="none" />
          {children}
        </svg>
        <span className="magnifier-loupe__cross magnifier-loupe__cross--h" />
        <span className="magnifier-loupe__cross magnifier-loupe__cross--v" />
        <span className="magnifier-loupe__badge">×{zoom}</span>
      </div>
    );
  }

  return (
    <div ref={rootRef} className="magnifier-loupe" aria-hidden="true">
      {content}
    </div>
  );
}

type LoupeControlsProps = {
  settings: LoupeSettings;
  className?: string;
};

/** Переключатель лупы и ползунок силы зума для панели редактора. */
export function LoupeControls({ settings, className }: LoupeControlsProps) {
  return (
    <div className={`magnifier-loupe-controls${className ? ` ${className}` : ""}`}>
      <label className="magnifier-loupe-controls__toggle">
        <input
          type="checkbox"
          checked={settings.enabled}
          onChange={(event) => settings.setEnabled(event.target.checked)}
        />
        Лупа
      </label>
      <label className="magnifier-loupe-controls__zoom">
        <span>Зум ×{settings.zoom}</span>
        <input
          type="range"
          min={LOUPE_MIN_ZOOM}
          max={LOUPE_MAX_ZOOM}
          step={1}
          value={settings.zoom}
          disabled={!settings.enabled}
          onChange={(event) => settings.setZoom(Number(event.target.value))}
        />
      </label>
    </div>
  );
}
