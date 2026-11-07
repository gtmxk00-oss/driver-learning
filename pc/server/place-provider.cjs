'use strict';

// Photon explicitly permits reasonable project use of its public demonstration API.
// Search is manual-only, rate limited, bounded, and cached only in process memory.
const net=require('node:net');
const {sharedPhotonGate}=require('./shared-photon-gate.cjs');
const DEFAULT_BASE='https://photon.komoot.io';
const ATTRIBUTION='Search: Photon by komoot · © OpenStreetMap contributors';
const USER_AGENT='RouteMemory/0.6.0 (PC navigation prototype; address and place search)';
class PlaceError extends Error {
  constructor(status,code,message){super(message);this.status=status;this.code=code;}
}
const fail=(status,code,message)=>new PlaceError(status,code,message);
const finite=value=>typeof value==='number'&&Number.isFinite(value);
const clean=(value,limit=160)=>typeof value==='string'?value.replace(/[\u0000-\u001f\u007f-\u009f]/g,' ').trim().replace(/\s+/g,' ').slice(0,limit):'';

function searchParameters(input){
  if(!input||typeof input!=='object'||Array.isArray(input)||typeof input.query!=='string'||/[\u0000-\u001f\u007f-\u009f]/.test(input.query))
    throw fail(400,'INVALID_QUERY','주소나 장소 이름을 2~120자로 입력해주세요.');
  // Korean road-name addresses are commonly entered without a house-number space.
  // Photon tokenizes that space: preserve the number instead of accepting broad fuzzy matches.
  const query=input.query.normalize('NFC').trim().replace(/\s+/g,' ')
    .replace(/([가-힣A-Za-z0-9·]+(?:대로|로|길))\s*(\d+(?:-\d+)?)(?=\s|$)/g,'$1 $2');
  if(query.length<2||query.length>120)throw fail(400,'INVALID_QUERY','주소나 장소 이름을 2~120자로 입력해주세요.');
  return {query};
}

function baseURL(value){
  let url;try{url=new URL(value);}catch{throw fail(503,'INVALID_CONFIGURATION','장소 검색 서버 설정을 확인해주세요.');}
  const host=url.hostname.toLowerCase();
  if(url.protocol!=='https:'||url.username||url.password||url.search||url.hash||url.pathname!=='/'||url.port&&url.port!=='443'||
    host==='localhost'||host.endsWith('.localhost')||host.endsWith('.local')||host.endsWith('.internal')||host.includes(':')||net.isIP(host))
    throw fail(503,'INVALID_CONFIGURATION','공개 HTTPS 장소 검색 서버를 설정해주세요.');
  return url.origin;
}

function category(properties){
  const key=clean(properties.osm_key,40),value=clean(properties.osm_value,40);
  if(key==='railway'&&['station','stop','halt','tram_stop'].includes(value))return '역';
  if(key==='tourism')return '관광지';
  if(key==='amenity'){
    if(['restaurant','fast_food','food_court'].includes(value))return '음식점';
    if(value==='cafe')return '카페';
    if(['hospital','clinic'].includes(value))return '의료시설';
    if(['school','college','university'].includes(value))return '학교';
    if(value==='parking')return '주차장';
  }
  const labels={shop:'상점',building:'건물',leisure:'시설',place:'지역'};
  return Object.hasOwn(labels,key)?labels[key]:'장소';
}

function normalize(payload,query,fetchedAt){
  if(!payload||typeof payload!=='object'||!Array.isArray(payload.features)||payload.features.length>100||payload.type&&payload.type!=='FeatureCollection')
    throw fail(502,'INVALID_RESPONSE','장소 검색 응답을 읽지 못했습니다. 잠시 후 다시 검색해주세요.');
  const results=[],seen=new Set();
  for(const feature of payload.features){
    const p=feature?.properties,coordinates=feature?.geometry?.coordinates;
    if(!p||typeof p!=='object'||Array.isArray(p)||feature?.geometry?.type!=='Point'||!Array.isArray(coordinates)||coordinates.length<2)continue;
    const [lon,lat]=coordinates;
    if(!finite(lat)||!finite(lon)||lat<32||lat>39.5||lon<124||lon>132)continue;
    const country=clean(p.countrycode,8).toUpperCase();if(country&&country!=='KR')continue;
    const streetHouse=[clean(p.street),clean(p.housenumber,40)].filter(Boolean).join(' ');
    const components=[p.state,p.city,p.county,p.district,p.locality].map(value=>clean(value)).filter(Boolean);
    if(streetHouse)components.push(streetHouse);
    const address=[...new Set(components)].join(' ').slice(0,240);
    const name=clean(p.name)||streetHouse||clean(p.locality)||clean(p.city)||clean(p.state);
    if(!name)continue;
    const osmType=clean(p.osm_type,2).toUpperCase(),osmId=typeof p.osm_id==='number'&&Number.isSafeInteger(p.osm_id)&&p.osm_id>0?String(p.osm_id):typeof p.osm_id==='string'&&/^[1-9][0-9]{0,15}$/.test(p.osm_id)?p.osm_id:'';
    const id=/^[NWR]$/.test(osmType)&&osmId?'photon-'+osmType+'-'+osmId:'photon-point-'+lat.toFixed(6)+'-'+lon.toFixed(6);
    if(seen.has(id))continue;seen.add(id);
    results.push({id,name:name.slice(0,160),address,lat,lon,category:category(p)});
    if(results.length===8)break;
  }
  return {source:'real-places',provider:'photon',query,results,fetchedAt,attribution:ATTRIBUTION};
}

async function readPayload(response,maxBytes){
  const length=Number(response.headers?.get?.('content-length'));
  if(Number.isFinite(length)&&length>maxBytes)throw fail(502,'RESPONSE_TOO_LARGE','장소 검색 응답이 너무 큽니다. 검색어를 더 구체적으로 입력해주세요.');
  if(!response.body||typeof response.body[Symbol.asyncIterator]!=='function')throw fail(502,'INVALID_RESPONSE','장소 검색 응답을 읽지 못했습니다.');
  const chunks=[];let size=0;
  for await(const chunk of response.body){
    size+=Buffer.byteLength(chunk);if(size>maxBytes)throw fail(502,'RESPONSE_TOO_LARGE','장소 검색 응답이 너무 큽니다. 검색어를 더 구체적으로 입력해주세요.');
    chunks.push(Buffer.from(chunk));
  }
  try{return JSON.parse(Buffer.concat(chunks).toString('utf8'));}
  catch{throw fail(502,'INVALID_RESPONSE','장소 검색 응답을 읽지 못했습니다. 잠시 후 다시 검색해주세요.');}
}

function createPlaceProvider({fetchFn=globalThis.fetch,now=()=>Date.now(),sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms)),timeoutMs=12000,cacheMs=300000,maxEntries=30,minIntervalMs=1000,maxPending=4,maxBytes=524288,base=process.env.PHOTON_BASE_URL||DEFAULT_BASE,photonGate=fetchFn===globalThis.fetch?sharedPhotonGate:null}={}){
  const cache=new Map(),inFlight=new Map();let queue=Promise.resolve(),lastStarted=-Infinity,pending=0,cooldownUntil=0,endpoint,configurationError;
  try{endpoint=baseURL(base)+'/api';}catch(e){configurationError=e;}
  return {
    configured:!configurationError,
    async search(input){
      const {query}=searchParameters(input);if(configurationError)throw configurationError;
      const cached=cache.get(query);
      if(cached&&now()-cached.at<cacheMs)return structuredClone(cached.data);
      if(cached)cache.delete(query);
      if(inFlight.has(query))return structuredClone(await inFlight.get(query));
      if(now()<cooldownUntil)throw fail(429,'UPSTREAM_LIMIT','장소 검색 서버가 요청을 제한했습니다. 잠시 후 다시 검색해주세요.');
      if(pending>=maxPending)throw fail(429,'BUSY','장소 검색 중입니다. 잠시 후 다시 눌러주세요.');
      const url=new URL(endpoint);url.search=new URLSearchParams({q:query,limit:'8',countrycode:'KR'}).toString();
      pending++;
      const operation=queue.catch(()=>{}).then(async()=>{
        if(now()<cooldownUntil)throw fail(429,'UPSTREAM_LIMIT','장소 검색 서버가 요청을 제한했습니다. 잠시 후 다시 검색해주세요.');
        const delay=Math.max(0,minIntervalMs-(now()-lastStarted));if(delay)await sleep(delay);if(photonGate)await photonGate.wait();lastStarted=now();
        const controller=new AbortController();let timer;
        try{
          const timeout=new Promise((_,reject)=>{timer=setTimeout(()=>{controller.abort();reject(fail(504,'UPSTREAM_TIMEOUT','장소 검색 서버의 응답 시간이 초과되었습니다. 다시 검색해주세요.'));},timeoutMs);});
          const request=(async()=>{
            const response=await fetchFn(url.href,{method:'GET',headers:{'User-Agent':USER_AGENT,Accept:'application/json'},redirect:'error',signal:controller.signal});
            if(response.status===429||response.status===503){
              const seconds=Number(response.headers?.get?.('retry-after'));
              cooldownUntil=now()+Math.min(300,Number.isFinite(seconds)&&seconds>0?Math.max(1,seconds):60)*1000;
              throw fail(429,'UPSTREAM_LIMIT','장소 검색 서버가 요청을 제한했습니다. 잠시 후 다시 검색해주세요.');
            }
            if(!response.ok)throw fail(502,'UPSTREAM_ERROR','장소 검색 서버에 연결하지 못했습니다. 잠시 후 다시 검색해주세요.');
            return normalize(await readPayload(response,maxBytes),query,new Date(now()).toISOString());
          })();
          const data=await Promise.race([request,timeout]);
          while(cache.size>=Math.max(1,maxEntries))cache.delete(cache.keys().next().value);
          cache.set(query,{at:now(),data});return data;
        }catch(e){if(e instanceof PlaceError)throw e;throw fail(502,'UPSTREAM_ERROR','장소 검색을 완료하지 못했습니다. 인터넷 연결을 확인해주세요.');}
        finally{clearTimeout(timer);controller.abort();}
      });
      queue=operation.catch(()=>{});inFlight.set(query,operation);
      try{return structuredClone(await operation);}finally{pending--;inFlight.delete(query);}
    }
  };
}
module.exports={createPlaceProvider,PlaceError,searchParameters,normalize,category,ATTRIBUTION};
