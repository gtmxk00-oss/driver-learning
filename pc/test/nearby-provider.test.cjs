'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {Readable}=require('node:stream');
const {createNearbyProvider,searchParameters,normalize,distanceMeters,normalizeFuelPrice,normalizeParking,normalizeOpinetDetail,normalizeSeoulParkingRow,applyLiveRecords}=require('../server/nearby-provider.cjs');
const {createPlaceProvider}=require('../server/place-provider.cjs');
const {createPhotonGate}=require('../server/shared-photon-gate.cjs');
const {createHandler}=require('../server/http-server.cjs');
const center={lat:37.5665,lon:126.978};
const input=(kind='fuel',radiusMeters=3000)=>({kind,center:{...center},radiusMeters});
const feature=(properties={},coordinates=[126.978,37.567])=>({type:'Feature',geometry:{type:'Point',coordinates},properties:{osm_type:'N',osm_id:1,countrycode:'KR',name:'실제 주유소',city:'서울특별시',district:'중구',street:'세종대로',housenumber:'110',osm_key:'amenity',osm_value:'fuel',...properties}});
const payload=(features=[feature()])=>({type:'FeatureCollection',features});
const reply=(value=payload(),status=200,headers={})=>new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json',...headers}});
const make=options=>createNearbyProvider({minIntervalMs:0,env:{},...options});
async function request(handler,{method='POST',url='/api/nearby/search',host='127.0.0.1:4173',origin,body=input(),headers={}}={}){
  const req=Readable.from(body===undefined?[]:[typeof body==='string'?body:JSON.stringify(body)]);
  Object.assign(req,{method,url,headers:{host,...(origin?{origin}:{}),...(body!==undefined?{'content-type':'application/json'}:{}),...headers},socket:{localPort:4173}});
  let status,output,responseHeaders;const res={writeHead(s,h){status=s;responseHeaders=h;},end(value){output=value;}};
  await handler(req,res);return {status,headers:responseHeaders,body:output?JSON.parse(output):null};
}

test('nearby inputs allow only bounded Korean coordinates, explicit categories and two radii',()=>{
  for(const value of [null,[],{}, {kind:'fuel',center:[]},{kind:'fuel',center:{lat:'37',lon:127}}, {kind:'fuel',center:{lat:NaN,lon:127}}, {kind:'fuel',center:{lat:31,lon:127}}, {kind:'fuel',center:{lat:37,lon:133}}])assert.throws(()=>searchParameters(value),e=>e.code==='INVALID_CENTER');
  assert.throws(()=>searchParameters({...input(),kind:'__proto__'}),e=>e.code==='INVALID_KIND');
  for(const radiusMeters of [0,-1,2000,'3000',100000])assert.throws(()=>searchParameters({...input(),radiusMeters}),e=>e.code==='INVALID_RADIUS');
  assert.deepEqual(searchParameters({kind:'parking',center,unknown:'ignored'}),input('parking'));
});

test('manual nearby request uses fixed HTTPS Photon reverse, km radius, and exact tag without credentials',async()=>{
  let args;const provider=make({fetchFn:async(...a)=>{args=a;return reply();}});
  const result=await provider.search({...input('fuel',1000),url:'https://attacker.invalid',key:'secret'});
  const url=new URL(args[0]);assert.equal(url.origin,'https://photon.komoot.io');assert.equal(url.pathname,'/reverse');
  assert.deepEqual([...url.searchParams.entries()],[['lat','37.5665'],['lon','126.978'],['radius','1'],['limit','20'],['osm_tag','amenity:fuel']]);
  assert.equal(args[1].redirect,'error');assert.match(args[1].headers['User-Agent'],/^RouteMemory/);assert(!Object.hasOwn(args[1].headers,'Authorization'));
  assert.equal(result.source,'real-nearby');assert.equal(result.provider,'photon');assert.match(result.attribution,/Photon.*OpenStreetMap/);assert.match(result.notice,/직선거리/);
});

test('server configuration rejects local, insecure, credentialed, custom-path or query destinations',async()=>{
  for(const base of ['http://photon.komoot.io','https://user:secret@public.example','https://localhost','https://127.0.0.1','https://[::1]','https://x.internal','https://x.local','https://public.example/path','https://public.example/?key=secret']){
    const provider=make({base,fetchFn:()=>assert.fail('must not fetch')});assert.equal(provider.configured,false);await assert.rejects(provider.search(input()),e=>e.code==='INVALID_CONFIGURATION');
  }
});

test('actual category, Korean points, finite coordinates and exact requested radius are rechecked',()=>{
  const invalids=[feature({osm_value:'parking'}),feature({osm_key:'shop'}),feature({countrycode:'JP'}),feature({},[126.978,37.61]),feature({},[NaN,37.56]),feature({},['126.978',37.56]),{...feature(),geometry:{type:'Polygon',coordinates:[[126.978,37.56]]}}];
  const data=normalize(payload([...invalids,feature(),feature()]),input('fuel',1000),'now');assert.equal(data.results.length,1);
  assert.equal(data.results[0].name,'실제 주유소');assert.equal(data.results[0].address,'서울특별시 중구 세종대로 110');assert.equal(data.results[0].category,'주유소');assert.equal(data.results[0].id,'photon-N-1');assert.equal(data.results[0].distanceMeters,56);
  assert.deepEqual(normalize(payload([]),input(),'now').results,[]);
  for(const value of [{features:'invalid'},{type:'Other',features:[]},payload(Array.from({length:101},()=>feature()))])assert.throws(()=>normalize(value,input(),'now'),e=>e.code==='INVALID_RESPONSE');
});

test('results are sorted by computed straight-line distance and names/addresses/count are bounded',()=>{
  const entries=Array.from({length:30},(_,i)=>feature({osm_id:i+1,name:i===29?'':('이름'.repeat(200)),city:'서울특별시',district:'구'.repeat(300)},[126.978,37.568+(29-i)*0.0001]));
  const data=normalize(payload(entries),input(),'now');assert.equal(data.results.length,20);assert.equal(data.results[0].id,'photon-N-30');assert.equal(data.results[0].name,'주유소 (이름 미등록)');assert(data.results[1].name.length<=160);assert(data.results[1].address.length<=240);
  assert(data.results.every((value,index,array)=>!index||array[index-1].distanceMeters<=value.distanceMeters));
  assert.equal(distanceMeters(center,center.lat,center.lon),0);
  assert(Math.abs(distanceMeters(center,37.5675,126.978)-111.195)<0.01);
});

test('keyless actual POIs never synthesize prices, parking spaces or a full/available claim',()=>{
  for(const kind of ['fuel','parking']){
    const place=normalize(payload([feature({osm_value:kind,capacity:'25',price:'1700'})]),input(kind),'now').results[0];
    assert.deepEqual(place.fuelPrice,{status:'unavailable',priceWonPerLiter:null,fuelType:'gasoline',source:null,updatedAt:null,reason:'API_KEY_REQUIRED'});
    assert.deepEqual(place.parking,{status:'unavailable',capacity:null,available:null,source:null,updatedAt:null,reason:'API_KEY_REQUIRED'});
  }
});

test('keys present status does not activate unimplemented live adapters or send keys to Photon',async()=>{
  let target;const provider=make({env:{OPINET_API_KEY:'private-oil',SEOUL_PARKING_API_KEY:'private-parking'},fetchFn:async url=>{target=url;return reply();}});
  assert.deepEqual(provider.status(),{configured:true,provider:'photon',priceConfigured:false,parkingConfigured:false,keysPresent:{opinet:true,seoulParking:true},connected:false});
  const status=provider.status();status.keysPresent.opinet=false;assert.equal(provider.status().keysPresent.opinet,true);
  const data=await provider.search(input());assert.equal(data.results[0].fuelPrice.status,'unavailable');assert(!target.includes('private'));
});

test('fresh prices require source identity, supported fuel, positive value and source time; old prices are stale',()=>{
  const now=Date.parse('2026-11-05T04:00:00Z'),record={source:'opinet',sourceId:'A123',fuelType:'gasoline',priceWonPerLiter:1750,updatedAt:'2026-11-05T12:59:00+09:00'};
  const value=normalizeFuelPrice(record,'A123',now);assert.equal(value.status,'reported');assert.equal(value.priceWonPerLiter,1750);assert.equal(value.updatedAt,'2026-11-05T03:59:00.000Z');
  assert.equal(normalizeFuelPrice({...record,updatedAt:'2026-11-05T01:00:00Z'},'A123',now).status,'reported');
  assert.equal(normalizeFuelPrice({...record,updatedAt:'2026-11-04T03:59:59Z'},'A123',now).status,'stale');
  for(const patch of [{source:null},{sourceId:'other'},{fuelType:'other'},{priceWonPerLiter:0},{priceWonPerLiter:-5},{priceWonPerLiter:'1750'},{updatedAt:null},{updatedAt:'2026-11-05T04:03:00Z'}]){
    const invalid=normalizeFuelPrice({...record,...patch},'A123',now);assert.equal(invalid.status,'unavailable');assert.equal(invalid.priceWonPerLiter,null);
  }
});

test('parking zero is a real full report only with source/time; missing, impossible and stale counts stay distinct',()=>{
  const now=Date.parse('2026-11-05T04:00:00Z'),record={source:'seoul-parking',sourceId:'P123',capacity:10,available:0,updatedAt:'2026-11-05T03:59:00Z'};
  const full=normalizeParking(record,'P123',now);assert.equal(full.status,'reported');assert.equal(full.available,0);assert.equal(full.capacity,10);
  assert.equal(normalizeParking({...record,available:1,updatedAt:'2026-11-05T03:54:00Z'},'P123',now).status,'stale');
  for(const patch of [{source:null},{sourceId:'other'},{available:null},{available:-1},{available:'0'},{capacity:0,available:1},{available:0.5},{updatedAt:null},{updatedAt:'2026-11-05T05:00:00Z'}]){
    const invalid=normalizeParking({...record,...patch},'P123',now);assert.equal(invalid.status,'unavailable');assert.equal(invalid.available,null);
  }
});

test('official Opinet detail parser selects the requested product and keeps its KST report time',()=>{
  const now=Date.parse('2026-11-05T04:00:00Z'),station={UNI_ID:'A123',OIL_PRICE:[{PRODCD:'B027',PRICE:'1750',TRADE_DT:'20261105',TRADE_TM:'120000'},{PRODCD:'D047',PRICE:1600,TRADE_DT:'20261105',TRADE_TM:'110000'}]};
  const gasoline=normalizeOpinetDetail(station,'A123','gasoline',now);assert.equal(gasoline.status,'reported');assert.equal(gasoline.priceWonPerLiter,1750);assert.equal(gasoline.updatedAt,'2026-11-05T03:00:00.000Z');
  assert.equal(normalizeOpinetDetail(station,'A123','diesel',now).priceWonPerLiter,1600);
  assert.equal(normalizeOpinetDetail(station,'other','gasoline',now).status,'unavailable');
  assert.equal(normalizeOpinetDetail(station,'A123','premium',now).status,'unavailable');
  const invalid={...station,OIL_PRICE:[{...station.OIL_PRICE[0],TRADE_DT:'20260231'}]};assert.equal(normalizeOpinetDetail(invalid,'A123','gasoline',now).status,'unavailable');
});

test('official Seoul current-vehicle count is occupancy, subtract exactly once and retain stale/unknown/full',()=>{
  const now=Date.parse('2026-11-05T04:00:00Z'),row={PKLT_CD:'00123',PRK_STTS_YN:'1',TPKCT:100,NOW_PRK_VHCL_CNT:90,NOW_PRK_VHCL_UPDT_TM:'2026-11-05 12:59:00'};
  const current=normalizeSeoulParkingRow(row,'00123',now);assert.equal(current.available,10);assert.equal(current.capacity,100);assert.equal(current.status,'reported');
  assert.equal(normalizeSeoulParkingRow({...row,NOW_PRK_VHCL_CNT:100},'00123',now).available,0);
  assert.equal(normalizeSeoulParkingRow({...row,NOW_PRK_VHCL_UPDT_TM:'2026-11-05 12:50:00'},'00123',now).status,'stale');
  for(const patch of [{PRK_STTS_YN:'0'},{NOW_PRK_VHCL_CNT:null},{NOW_PRK_VHCL_CNT:101},{NOW_PRK_VHCL_UPDT_TM:''},{PKLT_CD:'123'}])assert.equal(normalizeSeoulParkingRow({...row,...patch},'00123',now).status,'unavailable');
});

test('live seam attaches only an exact verified provider binding and rejects ambiguous/unmatched records',()=>{
  const now=Date.parse('2026-11-05T04:00:00Z'),record={source:'opinet',sourceId:'A123',fuelType:'gasoline',priceWonPerLiter:1750,updatedAt:'2026-11-05T03:59:00Z'};
  const data=()=>normalize(payload(),input(),new Date(now).toISOString());
  const bindings=new Map([['photon-N-1',{source:'opinet',sourceId:'A123'}]]);
  assert.equal(applyLiveRecords(data(),[record],bindings,now).results[0].fuelPrice.status,'reported');
  for(const [records,map] of [[[record],new Map()],[[{...record,sourceId:'wrong'}],bindings],[[record,record],bindings],[[record],new Map([['photon-N-1',{source:'other',sourceId:'A123'}]])]])assert.equal(applyLiveRecords(data(),records,map,now).results[0].fuelPrice.status,'unavailable');
});

test('nationwide parking seams accept exact verified operator IDs and never infer provider matches',()=>{
  const now=Date.parse('2026-11-05T04:00:00Z'),data=()=>normalize(payload([feature({osm_value:'parking'})]),input('parking'),new Date(now).toISOString());
  const record={source:'national-parking',sourceId:'operator-123',capacity:100,available:7,updatedAt:'2026-11-05T03:59:00Z'};
  const binding=new Map([['photon-N-1',{source:'national-parking',sourceId:'operator-123'}]]);
  const current=applyLiveRecords(data(),[record],binding,now).results[0].parking;assert.equal(current.status,'reported');assert.equal(current.available,7);assert.equal(current.source,'national-parking');
  assert.equal(applyLiveRecords(data(),[{...record,source:'other-operator'}],binding,now).results[0].parking.status,'unavailable');
  assert.equal(normalizeParking(record,'operator-123',now,'unsafe source<script>').status,'unavailable');
});

test('internal live adapter is invoked for bound IDs only and failures preserve real places',async()=>{
  let called=0;const bindings=new Map([['photon-N-1',{source:'opinet',sourceId:'A123'}]]);
  const provider=make({fetchFn:async()=>reply(),identityBindings:bindings,liveAdapter:{read:async({kind,places,signal})=>{called++;assert.equal(kind,'fuel');assert.equal(places[0].binding.sourceId,'A123');assert.equal(signal.aborted,false);throw new Error('private provider failure');}}});
  const data=await provider.search(input());assert.equal(called,1);assert.equal(data.results.length,1);assert.equal(data.results[0].fuelPrice.status,'unavailable');
  const unbound=make({fetchFn:async()=>reply(),liveAdapter:{read:async()=>assert.fail('must not call without source binding')}});await unbound.search(input());
});

test('60-second memory cache is separate by center/category/radius, bounded and safely cloned',async()=>{
  let time=100,calls=0;const provider=make({now:()=>time,maxEntries:2,cacheMs:60,fetchFn:async()=>{calls++;return reply();}});
  const first=await provider.search(input());first.results[0].name='mutated';assert.equal((await provider.search(input())).results[0].name,'실제 주유소');assert.equal(calls,1);
  await provider.search(input('parking'));await provider.search(input('fuel',1000));await provider.search(input());assert.equal(calls,4);
  time+=61;await provider.search(input());assert.equal(calls,5);
});

test('same request coalesces; bounded queue and Retry-After prevent provider flooding',async()=>{
  let release,calls=0;const held=new Promise(resolve=>release=resolve);const provider=make({maxPending:1,fetchFn:async()=>{calls++;await held;return reply();}});
  const one=provider.search(input()),two=provider.search(input());await assert.rejects(provider.search(input('parking')),e=>e.code==='BUSY');release();assert.deepEqual(await one,await two);assert.equal(calls,1);
  let time=100,limitedCalls=0;const limited=make({now:()=>time,fetchFn:async()=>{limitedCalls++;return reply({},429,{'Retry-After':'10'});}});
  await assert.rejects(limited.search(input()),e=>e.code==='UPSTREAM_LIMIT');await assert.rejects(limited.search(input('parking')),e=>e.code==='UPSTREAM_LIMIT');assert.equal(limitedCalls,1);time+=10001;await assert.rejects(limited.search(input()));assert.equal(limitedCalls,2);
});

test('address and nearby providers share one-second upstream start spacing',async()=>{
  let time=100;const starts=[],gate=createPhotonGate({now:()=>time,sleep:async ms=>{time+=ms;}});
  const fetchFn=async url=>{starts.push(time);return reply(new URL(url).pathname==='/reverse'?payload():payload([feature({osm_value:'cafe'})]));};
  const nearby=make({fetchFn,now:()=>time,photonGate:gate}),places=createPlaceProvider({fetchFn,now:()=>time,minIntervalMs:0,photonGate:gate});
  await Promise.all([nearby.search(input()),places.search({query:'서울역'}),nearby.search(input('parking'))]);assert.deepEqual(starts,[100,1100,2100]);
});

test('oversized bodies, invalid JSON, upstream failures and timeouts return bounded public errors',async()=>{
  for(const headers of [{'Content-Length':'1000000'},{}]){const provider=make({maxBytes:64,fetchFn:async()=>reply(payload(),200,headers)});await assert.rejects(provider.search(input()),e=>e.code==='RESPONSE_TOO_LARGE');}
  const malformed=make({fetchFn:async()=>new Response('secret upstream page')});await assert.rejects(malformed.search(input()),e=>e.code==='INVALID_RESPONSE'&&!e.message.includes('secret'));
  const broken=make({fetchFn:async()=>{throw new Error('secret coordinate and API key');}});await assert.rejects(broken.search(input()),e=>e.code==='UPSTREAM_ERROR'&&!e.message.includes('secret'));
  let signal;const hung=make({timeoutMs:10,fetchFn:async(url,options)=>{signal=options.signal;return new Promise(()=>{});}});await assert.rejects(hung.search(input()),e=>e.code==='UPSTREAM_TIMEOUT');assert.equal(signal.aborted,true);
});

test('nearby HTTP endpoints retain same-origin loopback guards and nested errors without upstream calls on status',async()=>{
  let calls=0;const provider=make({fetchFn:async()=>{calls++;return reply();}}),handler=createHandler({nearbyProvider:provider});
  for(const options of [{host:'attacker.invalid:4173'},{origin:'https://attacker.invalid'}])assert.equal((await request(handler,options)).status,403);assert.equal(calls,0);
  const status=await request(handler,{method:'GET',url:'/api/nearby/status'});assert.equal(status.status,200);assert.equal(status.body.connected,false);assert.equal(calls,0);
  const found=await request(handler);assert.equal(found.status,200);assert.equal(found.body.results[0].category,'주유소');assert.equal(found.headers['Cache-Control'],'no-store');
  assert.equal((await request(handler,{body:{...input(),center:{lat:0,lon:0}}})).body.error.code,'INVALID_CENTER');
  assert.equal((await request(handler,{body:'x'.repeat(9000)})).status,413);
  assert.equal((await request(handler,{headers:{'content-type':'text/plain'}})).status,415);
});
