'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {Readable}=require('node:stream');
const {createTrafficProvider,coordinates,normalize}=require('../server/traffic-provider.cjs');
const {createHandler}=require('../server/http-server.cjs');
const coords={origin:{x:127.10764,y:37.40246},destination:{x:127.11056,y:37.39420}};
const sample=()=>({routes:[{result_code:0,summary:{duration:420,distance:1234},sections:[{roads:[
  {name:'정체 도로',distance:100,duration:50,traffic_speed:0,traffic_state:2},
  {name:'정보 없는 도로',distance:200,duration:100},
  {name:'일반 도로',distance:934,duration:270,traffic_speed:42.5,traffic_state:0}
]}]}]});
const reply=(payload=sample(),status=200)=>({ok:status>=200&&status<300,status,json:async()=>payload});
async function request(handler,{method='GET',url='/api/traffic/status',host='127.0.0.1:4173',origin,body,headers={}}={}){
  const req=Readable.from(body===undefined?[]:[typeof body==='string'?body:JSON.stringify(body)]);
  Object.assign(req,{method,url,headers:{host,...(origin?{origin}:{}),...(body!==undefined?{'content-type':'application/json'}:{}),...headers},socket:{localPort:4173}});
  let status,responseHeaders,content;
  const res={writeHead(s,h){status=s;responseHeaders=h;},end(value){content=value;}};
  await handler(req,res);
  return {status,headers:responseHeaders,body:content?JSON.parse(content):null,raw:content};
}

test('missing key exposes configuration status without requesting upstream',async()=>{
  let calls=0;const provider=createTrafficProvider({apiKey:'',fetchFn:()=>{calls++;throw new Error('not called');}});
  const handler=createHandler({provider});
  const state=await request(handler);assert.equal(state.status,200);assert.deepEqual(state.body,{configured:false,provider:'kakao'});
  const result=await request(handler,{method:'POST',url:'/api/traffic/routes',body:coords});
  assert.equal(result.status,503);assert.equal(result.body.error.code,'NOT_CONFIGURED');assert.equal(calls,0);
});
test('fixed Kakao request includes traffic-aware options, authorization stays server-side',async()=>{
  let args;const provider=createTrafficProvider({apiKey:'not-a-real-key',fetchFn:async(...input)=>{args=input;return reply();}});
  const result=await provider.routes({...coords,url:'https://untrusted.invalid'});
  const url=new URL(args[0]);assert.equal(url.origin,'https://apis-navi.kakaomobility.com');assert.equal(url.pathname,'/v1/directions');
  for(const [k,v]of Object.entries({priority:'TIME',summary:'false',alternatives:'true',road_details:'true',roadevent:'0'}))assert.equal(url.searchParams.get(k),v);
  assert.equal(args[1].headers.Authorization,'KakaoAK not-a-real-key');assert.equal(args[1].redirect,'error');
  assert.equal(result.source,'live');assert.equal(result.provider,'kakao');assert(!JSON.stringify(result).includes('not-a-real-key'));
});
test('normalization distinguishes zero speed and missing data without inventing congestion labels',()=>{
  const r=normalize(sample(),'2026-11-04T00:00:00.000Z');
  assert.equal(r.routes[0].roads[0].speedKph,0);assert.equal(r.routes[0].roads[1].speedKph,null);
  assert.equal(r.routes[0].roads[1].trafficState,null);assert.equal(r.routes[0].roads[2].trafficState,0);
  assert(!Object.hasOwn(r.routes[0].roads[1],'status'));
});
test('alternatives retain valid routes and filter failed or invalid responses',()=>{
  const data=sample();data.routes.push({result_code:104,summary:{distance:10,duration:10}},
    {result_code:0,summary:{distance:-1,duration:10}},
    {result_code:0,summary:{distance:1500,duration:460},sections:[]});
  const result=normalize(data,'2026-11-04T00:00:00.000Z');assert.equal(result.routes.length,2);assert.equal(result.routes[1].roads.length,0);
  assert.throws(()=>normalize({routes:[]},'now'),e=>e.code==='NO_ROUTE');
});
test('coordinate validation rejects strings, global locations, identical points and URL input',()=>{
  for(const input of [null,{}, {...coords,origin:{x:'127',y:37}}, {...coords,origin:{x:0,y:0}}, {...coords,destination:coords.origin}, {origin:'https://bad.invalid',destination:coords.destination}])
    assert.throws(()=>coordinates(input),e=>e.status===400);
});
test('cache avoids repeats for 30 seconds and preserves original fetch timestamp',async()=>{
  let now=Date.parse('2026-11-04T00:00:00Z'),calls=0;
  const provider=createTrafficProvider({apiKey:'fake',now:()=>now,fetchFn:async()=>{calls++;return reply();}});
  const first=await provider.routes(coords);now+=29000;const cached=await provider.routes(coords);
  assert.equal(calls,1);assert.equal(cached.fetchedAt,first.fetchedAt);
  now+=1001;const refreshed=await provider.routes(coords);assert.equal(calls,2);assert.notEqual(refreshed.fetchedAt,first.fetchedAt);
});
test('cache retains at most configured number of coordinate pairs',async()=>{
  let calls=0;const provider=createTrafficProvider({apiKey:'fake',maxEntries:2,fetchFn:async()=>{calls++;return reply();}});
  await provider.routes(coords);await provider.routes({...coords,origin:{x:127.1,y:37.4}});await provider.routes({...coords,origin:{x:127.2,y:37.4}});
  await provider.routes(coords);assert.equal(calls,4);
});
test('authentication, usage-limit and unexpected errors are safe and never leak provider bodies',async()=>{
  for(const [status,code]of [[401,'UPSTREAM_AUTH'],[403,'UPSTREAM_AUTH'],[429,'UPSTREAM_LIMIT'],[500,'UPSTREAM_ERROR']]){
    const provider=createTrafficProvider({apiKey:'secret-value',fetchFn:async()=>reply({message:'secret-value'},status)});
    await assert.rejects(provider.routes(coords),e=>e.code===code&&!e.message.includes('secret-value'));
  }
  const provider=createTrafficProvider({apiKey:'secret-value',fetchFn:async()=>{throw new Error('secret-value private provider body');}});
  await assert.rejects(provider.routes(coords),e=>e.code==='UPSTREAM_ERROR'&&!e.message.includes('secret-value'));
});
test('timeout aborts hung upstream calls and never substitutes simulated traffic',async()=>{
  let signal;const provider=createTrafficProvider({apiKey:'fake',timeoutMs:10,fetchFn:async(u,opts)=>{signal=opts.signal;return new Promise(()=>{});}});
  await assert.rejects(provider.routes(coords),e=>e.status===504&&e.code==='UPSTREAM_TIMEOUT');assert(signal.aborted);
});
test('local API rejects external Host and cross-origin requests before querying provider',async()=>{
  let calls=0;const handler=createHandler({provider:{configured:true,routes:async()=>{calls++;return {routes:[]};}}});
  for(const options of [{host:'attacker.invalid:4173'},{origin:'https://attacker.invalid'},{origin:'null'},{host:'127.0.0.1:80'}]){
    const result=await request(handler,{method:'POST',url:'/api/traffic/routes',body:coords,...options});
    assert.equal(result.status,403);assert.equal(result.body.error.code,'LOCAL_ONLY');assert.equal(result.headers['Access-Control-Allow-Origin'],undefined);
  }
  assert.equal(calls,0);
});
test('server bounds JSON payload, rejects malformed input, and handles successful same-origin call',async()=>{
  const handler=createHandler({provider:{configured:true,routes:async()=>({source:'live',routes:[]})}});
  const tooLarge=await request(handler,{method:'POST',url:'/api/traffic/routes',body:'x'.repeat(8193)});assert.equal(tooLarge.status,413);
  const malformed=await request(handler,{method:'POST',url:'/api/traffic/routes',body:'{oops'});assert.equal(malformed.status,400);
  const wrongType=await request(handler,{method:'POST',url:'/api/traffic/routes',body:coords,headers:{'content-type':'text/plain'}});assert.equal(wrongType.status,415);
  const success=await request(handler,{method:'POST',url:'/api/traffic/routes',body:coords,origin:'http://127.0.0.1:4173'});assert.equal(success.status,200);assert.equal(success.body.source,'live');
});
