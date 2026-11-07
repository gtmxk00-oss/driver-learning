'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {Readable}=require('node:stream');
const {createRoadProvider,coordinates,normalize,koreanCue}=require('../server/road-provider.cjs');
const {createHandler}=require('../server/http-server.cjs');
const coords={origin:{lon:127,lat:37},destination:{lon:127.002,lat:37}};
const sample=()=>({code:'Ok',waypoints:[{location:[127,37],name:'출발 도로',distance:5},{location:[127.002,37],name:'도착 도로',distance:8}],routes:[{
  distance:200,duration:40,geometry:{type:'LineString',coordinates:[[127,37],[127.001,37],[127.002,37]]},legs:[{steps:[
    {name:'첫 도로',distance:100,duration:20,geometry:{type:'LineString',coordinates:[[127,37],[127.001,37]]},maneuver:{type:'depart',modifier:'straight',location:[127,37],bearing_before:0,bearing_after:90}},
    {name:'둘째 도로',distance:100,duration:20,geometry:{type:'LineString',coordinates:[[127.001,37],[127.002,37]]},maneuver:{type:'turn',modifier:'right',location:[127.001,37],bearing_before:90,bearing_after:180}},
    {name:'',distance:0,duration:0,geometry:{type:'LineString',coordinates:[[127.002,37],[127.002,37]]},maneuver:{type:'arrive',modifier:'straight',location:[127.002,37],bearing_before:180,bearing_after:0}}
  ]}]
}]});
const reply=(data=sample(),status=200)=>({ok:status>=200&&status<300,status,json:async()=>data});
const make=options=>createRoadProvider({minIntervalMs:0,...options});
async function request(handler,{method='GET',url='/api/navigation/status',host='127.0.0.1:4173',origin,body,headers={}}={}){
  const req=Readable.from(body===undefined?[]:[typeof body==='string'?body:JSON.stringify(body)]);
  Object.assign(req,{method,url,headers:{host,...(origin?{origin}:{}),...(body!==undefined?{'content-type':'application/json'}:{}),...headers},socket:{localPort:4173}});
  let status,responseHeaders,content;const res={writeHead(s,h){status=s;responseHeaders=h;},end(value){content=value;}};
  await handler(req,res);return {status,headers:responseHeaders,body:content?JSON.parse(content):null};
}

test('OSRM request uses fixed HTTPS origin and correct coordinate order without traffic claims',async()=>{
  let args;const p=make({fetchFn:async(...a)=>{args=a;return reply();}});const result=await p.route({...coords,url:'https://attacker.invalid'});
  const url=new URL(args[0]);assert.equal(url.origin,'https://router.project-osrm.org');assert.equal(url.pathname,'/route/v1/driving/127,37;127.002,37');
  for(const [name,value]of Object.entries({steps:'true',overview:'full',geometries:'geojson',alternatives:'true'}))assert.equal(url.searchParams.get(name),value);
  assert.match(args[1].headers['User-Agent'],/^RouteMemoryPC/);assert.equal(args[1].redirect,'error');
  assert.equal(result.source,'real-road');assert.equal(result.traffic,'unavailable');assert(!Object.hasOwn(result.routes[0],'speedKph'));
  assert.equal(result.routes[0].steps[1].maneuver.location[0],127.001);assert.match(result.routes[0].steps[1].cue,/우회전/);
});
test('coordinate validation rejects unsafe types, bounds and coincident endpoints',()=>{
  for(const input of [null,{}, {origin:'https://bad.invalid',destination:coords.destination},{...coords,origin:{lat:'37',lon:127}},{...coords,origin:{lat:91,lon:127}},{...coords,destination:coords.origin},{...coords,origin:{lat:37,lon:NaN}}])
    assert.throws(()=>coordinates(input),e=>e.status===400);
});
test('server-controlled OSRM base rejects credentials, insecure URLs and local hosts',async()=>{
  for(const base of ['http://router.project-osrm.org','https://key:secret@router.project-osrm.org','https://localhost','https://127.0.0.1','https://public.example/route?q=secret'])
    await assert.rejects(make({base,fetchFn:()=>{throw new Error('must not request');}}).route(coords),e=>e.code==='INVALID_CONFIGURATION');
});
test('normalization keeps real geometry and snapped waypoint distances, filters bad alternatives',()=>{
  const payload=sample();payload.routes.push({...payload.routes[0],distance:-1});
  const result=normalize(payload,'2026-11-04T00:00:00Z');assert.equal(result.routes.length,1);
  assert.deepEqual(result.routes[0].geometry,[[127,37],[127.001,37],[127.002,37]]);assert.equal(result.waypoints[0].distanceMeters,5);
  assert.equal(result.routes[0].steps[2].maneuver.type,'arrive');assert.match(result.attribution,/OpenStreetMap/);
});
test('missing, far snapped, disconnected and oversized routing responses are rejected',()=>{
  assert.throws(()=>normalize({code:'NoRoute'},'now'),e=>e.code==='NO_ROUTE');
  const far=sample();far.waypoints[0].distance=1001;assert.throws(()=>normalize(far,'now'),e=>e.code==='SNAP_TOO_FAR');
  const broken=sample();broken.routes[0].legs[0].steps[1].geometry.coordinates[0]=[128,38];assert.throws(()=>normalize(broken,'now'),e=>e.code==='INVALID_RESPONSE');
  const noSteps=sample();noSteps.routes[0].legs[0].steps=[];assert.throws(()=>normalize(noSteps,'now'),e=>e.code==='INVALID_RESPONSE');
  const huge=sample();huge.routes[0].geometry.coordinates=Array.from({length:100001},()=>[127,37]);assert.throws(()=>normalize(huge,'now'),e=>e.code==='INVALID_RESPONSE');
  const wrongEnd=sample();wrongEnd.routes[0].geometry.coordinates[2]=[127.5,37];assert.throws(()=>normalize(wrongEnd,'now'),e=>e.code==='INVALID_RESPONSE');
});
test('Korean instructions preserve OSRM maneuver meaning and avoid guessing unsupported turns',()=>{
  assert.match(koreanCue({type:'depart',modifier:'left'},'도로'),/출발/);
  assert.match(koreanCue({type:'turn',modifier:'left'},'도로'),/좌회전/);
  assert.match(koreanCue({type:'end of road',modifier:'right'},'도로'),/도로 끝에서 우회전/);
  assert.match(koreanCue({type:'roundabout',exit:3},'도로'),/3번째 출구/);
  assert.match(koreanCue({type:'merge',modifier:'right'},'도로'),/합류/);
  assert.equal(koreanCue({type:'arrive'},''),'목적지에 도착합니다');
  assert(!/좌회전|우회전/.test(koreanCue({type:'unrecognized',modifier:'left'},'도로')));
});
test('cache avoids repeated requests for 60 seconds and returns independent data copies',async()=>{
  let calls=0,clock=100000;const p=make({now:()=>clock,fetchFn:async()=>{calls++;return reply();}});
  const a=await p.route(coords);a.routes[0].geometry[0][0]=99;
  clock+=59000;const b=await p.route(coords);assert.equal(calls,1);assert.equal(b.routes[0].geometry[0][0],127);
  const at=b.fetchedAt;clock+=1001;const c=await p.route(coords);assert.equal(calls,2);assert.notEqual(c.fetchedAt,at);
});
test('upstream requests serialize and respect one request per second while duplicates coalesce',async()=>{
  let clock=100000,calls=0;const starts=[],waits=[];
  const p=createRoadProvider({now:()=>clock,sleep:async ms=>{waits.push(ms);clock+=ms;},fetchFn:async()=>{calls++;starts.push(clock);return reply();}});
  const other={...coords,origin:{lat:37.0001,lon:127}};
  await Promise.all([p.route(coords),p.route(coords),p.route(other)]);
  assert.equal(calls,2);assert.deepEqual(waits,[1000]);assert.equal(starts[1]-starts[0],1000);
});
test('request queue and coordinate cache are bounded',async()=>{
  let release;const waiting=new Promise(resolve=>release=resolve);
  const busy=make({maxPending:1,fetchFn:async()=>{await waiting;return reply();}});
  const first=busy.route(coords);
  await assert.rejects(busy.route({...coords,origin:{lat:37.0001,lon:127}}),e=>e.status===429);release();await first;
  let calls=0;const p=make({maxEntries:1,fetchFn:async()=>{calls++;return reply();}});
  await p.route(coords);await p.route({...coords,origin:{lat:37.0001,lon:127}});await p.route(coords);assert.equal(calls,3);
});
test('timeout aborts upstream and failures never substitute fake routes or expose error text',async()=>{
  let signal;const p=make({timeoutMs:10,fetchFn:async(u,o)=>{signal=o.signal;return new Promise(()=>{});}});
  await assert.rejects(p.route(coords),e=>e.code==='UPSTREAM_TIMEOUT');assert(signal.aborted);
  for(const status of [429,500])await assert.rejects(make({fetchFn:async()=>reply({secret:'private'},status)}).route(coords),e=>!e.message.includes('private')&&e.status===502);
  await assert.rejects(make({fetchFn:async()=>{throw new Error('precise coordinates secret');}}).route(coords),e=>!e.message.includes('secret')&&e.code==='UPSTREAM_ERROR');
});
test('local road endpoints return status and normalized route without allowing cross-origin access',async()=>{
  let calls=0;const h=createHandler({roadProvider:{configured:true,route:async()=>{calls++;return {source:'real-road',traffic:'unavailable',routes:[]};}}});
  const status=await request(h);assert.deepEqual(status.body,{configured:true,provider:'osrm',traffic:'unavailable'});
  for(const bad of [{host:'evil.invalid:4173'},{origin:'https://evil.invalid'},{origin:'null'}])assert.equal((await request(h,{method:'POST',url:'/api/navigation/route',body:coords,...bad})).status,403);
  assert.equal(calls,0);const good=await request(h,{method:'POST',url:'/api/navigation/route',origin:'http://127.0.0.1:4173',body:coords});assert.equal(good.status,200);assert.equal(calls,1);
  assert.equal((await request(h,{method:'POST',url:'/api/navigation/route',body:'x'.repeat(8193)})).status,413);
  assert.equal((await request(h,{method:'POST',url:'/api/navigation/route',body:'{bad'})).status,400);
});
test('RoadError is translated into the existing nested public API error shape',async()=>{
  const h=createHandler({roadProvider:make({fetchFn:async()=>reply({code:'NoRoute'})})});
  const result=await request(h,{method:'POST',url:'/api/navigation/route',body:coords});assert.equal(result.status,502);assert.equal(result.body.error.code,'NO_ROUTE');
});
