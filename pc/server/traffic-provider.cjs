'use strict';

const UPSTREAM='https://apis-navi.kakaomobility.com/v1/directions';

class TrafficError extends Error {
  constructor(status,code,message){super(message);this.status=status;this.code=code;}
}
const error=(status,code,message)=>new TrafficError(status,code,message);
const finiteNonnegative=value=>typeof value==='number'&&Number.isFinite(value)&&value>=0;
function coordinates(input){
  if(!input||typeof input!=='object'||Array.isArray(input))throw error(400,'INVALID_COORDINATES','출발지와 목적지 좌표를 확인해주세요.');
  function point(value){
    if(!value||typeof value!=='object'||Array.isArray(value)||typeof value.x!=='number'||typeof value.y!=='number'||!Number.isFinite(value.x)||!Number.isFinite(value.y)||value.x<124||value.x>132||value.y<33||value.y>39)
      throw error(400,'INVALID_COORDINATES','대한민국 범위의 경도·위도 숫자를 입력해주세요.');
    return {x:value.x,y:value.y};
  }
  const origin=point(input.origin),destination=point(input.destination);
  if(origin.x===destination.x&&origin.y===destination.y)throw error(400,'SAME_COORDINATES','출발지와 목적지를 다르게 입력해주세요.');
  return {origin,destination};
}
function normalize(payload,fetchedAt){
  if(!payload||!Array.isArray(payload.routes))throw error(502,'INVALID_RESPONSE','교통정보 응답을 읽지 못했습니다. 잠시 후 다시 조회해주세요.');
  const routes=payload.routes.flatMap((r,index)=>{
    if(!r||r.result_code!==0||!finiteNonnegative(r.summary?.duration)||r.summary.duration<=0||!finiteNonnegative(r.summary?.distance)||r.summary.distance<=0)return [];
    const roads=(Array.isArray(r.sections)?r.sections:[]).flatMap(section=>Array.isArray(section?.roads)?section.roads:[]).flatMap(road=>{
      if(!road||!finiteNonnegative(road.distance)||!finiteNonnegative(road.duration))return [];
      return [{name:typeof road.name==='string'?road.name.slice(0,150):'',distanceMeters:road.distance,durationSeconds:road.duration,
        speedKph:finiteNonnegative(road.traffic_speed)?road.traffic_speed:null,
        trafficState:Number.isInteger(road.traffic_state)&&road.traffic_state>=0?road.traffic_state:null}];
    });
    return [{id:'kakao-route-'+index,name:'실제 경로 후보 '+(index+1),durationSeconds:r.summary.duration,distanceMeters:r.summary.distance,roads}];
  });
  if(!routes.length)throw error(502,'NO_ROUTE','이 좌표에서 이용 가능한 실제 경로를 찾지 못했습니다.');
  return {source:'live',provider:'kakao',fetchedAt,routes};
}
function createTrafficProvider({apiKey=process.env.KAKAO_MOBILITY_REST_KEY,fetchFn=globalThis.fetch,now=()=>Date.now(),timeoutMs=8000,cacheMs=30000,maxEntries=20}={}){
  const key=typeof apiKey==='string'?apiKey.trim():'';
  const cache=new Map();
  return {
    configured:!!key,
    async routes(input){
      const params=coordinates(input);
      if(!key)throw error(503,'NOT_CONFIGURED','실시간 연결을 준비하려면 서버에 KAKAO_MOBILITY_REST_KEY를 설정해주세요. 키는 채팅이나 HTML에 넣지 마세요.');
      const cacheKey=JSON.stringify(params),cached=cache.get(cacheKey),startedAt=now();
      if(cached&&startedAt-cached.at<cacheMs)return cached.data;
      if(cached)cache.delete(cacheKey);
      const url=new URL(UPSTREAM);
      url.search=new URLSearchParams({origin:params.origin.x+','+params.origin.y,destination:params.destination.x+','+params.destination.y,priority:'TIME',summary:'false',alternatives:'true',road_details:'true',roadevent:'0'}).toString();
      const controller=new AbortController();let timer;
      try{
        const timeout=new Promise((_,reject)=>{timer=setTimeout(()=>{controller.abort();reject(error(504,'UPSTREAM_TIMEOUT','교통정보 조회 시간이 초과되었습니다. 잠시 후 다시 조회해주세요.'));},timeoutMs);});
        const operation=(async()=>{
          const response=await fetchFn(url.toString(),{method:'GET',headers:{Authorization:'KakaoAK '+key,'Content-Type':'application/json'},signal:controller.signal,redirect:'error'});
          if(response.status===401||response.status===403)throw error(502,'UPSTREAM_AUTH','카카오모빌리티 API 키와 사용 권한을 확인해주세요.');
          if(response.status===429)throw error(502,'UPSTREAM_LIMIT','교통정보 API 이용 한도에 도달했습니다. 제공 업체의 사용량을 확인해주세요.');
          if(!response.ok)throw error(502,'UPSTREAM_ERROR','교통정보 제공 업체에 연결하지 못했습니다. 잠시 후 다시 조회해주세요.');
          const payload=await response.json();
          return normalize(payload,new Date(now()).toISOString());
        })();
        const data=await Promise.race([operation,timeout]);
        while(cache.size>=maxEntries)cache.delete(cache.keys().next().value);
        cache.set(cacheKey,{at:startedAt,data});return data;
      }catch(e){
        if(e instanceof TrafficError)throw e;
        throw error(502,'UPSTREAM_ERROR','교통정보를 받아오지 못했습니다. 네트워크와 API 사용 설정을 확인해주세요.');
      }finally{clearTimeout(timer);}
    }
  };
}
module.exports={createTrafficProvider,TrafficError,coordinates,normalize};
