'use strict';

const DEFAULT_BASE='https://router.project-osrm.org';
const USER_AGENT='RouteMemoryPC/0.4 (local navigation prototype; https://project-osrm.org/)';
class RoadError extends Error {
  constructor(status,code,message){super(message);this.status=status;this.code=code;}
}
const fail=(status,code,message)=>new RoadError(status,code,message);
const finite=value=>typeof value==='number'&&Number.isFinite(value);
const positive=value=>finite(value)&&value>0;
const nonnegative=value=>finite(value)&&value>=0;
const text=value=>typeof value==='string'?value.trim().slice(0,200):'';
const coordinate=value=>Array.isArray(value)&&value.length>=2&&finite(value[0])&&finite(value[1])&&Math.abs(value[0])<=180&&Math.abs(value[1])<=90;
function distance(a,b){
  const rad=Math.PI/180,dy=(b[1]-a[1])*rad,dx=(b[0]-a[0])*rad;
  const h=Math.sin(dy/2)**2+Math.cos(a[1]*rad)*Math.cos(b[1]*rad)*Math.sin(dx/2)**2;
  return 6371000*2*Math.atan2(Math.sqrt(h),Math.sqrt(Math.max(0,1-h)));
}
function coordinates(input){
  if(!input||typeof input!=='object'||Array.isArray(input))throw fail(400,'INVALID_COORDINATES','출발지와 목적지 좌표를 확인해주세요.');
  const point=value=>{
    if(!value||typeof value!=='object'||Array.isArray(value)||!finite(value.lat)||!finite(value.lon)||Math.abs(value.lat)>90||Math.abs(value.lon)>180)
      throw fail(400,'INVALID_COORDINATES','올바른 위도·경도 숫자를 입력해주세요.');
    return {lat:value.lat,lon:value.lon};
  };
  const origin=point(input.origin),destination=point(input.destination);
  if(distance([origin.lon,origin.lat],[destination.lon,destination.lat])<1)throw fail(400,'SAME_COORDINATES','출발지와 목적지를 다르게 선택해주세요.');
  return {origin,destination};
}
function baseURL(value){
  let url;try{url=new URL(value);}catch{throw fail(503,'INVALID_CONFIGURATION','도로 경로 서버 설정을 확인해주세요.');}
  if(url.protocol!=='https:'||url.username||url.password||url.search||url.hash||url.port&&url.port!=='443')throw fail(503,'INVALID_CONFIGURATION','HTTPS 경로 서버 주소만 설정할 수 있습니다.');
  if(['localhost','127.0.0.1','[::1]'].includes(url.hostname)||/^\d+\.\d+\.\d+\.\d+$/.test(url.hostname))throw fail(503,'INVALID_CONFIGURATION','공개 HTTPS 경로 서버를 설정해주세요.');
  return url.href.replace(/\/+$/,'');
}
function koreanCue(maneuver,name){
  const road=name?name+' 방면으로 ':'',type=text(maneuver?.type),modifier=text(maneuver?.modifier);
  const turn={left:'좌회전',right:'우회전','slight left':'왼쪽 방향','slight right':'오른쪽 방향','sharp left':'크게 좌회전','sharp right':'크게 우회전',uturn:'유턴',straight:'직진'}[modifier];
  if(type==='arrive')return '목적지에 도착합니다';
  if(type==='depart')return road+'출발하세요';
  if(['roundabout','rotary','roundabout turn'].includes(type)){
    const exit=Number.isInteger(maneuver.exit)&&maneuver.exit>0&&maneuver.exit<100?maneuver.exit:null;
    return '회전교차로에서 '+(exit?exit+'번째 출구로 나가세요':road+'경로를 따라 이동하세요');
  }
  if(type==='exit roundabout'||type==='exit rotary')return '회전교차로를 나와 '+road+'이동하세요';
  if(type==='fork')return '갈림길에서 '+(turn?turn+'하여 ':'')+road+'이동하세요';
  if(type==='merge')return road+'합류 구간으로 이동하세요';
  if(type==='on ramp')return road+'진입로를 따라 이동하세요';
  if(type==='off ramp')return road+'진출로를 따라 이동하세요';
  if(['turn','end of road','continue'].includes(type)&&turn)return (type==='end of road'?'도로 끝에서 ':'')+turn+'하여 '+road+'이동하세요';
  return road+'경로를 따라 이동하세요';
}
function line(raw,max=100000){
  if(!raw||raw.type!=='LineString'||!Array.isArray(raw.coordinates)||raw.coordinates.length<2||raw.coordinates.length>max||raw.coordinates.some(p=>!coordinate(p)))return null;
  return raw.coordinates.map(p=>[p[0],p[1]]);
}
function normalize(payload,fetchedAt){
  if(!payload||payload.code!=='Ok'||!Array.isArray(payload.routes)||!payload.routes.length)
    throw fail(502,payload?.code==='NoRoute'||payload?.code==='NoSegment'?'NO_ROUTE':'INVALID_RESPONSE','선택한 좌표 사이의 도로 경로를 찾지 못했습니다. 지도에서 가까운 도로를 선택해주세요.');
  if(!Array.isArray(payload.waypoints)||payload.waypoints.length!==2||payload.waypoints.some(w=>!coordinate(w?.location)||!nonnegative(w.distance)))
    throw fail(502,'INVALID_RESPONSE','도로 연결 위치를 확인할 수 없는 응답입니다.');
  if(payload.waypoints.some(w=>w.distance>1000))throw fail(502,'SNAP_TOO_FAR','선택한 위치에서 이용 가능한 도로가 너무 멉니다. 지도에서 도로 가까운 곳을 선택해주세요.');
  const waypoints=payload.waypoints.map(w=>({name:text(w.name),location:[w.location[0],w.location[1]],distanceMeters:w.distance}));
  const routes=payload.routes.slice(0,5).flatMap((raw,index)=>{
    const geometry=line(raw?.geometry);if(!geometry||!positive(raw.distance)||!positive(raw.duration)||!Array.isArray(raw.legs)||raw.legs.length!==1)return [];
    if(distance(geometry[0],waypoints[0].location)>50||distance(geometry.at(-1),waypoints[1].location)>50)return [];
    const inputSteps=raw.legs.flatMap(leg=>Array.isArray(leg?.steps)?leg.steps:[]);
    if(inputSteps.length<2||inputSteps.length>2000)return [];
    let count=0;
    const steps=[];
    for(let i=0;i<inputSteps.length;i++){
      const s=inputSteps[i],points=line(s?.geometry);count+=points?.length||0;
      if(!points||count>100000||!nonnegative(s.distance)||!nonnegative(s.duration)||!coordinate(s.maneuver?.location)||!text(s.maneuver.type))return [];
      if(distance(points[0],s.maneuver.location)>50||i&&distance(steps[i-1].geometry.at(-1),points[0])>25)return [];
      const m=s.maneuver,maneuver={type:text(m.type),modifier:text(m.modifier),location:[m.location[0],m.location[1]],bearingBefore:finite(m.bearing_before)?m.bearing_before:null,bearingAfter:finite(m.bearing_after)?m.bearing_after:null,exit:Number.isInteger(m.exit)&&m.exit>0?m.exit:null};
      steps.push({id:'osrm-'+index+'-step-'+i,name:text(s.name),distanceMeters:s.distance,durationSeconds:s.duration,geometry:points,maneuver,cue:koreanCue(maneuver,text(s.name))});
    }
    if(steps[0].maneuver.type!=='depart'||steps.at(-1).maneuver.type!=='arrive'||distance(steps[0].geometry[0],geometry[0])>25||distance(steps.at(-1).geometry.at(-1),geometry.at(-1))>25)return [];
    const stepDistance=steps.reduce((n,s)=>n+s.distanceMeters,0);
    if(Math.abs(stepDistance-raw.distance)>Math.max(100,raw.distance*.1))return [];
    return [{id:'osrm-route-'+index,distanceMeters:raw.distance,durationSeconds:raw.duration,geometry,steps}];
  });
  if(!routes.length)throw fail(502,'INVALID_RESPONSE','올바른 경로와 회전 안내를 받지 못했습니다. 잠시 후 다시 요청해주세요.');
  return {source:'real-road',provider:'osrm',traffic:'unavailable',fetchedAt,routes,waypoints,attribution:'Routes by OSRM · © OpenStreetMap contributors'};
}
function createRoadProvider({fetchFn=globalThis.fetch,now=()=>Date.now(),sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms)),timeoutMs=12000,cacheMs=60000,maxEntries=20,minIntervalMs=1000,maxPending=4,base=process.env.OSRM_BASE_URL||DEFAULT_BASE}={}){
  const cache=new Map(),inFlight=new Map();let queue=Promise.resolve(),lastStarted=-Infinity,pending=0;
  return {
    configured:true,
    async route(input){
      const params=coordinates(input),cacheKey=JSON.stringify(params),cached=cache.get(cacheKey);
      if(cached&&now()-cached.at<cacheMs)return structuredClone(cached.data);
      if(cached)cache.delete(cacheKey);
      if(inFlight.has(cacheKey))return structuredClone(await inFlight.get(cacheKey));
      if(pending>=maxPending)throw fail(429,'BUSY','도로 경로 요청을 처리 중입니다. 잠시 후 다시 눌러주세요.');
      const url=new URL(baseURL(base)+'/route/v1/driving/'+params.origin.lon+','+params.origin.lat+';'+params.destination.lon+','+params.destination.lat);
      url.search=new URLSearchParams({overview:'full',geometries:'geojson',steps:'true',alternatives:'true',continue_straight:'true'}).toString();
      pending++;
      const operation=queue.catch(()=>{}).then(async()=>{
        const delay=Math.max(0,minIntervalMs-(now()-lastStarted));if(delay)await sleep(delay);lastStarted=now();
        let timer;const controller=new AbortController();
        try{
          const timeout=new Promise((_,reject)=>{timer=setTimeout(()=>{controller.abort();reject(fail(504,'UPSTREAM_TIMEOUT','도로 경로 서버의 응답 시간이 초과되었습니다. 다시 시도해주세요.'));},timeoutMs);});
          const request=(async()=>{
            const response=await fetchFn(url.href,{method:'GET',headers:{'User-Agent':USER_AGENT,Accept:'application/json'},signal:controller.signal,redirect:'error'});
            if(response.status===429)throw fail(502,'UPSTREAM_LIMIT','공개 경로 서버가 요청을 제한했습니다. 잠시 후 다시 시도해주세요.');
            if(!response.ok)throw fail(502,'UPSTREAM_ERROR','실제 도로 경로 서버에 연결하지 못했습니다. 잠시 후 다시 시도해주세요.');
            return normalize(await response.json(),new Date(now()).toISOString());
          })();
          const data=await Promise.race([request,timeout]);
          while(cache.size>=Math.max(1,maxEntries))cache.delete(cache.keys().next().value);
          cache.set(cacheKey,{at:now(),data});return data;
        }catch(e){if(e instanceof RoadError)throw e;throw fail(502,'UPSTREAM_ERROR','실제 경로 데이터를 받지 못했습니다. 네트워크 상태를 확인해주세요.');}
        finally{clearTimeout(timer);}
      });
      queue=operation.catch(()=>{});inFlight.set(cacheKey,operation);
      try{return structuredClone(await operation);}finally{pending--;inFlight.delete(cacheKey);}
    }
  };
}
module.exports={createRoadProvider,RoadError,coordinates,normalize,koreanCue,distance};
