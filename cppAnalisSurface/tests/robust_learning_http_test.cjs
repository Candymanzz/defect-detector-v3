// node tests/robust_learning_http_test.cjs <cpp_analis_surface.exe> <moving_shadow_test.exe>
'use strict';
const fs=require('fs'),path=require('path'),os=require('os'),cp=require('child_process'),net=require('net'),assert=require('assert/strict');
(async()=>{
 const [exe,fixture]=process.argv.slice(2).map(p=>path.resolve(p));
 const data=fs.mkdtempSync(path.join(os.tmpdir(),'cas-robust-http-'));
 cp.execFileSync(fixture,['--fixtures',data],{windowsHide:true});
 const socket=net.createServer();await new Promise(r=>socket.listen(0,'127.0.0.1',r));const port=socket.address().port;await new Promise(r=>socket.close(r));
 const root='http://127.0.0.1:'+port, product='robust-http';let child,output='';
 const delay=ms=>new Promise(r=>setTimeout(r,ms));
 const form=(file,fields={})=>{const f=new FormData();f.append('file',new Blob([fs.readFileSync(path.join(data,file))],{type:'image/png'}),file);for(const [k,v]of Object.entries(fields))f.append(k,typeof v==='string'?v:JSON.stringify(v));return f;};
 async function api(route,opts){const r=await fetch(root+route,opts);const j=await r.json();assert.equal(r.status,200,JSON.stringify(j));return j;}
 const put=body=>api('/settings/'+product,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
 const inspect=(file,fields={})=>api('/inspect',{method:'POST',body:form(file,{product_type:product,...fields})});
 async function start(){
  child=cp.spawn(exe,['--host','127.0.0.1','--port',String(port),'--data-dir',data,'--log-dir',path.join(data,'logs')],{windowsHide:true,env:{...process.env,CAS_PERFORMANCE_LOG:'0'}});
  child.stdout.on('data',b=>output+=b);child.stderr.on('data',b=>output+=b);
  for(let i=0;i<100;i++){try{if((await fetch(root+'/health')).ok)return;}catch{}await delay(100);}throw Error('server failed: '+output);
 }
 async function stop(){if(child&&child.exitCode===null){const exited=new Promise(r=>child.once('exit',r));child.kill();await exited;}}
 const upload=()=>api('/upload-ref',{method:'POST',body:form('base.png',{product_type:product})});
 try{
  await start();await upload();
  let settings=(await api('/settings/'+product)).settings;
  assert.equal(settings.learning_robust_enabled,true);assert.equal(settings.learning_shadow_enabled,true);assert.equal(settings.learning_search_radius,24);
  await put({threshold:.1,min_blob_area:3,shift_tolerance_x:1,shift_tolerance_y:1,learning_search_radius:24});
  assert.equal((await inspect('moved.png')).status,'БРАК');
  const added=await api('/learning/regions?product_type='+product,{method:'POST',body:form('crop.png',{bbox:{x:102,y:97,width:76,height:76},coordinate_space:{width:384,height:320},source_inspection_id:'1'})});
  const id=added.accepted_cases[0].id;
  let result=await inspect('moved.png');assert.equal(result.status,'ГОДЕН');assert.ok(result.raw_anomaly_score>result.anomaly_score);
  assert.equal(result.learning_engine,'robust-v2');assert.equal(result.learning_diagnostics[0].id,id);assert.equal(result.learning_diagnostics[0].method,'shadow');assert.ok(result.learning_ms>=0);
  assert.equal((await inspect('scratch.png')).status,'БРАК');
  assert.equal((await inspect('moved.png',{learning_search_radius:'4'})).status,'БРАК');
  const jsonOverride=await api('/inspect-test-frame',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({product_type:product,cache_key:'moved-fixture',file_path:path.join(data,'moved.png'),settings:{learning_robust_enabled:false}})});
  assert.equal(jsonOverride.status,'БРАК');assert.equal(jsonOverride.learning_engine,'native-52aa31ea');
  assert.equal((await inspect('moved.png',{learning_robust_enabled:'false'})).status,'БРАК');
  assert.equal((await api('/settings/'+product)).settings.learning_robust_enabled,true,'temporary override must not persist');
  await put({learning_robust_enabled:false});assert.equal((await inspect('moved.png')).learning_engine,'native-52aa31ea');
  await put({learning_robust_enabled:true,learning_search_radius:20});
  const invalid=await fetch(root+'/settings/'+product,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({learning_search_radius:65})});assert.equal(invalid.status,400);
  await stop();await start();await upload();
  settings=(await api('/settings/'+product)).settings;assert.equal(settings.learning_search_radius,20);assert.equal(settings.learning_robust_enabled,true);
  result=await inspect('moved.png');assert.equal(result.status,'ГОДЕН');assert.equal(result.learning_diagnostics[0].id,id);
  await api('/learning/accepted-cases/'+id,{method:'DELETE'});assert.equal((await inspect('moved.png')).status,'БРАК');
  console.log('Main detector HTTP learning checks passed: moving shadow/scratch/settings/overrides/diagnostics/restart/delete');
 }finally{
  await stop();assert.equal(path.dirname(path.resolve(data)),path.resolve(os.tmpdir()));assert.ok(path.basename(data).startsWith('cas-robust-http-'));fs.rmSync(data,{recursive:true});
 }
})().catch(e=>{console.error(e);process.exitCode=1;});
