import type { InspectionExclusions } from "../../shared/inspectionExclusions";
import { validateExclusions } from "../../shared/inspectionExclusions";
import "./InspectionExclusions.css";

/** This is the inspection's snapshot, never the current profile or editor draft. */
export function InspectionExclusionsResult({ exclusions }: { exclusions?: InspectionExclusions }) {
  if (!exclusions || exclusions.coordinate_space !== "normalized" || validateExclusions(exclusions.polygons)) return null;
  return (
    <details className="inspection-exclusions-result">
      <summary>{exclusions.surface_skipped ? "Вся поверхность исключена из проверки" : `Исключающих зон: ${exclusions.polygons.length}`}</summary>
      <p>Проверяемая площадь: {exclusions.remaining_area_work_px} пикселей рабочего масштаба.
        Шов вне исключений и геометрия учитываются в общем вердикте.</p>
      {exclusions.polygons.length > 0 && <>
        <svg className="inspection-exclusions-result__map" viewBox="0 0 1000 1000" role="img"
          aria-label="Схема исключений этой инспекции в координатах эталона">
          {exclusions.polygons.map((polygon, i) => <polygon key={i}
            points={polygon.map(([x, y]) => `${x * 1000},${y * 1000}`).join(" ")} />)}
        </svg>
        <p>Схема зон в координатах эталона этой инспекции.</p>
      </>}
    </details>
  );
}
