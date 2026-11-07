'use strict';

const net=require('node:net');
const {sharedPhotonGate}=require('./shared-photon-gate.cjs');
const DEFAULT_BASE='https://photon.komoot.io';
const ATTRIBUTION='Nearby places: Photon by komoot · © OpenStreetMap contributors';
const NOTICE='지도에 등록된 주변 장소입니다. 거리는 직선거리이며 영업·진입 가능 여부는 확인이 필요합니다. 유가와 잔여 주차면은 아직 연결되지 않았으며 교통량으로 추정하지 않습니다.';
const USER_AGENT='RouteMemory/0.7.0 (PC navigation prototype; manual nearby search)';
const PARKING_MAX_AGE_MS=300000;
const FUEL_MAX_AGE_MS=86400000;
const LABELS={fuel:'주유소',parking:'주차장'};
const PRODUCT_CODES={gasoline:'B027',diesel:'D047',premium:'B034',lpg:'K015'};
class NearbyError extends Error {
  constructor(status,code,message){super(message);this.status=status;this.code=code;}
}
const fail=(status,code,message)=>new NearbyError(status,code,message);
const finite=value=>typeof value==='number'&&Number.isFinite(value);
const clean=(value,limit=160)=>typeof value==='string'?value.replace(/[\u0000-\u001f\u007f-\u009f]/g,' ').trim().replace(/\s+/g,' ').slice(0,limit):'';
const inKorea=(lat,lon)=>finite(lat)&&finite(lon)&&lat>=32&&lat<=39.5&&lon>=124&&lon<=132;

function searchParameters(input){
  if(!input||typeof input!=='object'||Array.isArray(input)||!input.center||typeof input.center!=='object'||Array.isArray(input.center)||!inKorea(input.center.lat,input.center.lon))
    throw fail(400,'INVALID_CENTER','국내 지도 위치를 선택한 뒤 주변을 검색해주세요.');
  if(!['fuel','parking'].includes(input.kind))throw fail(400,'INVALID_KIND','주유소 또는 주차장을 선택해주세요.');
  const radiusMeters=input.radiusMeters===undefined?3000:input.radiusMeters;
  if(![1000,3000].includes(radiusMeters))throw fail(400,'INVALID_RADIUS','검색 반경은 1km 또는 3km를 선택해주세요.');
  return {center:{lat:input.center.lat,lon:input.center.lon},kind:input.kind,radiusMeters};
}

function baseURL(value){
  let url;try{url=new URL(value);}catch{throw fail(503,'INVALID_CONFIGURATION','주변 검색 서버 설정을 확인해주세요.');}
  const host=url.hostname.toLowerCase();
  if(url.protocol!=='https:'||url.username||url.password||url.search||url.hash||url.pathname!=='/'||url.port&&url.port!=='443'||host==='localhost'||host.endsWith('.localhost')||host.endsWith('.local')||host.endsWith('.internal')||host.includes(':')||net.isIP(host))
    throw fail(503,'INVALID_CONFIGURATION','공개 HTTPS 주변 검색 서버를 설정해주세요.');
  return url.origin;
}

function distanceMeters(center,lat,lon){
  const radians=value=>value*Math.PI/180;
  const a=Math.sin(radians(lat-center.lat)/2)**2+Math.cos(radians(center.lat))*Math.cos(radians(lat))*Math.sin(radians(lon-center.lon)/2)**2;
  return 6371008.8*2*Math.atan2(Math.sqrt(a),Math.sqrt(Math.max(0,1-a)));
}

function unavailableFuel(reason='API_KEY_REQUIRED'){
  return {status:'unavailable',priceWonPerLiter:null,fuelType:'gasoline',source:null,updatedAt:null,reason};
}
function unavailableParking(reason='API_KEY_REQUIRED'){
  return {status:'unavailable',capacity:null,available:null,source:null,updatedAt:null,reason};
}
function recordTime(value,nowMs,maxAgeMs){
  if(typeof value!=='string'||!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?(?:Z|\+09:00)$/.test(value))return null;
  const time=Date.parse(value);
  if(!Number.isFinite(time)||time>nowMs+60000)return null;
  const localTime=new Date(time+(value.endsWith('+09:00')?32400000:0)).toISOString().slice(0,19);
  if(localTime!==value.slice(0,19))return null;
  return {updatedAt:new Date(time).toISOString(),stale:nowMs-time>maxAgeMs};
}

// These normalizers accept provider records only after an exact, externally
// verified source ID binding. They never use a station's name or proximity to
// attach prices/counts. Environment keys alone cannot activate this seam.
function normalizeFuelPrice(record,expectedId,nowMs){
  const missing=()=>unavailableFuel('INVALID_PROVIDER_RECORD');
  if(!record||record.source!=='opinet'||record.sourceId!==expectedId||!expectedId||!Object.hasOwn(PRODUCT_CODES,record.fuelType)||!finite(record.priceWonPerLiter)||record.priceWonPerLiter<=0||record.priceWonPerLiter>99999)return missing();
  const time=recordTime(record.updatedAt,nowMs,FUEL_MAX_AGE_MS);if(!time)return missing();
  return {status:time.stale?'stale':'reported',priceWonPerLiter:record.priceWonPerLiter,fuelType:record.fuelType,source:'opinet',updatedAt:time.updatedAt,reason:time.stale?'STALE':null};
}
function normalizeParking(record,expectedId,nowMs,expectedSource='seoul-parking'){
  const missing=()=>unavailableParking('INVALID_PROVIDER_RECORD');
  if(typeof expectedSource!=='string'||!(/^[a-z][a-z0-9-]{1,39}$/).test(expectedSource)||!record||record.source!==expectedSource||record.sourceId!==expectedId||!expectedId||!Number.isSafeInteger(record.available)||record.available<0||record.available>100000)return missing();
  const capacity=record.capacity===null||record.capacity===undefined?null:record.capacity;
  if(capacity!==null&&(!Number.isSafeInteger(capacity)||capacity<0||capacity>100000||record.available>capacity))return missing();
  const time=recordTime(record.updatedAt,nowMs,PARKING_MAX_AGE_MS);if(!time)return missing();
  return {status:time.stale?'stale':'reported',capacity,available:record.available,source:expectedSource,updatedAt:time.updatedAt,reason:time.stale?'STALE':null};
}

const providerNumber=value=>finite(value)?value:typeof value==='string'&&/^\d+(?:\.\d+)?$/.test(value)?Number(value):null;
// Official detailById: keep the product's own report time rather than replacing
// it with the request time. This pure parser makes no network calls.
function normalizeOpinetDetail(station,expectedId,fuelType,nowMs){
  if(!station||station.UNI_ID!==expectedId||!Object.hasOwn(PRODUCT_CODES,fuelType))return unavailableFuel('INVALID_PROVIDER_RECORD');
  const prices=Array.isArray(station.OIL_PRICE)?station.OIL_PRICE:station.OIL_PRICE?[station.OIL_PRICE]:[];
  const matches=prices.filter(price=>price?.PRODCD===PRODUCT_CODES[fuelType]);
  if(matches.length!==1)return unavailableFuel('INVALID_PROVIDER_RECORD');
  const price=matches[0],date=String(price.TRADE_DT||''),time=String(price.TRADE_TM||'');
  const updatedAt=/^\d{8}$/.test(date)&&/^\d{6}$/.test(time)?`${date.slice(0,4)}-${date.slice(4,6)}-${date.slice(6,8)}T${time.slice(0,2)}:${time.slice(2,4)}:${time.slice(4,6)}+09:00`:null;
  return normalizeFuelPrice({source:'opinet',sourceId:station.UNI_ID,fuelType,priceWonPerLiter:providerNumber(price.PRICE),updatedAt},expectedId,nowMs);
}
// GetParkingInfo (2026 schema) reports occupied vehicles, NOT remaining spaces.
// Its service flag can cover twenty-minute-old data, so check the timestamp too.
function normalizeSeoulParkingRow(row,expectedId,nowMs){
  if(!row||row.PKLT_CD!==expectedId||String(row.PRK_STTS_YN)!=='1')return unavailableParking('NO_CURRENT_REPORT');
  const capacity=providerNumber(row.TPKCT),occupied=providerNumber(row.NOW_PRK_VHCL_CNT);
  if(!Number.isSafeInteger(capacity)||capacity<0||!Number.isSafeInteger(occupied)||occupied<0||occupied>capacity)return unavailableParking('INVALID_PROVIDER_RECORD');
  const time=row.NOW_PRK_VHCL_UPDT_TM,updatedAt=typeof time==='string'&&/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(time)?time.replace(' ','T')+'+09:00':null;
  return normalizeParking({source:'seoul-parking',sourceId:row.PKLT_CD,capacity,available:capacity-occupied,updatedAt},expectedId,nowMs);
}

function normalize(payload,parameters,fetchedAt){
  if(!payload||typeof payload!=='object'||!Array.isArray(payload.features)||payload.features.length>100||payload.type&&payload.type!=='FeatureCollection')
    throw fail(502,'INVALID_RESPONSE','주변 검색 응답을 읽지 못했습니다. 잠시 후 다시 검색해주세요.');
  const {center,kind,radiusMeters}=parameters,results=[],seen=new Set();
  for(const feature of payload.features){
    const p=feature?.properties,coordinates=feature?.geometry?.coordinates;
    if(!p||typeof p!=='object'||Array.isArray(p)||feature?.geometry?.type!=='Point'||!Array.isArray(coordinates)||coordinates.length<2||p.osm_key!=='amenity'||p.osm_value!==kind)continue;
    const [lon,lat]=coordinates;if(!inKorea(lat,lon))continue;
    const country=clean(p.countrycode,8).toUpperCase();if(country&&country!=='KR')continue;
    const distance=distanceMeters(center,lat,lon);if(distance>radiusMeters)continue;
    const osmType=clean(p.osm_type,2).toUpperCase(),osmId=typeof p.osm_id==='number'&&Number.isSafeInteger(p.osm_id)&&p.osm_id>0?String(p.osm_id):typeof p.osm_id==='string'&&/^[1-9][0-9]{0,15}$/.test(p.osm_id)?p.osm_id:'';
    const id=/^[NWR]$/.test(osmType)&&osmId?'photon-'+osmType+'-'+osmId:'photon-point-'+lat.toFixed(6)+'-'+lon.toFixed(6);
    if(seen.has(id))continue;seen.add(id);
    const streetHouse=[clean(p.street),clean(p.housenumber,40)].filter(Boolean).join(' ');
    const components=[p.state,p.city,p.county,p.district,p.locality].map(value=>clean(value)).filter(Boolean);if(streetHouse)components.push(streetHouse);
    const address=[...new Set(components)].join(' ').slice(0,240);
    results.push({id,name:clean(p.name)||LABELS[kind]+' (이름 미등록)',address,category:LABELS[kind],lat,lon,distanceMeters:Math.round(distance),fuelPrice:unavailableFuel(),parking:unavailableParking()});
  }
  results.sort((a,b)=>a.distanceMeters-b.distanceMeters||a.id.localeCompare(b.id));
  return {source:'real-nearby',provider:'photon',kind,center:{...center},radiusMeters,fetchedAt,results:results.slice(0,20),attribution:ATTRIBUTION,notice:NOTICE};
}

function applyLiveRecords(data,records,identityBindings,nowMs){
  if(!Array.isArray(records)||records.length>100)return data;
  for(const place of data.results){
    const binding=identityBindings instanceof Map?identityBindings.get(place.id):Object.hasOwn(identityBindings,place.id)?identityBindings[place.id]:null;
    if(!binding||typeof binding.source!=='string'||!(/^[a-z][a-z0-9-]{1,39}$/).test(binding.source)||data.kind==='fuel'&&binding.source!=='opinet'||typeof binding.sourceId!=='string'||!binding.sourceId)continue;
    const matches=records.filter(record=>record?.source===binding.source&&record?.sourceId===binding.sourceId);
    if(matches.length!==1)continue;
    if(data.kind==='fuel')place.fuelPrice=normalizeFuelPrice(matches[0],binding.sourceId,nowMs);
    else place.parking=normalizeParking(matches[0],binding.sourceId,nowMs,binding.source);
  }
  return data;
}

async function readPayload(response,maxBytes){
  const length=Number(response.headers?.get?.('content-length'));
  if(Number.isFinite(length)&&length>maxBytes)throw fail(502,'RESPONSE_TOO_LARGE','주변 검색 응답이 너무 큽니다. 검색 반경을 줄여주세요.');
  if(!response.body||typeof response.body[Symbol.asyncIterator]!=='function')throw fail(502,'INVALID_RESPONSE','주변 검색 응답을 읽지 못했습니다.');
  const chunks=[];let size=0;
  for await(const chunk of response.body){size+=Buffer.byteLength(chunk);if(size>maxBytes)throw fail(502,'RESPONSE_TOO_LARGE','주변 검색 응답이 너무 큽니다. 검색 반경을 줄여주세요.');chunks.push(Buffer.from(chunk));}
  try{return JSON.parse(Buffer.concat(chunks).toString('utf8'));}catch{throw fail(502,'INVALID_RESPONSE','주변 검색 응답을 읽지 못했습니다. 잠시 후 다시 검색해주세요.');}
}

function createNearbyProvider({fetchFn=globalThis.fetch,now=()=>Date.now(),sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms)),timeoutMs=12000,cacheMs=60000,maxEntries=20,minIntervalMs=1000,maxPending=4,maxBytes=524288,base=process.env.PHOTON_BASE_URL||DEFAULT_BASE,photonGate=fetchFn===globalThis.fetch?sharedPhotonGate:null,liveAdapter=null,identityBindings=new Map(),env=process.env}={}){
  const cache=new Map(),inFlight=new Map();let queue=Promise.resolve(),lastStarted=-Infinity,pending=0,cooldownUntil=0,endpoint,configurationError;
  try{endpoint=baseURL(base)+'/reverse';}catch(e){configurationError=e;}
  const status={configured:!configurationError,provider:'photon',priceConfigured:false,parkingConfigured:false,keysPresent:{opinet:Boolean(env.OPINET_API_KEY),seoulParking:Boolean(env.SEOUL_PARKING_API_KEY)},connected:false};
  return {
    configured:!configurationError,
    status(){return structuredClone(status);},
    async search(input){
      const parameters=searchParameters(input);if(configurationError)throw configurationError;
      const key=JSON.stringify(parameters),cached=cache.get(key);
      if(cached&&now()-cached.at<cacheMs)return structuredClone(cached.data);if(cached)cache.delete(key);
      if(inFlight.has(key))return structuredClone(await inFlight.get(key));
      if(now()<cooldownUntil)throw fail(429,'UPSTREAM_LIMIT','주변 검색 서버가 요청을 제한했습니다. 잠시 후 다시 검색해주세요.');
      if(pending>=maxPending)throw fail(429,'BUSY','주변 검색 중입니다. 잠시 후 다시 눌러주세요.');
      const {center,kind,radiusMeters}=parameters,url=new URL(endpoint);
      url.search=new URLSearchParams({lat:String(center.lat),lon:String(center.lon),radius:String(radiusMeters/1000),limit:'20',osm_tag:'amenity:'+kind}).toString();
      pending++;
      const operation=queue.catch(()=>{}).then(async()=>{
        if(now()<cooldownUntil)throw fail(429,'UPSTREAM_LIMIT','주변 검색 서버가 요청을 제한했습니다. 잠시 후 다시 검색해주세요.');
        const delay=Math.max(0,minIntervalMs-(now()-lastStarted));if(delay)await sleep(delay);if(photonGate)await photonGate.wait();lastStarted=now();
        const controller=new AbortController();let timer;
        try{
          const timeout=new Promise((_,reject)=>{timer=setTimeout(()=>{controller.abort();reject(fail(504,'UPSTREAM_TIMEOUT','주변 검색 서버의 응답 시간이 초과되었습니다. 다시 검색해주세요.'));},timeoutMs);});
          const request=(async()=>{
            const response=await fetchFn(url.href,{method:'GET',headers:{'User-Agent':USER_AGENT,Accept:'application/json'},redirect:'error',signal:controller.signal});
            if(response.status===429||response.status===503){
              const seconds=Number(response.headers?.get?.('retry-after'));cooldownUntil=now()+Math.min(300,Number.isFinite(seconds)&&seconds>0?Math.max(1,seconds):60)*1000;
              throw fail(429,'UPSTREAM_LIMIT','주변 검색 서버가 요청을 제한했습니다. 잠시 후 다시 검색해주세요.');
            }
            if(!response.ok)throw fail(502,'UPSTREAM_ERROR','주변 검색 서버에 연결하지 못했습니다. 잠시 후 다시 검색해주세요.');
            const data=normalize(await readPayload(response,maxBytes),parameters,new Date(now()).toISOString());
            // An internal integration seam, intentionally unconfigured in this
            // release. No client payload or environment key can set bindings.
            if(liveAdapter&&typeof liveAdapter.read==='function'){
              const ids=data.results.map(place=>({id:place.id,binding:identityBindings instanceof Map?identityBindings.get(place.id):Object.hasOwn(identityBindings,place.id)?identityBindings[place.id]:null})).filter(place=>place.binding);
              if(ids.length){try{applyLiveRecords(data,await liveAdapter.read({kind,places:structuredClone(ids),signal:controller.signal}),identityBindings,now());}catch{ /* Keep real places when optional live data fails. */ }}
            }
            return data;
          })();
          const data=await Promise.race([request,timeout]);while(cache.size>=Math.max(1,maxEntries))cache.delete(cache.keys().next().value);cache.set(key,{at:now(),data});return data;
        }catch(e){if(e instanceof NearbyError)throw e;throw fail(502,'UPSTREAM_ERROR','주변 검색을 완료하지 못했습니다. 인터넷 연결을 확인해주세요.');}
        finally{clearTimeout(timer);controller.abort();}
      });
      queue=operation.catch(()=>{});inFlight.set(key,operation);
      try{return structuredClone(await operation);}finally{pending--;inFlight.delete(key);}
    }
  };
}
module.exports={createNearbyProvider,NearbyError,searchParameters,normalize,distanceMeters,normalizeFuelPrice,normalizeParking,normalizeOpinetDetail,normalizeSeoulParkingRow,applyLiveRecords,ATTRIBUTION,FUEL_MAX_AGE_MS,PARKING_MAX_AGE_MS};
