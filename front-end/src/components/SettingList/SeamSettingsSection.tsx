import { useSyncExternalStore } from "react";
import type { ChangeEvent } from "react";
import type { SeamSettings } from "../../shared/api";
import { getReferenceImage, subscribeReferenceImages } from "../../shared/referenceImages";

/**
 * Контроль стыка этикетки (cppAnalisSurface): включение и допуски. Саму линию стыка оператор задаёт при настройке
 * эталона кнопкой «Линия шва» в редакторе ROI; она хранится вместе с эталоном камеры и уходит в детектор с каждым кадром.
 * Допуск в середине стыка строгий, на концах мягкий.
 */

type SliderField = {

  name: "tol_mid" | "tol_edge" | "edge_frac" | "band";

  label: string;

  hint: string;

  min: number;

  max: number;

  step: number;

  factor: number;

  unit: string;

  decimals: number;

};



const SLIDERS: readonly SliderField[] = [

  {

    name: "tol_mid", label: "Допуск в середине стыка",

    hint: "Насколько линия стыка может отклоняться от плавной кривой в средней части. Меньше — строже к изломам.",

    min: 0.2, max: 5, step: 0.1, factor: 1, unit: "px", decimals: 1,

  },

  {

    name: "tol_edge", label: "Допуск на концах стыка",

    hint: "То же для краевых частей стыка: здесь небольшие отклонения считаются нормой.",

    min: 0.5, max: 15, step: 0.5, factor: 1, unit: "px", decimals: 1,

  },

  {

    name: "edge_frac", label: "Длина краевой части",

    hint: "Какая доля длины стыка с каждого конца считается краем (мягкий допуск).",

    min: 0, max: 0.4, step: 0.01, factor: 100, unit: "%", decimals: 0,

  },

  {

    name: "band", label: "Полоса поиска (±)",

    hint: "Насколько далеко от нарисованной линии искать стык. Нужен запас на положение изделия.",

    min: 10, max: 80, step: 1, factor: 1, unit: "px", decimals: 0,

  },

];



type Props = {
  cameraId: number;
  seam: SeamSettings;
  disabled?: boolean;
  onChange: (next: SeamSettings) => void;
};

export function SeamSettingsSection({ cameraId, seam, disabled = false, onChange }: Props) {
  const referenceSeamLine = useSyncExternalStore(
    subscribeReferenceImages,
    () => getReferenceImage(cameraId)?.seamLine,
    () => undefined,
  );
  const hasReferenceLine = Boolean(referenceSeamLine && referenceSeamLine.length === 2);
  const hasSettingsLine = Math.abs(seam.p1[0] - seam.p0[0]) + Math.abs(seam.p1[1] - seam.p0[1]) > 1e-6;

  return (
    <div className="seam-settings">
      <label className="seam-settings__toggle">
        <input
          type="checkbox"
          checked={seam.enabled}
          disabled={disabled}
          onChange={(event: ChangeEvent<HTMLInputElement>) => onChange({ ...seam, enabled: event.target.checked })}
        />
        <strong>Контроль стыка этикетки</strong>
      </label>
      <small className="seam-settings__hint">
        Проверяет, что линия стыка ровная. Изломы в середине — брак, на концах допускаются в пределах допуска.
      </small>
      <small className="seam-settings__hint" data-ok={hasReferenceLine || hasSettingsLine}>
        {hasReferenceLine
          ? "Линия стыка задана на эталоне камеры."
          : hasSettingsLine
            ? "Линия стыка взята из настроек (на эталоне не задана)."
            : "Линия стыка не задана: нарисуйте её при настройке эталона кнопкой «Линия шва» в редакторе ROI."}
      </small>

      <div className="analysis-presets__fields seam-settings__fields">
        {SLIDERS.map((field) => {
          const value = seam[field.name];
          return (
            <label className="analysis-presets__field" key={field.name}>
              <span>
                <strong>{field.label}</strong>
                <output>{(value * field.factor).toFixed(field.decimals)} {field.unit}</output>
              </span>
              <input
                type="range"
                min={field.min}
                max={field.max}
                step={field.step}
                value={value}
                disabled={disabled}
                onChange={(event: ChangeEvent<HTMLInputElement>) =>
                  onChange({ ...seam, [field.name]: Number(event.target.value) })}
              />
              <small>{field.hint}</small>
            </label>
          );
        })}
      </div>
    </div>
  );
}
