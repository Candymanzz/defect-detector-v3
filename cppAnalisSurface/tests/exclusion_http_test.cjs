// node tests/exclusion_http_test.cjs <cpp_analis_surface> <exclusion_test>
'use strict';
const fs=require('fs'),path=require('path'),os=require('os'),cp=require('child_process'),net=require('net'),assert=require('assert/strict');
(async()=>{
 const [exe,fixture]=process.argv.slice(2).map(x=>path.resolve(x));
 const data=fs.mkdtempSync(path.join(os.tmpdir(),'cas-exclusion-http-'));
 if(process.platform==='win32')fs.mkdirSync(path.join(data,'iml_shm'));
 cp.execFileSync(fixture,['--fixtures',data],{windowsHide:true});
 const listener=net.createServer();await new Promise(r=>listener.listen(0,'127.0.0.1',r));const port=listener.address().port;await new Promise(r=>listener.close(r));
 const root='http://127.0.0.1:'+port,product='bucket#cam=0',profile='camera-0',exported=[];let child,output='';
 const zones=[[[90/383,90/319],[190/383,90/319],[190/383,190/319],[90/383,190/319]]];
 const form=(file,fields={})=>{const result=new FormData();result.append('file',new Blob([fs.readFileSync(path.join(data,file))],{type:'image/png'}),file);for(const[k,v]of Object.entries(fields))result.append(k,typeof v==='string'?v:JSON.stringify(v));return result;};
 const api=async(route,options,status=200)=>{const response=await fetch(root+route,options),body=await response.json();assert.equal(response.status,status,JSON.stringify(body));return body;};
 const json=(route,body,method='POST',status=200)=>api(route,{method,headers:{'Content-Type':'application/json'},body:JSON.stringify(body)},status);
 const put=(body,status)=>json('/settings/'+profile,body,'PUT',status);
 const inspect=(file,fields={},status=200)=>api('/inspect',{method:'POST',body:form(file,{product_type:product,analysis_profile:profile,...fields})},status);
 const upload=()=>api('/upload-ref',{method:'POST',body:form('reference.png',{product_type:product})});
 async function start(){child=cp.spawn(exe,['--host','127.0.0.1','--port',String(port),'--data-dir',data,'--log-dir',path.join(data,'logs')],{windowsHide:true,env:{...process.env,...(process.platform==='win32'?{LOCALAPPDATA:data}:{}),CAS_PERFORMANCE_LOG:'0'}});child.stdout.on('data',x=>output+=x);child.stderr.on('data',x=>output+=x);for(let i=0;i<100;i++){try{if((await fetch(root+'/health')).ok)return;}catch{}await new Promise(r=>setTimeout(r,100));}throw Error(output);}
 async function stop(){if(child&&child.exitCode===null){const done=new Promise(r=>child.once('exit',r));child.kill();await done;}}
 try{
  await start();assert.deepEqual((await api('/settings/defaults')).settings.exclusions,[]);
  assert.equal((await api('/settings/'+profile)).saved,false);
  const saved=await put({exclusions:zones,threshold:.1,min_blob_area:3});assert.equal(saved.saved,true);assert.deepEqual(saved.settings.exclusions,zones);
  await upload();let result=await inspect('inside.png');assert.equal(result.status,'ГОДЕН');assert.equal(result.anomaly_score,0);assert.deepEqual(result.inspection_exclusions.polygons,zones);assert.equal(result.inspection_exclusions.coordinate_space,'normalized');assert.equal(result.inspection_exclusions.surface_skipped,false);
  assert.equal((await inspect('outside.png')).status,'БРАК');
  assert.equal((await inspect('inside.png',{exclusions:[]})).status,'БРАК','multipart temporary clear');
  assert.deepEqual((await api('/settings/'+profile)).settings.exclusions,zones,'temporary overrides do not persist');
  const before=(await api('/settings/'+profile)).settings;
  const bad=await put({threshold:.9,exclusions:[[[0,0],[1,1]]]},400);assert.equal(bad.error_code,'bad_settings');assert.match(bad.detail,/3\.\.128/);assert.ok(bad.detector_id);
  assert.deepEqual((await api('/settings/'+profile)).settings,before,'invalid persistent update is atomic');
  assert.equal((await inspect('inside.png',{exclusions:'garbage'},400)).error_code,'bad_request');
  assert.equal((await inspect('inside.png',{exclusions:[[[0,0],[2,0],[1,1]]]},400)).error_code,'bad_request');
  await put({sensitivity:.5});assert.deepEqual((await api('/settings/'+profile)).settings.exclusions,zones,'omitted field preserves zones');
  assert.deepEqual((await api('/settings/camera-1')).settings.exclusions,[]);
  const test={product_type:product,analysis_profile:profile,cache_key:'inside',file_path:path.join(data,'inside.png')};
  const overridden=await json('/inspect-test-frame',{...test,settings:{exclusions:[]}});assert.equal(overridden.status,'БРАК');assert.deepEqual(overridden.inspection_exclusions.polygons,[]);
  const prefix=path.basename(data)+'-';
  if(process.platform==='win32')for(const name of ['heat.u8','mask.u8'])fs.writeFileSync(path.join(data,'iml_shm',prefix+name),Buffer.alloc(0));
  const masked=await json('/inspect-test-frame',{...test,heatmap_u8_output_path:prefix+'heat.u8',segmentation_mask_u8_output_path:prefix+'mask.u8'});
  for(const descriptor of [masked.heatmap_u8,masked.segmentation_mask_u8])if(descriptor)exported.push(descriptor.path);
  assert.ok(masked.heatmap_u8);assert.equal(fs.readFileSync(masked.heatmap_u8.path).some(x=>x!==0),false,'ignored defect absent from raw heatmap');
  await stop();await start();await upload();assert.deepEqual((await api('/settings/'+profile)).settings.exclusions,zones);assert.equal((await inspect('inside.png')).status,'ГОДЕН');
  const full=[[[0,0],[1,0],[1,1],[0,1]]];await put({exclusions:full,sensitivity:1});result=await inspect('outside.png');assert.equal(result.status,'ГОДЕН');assert.equal(result.reason,'inspection_excluded');assert.equal(result.inspection_exclusions.surface_skipped,true);assert.equal(result.inspection_exclusions.remaining_area_work_px,0);
  await put({exclusions:[],sensitivity:.5});assert.equal((await inspect('inside.png')).status,'БРАК','clear restores inspection');
  await api('/settings/'+profile,{method:'DELETE'});assert.equal((await api('/settings/'+profile)).saved,false);assert.deepEqual((await api('/settings/'+profile)).settings.exclusions,[]);
  console.log('Main exclusion HTTP: defaults, profile persistence, inside/outside defects, strict errors, JSON/multipart overrides, heatmap, restart and reset passed');
 }catch(error){console.error(output);throw error;}finally{await stop();for(const file of exported){if(process.platform!=='win32'){assert.equal(path.dirname(file),'/dev/shm');assert.ok(path.basename(file).startsWith(path.basename(data)+'-'));fs.rmSync(file,{force:true});}}assert.equal(path.dirname(path.resolve(data)),path.resolve(os.tmpdir()));assert.ok(path.basename(data).startsWith('cas-exclusion-http-'));fs.rmSync(data,{recursive:true});}
})().catch(e=>{console.error(e);process.exitCode=1;});
