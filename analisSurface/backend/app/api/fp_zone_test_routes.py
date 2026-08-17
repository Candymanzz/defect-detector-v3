"""Мини-стенд для FP-зон с мини-эталоном: выделить ложняк, проверить следующую инспекцию."""

from fastapi import APIRouter
from fastapi.responses import HTMLResponse


router = APIRouter()


@router.get("/fp-zone-test", response_class=HTMLResponse, include_in_schema=False)
async def fp_zone_test_page() -> HTMLResponse:
    return HTMLResponse(
        FP_ZONE_TEST_HTML,
        headers={"Cache-Control": "no-store, no-cache, must-revalidate"},
    )


FP_ZONE_TEST_HTML = r"""<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>Тест FP-зон (мини-эталон)</title>
  <style>
    :root { color-scheme:dark; font-family:Inter,Segoe UI,sans-serif; background:#0f151d; color:#edf2f8; }
    * { box-sizing:border-box; }
    body { margin:0; padding:22px; }
    h1,h2 { margin:0; }
    a { color:#7eb6ff; }
    .muted { color:#9eabbc; }
    .panel { background:#17212d; border:1px solid #2d3b4f; border-radius:12px; padding:16px; margin-bottom:16px; }
    .form-grid { display:grid; grid-template-columns:1fr 140px 1fr 1fr auto auto; gap:12px; align-items:end; }
    label { display:flex; flex-direction:column; gap:6px; color:#b7c2d0; font-size:13px; }
    input { width:100%; border:1px solid #3a4b62; border-radius:8px; background:#0e151f; color:#fff; padding:9px; }
    input[type=file] { padding:7px; }
    button { border:1px solid #40628b; background:#285181; color:#fff; border-radius:8px; padding:10px 14px; cursor:pointer; }
    button:hover { background:#32659f; }
    button.danger { border-color:#7e3d46; background:#582a32; }
    button.primary { border-color:#2f6f4a; background:#1f5136; font-weight:700; }
    .previews,.visuals { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:12px; margin-top:14px; }
    .visuals { grid-template-columns:repeat(4,minmax(0,1fr)); }
    figure { margin:0; background:#0a0e14; border:1px solid #29384b; border-radius:10px; overflow:hidden; }
    figcaption { padding:8px 10px; color:#b8c4d2; border-bottom:1px solid #29384b; }
    figure img { display:block; width:100%; min-height:120px; max-height:320px; object-fit:contain; background:#070a0e; }
    .editor { position:relative; width:100%; background:#070a0e; }
    .editor img { width:100%; height:auto; min-height:0; max-height:none; object-fit:initial; user-select:none; }
    .overlay { position:absolute; inset:0; width:100%; height:100%; cursor:crosshair; }
    .overlay polygon { fill:rgba(80,220,80,.22); stroke:#45df91; stroke-width:.005; vector-effect:non-scaling-stroke; pointer-events:none; }
    .overlay polyline { fill:none; stroke:#76e0a4; stroke-width:.004; vector-effect:non-scaling-stroke; pointer-events:none; }
    .overlay circle { fill:#ffe073; stroke:#152131; stroke-width:.003; vector-effect:non-scaling-stroke; pointer-events:none; }
    .overlay polygon.saved { fill:rgba(40,150,220,.18); stroke:#55a4ff; }
    .controls { display:flex; gap:8px; align-items:center; flex-wrap:wrap; padding:9px 10px; border-top:1px solid #29384b; }
    .result { display:grid; grid-template-columns:repeat(auto-fit,minmax(145px,1fr)); gap:10px; margin-top:12px; }
    .metric { padding:11px; border-radius:9px; background:#101923; border:1px solid #2d3c51; }
    .metric b { display:block; margin-top:4px; font-size:20px; }
    .good { color:#50dc91; }
    .bad { color:#ff7777; }
    .hidden { display:none !important; }
    .zones { display:grid; grid-template-columns:repeat(auto-fill,minmax(260px,1fr)); gap:10px; margin-top:12px; }
    .zone { display:grid; grid-template-columns:86px minmax(0,1fr); gap:10px; background:#111b25; border:1px solid #31513f; border-radius:10px; padding:9px; }
    .zone img { width:86px; height:86px; object-fit:cover; border-radius:7px; background:#070a0e; }
    #message { margin-top:10px; min-height:20px; }
    @media(max-width:1050px) { .form-grid { grid-template-columns:1fr 140px; } .visuals { grid-template-columns:repeat(2,1fr); } }
    @media(max-width:720px) { body { padding:12px; } .form-grid,.previews,.visuals { grid-template-columns:1fr; } }
  </style>
</head>
<body>
  <header>
    <h1>Тест FP-зон (мини-эталон)</h1>
    <div class="muted">Эталон → кадр с ложняком → <b>Дообучить</b>: программа сама найдёт контуры и сохранит мини-эталон с отступом. Ручная обводка — запасной вариант. <a href="/local-inspection-test">Обычный тест инспекции</a></div>
  </header>

  <section class="panel">
    <div class="form-grid">
      <label>Тип теста<input id="productType" value="fp-test" autocomplete="off"></label>
      <label>Порог<input id="threshold" type="number" value="0.25" min="0" max="1" step="0.01"></label>
      <label>Эталон<input id="referenceFile" type="file" accept="image/*"></label>
      <label>Текущий кадр<input id="currentFile" type="file" accept="image/*"></label>
      <button class="primary" onclick="runInspection()">Инспекция</button>
      <button class="primary" id="learnButton" onclick="learnFalsePositive()" disabled>Дообучить</button>
    </div>
    <div id="message" class="muted"></div>
  </section>

  <section class="panel">
    <h2>Кадр и зона ложняка</h2>
    <div class="muted" style="margin:8px 0 10px">После инспекции нажмите «Дообучить» — зона строится по найденным контурам с небольшим отступом. Ручная обводка ниже, если нужно поправить.</div>
    <div class="editor">
      <img id="alignedImage" alt="Выровненный кадр">
      <svg id="fpOverlay" class="overlay" viewBox="0 0 1 1" preserveAspectRatio="none" onclick="addPoint(event)">
        <g id="savedPolygons"></g>
        <polygon id="draftPolygon"></polygon>
        <polyline id="draftLine"></polyline>
        <g id="draftVertices"></g>
      </svg>
    </div>
    <div class="controls">
      <button onclick="undoPoint()">Удалить точку</button>
      <button onclick="clearDraft()">Очистить</button>
      <button class="primary" onclick="saveZone()">Сохранить как мини-эталон</button>
      <button class="danger" onclick="clearAllZones()">Сбросить все зоны</button>
      <span id="draftStatus" class="muted">Нет точек</span>
    </div>
    <div class="result hidden" id="metrics"></div>
    <div class="visuals">
      <figure><figcaption>Heatmap</figcaption><img id="heatmapImage"></figure>
      <figure><figcaption>Diff</figcaption><img id="diffImage"></figure>
      <figure><figcaption>Маска</figcaption><img id="maskImage"></figure>
      <figure><figcaption>Эталон</figcaption><img id="referencePreview"></figure>
    </div>
  </section>

  <section class="panel">
    <h2>Сохранённые мини-эталоны</h2>
    <div id="zones" class="zones muted">Пока нет зон</div>
  </section>

<script>
  const productType = () => document.getElementById('productType').value.trim() || 'fp-test';
  let draft = [];
  let savedZones = [];
  let lastInspectionId = null;
  let lastHeatmapSize = {w: 1, h: 1};

  function setMessage(text, bad=false) {
    const el = document.getElementById('message');
    el.textContent = text;
    el.className = bad ? 'bad' : 'muted';
  }
  async function jsonResponse(response) {
    const payload = await response.json();
    if(!response.ok) {
      const detail = payload.detail;
      throw new Error(typeof detail === 'string' ? detail : JSON.stringify(detail || payload));
    }
    return payload;
  }
  function imageData(b64) { return b64 ? `data:image/png;base64,${b64}` : ''; }
  function svgPoints(points) { return points.map(p => `${p.x},${p.y}`).join(' '); }

  function renderDraft() {
    document.getElementById('draftPolygon').setAttribute('points', draft.length >= 3 ? svgPoints(draft) : '');
    document.getElementById('draftLine').setAttribute('points', draft.length >= 2 ? svgPoints(draft) : '');
    document.getElementById('draftVertices').innerHTML = draft.map(p => `<circle cx="${p.x}" cy="${p.y}" r="0.008"/>`).join('');
    const status = document.getElementById('draftStatus');
    status.textContent = draft.length === 0 ? 'Нет точек' : draft.length < 3 ? `Точек: ${draft.length} — нужно минимум 3` : `Зона: ${draft.length} точек`;
    status.className = draft.length > 0 && draft.length < 3 ? 'bad' : 'muted';
  }
  function renderSaved() {
    document.getElementById('savedPolygons').innerHTML = savedZones.map(z => {
      const pts = z.points_norm_ref || z.points_norm_heatmap || [];
      return pts.length >= 3 ? `<polygon class="saved" points="${svgPoints(pts)}"></polygon>` : '';
    }).join('');
  }
  function addPoint(event) {
    const image = document.getElementById('alignedImage');
    if(!image.src) { setMessage('Сначала выполните инспекцию.', true); return; }
    const rect = event.currentTarget.getBoundingClientRect();
    draft.push({
      x: Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width)),
      y: Math.min(1, Math.max(0, (event.clientY - rect.top) / rect.height)),
    });
    renderDraft();
  }
  function undoPoint() { draft = draft.slice(0, -1); renderDraft(); }
  function clearDraft() { draft = []; renderDraft(); }

  async function runInspection() {
    const reference = document.getElementById('referenceFile').files[0];
    const current = document.getElementById('currentFile').files[0];
    if(!reference || !current) { setMessage('Выберите эталон и текущий кадр.', true); return; }
    setMessage('Инспекция...');
    try {
      const refForm = new FormData();
      refForm.append('product_type', productType());
      refForm.append('file', reference);
      const refResult = await jsonResponse(await fetch('/upload-ref', {method:'POST', body:refForm}));
      document.getElementById('referencePreview').src = imageData(refResult.reference_b64);

      const inspectForm = new FormData();
      inspectForm.append('product_type', productType());
      inspectForm.append('file', current);
      inspectForm.append('threshold', document.getElementById('threshold').value);
      const result = await jsonResponse(await fetch('/inspect', {method:'POST', body:inspectForm}));
      showResult(result);
      await loadZones();
      setMessage(`Готово: ${result.status}, score ${Number(result.anomaly_score).toFixed(3)}. Если это ложняк — нажмите «Дообучить».`);
    } catch (error) {
      setMessage(error.message, true);
    }
  }

  function showResult(result) {
    lastInspectionId = result.inspection_id || null;
    document.getElementById('learnButton').disabled = !lastInspectionId;
    document.getElementById('alignedImage').src = imageData(result.aligned_image_b64);
    document.getElementById('heatmapImage').src = imageData(result.heatmap_b64);
    document.getElementById('diffImage').src = imageData(result.diff_map_b64);
    document.getElementById('maskImage').src = imageData(result.segmentation_mask_b64);
    const heatmap = document.getElementById('heatmapImage');
    heatmap.onload = () => { lastHeatmapSize = {w: heatmap.naturalWidth || 1, h: heatmap.naturalHeight || 1}; };
    const statusClass = result.status === 'БРАК' ? 'bad' : 'good';
    const zoneLines = (result.fp_zone_scores || []).map(z =>
      `<div class="metric">FP ${z.zone_id.slice(0,8)}<b class="${z.status === 'БРАК' ? 'bad' : 'good'}">${z.status}</b><div class="muted">${z.note || ''} · residual ${Number(z.residual_score).toFixed(3)} · ${z.applied_fp_etalon ? 'эталон применён' : (z.triggered_vs_reference ? 'сработала' : 'тихо')}</div></div>`
    ).join('');
    const metrics = document.getElementById('metrics');
    metrics.classList.remove('hidden');
    metrics.innerHTML = `
      <div class="metric">Вердикт<b class="${statusClass}">${result.status}</b></div>
      <div class="metric">Score<b>${Number(result.anomaly_score).toFixed(3)}</b></div>
      <div class="metric">Порог<b>${Number(result.threshold).toFixed(3)}</b></div>
      <div class="metric">Raw score<b>${Number(result.raw_anomaly_score).toFixed(3)}</b></div>
      ${zoneLines}`;
  }

  async function learnFalsePositive() {
    if(!lastInspectionId) { setMessage('Сначала выполните инспекцию.', true); return; }
    const button = document.getElementById('learnButton');
    button.disabled = true;
    setMessage('Дообучение: ищу контуры и сохраняю мини-эталон с отступом...');
    try {
      const payload = await jsonResponse(await fetch(`/learning/reviews/${encodeURIComponent(lastInspectionId)}/accept-all-as-normal`, {
        method:'POST',
        headers:{'Content-Type':'application/json'},
        body: JSON.stringify({note:'auto FP mini-etalon'}),
      }));
      await loadZones();
      const zones = Number(payload.fp_zones_count || 0);
      const preview = payload.counterfactual_status
        ? ` Пересчёт: ${payload.counterfactual_status}, score ${Number(payload.counterfactual_score).toFixed(3)}.`
        : '';
      setMessage(`Дообучено. Мини-эталонов: ${zones}, контуров: ${Number(payload.accepted_count || 0)}.${preview} Запустите инспекцию повторно.`);
    } catch (error) {
      document.getElementById('learnButton').disabled = !lastInspectionId;
      setMessage(error.message, true);
    }
  }

  async function saveZone() {
    if(draft.length < 3) { setMessage('Нужно минимум 3 точки.', true); return; }
    const aligned = document.getElementById('alignedImage');
    const w = aligned.naturalWidth || lastHeatmapSize.w;
    const h = aligned.naturalHeight || lastHeatmapSize.h;
    setMessage('Сохраняю мини-эталон...');
    try {
      const zone = await jsonResponse(await fetch('/fp-zones', {
        method:'POST',
        headers:{'Content-Type':'application/json'},
        body: JSON.stringify({
          product_type: productType(),
          points: draft,
          heatmap_w: w,
          heatmap_h: h,
          note: 'mini-etalon from test UI',
        }),
      }));
      draft = [];
      renderDraft();
      await loadZones();
      setMessage(`Зона сохранена: ${zone.id.slice(0,8)}… ${zone.has_crop ? 'кроп есть' : 'кропа нет'}`);
    } catch (error) {
      setMessage(error.message, true);
    }
  }

  async function loadZones() {
    try {
      const payload = await jsonResponse(await fetch(`/fp-zones/${encodeURIComponent(productType())}`));
      savedZones = payload.zones || [];
      renderSaved();
      const root = document.getElementById('zones');
      if(!savedZones.length) { root.className = 'zones muted'; root.textContent = 'Пока нет зон'; return; }
      root.className = 'zones';
      root.innerHTML = savedZones.map(z => `
        <div class="zone">
          <img src="/fp-zones/${z.id}/crop" alt="кроп">
          <div>
            <div>${z.id.slice(0,8)}… ${z.has_crop ? 'мини-эталон' : 'без кропа'}</div>
            <div class="muted">${z.note || ''} · ${z.created_at || ''}</div>
            <button class="danger" onclick="deleteZone('${z.id}')">Удалить</button>
          </div>
        </div>`).join('');
    } catch (error) {
      setMessage(error.message, true);
    }
  }

  async function deleteZone(id) {
    await jsonResponse(await fetch(`/fp-zones/${id}`, {method:'DELETE'}));
    await loadZones();
  }
  async function clearAllZones() {
    await jsonResponse(await fetch('/fp-zones', {method:'DELETE'}));
    await loadZones();
    setMessage('Все FP-зоны удалены');
  }

  document.getElementById('productType').addEventListener('change', loadZones);
  renderDraft();
  loadZones();
</script>
</body>
</html>
"""
