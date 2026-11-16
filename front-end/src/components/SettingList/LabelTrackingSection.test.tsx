import { renderToStaticMarkup } from "react-dom/server";
import { expect, it } from "vitest";
import type { DetectorSettings } from "../../shared/api";
import { LabelTrackingSection } from "./LabelTrackingSection";
import { editableSettings } from "./DetectorSettingsPanel";

it("renders the contour switch and explicit confirmation on the reference", () => {
  const settings = { label_outline: [], label_tracking_enabled: true } as unknown as DetectorSettings;
  const html = renderToStaticMarkup(<LabelTrackingSection cameraId={1} settings={settings}
    disabled={false} onChange={() => undefined} />);
  expect(html).toContain("Компенсация с вырезанием краёв");
  expect(html).toContain("Обвести этикетку заново");
  expect(html).toContain("Вырезание по контуру этикетки и запас по краю отключены.");
  expect(html).not.toContain('checked=""');
  expect(html.indexOf('type="checkbox"')).toBeLessThan(html.indexOf('<details'));
});

it("clearly distinguishes normal positioning from cutting with the saved contour", () => {
  const settings = { label_outline: [[.1,.1],[.9,.1],[.5,.9]], label_tracking_enabled: true } as unknown as DetectorSettings;
  const render = () => renderToStaticMarkup(<LabelTrackingSection cameraId={1} settings={settings}
    disabled={false} onChange={() => undefined} />);
  expect(render()).toContain('checked=""');
  expect(render()).toContain("запас по краю — 1 px");
  settings.label_tracking_enabled=false;
  const off=render();
  expect(off).not.toContain('checked=""');
  expect(off).toContain("Обычное позиционирование и сравнение ROI.");
  expect(settings.label_outline).toHaveLength(3);
});

it("keeps the contour camera-specific when applying common settings", () => {
  const settings = { label_outline: [[.1,.1],[.9,.1],[.5,.9]], label_tracking_enabled: false,
    label_reference_fingerprint: "reference", shift_tolerance_x: 3 } as unknown as DetectorSettings;
  const camera = editableSettings(settings,true);
  expect(camera.label_outline).toEqual(settings.label_outline);
  expect(camera.label_tracking_enabled).toBe(false);
  expect(camera).not.toHaveProperty("label_reference_fingerprint");
  const common = editableSettings(settings,false);
  expect(common).not.toHaveProperty("label_outline");
  expect(common).not.toHaveProperty("label_tracking_enabled");
  expect(common).not.toHaveProperty("label_reference_fingerprint");
});
