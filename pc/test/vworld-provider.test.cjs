'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {Readable}=require('node:stream');
const {createVworldProvider,MapError,tileParameters,validJpeg256,cacheLifetime}=require('../server/vworld-provider.cjs');
const {createHandler}=require('../server/http-server.cjs');
// A locally generated solid-colour JPEG, not provider imagery or a real API key.
const jpeg=()=>fs.readFileSync(path.join(__dirname,'fixtures/vworld-test.jpg'));
const key='TEST-ONLY-NOT-A-REAL-REGISTERED-KEY';
const reply=({status=200,body=jpeg(),headers={}}={})=>({ok:status>=200&&status<300,status,headers:new Headers({'content-type':'image/jpeg',...headers}),body:Readable.from([body])});
const make=options=>createVworldProvider({env:{VWORLD_API_KEY:key},minIntervalMs:0,...options});
async function request(handler,{method='GET',url='/api/maps/status',host='127.0.0.1:4173',origin,headers={}}={}){
  const req=Readable.from([]);Object.assign(req,{method,url,headers:{host,...(origin?{origin}:{}),...headers},socket:{localPort:4173}});
  let status,responseHeaders,content;const res={writeHead(s,h){status=s;responseHeaders=h;},end(value){content=value;}};
  await handler(req,res);return {status,headers:responseHeaders,body:responseHeaders?.['Content-Type']==='image/jpeg'?content:content?JSON.parse(content):null};
}

test('missing key status and tile failure are explicit and do not contact any provider',async()=>{
  let calls=0;const provider=make({env:{},fetchFn:async()=>{calls++;throw Error('never');}});
  const info=provider.status();assert.equal(info.configured,false);assert.equal(info.authentication,'unverified');assert.equal(info.maxNativeZoom,18);
  await assert.rejects(provider.tile(14,13970,6344),e=>e.status===503&&e.code==='MAP_KEY_REQUIRED');assert.equal(calls,0);
});
test('key presence never claims verified authentication or leaks key/domain values',()=>{
  const provider=make({env:{VWORLD_API_KEY:key,VWORLD_DOMAIN:'https://unverified.invalid'}}),status=provider.status();
  assert.equal(status.configured,true);assert.equal(status.authentication,'unverified');assert(!JSON.stringify(status).includes(key));assert(!JSON.stringify(status).includes('unverified.invalid'));
  assert.equal(status.tileUrl,'/api/maps/vworld/{z}/{x}/{y}');status.configured=false;assert.equal(provider.status().configured,true);
});
test('tile coordinates are canonical bounded integers without wrapping or path/query input',()=>{
  assert.deepEqual(tileParameters('18','223520','101500'),{z:18,x:223520,y:101500});assert.deepEqual(tileParameters(0,0,0),{z:0,x:0,y:0});
  for(const input of [[19,1,1],[14,-1,1],[14,1,16384],[14,16384,1],[14,1.5,1],[14,NaN,1],['014','1','1'],['14','1?key=x','1'],['14','1/2','1'],['14','%31','1'],['14',null,'1'],[true,1,1]])assert.throws(()=>tileParameters(...input),e=>e.status===400);
});
test('upstream request has fixed HTTPS Satellite row/column order and no spoofed domain headers',async()=>{
  let args;const provider=make({env:{VWORLD_API_KEY:key,VWORLD_DOMAIN:'https://ignore.invalid'},fetchFn:async(...a)=>{args=a;return reply();}});
  const tile=await provider.tile('14','13970','6344');
  assert.equal(args[0],'https://api.vworld.kr/req/wmts/1.0.0/'+key+'/Satellite/14/6344/13970.jpeg');
  assert.equal(args[1].redirect,'error');assert.equal(args[1].headers.Accept,'image/jpeg');
  for(const name of ['Referer','Origin','Authorization'])assert(!Object.hasOwn(args[1].headers,name));
  assert(!args[0].includes('ignore.invalid'));assert(validJpeg256(tile.body));assert.equal(tile.cacheControl,'private, max-age=21600');
});
test('JPEG response validation requires image MIME, SOI/EOI, frame dimensions and scan data',async()=>{
  const bytes=jpeg();assert(validJpeg256(bytes));assert(!validJpeg256(Buffer.from('not image')));
  const wrong=Buffer.from(bytes);const frame=wrong.indexOf(Buffer.from([0xff,0xc0]));assert(frame>0);wrong.writeUInt16BE(512,frame+5);assert(!validJpeg256(wrong));
  for(const response of [reply({headers:{'content-type':'text/xml'},body:Buffer.from('<error>secret</error>')}),reply({body:Buffer.from('key='+key)}),reply({body:wrong}),reply({body:bytes.subarray(0,bytes.length-2)})])await assert.rejects(make({fetchFn:async()=>response}).tile(14,13970,6344),e=>e.code==='INVALID_TILE_RESPONSE'&&!e.message.includes(key)&&!e.message.includes('secret'));
});
test('image stream and advertised lengths are bounded before exposing binary data',async()=>{
  for(const response of [reply({headers:{'content-length':'1048577'}}),reply({body:Buffer.alloc(1048577)})])await assert.rejects(make({fetchFn:async()=>response}).tile(14,13970,6344),e=>e.code==='TILE_TOO_LARGE');
});
test('cache expiry honours no-store/no-cache, age and six-hour maximum',()=>{
  const at=1700000000000,h=value=>new Headers(value);
  assert.equal(cacheLifetime(h({'cache-control':'public, max-age=86400'}),at),21600000);
  assert.equal(cacheLifetime(h({'cache-control':'max-age=60','age':'10'}),at),50000);
  assert.equal(cacheLifetime(h({'cache-control':'private, no-store'}),at),0);assert.equal(cacheLifetime(h({'cache-control':'no-cache'}),at),0);
  assert.equal(cacheLifetime(h({'expires':new Date(at+10000).toUTCString()}),at),10000);
});
test('bounded memory cache returns copies, expires and evicts without storing a disk key',async()=>{
  let calls=0,clock=100000;const provider=make({now:()=>clock,maxEntries:1,fetchFn:async()=>{calls++;return reply({headers:{'cache-control':'max-age=60'}});}});
  const first=await provider.tile(14,13970,6344);first.body[0]=0;clock+=10000;
  const second=await provider.tile(14,13970,6344);assert.equal(calls,1);assert(validJpeg256(second.body));assert.equal(second.cacheControl,'private, max-age=50');
  await provider.tile(14,13971,6344);await provider.tile(14,13970,6344);assert.equal(calls,3);
  clock+=61000;await provider.tile(14,13970,6344);assert.equal(calls,4);
});
test('no-store responses and invalid images never populate the memory cache',async()=>{
  let calls=0;const provider=make({fetchFn:async()=>{calls++;return reply({headers:{'cache-control':'no-store'}});}});
  await provider.tile(14,13970,6344);await provider.tile(14,13970,6344);assert.equal(calls,2);
  let badCalls=0;const bad=make({fetchFn:async()=>{badCalls++;return reply({body:Buffer.from('bad')});}});
  for(let i=0;i<2;i++)await assert.rejects(bad.tile(14,13970,6344));assert.equal(badCalls,2);
});
test('duplicate tile requests coalesce and pending work is bounded',async()=>{
  let release,calls=0;const waiting=new Promise(resolve=>release=resolve);
  const provider=make({maxPending:1,fetchFn:async()=>{calls++;await waiting;return reply();}});
  const first=provider.tile(14,13970,6344),duplicate=provider.tile(14,13970,6344);
  await assert.rejects(provider.tile(14,13971,6344),e=>e.code==='MAP_BUSY');release();const results=await Promise.all([first,duplicate]);assert.equal(calls,1);assert.notEqual(results[0].body,results[1].body);
});
test('upstream has at most two concurrent transfers and spaced starts',async()=>{
  let clock=1000,active=0,peak=0;const starts=[],waits=[],releases=[];
  const provider=make({now:()=>clock,minIntervalMs:250,sleep:async ms=>{waits.push(ms);clock+=ms;},fetchFn:async()=>{active++;peak=Math.max(peak,active);starts.push(clock);await new Promise(resolve=>releases.push(resolve));active--;return reply();}});
  const work=[provider.tile(14,13970,6344),provider.tile(14,13971,6344),provider.tile(14,13972,6344)];
  while(releases.length<2)await new Promise(resolve=>setImmediate(resolve));assert.equal(peak,2);assert.equal(starts[1]-starts[0],250);
  releases.shift()();while(releases.length<2)await new Promise(resolve=>setImmediate(resolve));releases.splice(0).forEach(resolve=>resolve());await Promise.all(work);assert.equal(peak,2);assert.equal(starts.length,3);
});
test('authentication, quota and network errors never expose upstream messages or credentials',async()=>{
  for(const [status,code]of [[401,'MAP_AUTH_REQUIRED'],[403,'MAP_AUTH_REQUIRED'],[429,'MAP_RATE_LIMIT'],[404,'MAP_TILE_UNAVAILABLE'],[500,'MAP_UPSTREAM_ERROR']])await assert.rejects(make({fetchFn:async()=>reply({status,body:Buffer.from('private '+key)})}).tile(14,13970,6344),e=>e.code===code&&!e.message.includes(key)&&!e.message.includes('private'));
  await assert.rejects(make({fetchFn:async()=>{throw Error('request '+key);}}).tile(14,13970,6344),e=>e.code==='MAP_UPSTREAM_ERROR'&&!e.message.includes(key));
});
test('provider Retry-After prevents a new request during cooldown',async()=>{
  let calls=0,clock=1000;const provider=make({now:()=>clock,fetchFn:async()=>{calls++;return calls===1?reply({status:429,headers:{'retry-after':'2'}}):reply();}});
  await assert.rejects(provider.tile(14,13970,6344),e=>e.code==='MAP_RATE_LIMIT');await assert.rejects(provider.tile(14,13971,6344),e=>e.code==='MAP_RATE_LIMIT');assert.equal(calls,1);
  clock+=2001;await provider.tile(14,13971,6344);assert.equal(calls,2);
});
test('timeout aborts the provider and leaves a public fixed error',async()=>{
  let signal;await assert.rejects(make({timeoutMs:10,fetchFn:async(url,options)=>{signal=options.signal;return new Promise(()=>{});}}).tile(14,13970,6344),e=>e.code==='MAP_TIMEOUT');assert(signal.aborted);
});
test('HTTP map endpoints retain loopback origin protection and do not return keys',async()=>{
  let calls=0;const handler=createHandler({mapProvider:make({fetchFn:async()=>{calls++;return reply();}})});
  const status=await request(handler);assert.equal(status.status,200);assert.equal(status.body.authentication,'unverified');assert(!JSON.stringify(status.body).includes(key));
  for(const bad of [{host:'evil.invalid:4173'},{origin:'https://evil.invalid'},{headers:{'sec-fetch-site':'cross-site'}}])assert.equal((await request(handler,{url:'/api/maps/vworld/14/13970/6344',...bad})).status,403);
  assert.equal(calls,0);const tile=await request(handler,{url:'/api/maps/vworld/14/13970/6344',origin:'http://127.0.0.1:4173'});assert.equal(tile.status,200);assert.equal(tile.headers['Content-Type'],'image/jpeg');assert(validJpeg256(tile.body));
  for(const url of ['/api/maps/vworld/14/13970/6344?key=bad','/api/maps/vworld/14/13970/6344/extra','/api/maps/vworld/19/1/1'])assert.equal((await request(handler,{url})).status,400);
  const missing=await request(createHandler({mapProvider:make({env:{}})}),{url:'/api/maps/vworld/14/13970/6344'});assert.equal(missing.status,503);assert.equal(missing.body.error.code,'MAP_KEY_REQUIRED');
});
