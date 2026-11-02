import type { LearningDetails } from "../../shared/ws/types";

const statuses: Record<string, string> = {
  matched: "применён", no_effect: "не уменьшил отличие", outside_region: "вне области проверки",
  no_anomaly: "нет аномалий рядом", crop_too_small: "слишком маленький участок",
  no_learned_difference: "почти не отличается от эталона",
};
const reasons: Record<string, string> = {
  unreliable_match: "недостаточное сходство тени", not_smooth: "изменение не похоже на плавную тень",
  lighting_change_too_large: "слишком сильно изменилась яркость",
};
const number = (value: number | null | undefined) => typeof value === "number" && Number.isFinite(value) ? value.toFixed(2) : "—";

export function LearningDiagnosticsPanel({ learning }: { learning?: LearningDetails }) {
  if (!learning?.learning_engine) return null;
  return (
    <details className="modal-inspect-result__learning">
      <summary>Применение образцов нормы · {number(learning.learning_ms)} мс</summary>
      <p>Оценка до образцов: {number(learning.raw_anomaly_score === undefined ? undefined : learning.raw_anomaly_score * 100)}%.
        Совпавших образцов: {learning.learned_normal_matches_count ?? 0}.</p>
      {learning.learning_robust_enabled === false && <p>Прежнее сопоставление. Устойчивый режим выключен.</p>}
      {learning.learning_robust_enabled && !learning.learning_diagnostics?.length && <p>Для этого эталона нет активных образцов.</p>}
      {learning.learning_diagnostics?.map((item) => (
        <p key={item.id}>
          {item.id}: {statuses[item.status] ?? item.status}.
          {item.method === "shadow" ? " Плавная тень." : item.method === "pattern" ? " Сопоставление изображения." : ""}
          {item.shift && ` Сдвиг: ${number(item.shift[0])}, ${number(item.shift[1])} px.`}
          {` Убрано рабочих пикселей: ${item.removed_pixels}.`}
          {` Сходство изображения: ${number(item.pattern_correlation)}; тени: ${number(item.shadow_correlation)}.`}
          {item.shadow_skip_reason && ` ${reasons[item.shadow_skip_reason] ?? item.shadow_skip_reason}.`}
        </p>
      ))}
    </details>
  );
}
