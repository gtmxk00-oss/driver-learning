'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {Readable}=require('node:stream');
const {createPlaceProvider,searchParameters,normalize,category}=require('../server/place-provider.cjs');
const {createHandler}=require('../server/http-server.cjs');

const feature=(properties={},coordinates=[126.9766818,37.579754])=>({type:'Feature',geometry:{type:'Point',coordinates},properties:{osm_type:'R',osm_id:5501517,countrycode:'KR',name:'경복궁',city:'서울특별시',district:'사직동',street:'사직로',housenumber:'161',osm_key:'tourism',osm_value:'attraction',...properties}});
const payload=(features=[feature()])=>({type:'FeatureCollection',features});
const reply=(value=payload(),status=200,headers={})=>new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json',...headers}});
const make=options=>createPlaceProvider({minIntervalMs:0,...options});
async function request(handler,{method='POST',url='/api/places/search',host='127.0.0.1:4173',origin,body={query:'경복궁'},headers={}}={}){
  const req=Readable.from(body===undefined?[]:[typeof body==='string'?body:JSON.stringify(body)]);
  Object.assign(req,{method,url,headers:{host,...(origin?{origin}:{}),...(body!==undefined?{'content-type':'application/json'}:{}),...headers},socket:{localPort:4173}});
  let status,output,responseHeaders;const res={writeHead(s,h){status=s;responseHeaders=h;},end(value){output=value;}};
  await handler(req,res);return {status,headers:responseHeaders,body:output?JSON.parse(output):null};
}

test('manual place query uses fixed Photon HTTPS with Korea filter and never includes GPS bias',async()=>{
  let args;const provider=make({fetchFn:async(...a)=>{args=a;return reply();}});
  const result=await provider.search({query:'경복궁',lat:37.12,lon:127.33,url:'https://attacker.invalid'});
  const url=new URL(args[0]);assert.equal(url.origin,'https://photon.komoot.io');assert.equal(url.pathname,'/api');
  assert.deepEqual([...url.searchParams.entries()],[['q','경복궁'],['limit','8'],['countrycode','KR']]);
  assert.match(args[1].headers['User-Agent'],/^RouteMemory/);assert.equal(args[1].redirect,'error');assert(!Object.hasOwn(args[1].headers,'Authorization'));
  assert.equal(result.source,'real-places');assert.equal(result.provider,'photon');assert.equal(result.results[0].name,'경복궁');assert.match(result.attribution,/Photon.*OpenStreetMap/);
});

test('query validation rejects unsafe shapes, control characters and excessive length; text is normalized',()=>{
  for(const input of [null,[],{}, {query:3},{query:' '},{query:'역'},{query:'a'.repeat(121)},{query:'서울\n역'},{query:'서울\0역'}])assert.throws(()=>searchParameters(input),e=>e.code==='INVALID_QUERY');
  assert.deepEqual(searchParameters({query:'  서울특별시   사직로 161  '}),{query:'서울특별시 사직로 161'});
  assert.equal(searchParameters({query:'서울특별시 종로구 사직로161'}).query,'서울특별시 종로구 사직로 161');
  assert.equal(searchParameters({query:'북촌로5길48-2'}).query,'북촌로5길 48-2');
});

test('query punctuation cannot inject provider parameters or alter the destination origin',async()=>{
  let target;const provider=make({fetchFn:async(url)=>{target=new URL(url);return reply();}});
  await provider.search({query:'서울역&limit=999#https://attacker.invalid'});
  assert.equal(target.searchParams.get('q'),'서울역&limit=999#https://attacker.invalid');assert.equal(target.searchParams.get('limit'),'8');assert.equal(target.hash,'');
});

test('search server configuration rejects insecure, credentialed, local and arbitrary-path URLs',async()=>{
  for(const base of ['http://photon.komoot.io','https://key:secret@photon.komoot.io','https://localhost','https://127.0.0.1','https://[::1]','https://x.local','https://public.example/?key=secret','https://public.example/redirect']){
    const provider=make({base,fetchFn:()=>assert.fail('must not fetch')});assert.equal(provider.configured,false);
    await assert.rejects(provider.search({query:'서울역'}),e=>e.code==='INVALID_CONFIGURATION');
  }
});

test('results preserve real coordinates and compose a bounded deduplicated Korean address',()=>{
  const raw=feature({state:'서울특별시',county:'종로구',locality:'세종로'});
  const result=normalize(payload([raw]),'경복궁','now');
  assert.deepEqual(result.results[0],{id:'photon-R-5501517',name:'경복궁',address:'서울특별시 종로구 사직동 세종로 사직로 161',lat:37.579754,lon:126.9766818,category:'관광지'});
  raw.properties.name='changed';assert.equal(result.results[0].name,'경복궁');
});

test('invalid points, wrong geometry, foreign results and duplicates are filtered while empty results remain truthful',()=>{
  const good=feature();
  const invalids=[feature({},[NaN,37]),feature({},[126,91]),feature({},[123,37]),feature({countrycode:'JP'}),{...feature(),geometry:{type:'LineString',coordinates:[[126,37],[127,38]]}}];
  const result=normalize(payload([...invalids,good,good]),'경복궁','now');assert.equal(result.results.length,1);
  assert.deepEqual(normalize(payload([]),'없는 장소','now').results,[]);
  assert.throws(()=>normalize({features:'not an array'},'서울역','now'),e=>e.code==='INVALID_RESPONSE');
});

test('result count and labels are bounded and missing names use actual street/address data',()=>{
  const features=Array.from({length:20},(_,i)=>feature({osm_id:i+1,name:'긴이름'.repeat(100),district:'구'.repeat(300)}));
  const result=normalize(payload(features),'서울','now');assert.equal(result.results.length,8);
  assert.equal(result.results[0].name.length,160);assert(result.results[0].address.length<=240);
  const unnamed=normalize(payload([feature({name:'',osm_type:'?',osm_id:null})]),'사직로161','now').results[0];
  assert.equal(unnamed.name,'사직로 161');assert.match(unnamed.id,/^photon-point-37\.579754-126\.976682$/);
  assert.equal(category({osm_key:'__proto__'}),'장소');assert.equal(category({osm_key:'railway',osm_value:'station'}),'역');
});

test('cache is bounded, expires, includes empty results and never exposes shared mutable response objects',async()=>{
  let calls=0,time=100;const provider=make({maxEntries:2,cacheMs:50,now:()=>time,fetchFn:async()=>{calls++;return reply();}});
  const first=await provider.search({query:'경복궁'});first.results[0].name='mutated';
  assert.equal((await provider.search({query:'경복궁'})).results[0].name,'경복궁');assert.equal(calls,1);
  await provider.search({query:'서울역'});await provider.search({query:'광화문'});await provider.search({query:'경복궁'});assert.equal(calls,4);
  time+=51;await provider.search({query:'경복궁'});assert.equal(calls,5);
  let emptyCalls=0;const empty=make({fetchFn:async()=>{emptyCalls++;return reply(payload([]));}});
  assert.deepEqual((await empty.search({query:'없는 곳'})).results,[]);await empty.search({query:'없는 곳'});assert.equal(emptyCalls,1);
});

test('duplicate in-flight queries share a request and independent queries obey one-second start spacing',async()=>{
  let time=100,calls=0;const starts=[];
  const provider=createPlaceProvider({now:()=>time,sleep:async ms=>{time+=ms;},fetchFn:async()=>{calls++;starts.push(time);return reply();}});
  const [a,b]=await Promise.all([provider.search({query:'경복궁'}),provider.search({query:'경복궁'})]);assert.equal(calls,1);assert.deepEqual(a,b);
  await Promise.all([provider.search({query:'서울역'}),provider.search({query:'광화문'})]);assert.deepEqual(starts,[100,1100,2100]);
});

test('queue bounds and provider cooldown prevent excessive repeated upstream requests',async()=>{
  let release;const held=new Promise(resolve=>release=resolve);let calls=0;
  const provider=make({maxPending:1,fetchFn:async()=>{calls++;await held;return reply();}});
  const first=provider.search({query:'경복궁'});await assert.rejects(provider.search({query:'서울역'}),e=>e.code==='BUSY');release();await first;assert.equal(calls,1);
  let time=100,limitedCalls=0;const limited=make({now:()=>time,fetchFn:async()=>{limitedCalls++;return reply({},429,{'Retry-After':'10'});}});
  await assert.rejects(limited.search({query:'경복궁'}),e=>e.code==='UPSTREAM_LIMIT');
  await assert.rejects(limited.search({query:'서울역'}),e=>e.code==='UPSTREAM_LIMIT');assert.equal(limitedCalls,1);
  time+=10001;await assert.rejects(limited.search({query:'서울역'}));assert.equal(limitedCalls,2);
});

test('oversized declared or streamed bodies and malformed JSON are safely rejected',async()=>{
  const tooLong=make({maxBytes:64,fetchFn:async()=>reply(payload(),200,{'Content-Length':'1000000'})});
  await assert.rejects(tooLong.search({query:'경복궁'}),e=>e.code==='RESPONSE_TOO_LARGE');
  const streamed=make({maxBytes:64,fetchFn:async()=>reply(payload())});await assert.rejects(streamed.search({query:'경복궁'}),e=>e.code==='RESPONSE_TOO_LARGE');
  const invalid=make({fetchFn:async()=>new Response('secret internal error')});await assert.rejects(invalid.search({query:'경복궁'}),e=>e.code==='INVALID_RESPONSE'&&!e.message.includes('secret'));
});

test('timeouts abort hung requests and unexpected upstream errors never reveal provider details',async()=>{
  let signal;const hung=make({timeoutMs:10,fetchFn:async(url,options)=>{signal=options.signal;return new Promise(()=>{});}});
  await assert.rejects(hung.search({query:'서울역'}),e=>e.code==='UPSTREAM_TIMEOUT');assert.equal(signal.aborted,true);
  const broken=make({fetchFn:async()=>{throw new Error('private upstream URL and token');}});
  await assert.rejects(broken.search({query:'서울역'}),e=>e.code==='UPSTREAM_ERROR'&&!e.message.includes('private'));
});

test('place HTTP API keeps same-origin loopback protection and exposes the agreed nested error shape',async()=>{
  let calls=0;const handler=createHandler({placeProvider:{configured:true,search:async input=>{calls++;return normalize(payload(),searchParameters(input).query,'now');}}});
  for(const options of [{host:'attacker.invalid:4173'},{origin:'https://attacker.invalid'}])assert.equal((await request(handler,options)).status,403);
  assert.equal(calls,0);
  const status=await request(handler,{method:'GET',url:'/api/places/status'});assert.deepEqual(status.body,{configured:true,provider:'photon'});assert.equal(calls,0);
  const found=await request(handler);assert.equal(found.status,200);assert.equal(found.body.results[0].name,'경복궁');assert.equal(found.headers['Cache-Control'],'no-store');
  const invalid=await request(handler,{body:{query:' '}});assert.equal(invalid.status,400);assert.equal(invalid.body.error.code,'INVALID_QUERY');
  const oversized=await request(handler,{body:'x'.repeat(9000)});assert.equal(oversized.status,413);
});
