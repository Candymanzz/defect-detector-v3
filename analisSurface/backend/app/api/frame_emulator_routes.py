"""API and small browser UI for replaying archived camera frames locally."""

from __future__ import annotations

import json
from io import BytesIO
from typing import Any

from fastapi import APIRouter, HTTPException
from fastapi.responses import HTMLResponse, Response, StreamingResponse
from pydantic import BaseModel, Field

from app.services.frame_emulator import FrameEmulator


router = APIRouter()
emulator = FrameEmulator()


class SessionRequest(BaseModel):
    loop: bool = True
    auto_reference: bool = True


class SettingsRequest(BaseModel):
    mode: str = "simple"
    knobs: dict[str, Any] = Field(default_factory=dict)


class AcceptRequest(BaseModel):
    note: str = ""


class RoiRequest(BaseModel):
    points: list[dict[str, float]]


def _session(session_id: str):
    try:
        return emulator.get_session(session_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="Emulator session not found") from exc


@router.get("/local-emulator", response_class=HTMLResponse, include_in_schema=False)
async def local_emulator_page() -> HTMLResponse:
    return HTMLResponse(LOCAL_EMULATOR_HTML, headers={"Cache-Control": "no-store"})


@router.get("/local-emulator/dataset")
async def emulator_dataset() -> dict[str, Any]:
    emulator.refresh()
    return emulator.dataset_summary()


@router.post("/local-emulator/sessions")
async def create_emulator_session(payload: SessionRequest) -> dict[str, Any]:
    try:
        session = emulator.create_session(loop=payload.loop, auto_reference=payload.auto_reference)
        return emulator.session_state(session)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.get("/local-emulator/sessions/{session_id}")
async def get_emulator_session(session_id: str) -> dict[str, Any]:
    return emulator.session_state(_session(session_id))


@router.post("/local-emulator/sessions/{session_id}/next")
async def next_emulator_bucket(session_id: str) -> dict[str, Any]:
    session = _session(session_id)
    emulator.move(session, 1)
    return emulator.run_current(session)


@router.post("/local-emulator/sessions/{session_id}/previous")
async def previous_emulator_bucket(session_id: str) -> dict[str, Any]:
    session = _session(session_id)
    emulator.move(session, -1)
    return emulator.run_current(session)


@router.post("/local-emulator/sessions/{session_id}/run")
async def run_emulator_bucket(session_id: str) -> dict[str, Any]:
    return emulator.run_current(_session(session_id))


@router.post("/local-emulator/sessions/{session_id}/camera/{camera_id}/run")
async def run_emulator_camera(session_id: str, camera_id: int) -> dict[str, Any]:
    return emulator.run_current(_session(session_id), camera_id=camera_id)


@router.post("/local-emulator/sessions/{session_id}/camera/{camera_id}/reference/{bucket_id}")
async def set_emulator_reference(session_id: str, camera_id: int, bucket_id: str) -> dict[str, Any]:
    try:
        return emulator.set_reference_from_bucket(_session(session_id), camera_id, bucket_id)
    except (KeyError, ValueError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.get("/local-emulator/sessions/{session_id}/camera/{camera_id}/settings")
async def get_emulator_camera_settings(session_id: str, camera_id: int) -> dict[str, Any]:
    session = _session(session_id)
    return emulator.camera_state(session, camera_id)


@router.put("/local-emulator/sessions/{session_id}/camera/{camera_id}/settings")
async def put_emulator_camera_settings(
    session_id: str,
    camera_id: int,
    payload: SettingsRequest,
) -> dict[str, Any]:
    try:
        return emulator.update_settings(_session(session_id), camera_id, payload.model_dump())
    except (KeyError, TypeError, ValueError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.put("/local-emulator/sessions/{session_id}/camera/{camera_id}/roi")
async def put_emulator_camera_roi(
    session_id: str,
    camera_id: int,
    payload: RoiRequest,
) -> dict[str, Any]:
    try:
        return emulator.update_roi(_session(session_id), camera_id, payload.points)
    except (KeyError, TypeError, ValueError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.get("/local-emulator/sessions/{session_id}/reviews")
async def get_emulator_reviews(session_id: str) -> dict[str, Any]:
    return {"reviews": emulator.list_reviews(_session(session_id))}


@router.get("/local-emulator/sessions/{session_id}/reviews/{inspection_id}")
async def get_emulator_review(session_id: str, inspection_id: str) -> dict[str, Any]:
    review = emulator.get_review(_session(session_id), inspection_id)
    if review is None:
        raise HTTPException(status_code=404, detail="Inspection review not found")
    return review


@router.post("/local-emulator/sessions/{session_id}/reviews/{inspection_id}/accept-all")
async def accept_emulator_review(session_id: str, inspection_id: str, payload: AcceptRequest) -> dict[str, Any]:
    try:
        return emulator.accept_all(_session(session_id), inspection_id, payload.note)
    except (KeyError, ValueError) as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc


@router.post("/local-emulator/sessions/{session_id}/reviews/{inspection_id}/defects/{defect_id}/accept")
async def accept_emulator_defect(
    session_id: str,
    inspection_id: str,
    defect_id: str,
    payload: AcceptRequest,
) -> dict[str, Any]:
    try:
        return emulator.accept_one(_session(session_id), inspection_id, defect_id, payload.note)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc


@router.get("/local-emulator/sessions/{session_id}/accepted-cases")
async def get_emulator_cases(session_id: str) -> dict[str, Any]:
    return {"cases": emulator.accepted_cases(_session(session_id))}


@router.get("/local-emulator/sessions/{session_id}/artifact/{camera_id}/{bucket_id}/{kind}")
async def get_emulator_artifact(session_id: str, camera_id: int, bucket_id: str, kind: str):
    try:
        body, media_type = emulator.session_artifact(_session(session_id), camera_id, bucket_id, kind)
    except (KeyError, FileNotFoundError, ValueError) as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    return Response(content=body, media_type=media_type, headers={"Cache-Control": "no-store"})


LOCAL_EMULATOR_HTML = r"""<!doctype html>
<html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Локальный replay инспекции</title>
<style>
:root{color-scheme:dark;font-family:Segoe UI,Arial;background:#0e151e;color:#edf2f8}*{box-sizing:border-box}body{margin:0;padding:18px}button,select,input{background:#182536;color:#fff;border:1px solid #3a526e;border-radius:7px;padding:8px}button{cursor:pointer}button:hover{background:#28517d}.top,.toolbar,.camera-tools,.metrics{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.panel{background:#17212d;border:1px solid #2c4056;border-radius:11px;padding:14px;margin-bottom:14px}.muted{color:#9eabbc}.bad{color:#ff7777}.good{color:#55dc94}.bucket{font-size:18px}.cameras{display:grid;grid-template-columns:repeat(auto-fill,minmax(270px,1fr));gap:12px}.card{background:#111b26;border:1px solid #2d4259;border-radius:9px;padding:10px;cursor:pointer}.card:hover{border-color:#6aa4df}.card.active{border-color:#ffc445}.card img{width:100%;height:130px;object-fit:contain;background:#070b10;border-radius:6px}.card h3{margin:7px 0}.metrics span{padding:5px 8px;background:#0d1620;border-radius:6px;font-size:12px}.viewer{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.viewer figure{margin:0;background:#080c11;border:1px solid #2c4056;border-radius:8px;overflow:hidden}.viewer figcaption{padding:8px;color:#b8c4d2}.viewer img{display:block;width:100%;max-height:410px;object-fit:contain}.roi-editor{position:relative;background:#080c11;border:1px solid #2c4056;border-radius:8px;overflow:hidden}.roi-editor img{display:block;width:100%;max-height:430px;object-fit:contain}.roi-editor svg{position:absolute;inset:0;width:100%;height:100%;cursor:crosshair}.roi-editor polygon{fill:rgba(66,155,255,.22);stroke:#5ba9ff;stroke-width:.004;vector-effect:non-scaling-stroke}.roi-editor polyline{fill:none;stroke:#89c4ff;stroke-width:.004;vector-effect:non-scaling-stroke}.roi-editor circle{fill:#ffe27b;stroke:#182536;stroke-width:.003;vector-effect:non-scaling-stroke}.settings{display:grid;grid-template-columns:repeat(3,minmax(150px,1fr));gap:8px}.settings label{display:flex;flex-direction:column;gap:4px;color:#bac7d5;font-size:12px}.hidden{display:none}@media(max-width:760px){.viewer,.settings{grid-template-columns:1fr}}
</style></head><body>
<div class="panel"><div class="top"><h1>Локальный replay инспекции</h1><span class="muted">архив кадров → тот же Python-анализ → новый результат</span></div><div id="dataset" class="muted"></div></div>
<div class="panel"><div class="toolbar"><button onclick="start()">Новая сессия</button><button onclick="move(-1)">Предыдущее ведро</button><button onclick="move(1)">Следующее ведро</button><button onclick="runAll()">Проверить все камеры</button><label>Автоповтор <input id="loop" type="checkbox" checked></label><label>Интервал, мс <input id="interval" type="number" value="3000" min="200" step="100" style="width:90px"></label><button onclick="togglePlay()" id="play">Запустить цикл</button></div><div id="bucket" class="bucket muted">Сессия ещё не запущена</div></div>
<div class="panel"><h2>Камеры</h2><div id="cameras" class="cameras"></div></div>
<div id="detail" class="panel hidden"><div class="top"><h2 id="detailTitle"></h2><button onclick="setReference()">Сделать текущий архивный кадр эталоном</button><button onclick="runCamera()">Проверить эту камеру</button></div><div id="detailMetrics" class="metrics"></div><div class="viewer"><figure><figcaption>Исторический кадр</figcaption><img id="oldFrame"></figure><figure><figcaption>Историческая heatmap</figcaption><img id="oldHeatmap"></figure><figure><figcaption>Новая инспекция: кадр</figcaption><img id="newFrame"></figure><figure><figcaption>Новая heatmap</figcaption><img id="newHeatmap"></figure></div><div class="panel"><h3>Настройки камеры</h3><div class="settings"><label>Режим<select id="mode"><option value="simple">Simple</option><option value="pro">Pro</option></select></label><label>Порог<input id="threshold" type="number" min="0.01" max="1" step="0.01" value="0.25"></label><label>Чувствительность<input id="sensitivity" type="number" min="0" max="1" step="0.01" value="0.5"></label><label class="pro">Допуск шума<input id="noise" type="number" min="0" max="1" step="0.01" value="0.5"></label><label class="pro">Чувствительность царапин<input id="scratch" type="number" min="0" max="1" step="0.01" value="0.5"></label><label class="pro">Подавление границ<input id="edge" type="number" min="0" max="1" step="0.01" value="0.5"></label><label class="pro">Обработка текста<input id="text" type="number" min="0" max="1" step="0.01" value="0.5"></label><label class="pro">Предобработка<input id="preprocess" type="number" min="0" max="1" step="0.01" value="0.5"></label></div><button onclick="saveSettings()">Сохранить настройки и проверить</button><div id="settingsMessage" class="muted"></div></div><div class="panel"><h3>Дообучение</h3><div id="reviews" class="muted"></div><input id="note" placeholder="Комментарий оператора"><button onclick="acceptReview()">Принять дефекты этой инспекции как норму</button><button onclick="runCamera()">Повторить кадр после обучения</button></div></div>
<script>
let session=null,selected=null,timer=null;
const $=id=>document.getElementById(id), esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
async function api(url,opt){let r=await fetch(url,{headers:{'Content-Type':'application/json',...(opt?.headers||{})},...opt});let d=await r.json().catch(()=>({}));if(!r.ok)throw Error(d.detail||('HTTP '+r.status));return d}
async function loadDataset(){let d=await api('/local-emulator/dataset');$('dataset').textContent=`Архив: ${d.bucket_count} ведер, камеры: ${d.camera_ids.join(', ')}`}
async function start(){try{session=await api('/local-emulator/sessions',{method:'POST',body:JSON.stringify({loop:$('loop').checked,auto_reference:true})});await runAll()}catch(e){alert(e.message)}}
async function runAll(){if(!session){await start();return}try{session=await api(`/local-emulator/sessions/${session.session_id}/run`,{method:'POST'});render()}catch(e){alert(e.message)}}
async function move(delta){if(!session){await start();return}try{session=await api(`/local-emulator/sessions/${session.session_id}/${delta>0?'next':'previous'}`,{method:'POST'});render()}catch(e){alert(e.message)}}
function render(){let b=session.bucket; $('bucket').innerHTML=`Ведро <b>${esc(b.inspection_id)}</b> (${session.cursor+1}/${session.bucket_count}) — ${Object.keys(session.results||{}).length} новых результатов; полный цикл: <b>${Number(session.last_bucket_time_ms||0).toFixed(1)} ms</b>`;let cards=[];for(let id of b.camera_ids){let h=b.historical[String(id)]||{},r=(session.results||{})[String(id)]||null;cards.push(`<div class="card ${selected===id?'active':''}" onclick="selectCamera(${id})"><h3>Камера ${id}</h3><img src="${r?.source_frame_url||''}"><div class="metrics"><span>История: ${esc(h.geometry_status||'—')}</span><span>Новая: <b class="${r?.new?.status==='БРАК'?'bad':'good'}">${esc(r?.new?.status||'не проверена')}</b></span><span>score: ${r?.new?Number(r.new.anomaly_score).toFixed(3):'—'}</span>${r?.capture_to_verdict_ms?`<span>кадр→вердикт: ${Number(r.capture_to_verdict_ms).toFixed(1)} ms</span>`:''}</div></div>`)}$('cameras').innerHTML=cards.join('');if(selected!==null)showCamera(selected)}
function selectCamera(id){selected=id;ensureRoiEditor();render();refreshRoiEditor()}
function showCamera(id){let r=(session.results||{})[String(id)],h=session.bucket.historical[String(id)];if(!r)return;$('detail').classList.remove('hidden');$('detailTitle').textContent=`Камера ${id} — профиль ${r.product_type}`;$('oldFrame').src=r.source_frame_url;$('oldHeatmap').src=r.historical_heatmap_url;$('newFrame').src='data:image/jpeg;base64,'+r.new.aligned_image_b64;$('newHeatmap').src='data:image/png;base64,'+r.new.heatmap_b64;$('detailMetrics').innerHTML=`<span>История: ${esc(h.python_status||'—')} / geometry ${esc(h.geometry_status||'—')}</span><span>Новая: ${esc(r.new.status)}</span><span>score ${Number(r.new.anomaly_score).toFixed(4)} / threshold ${Number(r.new.threshold).toFixed(4)}</span><span><b>Кадр → вердикт: ${Number(r.capture_to_verdict_ms||r.processing_ms||0).toFixed(1)} ms</b></span><span>Полный цикл ведра: ${Number(session.last_bucket_time_ms||0).toFixed(1)} ms</span>`;$('reviews').textContent=`Review ID: ${r.new.inspection_id||'—'}; совпадений сохранённых норм: ${r.new.learned_normal_matches_count||0}`;loadSettings(id);loadReview(r.new.inspection_id)}
async function loadReview(id){if(!id){$('reviews').textContent='Review не создан';return}try{let d=await api(`/local-emulator/sessions/${session.session_id}/reviews/${id}`);let defects=d.defects||[];$('reviews').innerHTML=`Review <b>${esc(id)}</b>; кандидатов: ${defects.length}`+(defects.length?'<br>'+defects.map(x=>`<button onclick="acceptOne('${esc(id)}','${esc(x.id)}')">Принять ${esc(x.id)}</button>`).join(' '):'');}catch(e){$('reviews').textContent=e.message}}
async function loadSettings(id){let s=await api(`/local-emulator/sessions/${session.session_id}/camera/${id}/settings`);$('mode').value=s.mode;$('threshold').value=s.knobs.threshold??.25;$('sensitivity').value=s.knobs.sensitivity??.5}
async function saveSettings(){let id=selected;if(id===null)return;let mode=$('mode').value,k={threshold:+$('threshold').value,sensitivity:+$('sensitivity').value};if(mode==='pro')Object.assign(k,{noise_tolerance:+$('noise').value,scratch_sensitivity:+$('scratch').value,edge_suppression:+$('edge').value,text_handling:+$('text').value,preprocess_strength:+$('preprocess').value});try{await api(`/local-emulator/sessions/${session.session_id}/camera/${id}/settings`,{method:'PUT',body:JSON.stringify({mode,knobs:k})});$('settingsMessage').textContent='Сохранено';await runCamera()}catch(e){$('settingsMessage').textContent=e.message}}
async function runCamera(){if(selected===null)return;try{session=await api(`/local-emulator/sessions/${session.session_id}/camera/${selected}/run`,{method:'POST'});render()}catch(e){alert(e.message)}}
async function setReference(){if(selected===null)return;try{await api(`/local-emulator/sessions/${session.session_id}/camera/${selected}/reference/${session.bucket.inspection_id}`,{method:'POST'});$('settingsMessage').textContent='Эталон заменён';await runCamera()}catch(e){alert(e.message)}}
async function acceptReview(){if(selected===null)return;let r=(session.results||{})[String(selected)];if(!r?.new?.inspection_id)return;try{await api(`/local-emulator/sessions/${session.session_id}/reviews/${r.new.inspection_id}/accept-all`,{method:'POST',body:JSON.stringify({note:$('note').value})});$('settingsMessage').textContent='Норма сохранена; повторите инспекцию'}catch(e){alert(e.message)}}
async function acceptOne(review,defect){try{await api(`/local-emulator/sessions/${session.session_id}/reviews/${review}/defects/${defect}/accept`,{method:'POST',body:JSON.stringify({note:$('note').value})});$('settingsMessage').textContent='Дефект принят как норма';await loadReview(review)}catch(e){alert(e.message)}}
function togglePlay(){if(timer){clearInterval(timer);timer=null;$('play').textContent='Запустить цикл';return}if(!session)start();timer=setInterval(()=>move(1),Math.max(200,+$('interval').value||3000));$('play').textContent='Остановить цикл'}
let roiPoints=[];
function ensureRoiEditor(){if($('roiFrame'))return;let viewer=document.querySelector('#detail .viewer');if(!viewer)return;let box=document.createElement('div');box.className='panel';box.innerHTML='<h3>ROI камеры</h3><div class="muted">Кликните минимум 3 точки по архивному кадру. Инспекция выполняется только внутри сохранённого ROI.</div><div class="roi-editor"><img id="roiFrame"><svg id="roiOverlay" viewBox="0 0 1 1" preserveAspectRatio="none"><polygon id="roiPolygon"></polygon><polyline id="roiPolyline"></polyline><g id="roiVertices"></g></svg></div><div class="toolbar"><button onclick="undoRoiPoint()">Удалить точку</button><button onclick="clearRoi()">Очистить ROI</button><button onclick="fullRoi()">Весь кадр</button><button onclick="saveRoi()">Сохранить ROI</button><span id="roiMessage" class="muted"></span></div>';viewer.parentNode.insertBefore(box,viewer);$('roiOverlay').addEventListener('click',addRoiPoint)}
function refreshRoiEditor(){ensureRoiEditor();if(selected===null||!session)return;let r=(session.results||{})[String(selected)];let frameUrl=r?.source_frame_url||`/local-emulator/sessions/${session.session_id}/artifact/${selected}/${session.bucket.inspection_id}/frame.jpg`;$('roiFrame').src=frameUrl;let s=session.cameras[String(selected)];roiPoints=(s?.roi_points||[]).map(p=>({x:+p.x,y:+p.y}));renderRoi()}
function renderRoi(){if(!$('roiPolygon'))return;let pts=roiPoints.map(p=>`${p.x},${p.y}`).join(' ');$('roiPolygon').setAttribute('points',roiPoints.length>=3?pts:'');$('roiPolyline').setAttribute('points',pts);$('roiVertices').innerHTML=roiPoints.map(p=>`<circle cx="${p.x}" cy="${p.y}" r=".009"></circle>`).join('');if($('roiMessage'))$('roiMessage').textContent=roiPoints.length>=3?`Точек ROI: ${roiPoints.length}`:'Нужно минимум 3 точки'}
function addRoiPoint(e){let rect=e.currentTarget.getBoundingClientRect();if(!rect.width||!rect.height)return;roiPoints.push({x:Math.max(0,Math.min(1,(e.clientX-rect.left)/rect.width)),y:Math.max(0,Math.min(1,(e.clientY-rect.top)/rect.height))});renderRoi()}
function undoRoiPoint(){roiPoints.pop();renderRoi()} function clearRoi(){roiPoints=[];renderRoi()} function fullRoi(){roiPoints=[{x:0,y:0},{x:1,y:0},{x:1,y:1},{x:0,y:1}];renderRoi()}
async function saveRoi(){if(selected===null||roiPoints.length<3){alert('Задайте минимум 3 точки ROI');return}try{session=await api(`/local-emulator/sessions/${session.session_id}/camera/${selected}/roi`,{method:'PUT',body:JSON.stringify({points:roiPoints})});$('roiMessage').textContent='ROI сохранён. Теперь можно запускать инспекцию.';render()}catch(e){alert(e.message)}}
const _showCamera=showCamera;showCamera=function(id){let r=(session?.results||{})[String(id)];if(r?.error){let h=session.bucket.historical[String(id)]||{};$('detail').classList.remove('hidden');$('detailTitle').textContent=`Камера ${id}`;$('oldFrame').src=r.source_frame_url;$('oldHeatmap').removeAttribute('src');$('newFrame').removeAttribute('src');$('newHeatmap').removeAttribute('src');$('detailMetrics').innerHTML=`<span class="bad">Новая инспекция не выполнена: ${esc(r.error)}</span><span>Исторический geometry: ${esc(h.geometry_status||'—')}</span>`;$('reviews').textContent='Сначала сохраните ROI для этой камеры.';loadSettings(id);refreshRoiEditor();return}_showCamera(id);refreshRoiEditor()}
loadDataset();
</script></body></html>"""
