"""Простой браузерный стенд для ручной проверки детектора по двум файлам."""

from fastapi import APIRouter
from fastapi.responses import HTMLResponse


router = APIRouter()


@router.get("/local-inspection-test", response_class=HTMLResponse, include_in_schema=False)
async def local_inspection_test_page() -> HTMLResponse:
    return HTMLResponse(
        LOCAL_INSPECTION_TEST_HTML,
        headers={"Cache-Control": "no-store, no-cache, must-revalidate"},
    )


LOCAL_INSPECTION_TEST_HTML = r"""<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>Локальный тест инспекции</title>
  <style>
    :root { color-scheme:dark; font-family:Inter,Segoe UI,sans-serif; background:#0f151d; color:#edf2f8; }
    * { box-sizing:border-box; }
    body { margin:0; padding:22px; }
    h1,h2,h3 { margin:0; }
    header { margin-bottom:18px; }
    .muted { color:#9eabbc; }
    .warning { margin-top:10px; padding:10px 12px; background:#332d1d; border:1px solid #66552b; border-radius:9px; color:#ffe19a; }
    .panel { background:#17212d; border:1px solid #2d3b4f; border-radius:12px; padding:16px; margin-bottom:16px; }
    .form-grid { display:grid; grid-template-columns:1fr 150px 1fr 1fr auto; gap:12px; align-items:end; }
    label { display:flex; flex-direction:column; gap:6px; color:#b7c2d0; font-size:13px; }
    input { width:100%; border:1px solid #3a4b62; border-radius:8px; background:#0e151f; color:#fff; padding:9px; }
    input[type=file] { padding:7px; }
    button { border:1px solid #40628b; background:#285181; color:#fff; border-radius:8px; padding:10px 14px; cursor:pointer; }
    button:hover { background:#32659f; }
    button:disabled { opacity:.5; cursor:not-allowed; }
    button.danger { border-color:#7e3d46; background:#582a32; }
    button.danger:hover { background:#723640; }
    button.primary { border-color:#2f6f4a; background:#1f5136; font-weight:700; }
    button.primary:hover { background:#276544; }
    .previews,.visuals { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:12px; margin-top:14px; }
    .visuals { grid-template-columns:repeat(4,minmax(0,1fr)); }
    figure { margin:0; background:#0a0e14; border:1px solid #29384b; border-radius:10px; overflow:hidden; }
    figcaption { padding:8px 10px; color:#b8c4d2; border-bottom:1px solid #29384b; }
    figure img { display:block; width:100%; min-height:120px; max-height:360px; object-fit:contain; background:#070a0e; }
    .roi-editor { position:relative; width:100%; background:#070a0e; }
    .roi-editor img { width:100%; height:auto; min-height:0; max-height:none; object-fit:initial; user-select:none; }
    .roi-overlay { position:absolute; inset:0; width:100%; height:100%; cursor:crosshair; }
    .roi-overlay polygon { fill:rgba(65,148,255,.20); stroke:#55a4ff; stroke-width:.005; vector-effect:non-scaling-stroke; pointer-events:none; }
    .roi-overlay polyline { fill:none; stroke:#76b6ff; stroke-width:.004; vector-effect:non-scaling-stroke; pointer-events:none; }
    .roi-overlay circle { fill:#ffe073; stroke:#152131; stroke-width:.003; vector-effect:non-scaling-stroke; pointer-events:none; }
    .roi-controls { display:flex; gap:8px; align-items:center; flex-wrap:wrap; padding:9px 10px; border-top:1px solid #29384b; }
    .roi-controls button { padding:7px 10px; }
    .roi-help { padding:0 10px 10px; color:#9eabbc; font-size:12px; }
    .result { display:grid; grid-template-columns:repeat(auto-fit,minmax(145px,1fr)); gap:10px; margin-top:12px; }
    .metric { padding:11px; border-radius:9px; background:#101923; border:1px solid #2d3c51; }
    .metric b { display:block; margin-top:4px; font-size:20px; }
    .good { color:#50dc91; }
    .bad { color:#ff7777; }
    .hidden { display:none !important; }
    .review-grid { display:grid; grid-template-columns:minmax(0,2fr) minmax(270px,1fr); gap:14px; margin-top:12px; }
    .viewer { position:relative; min-width:0; background:#070a0e; border-radius:10px; overflow:hidden; }
    .viewer img { display:block; width:100%; height:auto; }
    .viewer svg { position:absolute; inset:0; width:100%; height:100%; }
    polygon { fill:rgba(255,65,65,.24); stroke:#ff5252; stroke-width:.004; vector-effect:non-scaling-stroke; pointer-events:none; }
    polygon.learned { fill:rgba(52,220,129,.28); stroke:#45df91; }
    .defect { border:1px solid #34465d; border-radius:9px; padding:10px; margin-bottom:8px; }
    .defect.learned { border-color:#45bd7e; }
    .row { display:flex; gap:8px; align-items:center; flex-wrap:wrap; }
    .history-strip { display:flex; gap:10px; overflow-x:auto; padding:10px 0 4px; }
    .history-item { flex:0 0 156px; margin:0; padding:0; background:#111b25; border:1px solid #2d3b4f; border-radius:10px; overflow:hidden; cursor:pointer; color:inherit; text-align:left; }
    .history-item:hover { border-color:#5687c5; }
    .history-item.active { border-color:#ffc441; box-shadow:0 0 0 1px #ffc441; }
    .history-item.learned { border-color:#45bd7e; }
    .history-item.pass-status { border-color:#2f6f4a; }
    .history-item img { width:100%; height:86px; object-fit:cover; display:block; background:#070a0e; }
    .history-item div { padding:8px; font-size:12px; }
    .norms { display:grid; grid-template-columns:repeat(auto-fill,minmax(280px,1fr)); gap:10px; margin-top:12px; }
    .normal { display:grid; grid-template-columns:86px minmax(0,1fr); gap:10px; align-items:center; background:#111b25; border:1px solid #31513f; border-radius:10px; padding:9px; }
    .normal img { width:86px; height:86px; object-fit:cover; border-radius:7px; background:#070a0e; }
    .normal div { min-width:0; overflow-wrap:anywhere; }
    .normal button { margin-top:7px; padding:7px 10px; }
    #message { margin-top:10px; min-height:20px; }
    @media(max-width:1050px) { .form-grid { grid-template-columns:1fr 140px; } .visuals { grid-template-columns:repeat(2,1fr); } }
    @media(max-width:720px) { body { padding:12px; } .form-grid,.previews,.visuals,.review-grid { grid-template-columns:1fr; } }
  </style>
</head>
<body>
  <header>
    <h1>Локальный тест инспекции</h1>
    <div class="muted">Эталон → текущий кадр → история инспекций (ГОДЕН и БРАК). Можно вернуться к кадру и пометить ложняк. После перезапуска Python история сбрасывается. <a href="/fp-zone-test" style="color:#7eb6ff">Тест FP-зон (мини-эталон)</a></div>
    <div class="warning">Используйте отдельный тип <b>local-test</b>. Если указать реальный тип изделия/камеры, сохранённая норма сможет повлиять на производственные инспекции этого типа.</div>
  </header>

  <section class="panel">
    <div class="form-grid">
      <label>Тип теста<input id="productType" value="local-test" autocomplete="off"></label>
      <label>Порог<input id="threshold" type="number" value="0.25" min="0" max="1" step="0.01"></label>
      <label>Эталон<input id="referenceFile" type="file" accept="image/*"></label>
      <label>Текущий кадр<input id="currentFile" type="file" accept="image/*"></label>
      <button id="runButton" onclick="runInspection()">Запустить проверку</button>
    </div>
    <div class="previews">
      <figure>
        <figcaption>Эталон и область инспекции</figcaption>
        <div class="roi-editor">
          <img id="referencePreview" alt="Эталон">
          <svg id="roiOverlay" class="roi-overlay" viewBox="0 0 1 1" preserveAspectRatio="none" onclick="addRoiPoint(event)">
            <polygon id="roiPolygon"></polygon>
            <polyline id="roiPolyline"></polyline>
            <g id="roiVertices"></g>
          </svg>
        </div>
        <div class="roi-controls">
          <button onclick="undoRoiPoint()">Удалить точку</button>
          <button onclick="clearRoi()">Очистить</button>
          <button onclick="useFullFrameRoi()">Весь кадр</button>
          <span id="roiStatus" class="muted">Весь кадр</span>
        </div>
        <div class="roi-help">Кликайте по эталону, чтобы поставить минимум 3 вершины. При проверке область замыкается автоматически.</div>
      </figure>
      <figure><figcaption>Выбранный текущий кадр</figcaption><img id="currentPreview" alt="Текущий кадр"></figure>
    </div>
    <div id="message" class="muted"></div>
  </section>

  <section class="panel">
    <div class="row">
      <h2>История инспекций</h2>
      <button type="button" onclick="shiftHistory(1)">Предыдущий кадр</button>
      <button type="button" onclick="shiftHistory(-1)">Следующий кадр</button>
      <button type="button" onclick="loadHistory()">Обновить</button>
    </div>
    <div class="muted">Последние кадры этой сессии — и хорошие, и брак (до 50). Нажмите кадр, чтобы вернуться. Ложное срабатывание можно пометить, если на кадре есть контуры.</div>
    <div id="frameHistory" class="history-strip"></div>
  </section>

  <section id="resultPanel" class="panel hidden">
    <h2>Результат</h2>
    <div id="resultMetrics" class="result"></div>
    <div class="visuals">
      <figure><figcaption>Текущий кадр без выравнивания</figcaption><img id="alignedImage"></figure>
      <figure><figcaption>Heatmap</figcaption><img id="heatmapImage"></figure>
      <figure><figcaption>Diff</figcaption><img id="diffImage"></figure>
      <figure><figcaption>Итоговая маска</figcaption><img id="maskImage"></figure>
    </div>
  </section>

  <section id="defectPanel" class="panel hidden">
    <h2>Дефекты этого кадра</h2>
    <div class="muted">Откройте кадр из истории. «Дообучить этот БРАК» запоминает контуры как ложные срабатывания. Если на кадре был и настоящий дефект — выучит оба.</div>
    <div class="review-grid">
      <div class="viewer"><img id="reviewImage"><svg id="overlay" viewBox="0 0 1 1" preserveAspectRatio="none"></svg></div>
      <div>
        <div id="defectList"></div>
        <label>Комментарий<input id="normalNote" placeholder="Например: блик этикетки"></label>
        <button class="primary" id="acceptAllButton" onclick="acceptAll()">Дообучить этот БРАК</button>
        <button id="acceptButton" disabled onclick="acceptAll()">Считать все дефекты нормой</button>
        <div id="acceptStatus" class="muted" style="margin-top:8px"></div>
      </div>
    </div>
  </section>

  <section class="panel">
    <div class="row"><h2>Сохранённые нормы теста</h2><button onclick="loadAcceptedCases()">Обновить список</button><button class="danger" onclick="clearAllCases()">Сбросить все нормы</button></div>
    <div id="acceptedCases" class="norms"></div>
  </section>

<script>
  let currentReview = null;
  let historyItems = [];
  let roiPoints = [];
  const fullFrameRoi = [{x:0,y:0},{x:1,y:0},{x:1,y:1},{x:0,y:1}];
  const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const productType = () => document.getElementById('productType').value.trim() || 'local-test';
  const setMessage = (text, error=false) => { const node=document.getElementById('message'); node.textContent=text; node.className=error?'bad':'muted'; };

  function bindPreview(inputId, imageId) {
    document.getElementById(inputId).addEventListener('change', event => {
      const file = event.target.files[0];
      if(file) document.getElementById(imageId).src = URL.createObjectURL(file);
    });
  }
  bindPreview('referenceFile','referencePreview');
  bindPreview('currentFile','currentPreview');
  document.getElementById('referenceFile').addEventListener('change', clearRoi);
  document.getElementById('productType').addEventListener('change', () => { clearRoi(); loadAcceptedCases(); loadHistory(); });

  const roiSvgPoints = points => points.map(point => `${point.x},${point.y}`).join(' ');
  function renderRoi() {
    const polygon = document.getElementById('roiPolygon');
    const polyline = document.getElementById('roiPolyline');
    polygon.setAttribute('points', roiPoints.length >= 3 ? roiSvgPoints(roiPoints) : '');
    polyline.setAttribute('points', roiPoints.length >= 2 ? roiSvgPoints(roiPoints) : '');
    document.getElementById('roiVertices').innerHTML = roiPoints.map(
      point => `<circle cx="${point.x}" cy="${point.y}" r="0.009"></circle>`
    ).join('');
    const status = document.getElementById('roiStatus');
    status.textContent = roiPoints.length === 0
      ? 'Весь кадр'
      : roiPoints.length < 3
        ? `Точек: ${roiPoints.length} — нужно минимум 3`
        : `ROI: ${roiPoints.length} точек`;
    status.className = roiPoints.length > 0 && roiPoints.length < 3 ? 'bad' : 'muted';
  }
  function addRoiPoint(event) {
    const image = document.getElementById('referencePreview');
    if(!image.src) { setMessage('Сначала выберите эталон.', true); return; }
    const rect = event.currentTarget.getBoundingClientRect();
    if(rect.width <= 0 || rect.height <= 0) return;
    roiPoints.push({
      x:Math.max(0, Math.min(1, (event.clientX - rect.left) / rect.width)),
      y:Math.max(0, Math.min(1, (event.clientY - rect.top) / rect.height)),
    });
    renderRoi();
  }
  function undoRoiPoint() { roiPoints = roiPoints.slice(0, -1); renderRoi(); }
  function clearRoi() { roiPoints = []; renderRoi(); }
  function useFullFrameRoi() { roiPoints = fullFrameRoi.map(point => ({...point})); renderRoi(); }

  async function jsonResponse(response) {
    const payload = await response.json().catch(() => ({}));
    if(!response.ok) throw new Error(payload.detail || `HTTP ${response.status}`);
    return payload;
  }

  async function runInspection() {
    const reference = document.getElementById('referenceFile').files[0];
    const current = document.getElementById('currentFile').files[0];
    if(!reference || !current) { setMessage('Выберите оба изображения: эталон и текущий кадр.', true); return; }
    if(roiPoints.length > 0 && roiPoints.length < 3) { setMessage('Для области инспекции нужно минимум 3 точки либо нажмите «Очистить» для проверки всего кадра.', true); return; }
    const button = document.getElementById('runButton');
    button.disabled = true;
    document.getElementById('resultPanel').classList.add('hidden');
    document.getElementById('defectPanel').classList.add('hidden');
    setMessage('Загрузка эталона...');
    const started = performance.now();
    try {
      const refForm = new FormData();
      refForm.append('product_type', productType());
      refForm.append('file', reference);
      await jsonResponse(await fetch('/upload-ref', {method:'POST', body:refForm}));

      setMessage('Сохранение области инспекции...');
      const effectiveRoi = roiPoints.length >= 3 ? roiPoints : fullFrameRoi;
      await jsonResponse(await fetch('/roi-polygon', {
        method:'POST',
        headers:{'Content-Type':'application/json'},
        body:JSON.stringify({product_type:productType(), points:effectiveRoi}),
      }));

      setMessage('Выполняется инспекция...');
      const inspectForm = new FormData();
      inspectForm.append('product_type', productType());
      inspectForm.append('file', current);
      const threshold = document.getElementById('threshold').value;
      if(threshold !== '') inspectForm.append('threshold', threshold);
      const result = await jsonResponse(await fetch('/inspect', {method:'POST', body:inspectForm}));
      renderResult(result, performance.now() - started);
      await loadHistory();
      if(result.inspection_id) await openHistoryFrame(result.inspection_id);
      else {
        currentReview = null;
        highlightHistory();
      }
      await loadAcceptedCases();
      setMessage('Проверка завершена. Кадр попал в историю инспекций — можно вернуться к ГОДЕН или БРАК.');
    } catch(error) {
      setMessage(`Ошибка: ${error.message || error}`, true);
    } finally {
      button.disabled = false;
    }
  }

  function imageData(value) { return value ? `data:image/png;base64,${value}` : ''; }
  function renderResult(result, elapsedMs) {
    const statusClass = result.status === 'ГОДЕН' ? 'good' : 'bad';
    document.getElementById('resultMetrics').innerHTML = `
      <div class="metric">Статус<b class="${statusClass}">${esc(result.status)}</b></div>
      <div class="metric">Итоговый score<b>${Number(result.anomaly_score).toFixed(4)}</b></div>
      <div class="metric">Исходный score<b>${Number(result.raw_anomaly_score).toFixed(4)}</b></div>
      <div class="metric">Порог<b>${Number(result.threshold).toFixed(4)}</b></div>
      <div class="metric">Область анализа<b>${roiPoints.length >= 3 ? `ROI · ${roiPoints.length} точек` : 'Весь кадр'}</b></div>
      <div class="metric">Исключено областей<b>${Number(result.learned_normal_matches_count || 0)}</b></div>
      <div class="metric">Вычтено из score<b>${Number(result.learned_normal_adjustment || 0).toFixed(4)}</b></div>
      <div class="metric">Время HTTP-проверки<b>${elapsedMs.toFixed(1)} мс</b></div>`;
    document.getElementById('alignedImage').src = imageData(result.aligned_image_b64);
    document.getElementById('heatmapImage').src = imageData(result.heatmap_b64);
    document.getElementById('diffImage').src = imageData(result.diff_map_b64);
    document.getElementById('maskImage').src = imageData(result.segmentation_mask_b64);
    document.getElementById('resultPanel').classList.remove('hidden');
  }

  async function loadReview(inspectionId) {
    currentReview = await jsonResponse(await fetch(`/learning/reviews/${encodeURIComponent(inspectionId)}`, {cache:'no-store'}));
    document.getElementById('normalNote').value = '';
    document.getElementById('acceptStatus').textContent = '';
    renderReview();
    document.getElementById('defectPanel').classList.remove('hidden');
  }

  async function openHistoryFrame(inspectionId) {
    await loadReview(inspectionId);
    const review = currentReview;
    const statusClass = review.original_status === 'ГОДЕН' ? 'good' : 'bad';
    const stamp = Date.now();
    const imageUrl = kind => `/learning/reviews/${encodeURIComponent(review.inspection_id)}/image/${kind}?v=${stamp}`;
    document.getElementById('resultMetrics').innerHTML = `
      <div class="metric">Статус<b class="${statusClass}">${esc(review.original_status)}</b></div>
      <div class="metric">Score кадра<b>${Number(review.original_score).toFixed(4)}</b></div>
      <div class="metric">Порог<b>${Number(review.threshold).toFixed(4)}</b></div>
      <div class="metric">Областей<b>${Number(review.defects.length)}</b></div>
      <div class="metric">Уже сохранено<b>${Number(review.accepted_defects_count || 0)}</b></div>
      <div class="metric">Кадр из истории<b>${esc(String(review.inspection_id).slice(0, 8))}</b></div>`;
    document.getElementById('alignedImage').src = imageUrl('aligned');
    document.getElementById('heatmapImage').src = imageUrl('heatmap');
    document.getElementById('diffImage').src = imageUrl('diff');
    document.getElementById('maskImage').src = imageUrl('mask');
    document.getElementById('resultPanel').classList.remove('hidden');
    highlightHistory();
  }

  async function loadHistory() {
    const root = document.getElementById('frameHistory');
    root.innerHTML = '<div class="muted">Загрузка истории...</div>';
    try {
      const query = new URLSearchParams({product_type: productType()});
      const payload = await jsonResponse(await fetch(`/learning/reviews?${query}`, {cache:'no-store'}));
      historyItems = payload.reviews || [];
      if(!historyItems.length) {
        root.innerHTML = '<div class="muted">История пуста. После проверки кадр появится здесь.</div>';
        return;
      }
      root.innerHTML = historyItems.map(item => {
        const isPass = item.original_status === 'ГОДЕН';
        const statusClass = isPass ? 'good' : 'bad';
        const hint = item.accepted_defects_count ? 'уже помечен' : (item.defects_count ? 'можно пометить' : 'дефектов нет');
        return `
        <button type="button" class="history-item${item.accepted_defects_count ? ' learned' : ''}${isPass ? ' pass-status' : ''}" data-inspection-id="${esc(item.inspection_id)}">
          <img src="/learning/reviews/${encodeURIComponent(item.inspection_id)}/image/${isPass ? 'aligned' : 'heatmap'}" alt="${esc(item.original_status)}" loading="lazy">
          <div><span class="${statusClass}">${esc(item.original_status)}</span> · ${Number(item.original_score).toFixed(3)}<br>
          <span class="muted">${hint} · областей ${item.defects_count}</span></div>
        </button>`;
      }).join('');
      root.querySelectorAll('.history-item').forEach(node => {
        node.addEventListener('click', () => openHistoryFrame(node.dataset.inspectionId));
      });
      highlightHistory();
    } catch(error) {
      root.innerHTML = `<div class="bad">${esc(error.message || error)}</div>`;
    }
  }

  function highlightHistory() {
    const currentId = currentReview && currentReview.inspection_id;
    document.querySelectorAll('#frameHistory .history-item').forEach(node => {
      node.classList.toggle('active', node.dataset.inspectionId === currentId);
    });
  }

  function shiftHistory(delta) {
    if(!historyItems.length) return;
    const currentId = currentReview && currentReview.inspection_id;
    const index = historyItems.findIndex(item => item.inspection_id === currentId);
    const nextIndex = index < 0 ? 0 : index + delta;
    if(nextIndex < 0 || nextIndex >= historyItems.length) return;
    openHistoryFrame(historyItems[nextIndex].inspection_id);
  }

  document.addEventListener('keydown', event => {
    if(event.target.matches('input, textarea')) return;
    if(event.key === 'ArrowLeft') { event.preventDefault(); shiftHistory(1); }
    if(event.key === 'ArrowRight') { event.preventDefault(); shiftHistory(-1); }
  });

  function polygonPoints(defect) { return (defect.polygon || []).map(point => `${point.x},${point.y}`).join(' '); }
  function defectState(item) {
    if(item.manually_accepted) return `СОХРАНЁН КАК НОРМА · ID ${String(item.matched_case_id || '').slice(0, 8)}`;
    if(item.accepted_as_normal) return `РАСПОЗНАН КАК НОРМА · ID ${String(item.matched_case_id || '').slice(0, 8)}`;
    return 'БУДЕТ СОХРАНЁН ПО КНОПКЕ · влияет на score';
  }
  function renderReview() {
    if(!currentReview) return;
    document.getElementById('reviewImage').src = `/learning/reviews/${encodeURIComponent(currentReview.inspection_id)}/image/aligned?v=${Date.now()}`;
    document.getElementById('overlay').innerHTML = currentReview.defects.map(item => `
      <polygon class="${item.manually_accepted || item.accepted_as_normal ? 'learned' : ''}" points="${polygonPoints(item)}"><title>${esc(item.id)}</title></polygon>`).join('');
    const list = document.getElementById('defectList');
    list.innerHTML = currentReview.defects.length
      ? currentReview.defects.map(item => `
      <div class="defect ${item.manually_accepted || item.accepted_as_normal?'learned':''}">
        <b>${esc(item.id)}</b> · ${esc(defectState(item))}<br>
        <span class="muted">score ${Number(item.score).toFixed(3)} · площадь ${Number(item.area)} · q90 ${Number(item.diff_q90).toFixed(1)}</span>
      </div>`).join('')
      : '<div class="muted">На этом кадре нет контуров для дообучения.</div>';
    const pending = currentReview.defects.filter(item => !item.manually_accepted && !item.accepted_as_normal);
    document.getElementById('acceptButton').disabled = pending.length === 0;
    const acceptAllButton = document.getElementById('acceptAllButton');
    if(acceptAllButton) acceptAllButton.disabled = pending.length === 0;
  }

  async function acceptAll() {
    if(!currentReview) return;
    const buttons = [document.getElementById('acceptAllButton'), document.getElementById('acceptButton')];
    buttons.forEach(button => { if(button) button.disabled = true; });
    document.getElementById('acceptStatus').textContent = 'Сохранение всех контуров...';
    try {
      const response = await fetch(`/learning/reviews/${encodeURIComponent(currentReview.inspection_id)}/accept-all-as-normal`, {
        method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({note:document.getElementById('normalNote').value})
      });
      const payload = await jsonResponse(response);
      const preview = payload.counterfactual_status
        ? ` Ознакомительный пересчёт: ${payload.counterfactual_status}, score ${Number(payload.counterfactual_score).toFixed(4)}.` : '';
      document.getElementById('acceptStatus').textContent = `Запомнено контуров: ${Number(payload.accepted_count)}.${preview} Запустите проверку повторно.`;
      currentReview = await jsonResponse(await fetch(`/learning/reviews/${encodeURIComponent(currentReview.inspection_id)}`, {cache:'no-store'}));
      renderReview();
      highlightHistory();
      await loadAcceptedCases();
      await loadHistory();
    } catch(error) {
      document.getElementById('acceptStatus').textContent = `Ошибка: ${error.message || error}`;
      renderReview();
    }
  }

  async function loadAcceptedCases() {
    const root = document.getElementById('acceptedCases');
    root.innerHTML = '<div class="muted">Загрузка...</div>';
    try {
      const query = new URLSearchParams({product_type:productType()});
      const payload = await jsonResponse(await fetch(`/learning/accepted-cases?${query}`, {cache:'no-store'}));
      if(!payload.cases.length) { root.innerHTML='<div class="muted">Для этого типа сохранённых норм пока нет.</div>'; return; }
      root.innerHTML = payload.cases.map(item => `
        <article class="normal">
          <img src="/learning/accepted-cases/${encodeURIComponent(item.id)}/image?v=${Date.now()}" alt="Норма">
          <div><b>${esc(item.source_defect_id)} · СОХРАНЁН КАК НОРМА</b><br><span class="muted">ID: ${esc(item.id)}<br>площадь ${Number(item.area)} · q90 ${Number(item.diff_q90).toFixed(1)}<br>${esc(item.created_at)}</span>${item.note?`<br>${esc(item.note)}`:''}<br><button class="danger delete-normal" data-case-id="${esc(item.id)}">Удалить</button></div>
        </article>`).join('');
      root.querySelectorAll('.delete-normal').forEach(button => button.addEventListener('click', () => deleteCase(button.dataset.caseId)));
    } catch(error) { root.innerHTML=`<div class="bad">${esc(error.message || error)}</div>`; }
  }

  async function deleteCase(caseId) {
    if(!confirm('Удалить этот фрагмент из нормы? Следующая проверка снова будет учитывать его как дефект.')) return;
    try {
      await jsonResponse(await fetch(`/learning/accepted-cases/${encodeURIComponent(caseId)}`, {method:'DELETE'}));
      await loadAcceptedCases();
      setMessage('Фрагмент удалён. Запустите проверку повторно, чтобы увидеть результат без исключения.');
    } catch(error) { setMessage(`Ошибка удаления: ${error.message || error}`, true); }
  }

  async function clearAllCases() {
    if(!confirm('Сбросить все выученные ложняки? Следующая проверка снова будет считать эти следы браком.')) return;
    try {
      const payload = await jsonResponse(await fetch('/learning/accepted-cases', {method:'DELETE'}));
      await loadAcceptedCases();
      if(currentReview) {
        currentReview = await jsonResponse(await fetch(`/learning/reviews/${encodeURIComponent(currentReview.inspection_id)}`, {cache:'no-store'}));
        renderReview();
      }
      setMessage(`Сброшено норм: ${Number(payload.cases_count)}. Запустите проверку повторно.`);
    } catch(error) { setMessage(`Ошибка сброса: ${error.message || error}`, true); }
  }

  loadAcceptedCases();
  loadHistory();
  renderRoi();
</script>
</body>
</html>"""
