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


@router.post("/learning/reviews/{inspection_id}/accept-all-as-normal")
async def accept_all_defects_as_normal(
    inspection_id: str,
    payload: AcceptDefectAsNormalRequest,
) -> dict:
    """Сохранить все показанные дефекты инспекции как отдельные примеры нормы."""
    try:
        return inspection_service.accept_all_review_defects_as_normal(
            inspection_id=inspection_id,
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
    .card.learned { border-color:#37b879; }
    .card.pass-status { border-color:#2f6f4a; }
    .good { color:#50dc91; font-weight:700; }
    .card.active { border-color:#ffc441; box-shadow:0 0 0 1px #ffc441; }
    dialog { width:min(1180px,96vw); height:min(850px,94vh); border:1px solid #41516a; border-radius:14px; background:#121a25; color:#eef3f8; padding:0; }
    dialog::backdrop { background:rgba(0,0,0,.72); }
    .modal-head { display:flex; align-items:center; justify-content:space-between; padding:15px 18px; border-bottom:1px solid #2b394c; }
    .modal-body { display:grid; grid-template-columns:minmax(0,2fr) minmax(300px,1fr); gap:16px; padding:16px; height:calc(100% - 64px); }
    .viewer-column { min-width:0; overflow:auto; }
    .viewer { position:relative; width:100%; background:#080b10; border-radius:10px; overflow:hidden; }
    .viewer img { width:100%; height:auto; display:block; }
    .viewer svg { position:absolute; inset:0; width:100%; height:100%; }
    polygon { fill:rgba(255,62,62,.22); stroke:#ff4f4f; stroke-width:.003; vector-effect:non-scaling-stroke; pointer-events:none; }
    polygon.learned { fill:rgba(40,205,115,.24); stroke:#37e58d; }
    .side { overflow:auto; display:flex; flex-direction:column; gap:12px; }
    .defect { border:1px solid #314159; border-radius:9px; padding:10px; background:#192332; }
    .defect.learned { border-color:#37b879; }
    .metrics { display:grid; grid-template-columns:1fr 1fr; gap:5px; font-size:13px; color:#b8c3d1; margin-top:7px; }
    .actions { display:flex; gap:8px; flex-wrap:wrap; }
    input { width:100%; border:1px solid #34465e; border-radius:8px; background:#0e151f; color:#fff; padding:9px; }
    .notice { border-radius:8px; background:#202c3c; padding:10px; font-size:13px; }
    .primary { border-color:#2f6f4a; background:#1f5136; font-weight:700; }
    .primary:hover { background:#276544; }
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
    <div><h1>Обучение допустимым фрагментам</h1><div class="muted">История инспекций: ГОДЕН и БРАК. Можно вернуться к кадру и пометить ложное срабатывание. После перезапуска Python список сбрасывается.</div></div>
    <button onclick="loadAll()">Обновить</button>
  </header>
  <section>
    <div class="section-head"><div><h2>История инспекций</h2><div class="muted">Последние кадры сессии — и хорошие, и брак. Откройте кадр, чтобы пометить ложное срабатывание.</div></div></div>
    <main id="reviews" class="grid"></main>
  </section>

  <section class="section">
    <div class="section-head"><div><h2>Сохранённые нормы</h2><div class="muted">Эти фрагменты исключаются из оценки будущих инспекций. Удаление применяется сразу.</div></div></div>
    <div id="acceptedCases" class="normal-grid"></div>
  </section>

  <dialog id="reviewDialog">
    <div class="modal-head"><div><h2 id="reviewTitle">Разбор дефектов</h2><div id="reviewMeta" class="muted"></div></div><div class="actions"><button onclick="shiftHistory(1)">Предыдущий кадр</button><button onclick="shiftHistory(-1)">Следующий кадр</button><button onclick="reviewDialog.close()">Закрыть</button></div></div>
    <div class="modal-body">
      <div class="viewer-column">
        <div class="actions" style="margin-bottom:10px"><button onclick="setImage('aligned')">Кадр</button><button onclick="setImage('heatmap')">Heatmap</button><button onclick="setImage('diff')">Diff</button><button onclick="setImage('mask')">Маска</button></div>
        <div class="viewer"><img id="reviewImage" alt="inspection"><svg id="overlay" viewBox="0 0 1 1" preserveAspectRatio="none"></svg></div>
      </div>
      <aside class="side">
        <div class="notice">Откройте любой кадр из истории. «Дообучить этот БРАК» доступна, если на кадре есть контуры. Если на кадре был и настоящий дефект — выучит оба. В конвейер повторное решение не отправляется.</div>
        <div id="counterfactual" class="counterfactual"></div>
        <div class="actions"><button class="primary" id="acceptAllButton" onclick="acceptAll()">Дообучить этот БРАК</button></div>
        <div id="defects"></div>
        <input id="note" placeholder="Комментарий (необязательно)">
        <button id="acceptButton" disabled onclick="acceptAll()">Считать все дефекты допустимой нормой</button>
        <div id="status" class="muted"></div>
      </aside>
    </div>
  </dialog>
<script>
  const reviewDialog = document.getElementById('reviewDialog');
  let currentReview = null;
  let historyItems = [];
  let imageKind = 'aligned';

  const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  async function loadReviews() {
    const root = document.getElementById('reviews');
    root.innerHTML = '<div class="muted">Загрузка...</div>';
    try {
      const payload = await fetch('/learning/reviews', {cache:'no-store'}).then(r => r.json());
      historyItems = payload.reviews || [];
      if (!historyItems.length) { root.innerHTML = '<div class="muted">История инспекций пуста. После проверки кадр появится здесь.</div>'; return; }
      root.innerHTML = historyItems.map(item => {
        const isPass = item.original_status === 'ГОДЕН';
        const statusClass = isPass ? 'good' : 'bad';
        const hint = item.accepted_defects_count ? 'уже помечен' : (item.defects_count ? 'можно пометить' : 'дефектов нет');
        return `
        <article class="card${item.accepted_defects_count ? ' learned' : ''}${isPass ? ' pass-status' : ''}" data-inspection-id="${esc(item.inspection_id)}" onclick="openReview('${encodeURIComponent(item.inspection_id)}')">
          <img src="/learning/reviews/${encodeURIComponent(item.inspection_id)}/image/${isPass ? 'aligned' : 'heatmap'}" loading="lazy">
          <div><span class="${statusClass}">${esc(item.original_status)}</span> · score ${Number(item.original_score).toFixed(3)}<br><b>${esc(item.product_type)}</b><br><span class="muted">${hint} · областей: ${item.defects_count}</span></div>
        </article>`;
      }).join('');
      if(currentReview) {
        const currentId = currentReview.inspection_id;
        root.querySelectorAll('.card').forEach(node => node.classList.toggle('active', node.dataset.inspectionId === currentId));
      }
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
            <span class="muted">Площадь: ${Number(item.area)} · q90: ${Number(item.diff_q90).toFixed(1)} · источник: ${esc(item.source_defect_id)}</span>
            <span class="muted">ID сохранённой нормы: ${esc(item.id)}</span>
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
    imageKind = 'aligned';
    document.getElementById('reviewTitle').textContent = `Разбор: ${currentReview.product_type}`;
    document.getElementById('reviewMeta').textContent = `Исходно ${currentReview.original_status} · score ${Number(currentReview.original_score).toFixed(3)} · ${currentReview.inspection_id}`;
    document.getElementById('note').value = '';
    document.getElementById('status').textContent = '';
    renderReview();
    reviewDialog.showModal();
  }

  function shiftHistory(delta) {
    if(!historyItems.length || !currentReview) return;
    const index = historyItems.findIndex(item => item.inspection_id === currentReview.inspection_id);
    const nextIndex = index < 0 ? 0 : index + delta;
    if(nextIndex < 0 || nextIndex >= historyItems.length) return;
    openReview(encodeURIComponent(historyItems[nextIndex].inspection_id));
  }

  document.addEventListener('keydown', event => {
    if(!reviewDialog.open || event.target.matches('input, textarea')) return;
    if(event.key === 'ArrowLeft') { event.preventDefault(); shiftHistory(1); }
    if(event.key === 'ArrowRight') { event.preventDefault(); shiftHistory(-1); }
  });

  function setImage(kind) { imageKind = kind; renderImage(); }
  function renderImage() { if(currentReview) document.getElementById('reviewImage').src = `/learning/reviews/${encodeURIComponent(currentReview.inspection_id)}/image/${imageKind}?v=${Date.now()}`; }
  function polygonPoints(defect) { return (defect.polygon || []).map(p => `${p.x},${p.y}`).join(' '); }
  function defectState(defect) {
    if(defect.manually_accepted) return `СОХРАНЁН КАК НОРМА · ID ${String(defect.matched_case_id || '').slice(0, 8)}`;
    if(defect.accepted_as_normal) return `РАСПОЗНАН КАК НОРМА · ID ${String(defect.matched_case_id || '').slice(0, 8)}`;
    return 'БУДЕТ СОХРАНЁН ПО КНОПКЕ · влияет на score';
  }
  function renderReview() {
    renderImage();
    const overlay = document.getElementById('overlay');
    overlay.innerHTML = currentReview.defects.map(defect => `<polygon class="${defect.manually_accepted || defect.accepted_as_normal ? 'learned' : ''}" points="${polygonPoints(defect)}"><title>${esc(defect.id)}</title></polygon>`).join('');
    document.getElementById('defects').innerHTML = currentReview.defects.length
      ? currentReview.defects.map(defect => `
      <div class="defect ${defect.manually_accepted || defect.accepted_as_normal?'learned':''}">
        <b>${esc(defect.id)}</b> · ${esc(defectState(defect))}
        <div class="metrics"><span>score ${Number(defect.score).toFixed(3)}</span><span>площадь ${defect.area}</span><span>q90 ${Number(defect.diff_q90).toFixed(1)}</span><span>max ${Number(defect.diff_max).toFixed(1)}</span></div>
      </div>`).join('')
      : '<div class="muted">На этом кадре нет контуров для дообучения.</div>';
    const pending = currentReview.defects.filter(defect => !defect.manually_accepted && !defect.accepted_as_normal);
    document.getElementById('acceptButton').disabled = pending.length === 0;
    document.getElementById('acceptAllButton').disabled = pending.length === 0;
    const cf = document.getElementById('counterfactual');
    cf.textContent = currentReview.counterfactual_status ? `Ознакомительный пересчёт: ${currentReview.counterfactual_status}, score ${Number(currentReview.counterfactual_score).toFixed(3)}. Никуда не передан.` : '';
    const currentId = currentReview.inspection_id;
    document.querySelectorAll('#reviews .card').forEach(node => {
      node.classList.toggle('active', node.dataset.inspectionId === currentId);
    });
  }

  async function acceptAll() {
    if(!currentReview) return;
    const buttons = [document.getElementById('acceptAllButton'), document.getElementById('acceptButton')];
    buttons.forEach(button => { if(button) button.disabled = true; });
    document.getElementById('status').textContent = 'Сохранение всех контуров как ложный БРАК...';
    const response = await fetch(`/learning/reviews/${encodeURIComponent(currentReview.inspection_id)}/accept-all-as-normal`, {
      method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({note:document.getElementById('note').value})
    });
    const payload = await response.json();
    if(!response.ok) { document.getElementById('status').textContent = payload.detail || 'Ошибка'; renderReview(); return; }
    document.getElementById('status').textContent = `Запомнено контуров: ${Number(payload.accepted_count)}. Будет применяться только к будущим инспекциям.`;
    currentReview = await fetch(`/learning/reviews/${encodeURIComponent(currentReview.inspection_id)}`, {cache:'no-store'}).then(r => r.json());
    renderReview();
    loadAll();
  }
  function loadAll() { return Promise.all([loadReviews(), loadAcceptedCases()]); }
  loadAll();
  setInterval(loadAll, 10000);
</script>
</body></html>"""
