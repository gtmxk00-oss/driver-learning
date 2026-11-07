'use strict';

// The user's registered key stays in this process. No demonstration key, proxy
// override, forged Referer, or unverified domain parameter is used.
const MAX_NATIVE_ZOOM=18;
const ATTRIBUTION='공간정보 오픈플랫폼(브이월드)';
const MAX_CACHE_MS=6*60*60*1000;
class MapError extends Error {
  constructor(status,code,message){super(message);this.status=status;this.code=code;}
}
const fail=(status,code,message)=>new MapError(status,code,message);
function tileParameters(z,x,y){
  const integer=value=>typeof value==='number'?Number.isSafeInteger(value)&&value>=0:typeof value==='string'&&/^(?:0|[1-9][0-9]{0,5})$/.test(value);
  if(!integer(z)||!integer(x)||!integer(y))throw fail(400,'INVALID_TILE','올바른 지도 타일 좌표가 필요합니다.');
  z=Number(z);x=Number(x);y=Number(y);
  if(z>MAX_NATIVE_ZOOM||x>=2**z||y>=2**z)throw fail(400,'INVALID_TILE','지원하는 지도 확대 단계와 타일 범위를 확인하세요.');
  return {z,x,y};
}

function validJpeg256(bytes){
  if(!Buffer.isBuffer(bytes)||bytes.length<16||bytes[0]!==0xff||bytes[1]!==0xd8||bytes.at(-2)!==0xff||bytes.at(-1)!==0xd9)return false;
  let position=2,dimensions=false;
  while(position<bytes.length-2){
    if(bytes[position++]!==0xff)return false;
    while(bytes[position]===0xff)position++;
    const marker=bytes[position++];
    if(marker===undefined||marker===0||marker===0xd8||marker===0xd9)return false;
    if(marker===0x01||marker>=0xd0&&marker<=0xd7)continue;
    if(position+2>bytes.length)return false;
    const length=bytes.readUInt16BE(position);
    if(length<2||position+length>bytes.length-2)return false;
    if(marker===0xc0||marker===0xc1||marker===0xc2){
      if(length<8)return false;
      const components=bytes[position+7];
      if(bytes[position+2]!==8||bytes.readUInt16BE(position+3)!==256||bytes.readUInt16BE(position+5)!==256||![1,3,4].includes(components)||length!==8+components*3)return false;
      dimensions=true;
    }
    if(marker===0xda){
      const components=bytes[position+2];
      return dimensions&&components>=1&&components<=4&&length===6+2*components&&position+length<bytes.length-2;
    }
    position+=length;
  }
  return false;
}

function cacheLifetime(headers,nowMs){
  const control=String(headers?.get?.('cache-control')||'').toLowerCase();
  if(/(?:^|,)\s*(?:no-store|no-cache)(?:\s|,|$)/.test(control))return 0;
  const match=/(?:^|,)\s*max-age\s*=\s*"?(\d+)"?/.exec(control);
  if(match){
    const age=Number(headers?.get?.('age')||0);
    return Math.max(0,Math.min(MAX_CACHE_MS,(Number(match[1])-(Number.isFinite(age)?Math.max(0,age):0))*1000));
  }
  const expires=Date.parse(headers?.get?.('expires')||'');
  return Number.isFinite(expires)?Math.max(0,Math.min(MAX_CACHE_MS,expires-nowMs)):MAX_CACHE_MS;
}

async function readJpeg(response,maxBytes){
  if(!/^image\/jpeg(?:\s*;|$)/i.test(response.headers?.get?.('content-type')||''))throw fail(502,'INVALID_TILE_RESPONSE','영상 대신 다른 응답을 받았습니다. 제공자 인증과 서비스 등록을 확인하세요.');
  const length=Number(response.headers?.get?.('content-length'));
  if(Number.isFinite(length)&&length>maxBytes)throw fail(502,'TILE_TOO_LARGE','영상 타일 응답이 너무 큽니다.');
  if(!response.body||typeof response.body[Symbol.asyncIterator]!=='function')throw fail(502,'INVALID_TILE_RESPONSE','영상 타일을 읽지 못했습니다.');
  const chunks=[];let size=0;
  for await(const chunk of response.body){
    size+=Buffer.byteLength(chunk);
    if(size>maxBytes)throw fail(502,'TILE_TOO_LARGE','영상 타일 응답이 너무 큽니다.');
    chunks.push(Buffer.from(chunk));
  }
  const body=Buffer.concat(chunks);
  if(!validJpeg256(body))throw fail(502,'INVALID_TILE_RESPONSE','올바른 크기의 JPEG 영상 타일을 받지 못했습니다.');
  return body;
}

function createVworldProvider({env=process.env,fetchFn=globalThis.fetch,now=()=>Date.now(),sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms)),timeoutMs=12000,maxBytes=1048576,maxEntries=64,maxPending=32,maxConcurrency=2,minIntervalMs=250}={}){
  const rawKey=typeof env.VWORLD_API_KEY==='string'?env.VWORLD_API_KEY.trim():'';
  const validKey=/^[A-Za-z0-9_-]{8,128}$/.test(rawKey);
  const cache=new Map(),inFlight=new Map(),queue=[];
  let active=0,pumping=false,lastStarted=-Infinity,cooldownUntil=0;
  const status={provider:'vworld',configured:validKey,authentication:'unverified',maxNativeZoom:MAX_NATIVE_ZOOM,attribution:ATTRIBUTION,tileUrl:'/api/maps/vworld/{z}/{x}/{y}',notice:validKey?'서버에 키가 설정되었습니다. 실제 인증·영상 품질은 타일을 불러온 뒤 확인해야 합니다.':'고해상도 항공영상은 브이월드 이용 등록과 서버 API 키 설정이 필요합니다.'};
  async function drain(){
    if(pumping)return;pumping=true;
    try{
      while(queue.length&&active<Math.max(1,Math.min(2,maxConcurrency))){
        const delay=Math.max(0,minIntervalMs-(now()-lastStarted));if(delay)await sleep(delay);
        const job=queue.shift();active++;lastStarted=now();
        run(job.parameters).then(job.resolve,job.reject).finally(()=>{active--;drain();});
      }
    }finally{pumping=false;}
  }
  async function run({z,x,y}){
    if(now()<cooldownUntil)throw fail(429,'MAP_RATE_LIMIT','영상 제공 서버가 요청을 제한했습니다. 잠시 후 다시 시도하세요.');
    const controller=new AbortController();let timer;
    try{
      const timeout=new Promise((_,reject)=>{timer=setTimeout(()=>{controller.abort();reject(fail(504,'MAP_TIMEOUT','항공영상 응답 시간이 초과되었습니다. 다시 시도하세요.'));},timeoutMs);});
      const operation=(async()=>{
        const url='https://api.vworld.kr/req/wmts/1.0.0/'+encodeURIComponent(rawKey)+'/Satellite/'+z+'/'+y+'/'+x+'.jpeg';
        const response=await fetchFn(url,{method:'GET',redirect:'error',headers:{Accept:'image/jpeg','User-Agent':'RouteMemory/0.9 (noncommercial navigation prototype)'},signal:controller.signal});
        if(response.status===401||response.status===403)throw fail(503,'MAP_AUTH_REQUIRED','브이월드 키와 허용 서비스 등록을 확인하세요.');
        if(response.status===429||response.status===503){
          const seconds=Number(response.headers?.get?.('retry-after'));
          cooldownUntil=now()+Math.min(300,Number.isFinite(seconds)&&seconds>0?Math.max(1,seconds):60)*1000;
          throw fail(429,'MAP_RATE_LIMIT','영상 제공 서버가 요청을 제한했습니다. 잠시 후 다시 시도하세요.');
        }
        if(response.status===404)throw fail(502,'MAP_TILE_UNAVAILABLE','해당 지역 또는 확대 단계의 항공영상을 제공하지 않습니다.');
        if(!response.ok)throw fail(502,'MAP_UPSTREAM_ERROR','항공영상 제공 서버에 연결하지 못했습니다.');
        const body=await readJpeg(response,maxBytes),lifetime=cacheLifetime(response.headers,now());
        return {body,expiresAt:now()+lifetime,cacheControl:lifetime?'private, max-age='+Math.floor(lifetime/1000):'no-store'};
      })();
      return await Promise.race([operation,timeout]);
    }catch(error){
      if(error instanceof MapError)throw error;
      throw fail(502,'MAP_UPSTREAM_ERROR','항공영상을 받지 못했습니다. 네트워크와 제공자 설정을 확인하세요.');
    }finally{clearTimeout(timer);controller.abort();}
  }
  return {
    status(){return structuredClone(status);},
    async tile(z,x,y){
      const parameters=tileParameters(z,x,y);
      if(!validKey)throw fail(503,rawKey?'INVALID_MAP_CONFIGURATION':'MAP_KEY_REQUIRED','고해상도 항공영상용 서버 키가 설정되지 않았습니다. 브이월드에서 발급한 키를 서버 환경에 설정하세요.');
      const key=parameters.z+'/'+parameters.x+'/'+parameters.y,cached=cache.get(key);
      if(cached){
        cache.delete(key);
        if(cached.expiresAt>now()){cache.set(key,cached);return {body:Buffer.from(cached.body),cacheControl:'private, max-age='+Math.floor((cached.expiresAt-now())/1000)};}
      }
      if(inFlight.has(key)){const answer=await inFlight.get(key);return {body:Buffer.from(answer.body),cacheControl:answer.cacheControl};}
      if(now()<cooldownUntil)throw fail(429,'MAP_RATE_LIMIT','영상 제공 서버가 요청을 제한했습니다. 잠시 후 다시 시도하세요.');
      if(inFlight.size>=Math.max(1,Math.min(32,maxPending)))throw fail(429,'MAP_BUSY','항공영상을 불러오는 중입니다. 잠시 후 다시 시도하세요.');
      const operation=new Promise((resolve,reject)=>{queue.push({parameters,resolve,reject});drain();});
      inFlight.set(key,operation);
      try{
        const answer=await operation;
        if(answer.expiresAt>now()){
          while(cache.size>=Math.max(1,Math.min(64,maxEntries)))cache.delete(cache.keys().next().value);
          cache.set(key,answer);
        }
        return {body:Buffer.from(answer.body),cacheControl:answer.cacheControl};
      }finally{inFlight.delete(key);}
    }
  };
}
module.exports={createVworldProvider,MapError,tileParameters,validJpeg256,cacheLifetime,MAX_NATIVE_ZOOM,ATTRIBUTION};
