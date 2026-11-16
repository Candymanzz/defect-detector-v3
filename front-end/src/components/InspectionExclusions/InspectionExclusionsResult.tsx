import type { InspectionExclusions } from "../../shared/inspectionExclusions";
import { validateExclusions } from "../../shared/inspectionExclusions";
import "./InspectionExclusions.css";

/** This is the inspection's snapshot, never the current profile or editor draft. */
export function InspectionExclusionsResult({ exclusions }: { exclusions?: InspectionExclusions }) {
  if (!exclusions || exclusions.coordinate_space !== "normalized" || validateExclusions(exclusions.polygons)) return null;
  const boundary=exclusions.label_boundary;
  const fallback=boundary && ["insufficient_edges","inconsistent_edges","excessive_crop"].includes(boundary.status);
  const inset=boundary && Number.isFinite(boundary.max_inset_px) ? boundary.max_inset_px.toFixed(1) : "—";
  const boundaryText=boundary?.status==="refined"
    ? `Граница этикетки уточнена. Коррекция контура: до ${inset} px исходного кадра; защитный запас — 1 px.`
    : boundary?.status==="unchanged" ? "Граница этикетки совпадает с ожидаемой; дополнительная обрезка не требуется."
    : fallback ? "Границу этикетки уточнить не удалось — используется подтверждённый контур."
    : null;
  return (
    <>
    {boundaryText && <p className="inspection-label-boundary-result" data-fallback={!!fallback} role="status">{boundaryText}</p>}
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
    </>
  );
}
