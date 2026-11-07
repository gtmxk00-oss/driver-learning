'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const tracker=require('../src/road-tracker.js');

// Exercise the actual UI event wiring without a browser, network or device GPS.
// The DOM stub exposes events/properties only; no rendering implementation is copied.
function harness({route:customRoute,placeSearch,nearbySearch,nearbyStatus,mapsStatus}={}){
  let clock=Date.UTC(2026,10,4,0,0,0);
  const elements=new Map(),documentEvents=new Map(),windowEvents=new Map(),intervals=new Map();
  const requests=[],watches=[],cleared=[],spoken=[],mapCalls=[],tooltips=[],mapEvents=new Map(),tileLayers=[];
  let intervalId=0;
  class Element {
    constructor(id){this.id=id;this.value='';this.textContent='';this.innerHTML='';this.disabled=false;this.dataset={};this.listeners=new Map();this.attributes={};this.classList={toggle(){}};}
    addEventListener(type,fn){this.listeners.set(type,fn);}
    setAttribute(name,value){this.attributes[name]=value;}
    emit(type,event={}){return this.listeners.get(type)?.({preventDefault(){},...event});}
    click(){if(!this.disabled)this.emit('click');}
  }
  const el=id=>{if(!elements.has(id))elements.set(id,new Element(id));return elements.get(id);};
  const pointButtons=['origin','destination'].map(name=>{const b=new Element(name);b.dataset.realPoint=name;return b;});
  const doc={hidden:false,getElementById:el,querySelectorAll:selector=>selector==='[data-real-point]'?pointButtons:[],addEventListener(type,fn){documentEvents.set(type,fn);}};
  el('realOrigin').value='37, 127';el('realDestination').value='37.01, 127.01';
  function layer(){const events=new Map();return {events,addTo(target){target?.layers?.add(this);return this;},on(name,fn){events.set(name,fn);return this;},bindTooltip(text){tooltips.push(text);return this;},bringToFront(){return this;},getBounds(){return {};},setLatLng(){return this;},setRadius(){return this;}};}
  const map={layers:new Set(),on(type,fn){mapEvents.set(type,fn);return this;},removeLayer(value){this.layers.delete(value);},panTo(p){mapCalls.push(['pan',p]);return this;},setView(p){mapCalls.push(['view',p]);return this;},fitBounds(){mapCalls.push(['fit']);return this;},invalidateSize(){}};
  const L={map(){mapCalls.push(['init']);return map;},tileLayer(url,options){const value=layer();value.url=url;value.options=options;tileLayers.push(value);return value;},circleMarker:layer,circle:layer,polyline:layer,control:{scale:layer}};
  const route=customRoute||{
    id:'synthetic-road',distanceMeters:2000,durationSeconds:200,
    geometry:[[127,37],[127.01,37],[127.01,37.01]],steps:[
      {id:'depart',name:'Test Road A',distanceMeters:900,durationSeconds:90,cue:'출발하세요',maneuver:{type:'depart',modifier:'',location:[127,37]}},
      {id:'turn',name:'Test Road B',distanceMeters:1100,durationSeconds:110,cue:'좌회전하세요',maneuver:{type:'turn',modifier:'left',location:[127.01,37]}},
      {id:'arrive',name:'Test destination',distanceMeters:0,durationSeconds:0,cue:'목적지 도착',maneuver:{type:'arrive',modifier:'',location:[127.01,37.01]}}
    ]
  };
  class TestDate extends Date{static now(){return clock;}}
  const context={
    document:doc,L,RMRoadTracker:tracker,location:{protocol:'http:'},Date:TestDate,AbortSignal:{timeout(){return {}; }},
    navigator:{geolocation:{watchPosition(success,error,options){watches.push({success,error,options});return watches.length;},clearWatch(id){cleared.push(id);}}},
    fetch:async(url,options)=>{
      const body=options.body?JSON.parse(options.body):undefined;requests.push({url,body});
      if(url==='/api/places/search')return placeSearch?await placeSearch(body.query):{ok:true,json:async()=>({source:'real-places',provider:'photon',query:body.query,results:[{id:'test-place',name:'테스트역',address:'테스트시 테스트로 12',lat:37.01,lon:127.01,category:'철도역'}]})};
      if(url==='/api/nearby/search')return nearbySearch?await nearbySearch(body):nearbyResponse(body);
      if(url==='/api/nearby/status')return nearbyStatus?await nearbyStatus():{ok:true,json:async()=>({configured:true,provider:'photon',priceConfigured:false,parkingConfigured:false,keysPresent:{opinet:true,seoulParking:true},connected:false})};
      if(url==='/api/maps/status')return mapsStatus?await mapsStatus():{ok:true,json:async()=>({provider:'vworld',configured:false,authentication:'unverified',maxNativeZoom:18})};
      return {ok:true,json:async()=>({source:'real-road',provider:'osrm',routes:[route],waypoints:[{distanceMeters:0},{distanceMeters:0}]})};
    },
    setInterval(fn){const id=++intervalId;intervals.set(id,fn);return id;},clearInterval(id){intervals.delete(id);},setTimeout(fn){fn();return 1;},
    isSecureContext:true,RMApp:{isLearningBusy:()=>false,getCurrentView:()=>'real',speak:value=>spoken.push(value),stopSpeech(){}},
    addEventListener(type,fn){windowEvents.set(type,fn);}
  };
  context.window=context;
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../src/real-road-ui.js'),'utf8'),context,{filename:'real-road-ui.js'});
  const fix=(lon=127,lat=37,accuracy=10)=>({coords:{longitude:lon,latitude:lat,accuracy},timestamp:clock});
  return {el,requests,watches,cleared,spoken,mapCalls,tooltips,context,map,tileLayers,
    gps(lon=127,lat=37,accuracy=10){watches.at(-1).success(fix(lon,lat,accuracy));},
    advanceClock(ms){clock+=ms;},
    watchdog(){for(const fn of [...intervals.values()])fn();},
    windowEvent(type){windowEvents.get(type)?.();},
    documentEvent(type,detail){documentEvents.get(type)?.({detail});},
    mapClick(lat,lon){mapEvents.get('click')?.({latlng:{lat,lng:lon}});},
    async plan(){el('realRouteForm').emit('submit');await new Promise(setImmediate);},
    async lookup(query){el('realPlaceQuery').value=query;el('realPlaceQuery').emit('input');el('realPlaceSearch').click();await new Promise(setImmediate);},
    async choosePlace(index=0){el('realPlaceResults').emit('click',{target:{closest:()=>({dataset:{realPlace:String(index)}})}});await new Promise(setImmediate);},
    async nearby(kind='fuel'){el(kind==='fuel'?'realNearbyFuel':'realNearbyParking').click();el('realNearbySearch').click();await new Promise(setImmediate);},
    async chooseNearby(index=0){el('realNearbyResults').emit('click',{target:{closest:()=>({dataset:{realNearby:String(index)}})}});await new Promise(setImmediate);},
    async flush(){await new Promise(setImmediate);}
  };
}

function nearbyResponse(body,results){
  return {ok:true,json:async()=>({source:'real-nearby',provider:'photon',kind:body.kind,center:body.center,radiusMeters:body.radiusMeters,fetchedAt:'2026-11-04T00:00:00Z',attribution:'Photon · © OpenStreetMap contributors',notice:'실시간 요금·잔여 면수 미연결',results:results||[{id:'nearby-test',name:body.kind==='fuel'?'테스트 주유소':'테스트 주차장',address:'테스트시 주변로 15',category:body.kind,lat:body.center.lat+.001,lon:body.center.lon,distanceMeters:111,fuelPrice:{status:'unavailable',priceWonPerLiter:null,fuelType:'gasoline',source:null,updatedAt:null},parking:{status:'unavailable',capacity:null,available:null,source:null,updatedAt:null}}]})};
}

test('real UI initialization never requests device location or a road route',()=>{
  const h=harness();
  assert.equal(h.watches.length,0);assert.equal(h.requests.length,0);
  assert.equal(h.context.RMRealRoadUI.isActive(),false);
  assert.equal(h.el('realStart').disabled,true);
});

test('GPS is requested only by its button with high-accuracy options; stopping invalidates late callbacks',()=>{
  const h=harness();h.el('realGpsToggle').click();
  assert.equal(h.watches.length,1);
  assert.equal(h.watches[0].options.enableHighAccuracy,true);
  assert.equal(h.watches[0].options.timeout,12000);
  h.gps();assert.equal(h.el('realOrigin').value,'37.000000, 127.000000');assert.equal(h.requests.length,0);
  h.el('realGpsToggle').click();
  assert.deepEqual(h.cleared,[1]);assert.equal(h.context.RMRealRoadUI.isActive(),false);
  const positionAfterStop=h.el('realOrigin').value,mapCallCount=h.mapCalls.length;
  h.advanceClock(1000);h.gps(128,38);
  assert.equal(h.el('realOrigin').value,positionAfterStop);assert.equal(h.mapCalls.length,mapCallCount);
  assert.equal(h.el('realReroute').disabled,true);
});

test('poor-accuracy GPS cannot start guidance or be used for rerouting',async()=>{
  const h=harness();h.el('realGpsToggle').click();h.gps(127,37,150);await h.plan();
  assert.equal(h.requests.length,1);assert.equal(h.el('realStart').disabled,true);assert.equal(h.el('realReroute').disabled,true);
  h.el('realReroute').click();await h.flush();assert.equal(h.requests.length,1);
});

test('a rejected physical GPS jump remains ineligible for rerouting after watchdog duplicate updates',async()=>{
  const h=harness();h.el('realGpsToggle').click();h.gps();await h.plan();h.el('realStart').click();
  assert.equal(h.el('realOrigin').disabled,true);
  h.advanceClock(1000);h.gps(127.01,37.01,10);
  assert.equal(h.el('realReroute').disabled,true);assert.match(h.el('realTurnCue').textContent,/위치 오차/);
  h.watchdog();assert.equal(h.el('realReroute').disabled,true);
  h.el('realReroute').click();await h.flush();assert.equal(h.requests.length,1);
  h.advanceClock(1000);h.gps(127.00001,37,10);
  assert.equal(h.el('realReroute').disabled,false);
});

test('guidance cannot start if the real route begins far from the current GPS fix',async()=>{
  const shifted={id:'distant-start',distanceMeters:2000,durationSeconds:200,
    geometry:[[127.004,37],[127.014,37],[127.014,37.01]],steps:[
      {id:'depart',name:'A',distanceMeters:900,durationSeconds:90,cue:'출발하세요',maneuver:{type:'depart',location:[127.004,37]}},
      {id:'turn',name:'B',distanceMeters:1100,durationSeconds:110,cue:'좌회전하세요',maneuver:{type:'turn',modifier:'left',location:[127.014,37]}},
      {id:'arrive',name:'end',distanceMeters:0,durationSeconds:0,cue:'목적지 도착',maneuver:{type:'arrive',location:[127.014,37.01]}}
    ]};
  const h=harness({route:shifted});h.el('realGpsToggle').click();h.gps();await h.plan();
  assert.equal(h.el('realStart').disabled,false);
  h.el('realStart').click();
  assert.match(h.el('realStatus').textContent,/경로 출발점과 떨어져/);
  assert.equal(h.el('realGuidanceMode').textContent,'경로 미리 보기');
  assert.equal(h.spoken.length,0);
});

test('permission denial and leaving the page clear an active GPS watch',()=>{
  const denied=harness();denied.el('realGpsToggle').click();denied.watches[0].error({code:1});
  assert.deepEqual(denied.cleared,[1]);assert.equal(denied.context.RMRealRoadUI.isActive(),false);
  assert.match(denied.el('realStatus').textContent,/권한이 허용되지/);
  const leaving=harness();leaving.el('realGpsToggle').click();leaving.gps();leaving.windowEvent('pagehide');
  assert.deepEqual(leaving.cleared,[1]);assert.equal(leaving.context.RMRealRoadUI.isActive(),false);
});

test('typing never searches; explicit Enter searches names without GPS or a road query',async()=>{
  const h=harness();h.el('realPlaceQuery').value='경복궁';h.el('realPlaceQuery').emit('input');await h.flush();
  assert.equal(h.requests.length,0);assert.equal(h.watches.length,0);
  h.el('realPlaceQuery').emit('keydown',{key:'Enter'});await h.flush();
  assert.equal(h.requests.length,1);assert.deepEqual(h.requests[0],{url:'/api/places/search',body:{query:'경복궁'}});
  assert.match(h.el('realPlaceResults').innerHTML,/테스트역/);assert.match(h.el('realPlaceResults').innerHTML,/테스트시 테스트로 12/);
  assert.equal(h.watches.length,0);assert.equal(h.el('realStart').disabled,true);
});

test('a selected named result sets the destination and requests its real route without starting GPS or guidance',async()=>{
  const h=harness();await h.lookup('테스트역');await h.choosePlace();
  assert.equal(h.el('realDestination').value,'37.010000, 127.010000');
  assert.equal(h.el('realDestinationName').textContent,'테스트역으로 안내');
  assert.match(h.el('realSelectedAddress').textContent,/테스트시 테스트로 12/);
  assert.equal(h.requests.length,2);assert.equal(h.requests[1].url,'/api/navigation/route');
  assert.deepEqual(h.requests[1].body.destination,{lat:37.01,lon:127.01});
  assert.equal(h.el('realTripEditor').open,false);assert.equal(h.el('realPlaceResults').innerHTML,'');
  assert.equal(h.watches.length,0);assert.equal(h.spoken.length,0);assert.equal(h.el('realStart').disabled,true);
});

test('place selection uses a fresh explicitly enabled GPS origin, retaining the chosen name after first GPS refresh',async()=>{
  const h=harness();await h.lookup('테스트역');await h.choosePlace();
  h.el('realGpsToggle').click();h.gps();
  assert.equal(h.el('realDestinationName').textContent,'테스트역으로 안내');
  h.el('realOrigin').value='35, 128';await h.lookup('테스트역');await h.choosePlace();
  assert.deepEqual(h.requests.at(-1).body.origin,{lat:37,lon:127});
  assert.equal(h.watches.length,1);assert.equal(h.spoken.length,0);
});

test('query validation and empty/error results do not fabricate destinations or route requests',async()=>{
  const invalid=harness();await invalid.lookup('가');await invalid.lookup('a'.repeat(121));
  assert.equal(invalid.requests.length,0);assert.match(invalid.el('realPlaceStatus').textContent,/2~120/);
  const empty=harness({placeSearch:async()=>({ok:true,json:async()=>({source:'real-places',provider:'photon',results:[]})})});
  await empty.lookup('없는 장소');assert.match(empty.el('realPlaceStatus').textContent,/검색 결과가 없습니다/);assert.equal(empty.requests.length,1);
  assert.equal(empty.el('realDestination').value,'37.01, 127.01');
  const failed=harness({placeSearch:async()=>({ok:false,json:async()=>({error:{message:'잠시 후 다시 검색해 주세요.'}})})});
  await failed.lookup('테스트역');assert.equal(failed.el('realPlaceResults').innerHTML,'');assert.match(failed.el('realPlaceStatus').textContent,/잠시 후/);
  assert.equal(failed.requests.length,1);assert.equal(failed.watches.length,0);
});

test('out-of-order place responses cannot replace newer results or reappear after an editor closes',async()=>{
  const pending=[];
  const h=harness({placeSearch:query=>new Promise(resolve=>pending.push({query,resolve}))});
  const result=name=>({ok:true,json:async()=>({source:'real-places',provider:'photon',results:[{id:name,name,address:'테스트 주소',lat:37.01,lon:127.01}]})});
  await h.lookup('첫 번째');await h.lookup('두 번째');
  pending[1].resolve(result('최신 결과'));await h.flush();assert.match(h.el('realPlaceResults').innerHTML,/최신 결과/);
  pending[0].resolve(result('지난 결과'));await h.flush();assert.doesNotMatch(h.el('realPlaceResults').innerHTML,/지난 결과/);
  await h.lookup('닫기 전 검색');h.el('realTripEditor').open=false;h.el('realTripEditor').emit('toggle');
  pending[2].resolve(result('닫은 뒤 결과'));await h.flush();assert.equal(h.el('realPlaceResults').innerHTML,'');
});

test('leaving the real map invalidates a pending lookup even when GPS is off',async()=>{
  let resolve;
  const h=harness({placeSearch:()=>new Promise(done=>resolve=done)});await h.lookup('테스트역');
  h.documentEvent('rm-view-changed',{view:'drive'});
  resolve({ok:true,json:async()=>({source:'real-places',provider:'photon',results:[{name:'늦은 결과',lat:37,lon:127}]})});
  await h.flush();assert.equal(h.el('realPlaceResults').innerHTML,'');assert.equal(h.watches.length,0);
});

test('provider text is escaped, malformed points are discarded, and manually changed destinations clear named labels',async()=>{
  const name='<img src=x onerror=alert(1)>';
  const h=harness({placeSearch:async()=>({ok:true,json:async()=>({source:'real-places',provider:'photon',results:[
    {id:'bad',name:'잘못된 좌표',lat:'37',lon:127},
    {id:'safe',name,address:'<script>text</script>',lat:37.01,lon:127.01,category:'역'}]})})});
  await h.lookup('테스트');assert.doesNotMatch(h.el('realPlaceResults').innerHTML,/<img|<script|잘못된 좌표/);
  assert.match(h.el('realPlaceResults').innerHTML,/&lt;img/);await h.choosePlace();
  assert.equal(h.el('realDestinationName').textContent,name+'으로 안내');
  assert.ok(h.tooltips.includes('&lt;img src=x onerror=alert(1)&gt;'));
  h.mapClick(37.02,127.02);assert.equal(h.el('realDestinationName').textContent,'선택한 목적지로 안내');assert.equal(h.el('realSelectedAddress').hidden,true);
});

test('nearby controls never query or enable GPS on opening, and clearly label the public sample center',async()=>{
  const h=harness();h.el('realSample').click();h.el('realNearbyFuel').click();
  assert.equal(h.el('realNearbyPanel').hidden,false);assert.match(h.el('realNearbyCenterLabel').textContent,/공개 샘플.*내 위치가 아닙니다/);
  assert.equal(h.requests.length,0);assert.equal(h.watches.length,0);
  h.el('realNearbyCenter').value='current';h.el('realNearbyCenter').emit('change');
  assert.equal(h.el('realNearbySearch').disabled,true);assert.equal(h.el('realNearbyGps').hidden,false);
  h.el('realNearbySearch').click();await h.flush();assert.equal(h.requests.length,0);assert.equal(h.watches.length,0);
  h.el('realNearbyGps').click();assert.equal(h.watches.length,1);assert.equal(h.requests.length,0);
  assert.equal(h.el('realNearbySearch').disabled,true);h.gps();assert.equal(h.el('realNearbySearch').disabled,false);
  h.el('realNearbySearch').click();await h.flush();
  assert.deepEqual(h.requests[0],{url:'/api/nearby/search',body:{center:{lat:37,lon:127},kind:'fuel',radiusMeters:3000}});
  assert.equal(h.watches.length,1);assert.equal(h.spoken.length,0);
});

test('manual nearby destination lookup sends only its selected center and preserves unknown prices and vacancy',async()=>{
  const h=harness();await h.nearby('fuel');
  assert.deepEqual(h.requests[0],{url:'/api/nearby/search',body:{center:{lat:37.01,lon:127.01},kind:'fuel',radiusMeters:3000}});
  assert.match(h.el('realNearbyResults').innerHTML,/테스트 주유소/);assert.match(h.el('realNearbyResults').innerHTML,/직선 111 m/);
  assert.match(h.el('realNearbyResults').innerHTML,/유가 연동 대기/);assert.doesNotMatch(h.el('realNearbyResults').innerHTML,/0원|잔여 0면/);
  assert.match(h.el('realNearbyAttribution').textContent,/Photon/);assert.match(h.el('realNearbyQueryLabel').textContent,/조회 기준.*3 km/);
  await h.nearby('parking');assert.match(h.el('realNearbyResults').innerHTML,/잔여 면수 미제공/);
  assert.doesNotMatch(h.el('realNearbyResults').innerHTML,/만차|잔여 0면/);assert.match(h.el('realNearbyResults').innerHTML,/주차 가능 여부 확인 불가/);assert.equal(h.watches.length,0);
});

test('nearby route selection uses the actual POI name and address, previews its route and never starts GPS',async()=>{
  const h=harness();await h.nearby('parking');await h.chooseNearby();
  assert.equal(h.el('realDestinationName').textContent,'테스트 주차장으로 안내');assert.equal(h.el('realNearbyPanel').hidden,true);
  assert.match(h.el('realSelectedAddress').textContent,/테스트시 주변로 15/);assert.equal(h.el('realNearbyResults').innerHTML,'');
  assert.equal(h.requests.length,2);assert.equal(h.requests[1].url,'/api/navigation/route');
  assert.deepEqual(h.requests[1].body.destination,{lat:37.011,lon:127.01});
  assert.equal(h.watches.length,0);assert.equal(h.spoken.length,0);assert.equal(h.el('realStart').disabled,true);
});

test('kind, radius and center changes invalidate older nearby responses and refresh only on explicit search',async()=>{
  const pending=[];
  const h=harness({nearbySearch:body=>new Promise(resolve=>pending.push({body,resolve}))});
  await h.nearby();h.el('realNearbyKindParking').click();
  assert.equal(h.el('realNearbyResults').innerHTML,'');assert.equal(h.requests.length,1);
  h.el('realNearbyRadius').value='1000';h.el('realNearbyRadius').emit('change');
  assert.equal(h.requests.length,1);h.el('realNearbySearch').click();await h.flush();
  assert.equal(h.requests[1].body.kind,'parking');assert.equal(h.requests[1].body.radiusMeters,1000);
  pending[1].resolve(nearbyResponse(pending[1].body));await h.flush();assert.match(h.el('realNearbyResults').innerHTML,/테스트 주차장/);
  pending[0].resolve(nearbyResponse(pending[0].body));await h.flush();assert.doesNotMatch(h.el('realNearbyResults').innerHTML,/테스트 주유소/);
  h.el('realNearbyCenter').value='current';h.el('realNearbyCenter').emit('change');
  assert.equal(h.el('realNearbyResults').innerHTML,'');assert.equal(h.el('realNearbySearch').disabled,true);
  assert.equal(h.watches.length,0);assert.equal(h.requests.length,2);
});

test('nearby GPS centers expire or move before responses arrive; stale results cannot appear',async()=>{
  const pending=[];
  const h=harness({nearbySearch:body=>new Promise(resolve=>pending.push({body,resolve}))});
  h.el('realGpsToggle').click();h.gps();h.el('realNearbyFuel').click();h.el('realNearbyCenter').value='current';h.el('realNearbyCenter').emit('change');
  h.el('realNearbySearch').click();await h.flush();h.advanceClock(16000);h.watchdog();
  assert.equal(h.el('realNearbySearch').disabled,true);assert.match(h.el('realNearbyStatus').textContent,/GPS 상태/);
  pending[0].resolve(nearbyResponse(pending[0].body));await h.flush();assert.equal(h.el('realNearbyResults').innerHTML,'');
  h.gps();h.el('realNearbySearch').click();await h.flush();h.advanceClock(1000);h.gps(127.01,37);
  pending[1].resolve(nearbyResponse(pending[1].body));await h.flush();assert.equal(h.el('realNearbyResults').innerHTML,'');
  assert.equal(h.requests.length,2);assert.equal(h.watches.length,1);
});

test('closed, hidden and changed destinations cannot receive late nearby results',async()=>{
  const pending=[];
  const h=harness({nearbySearch:body=>new Promise(resolve=>pending.push({body,resolve}))});
  await h.nearby();h.el('realNearbyClose').click();pending[0].resolve(nearbyResponse(pending[0].body));await h.flush();
  assert.equal(h.el('realNearbyResults').innerHTML,'');assert.equal(h.el('realNearbyPanel').hidden,true);
  await h.nearby();h.mapClick(37.02,127.02);pending[1].resolve(nearbyResponse(pending[1].body));await h.flush();
  assert.equal(h.el('realNearbyResults').innerHTML,'');assert.match(h.el('realNearbyStatus').textContent,/목적지가 바뀌었습니다/);
  await h.nearby();h.documentEvent('rm-view-changed',{view:'drive'});pending[2].resolve(nearbyResponse(pending[2].body));await h.flush();
  assert.equal(h.el('realNearbyResults').innerHTML,'');assert.equal(h.el('realNearbyPanel').hidden,true);
  await h.nearby();h.context.document.hidden=true;h.documentEvent('visibilitychange');pending[3].resolve(nearbyResponse(pending[3].body));await h.flush();
  assert.equal(h.el('realNearbyResults').innerHTML,'');assert.equal(h.watches.length,0);
});

test('reported zero occupancy means full; unknown and static capacity never become available parking',async()=>{
  const h=harness({nearbySearch:body=>nearbyResponse(body,[
    {name:'실제 만차',lat:37.011,lon:127.01,parking:{status:'reported',available:0,capacity:50,source:'운영자 시험 데이터',updatedAt:'2026-11-04T00:00:00Z'}},
    {name:'규모만 제공',lat:37.012,lon:127.01,parking:{status:'unavailable',available:null,capacity:100}},
    {name:'지난 빈자리',lat:37.013,lon:127.01,parking:{status:'stale',available:8,capacity:40,source:'지난 운영자 보고',updatedAt:'2026-11-03T23:00:00Z'}},
    {name:'출처 누락',lat:37.014,lon:127.01,parking:{status:'reported',available:9,capacity:30,source:null,updatedAt:'2026-11-04T00:00:00Z'}}
  ])});
  await h.nearby('parking');const html=h.el('realNearbyResults').innerHTML;
  assert.match(html,/만차 · 잔여 0면 · 최신 보고/);assert.match(html,/운영자 시험 데이터/);
  assert.match(html,/지도 등록 규모 100면 · 현재 빈자리와 다릅니다/);
  assert.match(html,/과거 보고: 잔여 8면 · 현재 확인 불가/);assert.match(html,/지난 운영자 보고/);
  assert.doesNotMatch(html,/잔여 9면/);assert.equal((html.match(/잔여 면수 미제공/g)||[]).length,2);
});

test('fuel displays only sourced timestamped report prices and distinguishes stale prices from current values',async()=>{
  const h=harness({nearbySearch:body=>nearbyResponse(body,[
    {name:'보고 유가',lat:37.011,lon:127.01,fuelPrice:{status:'reported',priceWonPerLiter:1720,fuelType:'gasoline',source:'오피넷 시험 데이터',updatedAt:'2026-11-04T00:00:00Z'}},
    {name:'과거 유가',lat:37.012,lon:127.01,fuelPrice:{status:'stale',priceWonPerLiter:1630,fuelType:'diesel',source:'지난 보고',updatedAt:'2026-11-02T00:00:00Z'}},
    {name:'시각 누락',lat:37.013,lon:127.01,fuelPrice:{status:'reported',priceWonPerLiter:1999,fuelType:'gasoline',source:'시각 없음',updatedAt:null}},
    {name:'잘못된 0원',lat:37.014,lon:127.01,fuelPrice:{status:'reported',priceWonPerLiter:0,fuelType:'gasoline',source:'잘못된 데이터',updatedAt:'2026-11-04T00:00:00Z'}}
  ])});
  await h.nearby();const html=h.el('realNearbyResults').innerHTML;
  assert.match(html,/최신 보고 가격 · 휘발유 1,720원\/L/);assert.match(html,/오피넷 시험 데이터/);
  assert.match(html,/과거 보고 가격 · 경유 1,630원\/L/);assert.match(html,/현재 가격 확인 불가/);
  assert.doesNotMatch(html,/1,999원\/L| 0원\/L/);assert.equal((html.match(/유가 연동 대기/g)||[]).length,2);
});

test('nearby provider text is escaped, invalid points rejected and a mismatched response center cannot be shown',async()=>{
  const h=harness({nearbySearch:body=>nearbyResponse(body,[
    {name:'<img src=x>',address:'<script>x</script>',lat:37.011,lon:127.01},
    {name:'좌표 문자열',lat:'37.01',lon:127.01},
    {name:'반경 밖',lat:38,lon:127.01}
  ])});
  await h.nearby();assert.match(h.el('realNearbyResults').innerHTML,/&lt;img/);assert.doesNotMatch(h.el('realNearbyResults').innerHTML,/<img|<script|좌표 문자열|반경 밖/);
  const invalid=harness({nearbySearch:body=>nearbyResponse({...body,center:{lat:35,lon:129}})});
  await invalid.nearby();assert.equal(invalid.el('realNearbyResults').innerHTML,'');assert.match(invalid.el('realNearbyStatus').textContent,/올바른 주변 시설 응답/);
});

test('connection status checks never send coordinates or infer data connection from key presence',async()=>{
  const h=harness();h.el('realNearbyFuel').click();h.el('realNearbyConnection').click();await h.flush();
  assert.equal(h.requests.length,1);assert.deepEqual(h.requests[0],{url:'/api/nearby/status',body:undefined});
  assert.match(h.el('realNearbyConnectionStatus').textContent,/지도 시설 검색: 사용 가능/);
  assert.match(h.el('realNearbyConnectionStatus').textContent,/유가: 미연결/);assert.match(h.el('realNearbyConnectionStatus').textContent,/면수: 미연결/);
  assert.doesNotMatch(h.el('realNearbyConnectionStatus').textContent,/연결 확인됨/);assert.equal(h.watches.length,0);
});

test('only an explicit satellite selection creates the attributed EOX imagery layer with native zoom 14',()=>{
  const h=harness();assert.equal(h.tileLayers.length,1);assert.match(h.tileLayers[0].url,/tile\.openstreetmap\.org/);
  assert.equal(h.el('realMapStyleRoad').attributes['aria-pressed'],'true');assert.equal(h.el('realMapStyleNotice').hidden,true);
  h.el('realMapStyleSatellite').click();const imagery=h.tileLayers.at(-1);
  assert.equal(imagery.url,'https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2025_3857/default/GoogleMapsCompatible/{z}/{y}/{x}.jpg');
  assert.equal(imagery.options.maxNativeZoom,14);assert.equal(imagery.options.maxZoom,19);
  assert.match(imagery.options.attribution,/EOxCloudless.*EOX IT Services GmbH.*Contains modified Copernicus Sentinel data 2025/);
  assert.match(imagery.options.attribution,/creativecommons\.org\/licenses\/by-nc-sa\/4\.0/);
  assert.equal(h.el('realMapStyleSatellite').attributes['aria-pressed'],'true');assert.equal(h.el('realMapStyleNotice').hidden,false);
  assert.equal(h.el('realSatelliteCredit').hidden,false);assert.match(h.el('realSatelliteCredit').innerHTML,/EOX IT Services GmbH.*Copernicus Sentinel data 2025/);
  assert.equal(h.el('realView').dataset.mapStyle,'satellite');assert.equal(h.requests.length,0);assert.equal(h.watches.length,0);
});

test('basemap switching keeps active guidance, GPS, the camera and every route/marker overlay unchanged',async()=>{
  const h=harness();h.el('realGpsToggle').click();h.gps();await h.plan();h.el('realStart').click();
  assert.equal(h.el('realGuidanceMode').textContent,'GPS 길 안내 중');
  const overlays=[...h.map.layers].filter(layer=>!h.tileLayers.includes(layer)),camera=JSON.stringify(h.mapCalls),route=h.el('realRouteOptions').innerHTML,cue=h.el('realTurnCue').textContent,origin=h.el('realOrigin').value;
  const requests=h.requests.length,speech=h.spoken.length;
  h.el('realMapStyleSatellite').click();h.el('realMapStyleRoad').click();
  assert.equal(h.el('realGuidanceMode').textContent,'GPS 길 안내 중');assert.equal(h.el('realOrigin').value,origin);
  assert.equal(h.el('realRouteOptions').innerHTML,route);assert.equal(h.el('realTurnCue').textContent,cue);
  assert.equal(JSON.stringify(h.mapCalls),camera);assert.equal(h.requests.length,requests);assert.equal(h.spoken.length,speech);
  assert.equal(h.watches.length,1);assert.equal(h.cleared.length,0);assert.equal(h.context.RMRealRoadUI.isActive(),true);
  assert.ok(overlays.length>3);for(const overlay of overlays)assert.equal(h.map.layers.has(overlay),true);
  assert.equal(h.map.layers.has(h.tileLayers[0]),false);assert.equal(h.map.layers.has(h.tileLayers[1]),false);assert.equal(h.map.layers.has(h.tileLayers[2]),true);
});

test('tile errors affect only the current layer and a failed tile remains reported until that tile recovers',()=>{
  const h=harness(),road=h.tileLayers[0];h.el('realMapStyleSatellite').click();const imagery=h.tileLayers.at(-1);
  road.events.get('tileerror')({coords:{z:14,x:1,y:1}});assert.equal(h.el('realMapError').hidden,true);
  imagery.events.get('tileerror')({coords:{z:14,x:2,y:2}});assert.equal(h.el('realMapError').hidden,false);assert.match(h.el('realMapError').textContent,/위성 영상.*일반지도/);
  imagery.events.get('tileload')({coords:{z:14,x:3,y:3}});assert.equal(h.el('realMapError').hidden,false);
  imagery.events.get('tileload')({coords:{z:14,x:2,y:2}});assert.equal(h.el('realMapError').hidden,true);
  imagery.events.get('tileerror')({coords:{z:14,x:2,y:2}});h.el('realMapStyleRoad').click();assert.equal(h.el('realMapError').hidden,true);
  imagery.events.get('tileerror')({coords:{z:14,x:2,y:2}});assert.equal(h.el('realMapError').hidden,true);assert.equal(h.el('realMapStyleNotice').hidden,true);
  assert.equal(h.el('realSatelliteCredit').hidden,true);
});

test('satellite selection leaves an explicit nearby lookup and its eventual results intact',async()=>{
  let resolve;
  const h=harness({nearbySearch:body=>new Promise(done=>resolve=()=>done(nearbyResponse(body)))});
  h.el('realNearbyParking').click();h.el('realNearbySearch').click();await h.flush();
  h.el('realMapStyleSatellite').click();resolve();await h.flush();
  assert.equal(h.requests.length,1);assert.match(h.el('realNearbyResults').innerHTML,/테스트 주차장/);assert.match(h.el('realNearbyResults').innerHTML,/잔여 면수 미제공/);
  assert.equal(h.el('realNearbyPanel').hidden,false);assert.equal(h.watches.length,0);
});

test('selecting the current basemap again does not remove or reload it',()=>{
  const h=harness();h.el('realMapStyleRoad').click();assert.equal(h.tileLayers.length,1);
  h.el('realMapStyleSatellite').click();const imagery=h.tileLayers.at(-1);h.el('realMapStyleSatellite').click();
  assert.equal(h.tileLayers.length,2);assert.equal(h.map.layers.has(imagery),true);assert.equal(h.requests.length,0);assert.equal(h.watches.length,0);
});

test('HD setup is explicit and missing server keys never create an HD layer or GPS request',async()=>{
  const h=harness();assert.equal(h.el('realMapStyleHighRes').disabled,true);
  h.el('realMapSettings').click();assert.equal(h.el('realMapConfig').hidden,false);assert.equal(h.requests.length,0);
  h.el('realMapConfigCheck').click();await h.flush();
  assert.deepEqual(h.requests.map(r=>r.url),['/api/maps/status']);assert.equal(h.requests[0].body,undefined);
  assert.equal(h.el('realMapStyleHighRes').disabled,true);assert.match(h.el('realMapConfigStatus').textContent,/인증키가 설정되지 않았습니다/);
  h.el('realMapStyleHighRes').click();assert.equal(h.tileLayers.length,1);assert.equal(h.watches.length,0);
});

test('configured HD uses only the local proxy and preserves the route without claiming authenticated data',async()=>{
  const h=harness({mapsStatus:async()=>({ok:true,json:async()=>({provider:'vworld',configured:true,authentication:'unverified',tileUrl:'https://untrusted.invalid/key=secret',maxNativeZoom:99})})});
  await h.plan();const overlays=[...h.map.layers].filter(v=>!h.tileLayers.includes(v)),camera=h.mapCalls.length;
  h.el('realMapSettings').click();h.el('realMapConfigCheck').click();await h.flush();
  assert.match(h.el('realMapConfigStatus').textContent,/인증 성공 여부는 아직 확인하지 않았습니다/);
  assert.equal(h.el('realMapStyleHighRes').disabled,false);h.el('realMapStyleHighRes').click();
  const hd=h.tileLayers.at(-1);assert.equal(hd.url,'/api/maps/vworld/{z}/{x}/{y}');assert.equal(hd.options.maxNativeZoom,18);
  assert.equal(h.el('realView').dataset.mapStyle,'vworld');assert.equal(h.mapCalls.length,camera);assert.equal(h.watches.length,0);
  for(const item of overlays)assert.equal(h.map.layers.has(item),true);
  assert.match(h.el('realSatelliteCredit').innerHTML,/브이월드/);assert.doesNotMatch(h.el('realSatelliteCredit').innerHTML,/secret|untrusted/);
});

test('closing map setup discards a late configuration response and leaves the previous source selected',async()=>{
  let resolve;
  const h=harness({mapsStatus:()=>new Promise(done=>resolve=()=>done({ok:true,json:async()=>({provider:'vworld',configured:true})}))});
  h.el('realMapSettings').click();h.el('realMapConfigCheck').click();await h.flush();h.el('realMapConfigClose').click();resolve();await h.flush();
  assert.equal(h.el('realMapConfig').hidden,true);assert.equal(h.el('realMapStyleHighRes').disabled,true);
  assert.equal(h.el('realView').dataset.mapStyle,'road');assert.equal(h.watches.length,0);assert.equal(h.tileLayers.length,1);
});
