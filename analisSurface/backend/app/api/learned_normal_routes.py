"""Операторский review и обучение на допустимых фрагментах.

Все POST-действия здесь являются постфактум-разметкой. Они не публикуют новое
решение в оркестратор и не меняют результат уже прошедшего изделия.
"""

from typing import Optional

from fastapi import APIRouter, HTTPException, Query
from fastapi.responses import HTMLResponse, Response
from pydantic import BaseModel

from app.api.dependencies import inspection_service


router = APIRouter()


class AcceptDefectAsNormalRequest(BaseModel):
    note: str = ""


@router.get("/learning/reviews")
async def list_learning_reviews(product_type: Optional[str] = Query(None)) -> dict:
    return {"reviews": inspection_service.list_learning_reviews(product_type=product_type)}


@router.get("/learning/reviews/{inspection_id}")
async def get_learning_review(inspection_id: str) -> dict:
    review = inspection_service.get_learning_review(inspection_id)
    if review is None:
        raise HTTPException(status_code=404, detail="Inspection review not found or expired")
    return review


@router.get("/learning/reviews/{inspection_id}/image/{kind}")
async def get_learning_review_image(inspection_id: str, kind: str) -> Response:
    image = inspection_service.get_learning_review_image(inspection_id, kind)
    if image is None:
        raise HTTPException(status_code=404, detail="Review image not found")
    content, media_type = image
    return Response(content=content, media_type=media_type, headers={"Cache-Control": "no-store"})


@router.post("/learning/reviews/{inspection_id}/defects/{defect_id}/accept-as-normal")
async def accept_defect_as_normal(
    inspection_id: str,
    defect_id: str,
    payload: AcceptDefectAsNormalRequest,
) -> dict:
    try:
        return inspection_service.accept_review_defect_as_normal(
            inspection_id=inspection_id,
            defect_id=defect_id,
            note=payload.note,
        )
    except KeyError as exc:
        label = str(exc.args[0]) if exc.args else "item"
        raise HTTPException(status_code=404, detail=f"Learning {label} not found") from exc
    except ValueError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc


@router.get("/learning/accepted-cases")
async def list_accepted_cases(product_type: Optional[str] = Query(None)) -> dict:
    return {"cases": inspection_service.list_accepted_normal_cases(product_type=product_type)}


@router.get("/learning/accepted-cases/{case_id}/image")
async def get_accepted_case_image(case_id: str) -> Response:
    image = inspection_service.get_accepted_normal_case_image(case_id)
    if image is None:
        raise HTTPException(status_code=404, detail="Accepted-normal case not found")
    content, media_type = image
    return Response(content=content, media_type=media_type, headers={"Cache-Control": "no-store"})


@router.delete("/learning/accepted-cases/{case_id}")
async def delete_accepted_case(case_id: str) -> dict:
    if not inspection_service.delete_accepted_normal_case(case_id):
        raise HTTPException(status_code=404, detail="Accepted-normal case not found")
    return {"deleted": True, "case_id": case_id}


@router.get("/learning-review", response_class=HTMLResponse, include_in_schema=False)
async def learning_review_page() -> HTMLResponse:
    return HTMLResponse(LEARNING_REVIEW_HTML)


LEARNING_REVIEW_HTML = r"""<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>Обучение допустимым фрагментам</title>
  <style>
    :root { color-scheme: dark; font-family: Inter, Segoe UI, sans-serif; background:#10151d; color:#e9eef5; }
    * { box-sizing:border-box; }
    body { margin:0; padding:24px; }
    header { display:flex; gap:16px; align-items:center; justify-content:space-between; margin-bottom:18px; }
    h1,h2 { margin:0; }
    .muted { color:#9eabbc; }
    button { border:1px solid #3d4d62; background:#253247; color:#fff; border-radius:8px; padding:9px 13px; cursor:pointer; }
    button:hover { background:#31425d; }
    button:disabled { opacity:.45; cursor:not-allowed; }
    .grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(250px,1fr)); gap:14px; }
    .card { background:#17202c; border:1px solid #2a394d; border-radius:12px; overflow:hidden; cursor:pointer; }
    .card:hover { border-color:#5687c5; }
    .card img { width:100%; aspect-ratio:16/10; object-fit:cover; display:block; background:#090d12; }
    .card div { padding:11px; }
    .bad { color:#ff7777; font-weight:700; }
    dialog { width:min(1180px,96vw); height:min(850px,94vh); border:1px solid #41516a; border-radius:14px; background:#121a25; color:#eef3f8; padding:0; }
    dialog::backdrop { background:rgba(0,0,0,.72); }
    .modal-head { display:flex; align-items:center; justify-content:space-between; padding:15px 18px; border-bottom:1px solid #2b394c; }
    .modal-body { display:grid; grid-template-columns:minmax(0,2fr) minmax(300px,1fr); gap:16px; padding:16px; height:calc(100% - 64px); }
    .viewer-column { min-width:0; overflow:auto; }
    .viewer { position:relative; width:100%; background:#080b10; border-radius:10px; overflow:hidden; }
    .viewer img { width:100%; height:auto; display:block; }
    .viewer svg { position:absolute; inset:0; width:100%; height:100%; }
    polygon { fill:rgba(255,62,62,.22); stroke:#ff4f4f; stroke-width:.003; vector-effect:non-scaling-stroke; cursor:pointer; }
    polygon.learned { fill:rgba(40,205,115,.24); stroke:#37e58d; }
    polygon.selected { fill:rgba(255,190,48,.30); stroke:#ffc441; stroke-width:.006; }
    .side { overflow:auto; display:flex; flex-direction:column; gap:12px; }
    .defect { border:1px solid #314159; border-radius:9px; padding:10px; background:#192332; cursor:pointer; }
    .defect.selected { border-color:#ffc441; }
    .defect.learned { border-color:#37b879; }
    .metrics { display:grid; grid-template-columns:1fr 1fr; gap:5px; font-size:13px; color:#b8c3d1; margin-top:7px; }
    .actions { display:flex; gap:8px; flex-wrap:wrap; }
    input { width:100%; border:1px solid #34465e; border-radius:8px; background:#0e151f; color:#fff; padding:9px; }
    .notice { border-radius:8px; background:#202c3c; padding:10px; font-size:13px; }
    .counterfactual { color:#ffd16a; }
    .section { margin-top:26px; }
    .section-head { display:flex; align-items:end; justify-content:space-between; gap:14px; margin-bottom:12px; }
    .normal-grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(290px,1fr)); gap:12px; }
    .normal-card { display:grid; grid-template-columns:96px minmax(0,1fr); gap:12px; align-items:center; background:#17202c; border:1px solid #31513f; border-radius:12px; padding:10px; }
    .normal-card img { width:96px; height:96px; object-fit:cover; image-rendering:auto; border-radius:8px; background:#090d12; }
    .normal-info { min-width:0; }
    .normal-info b, .normal-info span { display:block; overflow-wrap:anywhere; }
    .normal-note { color:#d6deea; margin:5px 0; }
    .danger { margin-top:8px; border-color:#7d3d46; background:#562a31; }
    .danger:hover { background:#71343e; }
    @media(max-width:800px) { .modal-body { grid-template-columns:1fr; overflow:auto; } dialog { height:96vh; } .side { overflow:visible; } }
  </style>
</head>
<body>
  <header>
    <div><h1>Обучение допустимым фрагментам</h1><div class="muted">Недавние изделия со статусом БРАК. Решение уже прошедшего изделия не изменяется.</div></div>
    <button onclick="loadAll()">Обновить</button>
  </header>
  <section>
    <div class="section-head"><div><h2>Недавний брак</h2><div class="muted">Выберите изделие, затем конкретный контур.</div></div></div>
    <main id="reviews" class="grid"></main>
  </section>

  <section class="section">
    <div class="section-head"><div><h2>Сохранённые нормы</h2><div class="muted">Эти фрагменты исключаются из оценки будущих инспекций. Удаление применяется сразу.</div></div></div>
    <div id="acceptedCases" class="normal-grid"></div>
  </section>

  <dialog id="reviewDialog">
    <div class="modal-head"><div><h2 id="reviewTitle">Разбор дефектов</h2><div id="reviewMeta" class="muted"></div></div><button onclick="reviewDialog.close()">Закрыть</button></div>
    <div class="modal-body">
      <div class="viewer-column">
        <div class="actions" style="margin-bottom:10px"><button onclick="setImage('aligned')">Кадр</button><button onclick="setImage('heatmap')">Heatmap</button><button onclick="setImage('diff')">Diff</button><button onclick="setImage('mask')">Маска</button></div>
        <div class="viewer"><img id="reviewImage" alt="inspection"><svg id="overlay" viewBox="0 0 1 1" preserveAspectRatio="none"></svg></div>
      </div>
      <aside class="side">
        <div class="notice">Выберите контур. Кнопка запоминает только этот фрагмент и его примерное место: похожая форма будет нормой лишь рядом с выбранной областью. В конвейер повторное решение не отправляется.</div>
        <div id="counterfactual" class="counterfactual"></div>
        <div id="defects"></div>
        <input id="note" placeholder="Комментарий (необязательно)">
        <button id="acceptButton" disabled onclick="acceptSelected()">Считать выбранный фрагмент допустимой нормой</button>
        <div id="status" class="muted"></div>
      </aside>
    </div>
  </dialog>
<script>
  const reviewDialog = document.getElementById('reviewDialog');
  let currentReview = null;
  let selectedDefectId = null;
  let imageKind = 'aligned';

  const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  async function loadReviews() {
    const root = document.getElementById('reviews');
    root.innerHTML = '<div class="muted">Загрузка...</div>';
    try {
      const payload = await fetch('/learning/reviews', {cache:'no-store'}).then(r => r.json());
      if (!payload.reviews.length) { root.innerHTML = '<div class="muted">Недавних результатов БРАК пока нет.</div>'; return; }
      root.innerHTML = payload.reviews.map(item => `
        <article class="card" onclick="openReview('${encodeURIComponent(item.inspection_id)}')">
          <img src="/learning/reviews/${encodeURIComponent(item.inspection_id)}/image/heatmap" loading="lazy">
          <div><span class="bad">БРАК</span> · score ${Number(item.original_score).toFixed(3)}<br><b>${esc(item.product_type)}</b><br><span class="muted">Областей: ${item.defects_count} · ${esc(item.created_at)}</span></div>
        </article>`).join('');
    } catch (error) { root.innerHTML = `<div class="bad">${esc(error)}</div>`; }
  }

  async function loadAcceptedCases() {
    const root = document.getElementById('acceptedCases');
    root.innerHTML = '<div class="muted">Загрузка...</div>';
    try {
      const response = await fetch('/learning/accepted-cases', {cache:'no-store'});
      const payload = await response.json();
      if(!response.ok) throw new Error(payload.detail || 'Не удалось загрузить сохранённые нормы');
      if(!payload.cases.length) {
        root.innerHTML = '<div class="muted">Сохранённых фрагментов пока нет.</div>';
        return;
      }
      root.innerHTML = payload.cases.map(item => `
        <article class="normal-card">
          <img src="/learning/accepted-cases/${encodeURIComponent(item.id)}/image?v=${Date.now()}" alt="Сохранённый фрагмент" loading="lazy">
          <div class="normal-info">
            <b>${esc(item.product_type)}</b>
            <span class="muted">${esc(item.created_at)}</span>
            <span class="muted">Площадь: ${Number(item.area)} · источник: ${esc(item.source_defect_id)}</span>
            ${item.note ? `<span class="normal-note">${esc(item.note)}</span>` : ''}
            <button class="danger delete-normal" data-case-id="${esc(item.id)}">Удалить из списка нормы</button>
          </div>
        </article>`).join('');
      root.querySelectorAll('.delete-normal').forEach(button => {
        button.addEventListener('click', () => deleteAcceptedCase(button.dataset.caseId, button));
      });
    } catch(error) {
      root.innerHTML = `<div class="bad">${esc(error)}</div>`;
    }
  }

  async function deleteAcceptedCase(caseId, button) {
    if(!confirm('Удалить этот фрагмент из списка допустимой нормы? Следующие инспекции больше не будут его исключать.')) return;
    button.disabled = true;
    button.textContent = 'Удаление...';
    try {
      const response = await fetch(`/learning/accepted-cases/${encodeURIComponent(caseId)}`, {method:'DELETE'});
      const payload = await response.json();
      if(!response.ok) throw new Error(payload.detail || 'Не удалось удалить фрагмент');
      await Promise.all([loadAcceptedCases(), loadReviews()]);
      if(currentReview && reviewDialog.open) {
        currentReview = await fetch(`/learning/reviews/${encodeURIComponent(currentReview.inspection_id)}`, {cache:'no-store'}).then(r => r.json());
        renderReview();
      }
    } catch(error) {
      alert(String(error));
      button.disabled = false;
      button.textContent = 'Удалить из списка нормы';
    }
  }

  async function openReview(encodedId) {
    const id = decodeURIComponent(encodedId);
    currentReview = await fetch(`/learning/reviews/${encodeURIComponent(id)}`, {cache:'no-store'}).then(async r => { if(!r.ok) throw new Error((await r.json()).detail); return r.json(); });
    selectedDefectId = null;
    imageKind = 'aligned';
    document.getElementById('reviewTitle').textContent = `Разбор: ${currentReview.product_type}`;
    document.getElementById('reviewMeta').textContent = `Исходно БРАК · score ${Number(currentReview.original_score).toFixed(3)} · ${currentReview.inspection_id}`;
    document.getElementById('note').value = '';
    document.getElementById('status').textContent = '';
    renderReview();
    reviewDialog.showModal();
  }

  function setImage(kind) { imageKind = kind; renderImage(); }
  function renderImage() { if(currentReview) document.getElementById('reviewImage').src = `/learning/reviews/${encodeURIComponent(currentReview.inspection_id)}/image/${imageKind}?v=${Date.now()}`; }
  function polygonPoints(defect) { return (defect.polygon || []).map(p => `${p.x},${p.y}`).join(' '); }
  function selectDefect(id) { selectedDefectId = id; renderReview(); }

  function renderReview() {
    renderImage();
    const overlay = document.getElementById('overlay');
    overlay.innerHTML = currentReview.defects.map(defect => `<polygon class="${defect.manually_accepted || defect.accepted_as_normal ? 'learned ' : ''}${selectedDefectId===defect.id ? 'selected' : ''}" points="${polygonPoints(defect)}" onclick="selectDefect('${defect.id}')"><title>${esc(defect.id)}</title></polygon>`).join('');
    document.getElementById('defects').innerHTML = currentReview.defects.map(defect => `
      <div class="defect ${selectedDefectId===defect.id?'selected ':''}${defect.manually_accepted || defect.accepted_as_normal?'learned':''}" onclick="selectDefect('${defect.id}')">
        <b>${esc(defect.id)}</b> ${defect.manually_accepted || defect.accepted_as_normal ? '· допустимая норма' : '· влияет на score'}
        <div class="metrics"><span>score ${Number(defect.score).toFixed(3)}</span><span>площадь ${defect.area}</span><span>q90 ${Number(defect.diff_q90).toFixed(1)}</span><span>max ${Number(defect.diff_max).toFixed(1)}</span></div>
      </div>`).join('');
    const selected = currentReview.defects.find(d => d.id === selectedDefectId);
    document.getElementById('acceptButton').disabled = !selected || selected.manually_accepted || selected.accepted_as_normal;
    const cf = document.getElementById('counterfactual');
    cf.textContent = currentReview.counterfactual_status ? `Ознакомительный пересчёт: ${currentReview.counterfactual_status}, score ${Number(currentReview.counterfactual_score).toFixed(3)}. Никуда не передан.` : '';
  }

  async function acceptSelected() {
    if(!currentReview || !selectedDefectId) return;
    const button = document.getElementById('acceptButton');
    button.disabled = true;
    document.getElementById('status').textContent = 'Сохранение обучающего примера...';
    const response = await fetch(`/learning/reviews/${encodeURIComponent(currentReview.inspection_id)}/defects/${encodeURIComponent(selectedDefectId)}/accept-as-normal`, {
      method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({note:document.getElementById('note').value})
    });
    const payload = await response.json();
    if(!response.ok) { document.getElementById('status').textContent = payload.detail || 'Ошибка'; renderReview(); return; }
    document.getElementById('status').textContent = 'Фрагмент запомнен. Будет применяться только к будущим инспекциям.';
    currentReview = await fetch(`/learning/reviews/${encodeURIComponent(currentReview.inspection_id)}`, {cache:'no-store'}).then(r => r.json());
    renderReview();
    loadAll();
  }
  function loadAll() { return Promise.all([loadReviews(), loadAcceptedCases()]); }
  loadAll();
  setInterval(loadAll, 10000);
</script>
</body></html>"""
