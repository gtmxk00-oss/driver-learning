(function(){
  'use strict';
  // Leaflet takes plain colours, so map marks read the current theme tokens when they are drawn.
  const FALLBACK={'--accent':'#ff6a1a','--ink':'#17181c','--surface':'#fbfaf7','--sim-route':'#ff7a26','--rule-strong':'#bdb7ab'};
  const token=name=>(typeof getComputedStyle==='function'&&getComputedStyle(document.documentElement).getPropertyValue(name).trim())||FALLBACK[name];
  const $=id=>document.getElementById(id),app=window.RMApp,T=RMRoadTracker;
  const esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const sample={origin:{lat:37.5665,lon:126.9780},destination:{lat:37.5796,lon:126.9770}};
  let map=null,tileLayer=null,routeLayers=[],endpointLayers=[],gpsMarker=null,gpsCircle=null,mapStyle='road';
  const mapSources={
    road:{url:'https://tile.openstreetmap.org/{z}/{x}/{y}.png',maxNativeZoom:19,attribution:'&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a> contributors'},
    satellite:{url:'https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2025_3857/default/GoogleMapsCompatible/{z}/{y}/{x}.jpg',maxNativeZoom:14,attribution:'<a href="https://cloudless.eox.at" target="_blank" rel="noopener">EOxCloudless</a> by EOX IT Services GmbH / Contains modified Copernicus Sentinel data 2025 / <a href="https://creativecommons.org/licenses/by-nc-sa/4.0/" target="_blank" rel="noopener">CC BY-NC-SA 4.0</a>'},
    vworld:{url:'/api/maps/vworld/{z}/{x}/{y}',maxNativeZoom:18,attribution:'<a href="https://www.vworld.kr/" target="_blank" rel="noopener">공간정보 오픈플랫폼(브이월드)</a>'}
  };
  let highResConfigured=false,mapConfigLoading=false,mapConfigGeneration=0;
  let routes=[],selected=0,tracker=null,lastFix=null,rejectedFixTimestamp=null,watchId=null,geoGeneration=0,requestGeneration=0,loading=false,follow=false,watchdog=null,navigating=false,pointMode='destination';
  let selectedPlace=null,placeResults=[],placeGeneration=0,placeLoading=false,placeAbort=null,placeMessage='검색 결과를 선택하면 해당 장소까지의 경로를 찾습니다.';
  let nearbyOpen=false,nearbyKind='fuel',nearbyCenterMode='destination',nearbyRadius=3000,nearbyResults=[],nearbyGeneration=0,nearbyLoading=false,nearbyAbort=null,nearbySnapshot=null,nearbyAttribution='',nearbyMessage='위치와 반경을 확인하고 주변 찾기를 눌러주세요.',nearbyConnectionGeneration=0,nearbyConnectionLoading=false;
  const active=()=>watchId!==null;
  const coord=p=>p.lat.toFixed(6)+', '+p.lon.toFixed(6);
  const uiText=(id,value)=>{const node=$(id);if(node)node.textContent=value;};
  function parse(value){const parts=value.trim().split(/[,\s]+/).filter(Boolean).map(Number);if(parts.length!==2||!Number.isFinite(parts[0])||!Number.isFinite(parts[1])||Math.abs(parts[0])>85||Math.abs(parts[1])>180)throw new Error('위도, 경도 순서의 좌표를 입력해주세요. 예: 37.5665, 126.9780');return {lat:parts[0],lon:parts[1]};}
  const near=(a,b)=>Math.abs(a.lat-b.lat)<.000002&&Math.abs(a.lon-b.lon)<.000002;
  function chosenPlace(){try{return selectedPlace&&near(selectedPlace,parse($('realDestination').value))?selectedPlace:null;}catch{return null;}}
  function status(text){$('realStatus').textContent=text;}
  function renderPlaceSearch(){
    uiText('realPlaceStatus',placeMessage);
    const button=$('realPlaceSearch'),input=$('realPlaceQuery'),list=$('realPlaceResults');
    if(button){button.textContent=placeLoading?'검색 중…':'검색';button.disabled=placeLoading||navigating;}
    if(input)input.disabled=navigating;
    if(list)list.innerHTML=placeResults.map((p,i)=>'<li><button type="button" class="real-place-result" data-real-place="'+i+'" '+(navigating?'disabled':'')+'><span class="real-place-result-pin" aria-hidden="true"><svg><use href="#i-pin"/></svg></span><span class="real-place-result-copy"><strong>'+esc(p.name)+'</strong><span>'+esc(p.address||'주소 정보 없음')+'</span><small>'+esc(p.category||'장소')+' · 이 장소로 경로 찾기</small></span><span class="real-place-result-arrow" aria-hidden="true">↗</span></button></li>').join('');
    const address=$('realSelectedAddress'),place=chosenPlace();
    if(address){address.hidden=!place;address.textContent=place?(place.name+' · '+(place.address||'주소 정보 없음')):'';}
  }
  function invalidatePlaceSearch(message=''){
    placeGeneration++;placeAbort?.abort();placeAbort=null;placeLoading=false;placeResults=[];placeMessage=message;renderPlaceSearch();
  }
  async function searchPlaces(){
    if(navigating)return;
    const query=($('realPlaceQuery')?.value||'').trim().replace(/\s+/g,' ');
    invalidatePlaceSearch();
    if(query.length<2||query.length>120){placeMessage='주소나 장소 이름을 2~120자로 입력해 주세요.';renderPlaceSearch();return;}
    if(!['http:','https:'].includes(location.protocol)){$('realServerNotice').hidden=false;placeMessage='시작 파일로 로컬 서버를 실행한 뒤 검색해 주세요.';renderPlaceSearch();return;}
    $('realPlaceQuery')?.blur?.();
    const generation=++placeGeneration;
    placeLoading=true;placeMessage='주소·장소를 찾고 있습니다…';
    placeAbort=typeof AbortController==='function'?new AbortController():null;
    const timeout=AbortSignal.timeout(12000),signal=placeAbort&&typeof AbortSignal.any==='function'?AbortSignal.any([placeAbort.signal,timeout]):timeout;
    renderPlaceSearch();
    try{
      const response=await fetch('/api/places/search',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({query}),signal});
      const data=await response.json();if(generation!==placeGeneration)return;
      if(!response.ok)throw new Error(data.error?.message||'검색 결과를 가져오지 못했습니다.');
      if(data.source!=='real-places'||data.provider!=='photon'||!Array.isArray(data.results))throw new Error('올바른 장소 검색 결과를 받지 못했습니다.');
      placeResults=data.results.slice(0,8).filter(p=>p&&typeof p.name==='string'&&p.name.trim()&&typeof p.lat==='number'&&typeof p.lon==='number'&&Number.isFinite(p.lat)&&Number.isFinite(p.lon)&&Math.abs(p.lat)<=85&&Math.abs(p.lon)<=180).map(p=>({id:String(p.id||'').slice(0,120),name:p.name.trim().slice(0,200),address:typeof p.address==='string'?p.address.slice(0,500):'',category:typeof p.category==='string'?p.category.slice(0,80):'장소',lat:p.lat,lon:p.lon}));
      placeMessage=placeResults.length?'검색 결과 '+placeResults.length+'곳 · 주소를 확인하고 목적지를 선택하세요.':'검색 결과가 없습니다. 도로명 주소나 주변 시설명으로 다시 검색해 주세요.';
    }catch(e){if(generation===placeGeneration){placeResults=[];placeMessage=e.name==='TimeoutError'?'검색 서버 응답이 늦습니다. 잠시 후 다시 검색해 주세요.':e.name==='AbortError'?'검색을 취소했습니다.':e.message;}}
    finally{if(generation===placeGeneration){placeLoading=false;placeAbort=null;renderPlaceSearch();}}
  }
  function useDestination(place){
    if(navigating||!place)return;
    selectedPlace=place;$('realDestination').value=coord(place);invalidatePlaceSearch();
    closeNearby(false);
    clearRoute();drawEndpoints();if($('realTripEditor'))$('realTripEditor').open=false;
    map?.setView([place.lat,place.lon],16);
    if(active()&&!freshFix()){status(place.name+'을 목적지로 선택했습니다. 최신 GPS 위치가 확인되면 경로 탐색을 눌러주세요.');return;}
    fetchRoute(active());
  }
  function selectPlace(index){useDestination(placeResults[index]);}
  const straightDistance=value=>value<1000?Math.round(value)+' m':(value/1000).toFixed(1)+' km';
  const nearbyDistance=(a,b)=>T.distanceMeters([a.lon,a.lat],[b.lon,b.lat]);
  function resetNearby(message='위치와 반경을 확인하고 주변 찾기를 눌러주세요.'){
    nearbyGeneration++;nearbyAbort?.abort();nearbyAbort=null;nearbyLoading=false;nearbyResults=[];nearbySnapshot=null;nearbyAttribution='';nearbyMessage=message;
  }
  function invalidateNearby(message){resetNearby(message);renderNearby();}
  function closeNearby(restoreFocus=true){
    nearbyOpen=false;resetNearby();nearbyConnectionGeneration++;nearbyConnectionLoading=false;renderNearby();
    if(restoreFocus)$(nearbyKind==='fuel'?'realNearbyFuel':'realNearbyParking')?.focus?.();
  }
  function openNearby(kind){
    if(navigating)return;
    nearbyKind=kind;nearbyOpen=true;resetNearby();invalidatePlaceSearch();if($('realTripEditor'))$('realTripEditor').open=false;
    renderNearby();$('realNearbyClose')?.focus?.();
  }
  function nearbyCenter(){
    if(nearbyCenterMode==='current'){
      if(!active()||!freshFix())throw new Error('GPS 위치를 직접 켜고 최신 위치가 확인된 뒤 주변 찾기를 눌러주세요.');
      return {lat:lastFix.lat,lon:lastFix.lon,label:'현재 GPS 위치',sample:false};
    }
    const point=parse($('realDestination').value),place=chosenPlace(),isSample=!place&&near(point,sample.destination);
    return {...point,label:place?.name||(isSample?'경복궁 · 공개 샘플 목적지':'지도에서 지정한 목적지'),sample:isSample};
  }
  function reportMeta(value){
    if(!value||!['reported','stale'].includes(value.status)||typeof value.source!=='string'||!value.source.trim()||typeof value.updatedAt!=='string')return null;
    const stamp=Date.parse(value.updatedAt);if(!Number.isFinite(stamp)||stamp>Date.now()+60000)return null;
    return {stale:value.status==='stale',text:value.source.trim().slice(0,120)+' · '+new Date(stamp).toLocaleString('ko-KR',{month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit'})+' 보고'};
  }
  function nearbyDetails(place){
    if(nearbyKind==='fuel'){
      const price=place.fuelPrice,meta=reportMeta(price),valid=typeof price?.priceWonPerLiter==='number'&&Number.isFinite(price.priceWonPerLiter)&&price.priceWonPerLiter>0;
      if(!meta||!valid)return '<strong class="real-nearby-unknown">유가 연동 대기</strong><small>최신 보고 가격 미제공 · 가격을 추정하지 않습니다</small>';
      const type={gasoline:'휘발유',diesel:'경유',lpg:'LPG',premium:'고급 휘발유'}[price.fuelType]||'유종 확인 필요';
      return '<strong class="'+(meta.stale?'real-nearby-unknown':'real-nearby-value')+'">'+(meta.stale?'과거 보고 가격 · ':'최신 보고 가격 · ')+esc(type)+' '+price.priceWonPerLiter.toLocaleString('ko-KR')+'원/L</strong><small>'+esc(meta.text)+(meta.stale?' · 현재 가격 확인 불가':'')+'</small>';
    }
    const parking=place.parking,meta=reportMeta(parking),capacity=Number.isInteger(parking?.capacity)&&parking.capacity>=0?parking.capacity:null;
    const available=Number.isInteger(parking?.available)&&parking.available>=0&&(capacity===null||parking.available<=capacity)?parking.available:null;
    let result=!meta||available===null?'<strong class="real-nearby-unknown">잔여 면수 미제공</strong><small>주차 가능 여부 확인 불가</small>':'<strong class="'+(meta.stale?'real-nearby-unknown':'real-nearby-value')+'">'+(meta.stale?'과거 보고: ':'')+(available===0?'만차 · 잔여 0면':'잔여 '+available+'면')+(meta.stale?' · 현재 확인 불가':' · 최신 보고')+'</strong><small>'+esc(meta.text)+'</small>';
    if(capacity!==null)result+='<small>지도 등록 규모 '+capacity+'면 · 현재 빈자리와 다릅니다</small>';
    return result;
  }
  function renderNearby(){
    if(nearbySnapshot&&nearbyCenterMode==='current'&&(!active()||!freshFix()||nearbyDistance(nearbySnapshot,lastFix)>150))resetNearby('현재 위치 또는 GPS 상태가 바뀌었습니다. 위치를 확인하고 다시 찾아주세요.');
    if(nearbySnapshot&&nearbyCenterMode==='destination'){
      try{if(!near(nearbySnapshot,parse($('realDestination').value)))resetNearby('목적지가 바뀌었습니다. 다시 주변 찾기를 눌러주세요.');}catch{resetNearby('목적지 위치를 먼저 선택해주세요.');}
    }
    const panel=$('realNearbyPanel');if(panel)panel.hidden=!nearbyOpen;
    for(const [id,kind]of [['realNearbyFuel','fuel'],['realNearbyParking','parking']]){const node=$(id);if(node){node.disabled=navigating;node.setAttribute('aria-expanded',String(nearbyOpen&&nearbyKind===kind));}}
    for(const [id,kind]of [['realNearbyKindFuel','fuel'],['realNearbyKindParking','parking']]){const node=$(id);if(node){node.disabled=navigating;node.setAttribute('aria-pressed',String(nearbyKind===kind));}}
    uiText('realNearbyTitle',nearbyKind==='fuel'?'주변 주유소':'주변 주차장');
    const center=$('realNearbyCenter'),radius=$('realNearbyRadius');if(center){center.value=nearbyCenterMode;center.disabled=navigating;}if(radius){radius.value=String(nearbyRadius);radius.disabled=navigating;}
    let centerReady=false;
    try{const point=nearbyCenter();uiText('realNearbyCenterLabel',point.label+(point.sample?' 주변 · 내 위치가 아닙니다':' 주변'));centerReady=true;}catch(e){uiText('realNearbyCenterLabel',e.message);}
    const gps=$('realNearbyGps');if(gps){gps.hidden=nearbyCenterMode!=='current';gps.textContent=active()?'GPS 위치 끄기':'GPS 위치 켜기';gps.disabled=navigating;}
    const search=$('realNearbySearch');if(search){search.textContent=nearbyLoading?'찾는 중…':nearbyResults.length?'이 위치에서 다시 찾기':'주변 찾기';search.disabled=nearbyLoading||!centerReady||navigating;}
    uiText('realNearbyStatus',nearbyMessage);
    const query=$('realNearbyQueryLabel');if(query){query.hidden=!nearbySnapshot;query.textContent=nearbySnapshot?'조회 기준: '+nearbySnapshot.label+' · 반경 '+nearbyRadius/1000+' km · '+nearbySnapshot.time:'';}
    const list=$('realNearbyResults');if(list)list.innerHTML=nearbyResults.map((p,i)=>'<li><div class="real-nearby-result-heading"><span class="real-nearby-result-symbol" aria-hidden="true">'+(nearbyKind==='fuel'?'<span class="real-fuel-symbol"></span>':'P')+'</span><div><h3>'+esc(p.name)+'</h3><p>'+esc(p.address||'주소 정보 없음')+'</p></div><span class="real-nearby-distance">직선 '+straightDistance(p.distanceMeters)+'</span></div><div class="real-nearby-result-data">'+nearbyDetails(p)+'</div><button type="button" class="real-nearby-route" data-real-nearby="'+i+'" '+(navigating?'disabled':'')+' aria-label="'+esc(p.name)+'으로 길찾기">여기로 길찾기</button></li>').join('');
    uiText('realNearbyAttribution',nearbyAttribution);
    const connection=$('realNearbyConnection');if(connection){connection.disabled=nearbyConnectionLoading;connection.textContent=nearbyConnectionLoading?'확인 중…':'연결 상태 확인';}
  }
  async function searchNearby(){
    if(!nearbyOpen||navigating)return;
    let center;try{center=nearbyCenter();}catch(e){invalidateNearby(e.message);return;}
    resetNearby();
    if(!['http:','https:'].includes(location.protocol)){invalidateNearby('로컬 서버를 실행한 뒤 주변 찾기를 이용해주세요.');return;}
    const generation=++nearbyGeneration,kind=nearbyKind,radiusMeters=nearbyRadius;
    nearbySnapshot={...center,time:new Date(Date.now()).toLocaleTimeString('ko-KR',{hour:'2-digit',minute:'2-digit'})};nearbyLoading=true;nearbyMessage='선택한 위치 주변의 지도 등록 시설을 찾고 있습니다…';
    nearbyAbort=typeof AbortController==='function'?new AbortController():null;
    const timeout=AbortSignal.timeout(12000),signal=nearbyAbort&&typeof AbortSignal.any==='function'?AbortSignal.any([nearbyAbort.signal,timeout]):timeout;renderNearby();
    try{
      const response=await fetch('/api/nearby/search',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({center:{lat:center.lat,lon:center.lon},kind,radiusMeters}),signal});
      const data=await response.json();if(generation!==nearbyGeneration)return;
      if(!response.ok)throw new Error(data.error?.message||'주변 시설을 불러오지 못했습니다.');
      if(data.source!=='real-nearby'||data.provider!=='photon'||data.kind!==kind||data.radiusMeters!==radiusMeters||!data.center||!near(data.center,center)||!Array.isArray(data.results))throw new Error('올바른 주변 시설 응답을 받지 못했습니다.');
      nearbyResults=data.results.slice(0,30).filter(p=>p&&typeof p.name==='string'&&p.name.trim()&&typeof p.lat==='number'&&typeof p.lon==='number'&&Number.isFinite(p.lat)&&Number.isFinite(p.lon)&&Math.abs(p.lat)<=85&&Math.abs(p.lon)<=180).map(p=>({id:String(p.id||'').slice(0,120),name:p.name.trim().slice(0,200),address:typeof p.address==='string'?p.address.slice(0,500):'',category:typeof p.category==='string'?p.category.slice(0,80):kind,lat:p.lat,lon:p.lon,distanceMeters:nearbyDistance(center,p),fuelPrice:p.fuelPrice,parking:p.parking})).filter(p=>Number.isFinite(p.distanceMeters)&&p.distanceMeters<=radiusMeters).sort((a,b)=>a.distanceMeters-b.distanceMeters);
      nearbyMessage=nearbyResults.length?'지도 등록 '+(kind==='fuel'?'주유소':'주차장')+' '+nearbyResults.length+'곳 · 거리순 · 요금과 주차 가능 여부는 별도 정보입니다.':'반경 '+radiusMeters/1000+' km에서 등록된 시설을 찾지 못했습니다. 반경을 넓히거나 위치를 바꿔보세요. 지도에 없는 시설이 있을 수 있습니다.';
      nearbyAttribution=typeof data.attribution==='string'?data.attribution.slice(0,500):'Photon · © OpenStreetMap contributors';
      if(typeof data.notice==='string'&&data.notice)nearbyAttribution+=' · '+data.notice.slice(0,500);
    }catch(e){if(generation===nearbyGeneration){nearbyResults=[];nearbyMessage=e.name==='TimeoutError'?'주변 검색 응답이 늦습니다. 잠시 후 다시 찾아주세요.':e.name==='AbortError'?'주변 검색을 취소했습니다.':e.message;}}
    finally{if(generation===nearbyGeneration){nearbyLoading=false;nearbyAbort=null;renderNearby();}}
  }
  async function checkNearbyConnection(){
    if(!nearbyOpen||nearbyConnectionLoading)return;
    const generation=++nearbyConnectionGeneration;nearbyConnectionLoading=true;renderNearby();
    try{
      const response=await fetch('/api/nearby/status',{signal:AbortSignal.timeout(10000)}),data=await response.json();if(generation!==nearbyConnectionGeneration)return;
      if(!response.ok||data.provider!=='photon')throw new Error('연결 상태를 확인하지 못했습니다. 잠시 후 다시 확인해주세요.');
      uiText('realNearbyConnectionStatus','지도 시설 검색: '+(data.configured?'사용 가능':'미연결')+' · 오피넷 최신 보고 유가: '+(data.priceConfigured===true?'데이터 제공 연결 확인됨':'미연결 · 설정·검증 대기')+' · 서울 주차 잔여 면수: '+(data.parkingConfigured===true?'데이터 제공 연결 확인됨':'미연결 · 설정·검증 대기'));
    }catch(e){if(generation===nearbyConnectionGeneration)uiText('realNearbyConnectionStatus',e.name==='TimeoutError'?'연결 상태 응답이 늦습니다. 다시 확인해주세요.':e.message);}
    finally{if(generation===nearbyConnectionGeneration){nearbyConnectionLoading=false;renderNearby();}}
  }
  function symbol(m){if(m?.type==='arrive')return '⚑';if(m?.type?.includes('roundabout')||m?.type?.includes('rotary'))return '↻';if(m?.modifier?.includes('left'))return '↰';if(m?.modifier?.includes('right'))return '↱';if(m?.modifier==='uturn')return '↶';return '↑';}
  function freshFix(){const age=lastFix?Date.now()-lastFix.timestamp:Infinity;return !!lastFix&&Number.isFinite(lastFix.accuracy)&&lastFix.accuracy>=0&&lastFix.accuracy<=100&&age>=-2000&&age<15000&&lastFix.timestamp!==rejectedFixTimestamp;}
  function currentRoute(){return routes[selected]||null;}
  function renderMapStyle(){
    for(const [id,style]of [['realMapStyleRoad','road'],['realMapStyleSatellite','satellite'],['realMapStyleHighRes','vworld']])$(id)?.setAttribute('aria-pressed',String(mapStyle===style));
    const hd=$('realMapStyleHighRes');if(hd)hd.disabled=!highResConfigured;
    uiText('realMapHighResState',highResConfigured?'키 설정됨':'연결 필요');
    const notice=$('realMapStyleNotice');if(notice){notice.hidden=mapStyle==='road';notice.innerHTML=mapStyle==='vworld'?'브이월드 위성영상<br>실시간 영상 아님':'위성 2025 / 원본 10m<br>실시간 영상 아님 / 확대 시 흐릴 수 있음';}
    const credit=$('realSatelliteCredit');if(credit){credit.hidden=mapStyle==='road';credit.innerHTML=mapSources[mapStyle].attribution;}
    const frame=$('realView');if(frame)frame.dataset.mapStyle=mapStyle;
  }
  function closeMapSettings(){mapConfigGeneration++;mapConfigLoading=false;const panel=$('realMapConfig');if(panel)panel.hidden=true;$('realMapSettings')?.setAttribute('aria-expanded','false');const check=$('realMapConfigCheck');if(check){check.disabled=false;check.textContent='연결 상태 확인';}}
  function openMapSettings(){const panel=$('realMapConfig');if(!panel)return;panel.hidden=false;$('realMapSettings')?.setAttribute('aria-expanded','true');$('realMapConfigClose')?.focus?.();}
  async function checkMapConfiguration(){
    if(mapConfigLoading)return;
    const ticket=++mapConfigGeneration,button=$('realMapConfigCheck');mapConfigLoading=true;if(button){button.disabled=true;button.textContent='확인 중…';}
    uiText('realMapConfigStatus','브이월드 연결 설정을 확인하고 있습니다.');
    try{
      const response=await fetch('/api/maps/status',{signal:AbortSignal.timeout(10000)}),data=await response.json();if(ticket!==mapConfigGeneration)return;
      if(!response.ok||data.provider!=='vworld'||typeof data.configured!=='boolean')throw new Error('지도 연결 상태를 확인하지 못했습니다. 잠시 후 다시 확인하세요.');
      highResConfigured=data.configured;
      uiText('realMapConfigStatus',highResConfigured?'서버에 키가 설정되어 있습니다. 인증 성공 여부는 아직 확인하지 않았습니다. 선명한 위성을 선택하면 실제 영상 조회를 시도합니다.':'브이월드 인증키가 설정되지 않았습니다. 서버에 키를 설정한 뒤 다시 확인하세요. 지금은 일반지도와 기본 위성을 사용할 수 있습니다.');
    }catch(e){if(ticket===mapConfigGeneration){highResConfigured=false;uiText('realMapConfigStatus',e.name==='TimeoutError'?'지도 서버 응답이 늦습니다. 잠시 후 다시 확인하세요.':e.message);}}
    finally{if(ticket===mapConfigGeneration){mapConfigLoading=false;if(button){button.disabled=false;button.textContent='연결 상태 확인';}renderMapStyle();}}
  }
  function setMapStyle(style){
    const source=mapSources[style];if(!map||!source||!source.url||style==='vworld'&&!highResConfigured||tileLayer&&mapStyle===style)return;
    const previous=tileLayer,layer=L.tileLayer(source.url,{maxZoom:19,maxNativeZoom:source.maxNativeZoom,keepBuffer:1,updateWhenIdle:true,attribution:source.attribution});
    const failures=new Set(),key=e=>e?.coords?[e.coords.z,e.coords.x,e.coords.y].join('/'):'unknown';
    mapStyle=style;tileLayer=layer;
    const showTileStatus=()=>{
      if(tileLayer!==layer)return;
      const error=$('realMapError');error.hidden=failures.size===0;
      error.textContent=failures.size?(style==='vworld'?'선명한 위성 영상을 받지 못했습니다. 인증키와 서비스 등록 주소를 확인하세요. 일반지도와 기본 위성으로 전환할 수 있습니다.':style==='satellite'?'위성 영상을 불러오지 못했습니다. 연결을 확인하거나 일반지도로 전환하세요. 경로와 GPS는 유지됩니다.':'지도 타일을 불러오지 못했습니다. 인터넷 연결을 확인해주세요.') : '';
    };
    layer.on('tileerror',e=>{failures.add(key(e));showTileStatus();});
    layer.on('tileload',e=>{failures.delete(key(e));showTileStatus();});
    layer.on('tileunload',e=>{failures.delete(key(e));showTileStatus();});
    showTileStatus();layer.addTo(map);if(previous)map.removeLayer(previous);renderMapStyle();
  }
  function initMap(){
    if(map)return;
    if(!['http:','https:'].includes(location.protocol)){$('realServerNotice').hidden=false;status('실제 지도·GPS는 로컬 서버에서 실행해주세요. 시작 파일을 열면 지도와 경로 기능을 사용할 수 있습니다.');return;}
    if(!window.L){status('지도 표시 라이브러리를 불러오지 못했습니다. 파일을 다시 열어주세요.');return;}
    map=L.map('realRoadMap',{center:[37.572,126.978],zoom:14,zoomControl:true,preferCanvas:true,scrollWheelZoom:true});
    setMapStyle(mapStyle);
    map.on('click',e=>{
      if(navigating){status('길 안내를 종료한 뒤 목적지를 바꿔주세요.');return;}
      const field=pointMode==='origin'?'realOrigin':'realDestination';if(field==='realDestination')selectedPlace=null;invalidatePlaceSearch();$(field).value=coord({lat:e.latlng.lat,lon:e.latlng.lng});if(field==='realDestination')invalidateNearby('목적지가 바뀌었습니다. 다시 주변 찾기를 눌러주세요.');clearRoute();drawEndpoints();status((pointMode==='origin'?'출발지':'목적지')+'를 선택했습니다. 실제 경로 탐색을 눌러주세요.');
    });
    map.on('dragstart',()=>{follow=false;renderButtons();});
    L.control.scale({imperial:false,position:'bottomleft'}).addTo(map);
    drawEndpoints();
  }
  function clearLines(){if(!map)return;for(const layer of routeLayers)map.removeLayer(layer);routeLayers=[];}
  function drawEndpoints(){
    if(!map)return;for(const layer of endpointLayers)map.removeLayer(layer);endpointLayers=[];
    for(const [id,color,label]of[['realOrigin',token('--accent'),'출발지'],['realDestination',token('--ink'),'목적지']]){
      try{const p=parse($(id).value),name=id==='realDestination'?chosenPlace()?.name:null;endpointLayers.push(L.circleMarker([p.lat,p.lon],{radius:8,color:token('--surface'),weight:2,fillColor:color,fillOpacity:1}).addTo(map).bindTooltip(esc(name||label),{permanent:true,direction:'top',className:'real-endpoint-label'}));}catch{}
    }
  }
  function drawRoutes(fit){
    if(!map)return;clearLines();
    routes.forEach((r,i)=>{const line=L.polyline(r.geometry.map(c=>[c[1],c[0]]),{color:i===selected?token('--sim-route'):token('--rule-strong'),weight:i===selected?7:4,opacity:i===selected?.95:.6}).addTo(map);line.on('click',()=>{if(!navigating){selected=i;tracker=null;drawRoutes(false);render();}});routeLayers.push(line);});
    if(routeLayers[selected]){routeLayers[selected].bringToFront();if(fit){const narrow=window.innerWidth<=760,bottom=mapStyle!=='road'?(window.innerHeight<=650?305:325):285;map.fitBounds(routeLayers[selected].getBounds(),{paddingTopLeft:narrow?[30,245]:[365,170],paddingBottomRight:narrow?[110,bottom]:[205,85],maxZoom:17});}}
    drawEndpoints();
  }
  function clearRoute(){
    requestGeneration++;loading=false;routes=[];tracker=null;navigating=false;clearLines();render();
  }
  async function fetchRoute(fromGps=false){
    if(loading)return;
    if(navigating&&!fromGps){status('안내를 종료하거나 현재 위치로 재탐색을 눌러주세요.');return;}
    if(!['http:','https:'].includes(location.protocol)){$('realServerNotice').hidden=false;status('실제 경로 탐색은 로컬 서버가 필요합니다. 소스 묶음의 실제지도-GPS-시작.cmd를 실행해주세요.');return;}
    let origin,destination;
    try{origin=fromGps?(freshFix()?{lat:lastFix.lat,lon:lastFix.lon}:null):parse($('realOrigin').value);destination=parse($('realDestination').value);if(!origin)throw new Error('최신 GPS 위치와 정확도를 먼저 확인해주세요.');}catch(e){status(e.message);return;}
    if(fromGps)$('realOrigin').value=coord(origin);
    const generation=++requestGeneration;loading=true;app.stopSpeech();
    const resumeAfter=fromGps&&navigating;navigating=false;tracker=null;routes=[];clearLines();render();status('실제 도로 연결망에서 경로를 조회하고 있습니다…');
    try{
      const response=await fetch('/api/navigation/route',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({origin,destination}),signal:AbortSignal.timeout(16000)});
      const data=await response.json();if(generation!==requestGeneration)return;
      if(!response.ok)throw new Error(data.error?.message||'실제 경로를 받아오지 못했습니다.');
      if(data.source!=='real-road'||data.provider!=='osrm'||!Array.isArray(data.routes)||!data.routes.length)throw new Error('올바른 도로 경로 응답을 받지 못했습니다.');
      routes=data.routes;selected=0;
      if($('realTripEditor'))$('realTripEditor').open=false;
      drawRoutes(true);render();
      const maxSnap=Math.max(0,...(data.waypoints||[]).map(w=>w.distanceMeters||0));
      status('실제 도로 경로 '+routes.length+'개를 찾았습니다. '+(maxSnap>100?'입력 지점에서 도로까지 약 '+Math.round(maxSnap)+'m 연결됩니다. ':'')+'예상 시간에는 실시간 교통을 반영하지 않았습니다.');
      if(resumeAfter&&freshFix())beginNavigation();
    }catch(e){if(generation===requestGeneration){routes=[];clearLines();status(e.name==='TimeoutError'?'경로 서버 응답이 지연되고 있습니다. 잠시 후 다시 탐색해주세요.':e.message);}}
    finally{if(generation===requestGeneration){loading=false;render();}}
  }
  function drawGps(){
    if(!map||!lastFix)return;
    const point=[lastFix.lat,lastFix.lon];
    if(!gpsMarker){gpsMarker=L.circleMarker(point,{radius:8,color:token('--surface'),weight:3,fillColor:'#156ce0',fillOpacity:1}).addTo(map);gpsCircle=L.circle(point,{radius:lastFix.accuracy,color:'#4181d5',weight:1,fillColor:'#5098f1',fillOpacity:.12}).addTo(map);}
    else{gpsMarker.setLatLng(point);gpsCircle.setLatLng(point).setRadius(lastFix.accuracy);}
    gpsMarker.bringToFront();if(follow)map.panTo(point,{animate:false});
  }
  function trackingUpdate(){
    if(!tracker||!navigating||!lastFix)return;
    T.update(tracker,lastFix,Date.now());
    if(['implausible-jump','invalid-fix','future-fix'].includes(tracker.reason))rejectedFixTimestamp=lastFix.timestamp;
    if(tracker.spoken)app.speak(tracker.spoken);
    if(tracker.status==='arrived'){
      navigating=false;clearGeo(false);status('목적지에 도착했습니다. GPS 위치 추적을 종료했습니다.');
    }else if(tracker.status==='offroute')status('여러 위치에서 경로 이탈이 확인됐습니다. 정확도를 확인한 뒤 현재 위치로 재탐색을 눌러주세요.');
    else if(tracker.status==='poor-accuracy')status('위치 정확도가 낮아 길 안내 진행을 보류합니다. 정확한 GPS 신호를 기다려주세요.');
    else if(tracker.status==='stale')status('새 위치가 들어오지 않아 안내 진행을 보류합니다. GPS 연결을 확인해주세요.');
    render();
  }
  function clearGeo(clearPosition=true){
    geoGeneration++;if(watchId!==null)navigator.geolocation.clearWatch(watchId);watchId=null;clearInterval(watchdog);watchdog=null;
    if(clearPosition){lastFix=null;rejectedFixTimestamp=null;if(map&&gpsMarker)map.removeLayer(gpsMarker);if(map&&gpsCircle)map.removeLayer(gpsCircle);gpsMarker=null;gpsCircle=null;}
    renderButtons();
  }
  function stopGps(message){
    clearGeo(true);navigating=false;if(tracker)T.stop(tracker);app.stopSpeech();status(message||'GPS 위치 추적과 길 안내를 종료했습니다.');render();
  }
  function startGps(){
    if(active()){stopGps();return;}
    if(!map||!['http:','https:'].includes(location.protocol)){$('realServerNotice').hidden=false;status('실제지도-GPS-시작.cmd로 로컬 서버를 실행한 뒤 GPS를 연결해주세요.');return;}
    if(app.isLearningBusy()||window.RMNavigatorUI?.isActive()){status('학습 시연 또는 가상 길 안내를 먼저 종료한 뒤 GPS를 연결해주세요.');return;}
    if(!window.isSecureContext){status('GPS는 HTTPS 또는 localhost에서 사용할 수 있습니다. 시작 파일로 로컬 서버를 실행해주세요.');return;}
    if(!navigator.geolocation){status('이 브라우저에서 위치 기능을 지원하지 않습니다. Chrome 또는 Edge에서 열어주세요.');return;}
    const generation=++geoGeneration;let first=true;
    status('브라우저의 위치 권한 요청을 확인해주세요. 허용 후 위치를 가져옵니다.');
    watchId=navigator.geolocation.watchPosition(pos=>{
      if(generation!==geoGeneration)return;
      const c=pos.coords,fix={lat:c.latitude,lon:c.longitude,accuracy:c.accuracy,timestamp:pos.timestamp};
      if(!Number.isFinite(fix.lat)||!Number.isFinite(fix.lon)||Math.abs(fix.lat)>90||Math.abs(fix.lon)>180||!Number.isFinite(fix.accuracy)||fix.accuracy<0||!Number.isFinite(fix.timestamp)||fix.timestamp>Date.now()+2000||lastFix&&fix.timestamp<=lastFix.timestamp)return;
      lastFix=fix;drawGps();
      if(first){first=false;follow=true;if(!navigating){$('realOrigin').value=coord(fix);clearRoute();drawEndpoints();}map.setView([fix.lat,fix.lon],16);status('현재 위치를 연결했습니다. 목적지를 선택하고 실제 경로를 탐색하세요.');}
      trackingUpdate();render();
    },err=>{
      if(generation!==geoGeneration)return;
      if(err.code===1){stopGps('위치 권한이 허용되지 않았습니다. 브라우저에서 직접 권한을 허용하면 다시 연결할 수 있습니다.');}
      else{status(err.code===2?'위치를 확인하지 못했습니다. 기기의 위치 설정과 GPS 신호를 확인해주세요.':'위치 응답을 기다리고 있습니다. 실내에서는 정확한 위치를 얻기 어려울 수 있습니다.');render();}
    },{enableHighAccuracy:true,maximumAge:1000,timeout:12000});
    watchdog=setInterval(()=>{if(lastFix&&navigating)trackingUpdate();renderButtons();},3000);render();
  }
  function beginNavigation(){
    if(!currentRoute()||!freshFix()||watchId===null){status('GPS 위치를 켜고 최신 위치를 확인한 뒤 안내를 시작해주세요.');return;}
    const route=currentRoute(),first=route.geometry[0],gap=T.distanceMeters([lastFix.lon,lastFix.lat],first);
    if(!Number.isFinite(gap)||gap>Math.max(150,lastFix.accuracy*2)){status('현재 위치가 경로 출발점과 떨어져 있습니다. 현재 위치로 재탐색을 눌러 경로를 다시 찾아주세요.');return;}
    invalidatePlaceSearch();closeNearby(false);tracker=T.create(route);navigating=true;follow=true;app.speak('현재 GPS 위치로 길 안내를 시작합니다.');status('GPS 길 안내 중입니다. 실제 위치가 이동할 때 안내가 갱신됩니다.');trackingUpdate();render();
  }
  function renderButtons(){
    const gpsOn=active();$('realGpsToggle').textContent=gpsOn?'GPS 위치 끄기':'GPS 위치 켜기';
    $('realStart').disabled=loading||!currentRoute()||!freshFix()||!gpsOn||navigating;
    $('realStop').disabled=!gpsOn&&!navigating;$('realReroute').disabled=loading||!currentRoute()||!freshFix()||!gpsOn;
    $('realRouteSearch').disabled=loading||navigating;$('realRouteSearch').setAttribute('aria-busy',String(loading));$('realOrigin').disabled=navigating;$('realDestination').disabled=navigating;$('realSample').disabled=navigating;
    $('realFollow').textContent=follow?'위치 따라가는 중':'현재 위치 따라가기';$('realFollow').disabled=!lastFix;
    $('realFollow').setAttribute('aria-pressed',String(follow));
    if($('realGpsChip'))$('realGpsChip').setAttribute('data-connected',String(gpsOn&&freshFix()));
    $('realGpsStatus').textContent=gpsOn?(lastFix?(freshFix()?'GPS 연결됨':'GPS 신호 대기 · 정확도 확인'):'GPS 권한·위치 확인 중'):'GPS 꺼짐';
    $('realAccuracy').textContent=lastFix?'정확도 ±'+Math.round(lastFix.accuracy)+'m':'정확도 -';
    document.querySelectorAll('[data-real-point]').forEach(b=>b.disabled=navigating);
    renderNearby();
  }
  function render(){
    renderButtons();renderPlaceSearch();const route=currentRoute();
    if($('realView'))$('realView').dataset.hasRoute=String(!!route);
    try{const destination=parse($('realDestination').value),origin=parse($('realOrigin').value);
      const place=chosenPlace();uiText('realDestinationName',place?place.name+'으로 안내':near(destination,sample.destination)?'경복궁으로 안내':'선택한 목적지로 안내');
      const fromCurrent=active()&&lastFix&&freshFix()&&T.distanceMeters([lastFix.lon,lastFix.lat],[origin.lon,origin.lat])<150;
      uiText('realOriginName',fromCurrent?'현재 위치에서 출발':near(origin,sample.origin)?'서울 시청에서 출발':'선택한 출발지에서 출발');
    }catch{uiText('realDestinationName','어디로 갈까요?');uiText('realOriginName','지도로 출발지와 목적지 선택');}
    const remaining=tracker&&navigating?tracker.remainingSeconds:route?.durationSeconds;
    const distance=tracker&&navigating?tracker.remainingMeters:route?.distanceMeters;
    uiText('realEtaValue',Number.isFinite(remaining)?Math.max(1,Math.ceil(remaining/60)):'-');
    uiText('realEtaUnit','분');
    uiText('realArrivalTime',Number.isFinite(remaining)?new Date(Date.now()+remaining*1000).toLocaleTimeString('ko-KR',{hour:'2-digit',minute:'2-digit',hour12:false})+' 도착 예정':'목적지까지, 나에게 맞게');
    uiText('realDistanceValue',Number.isFinite(distance)?(distance/1000).toFixed(1)+'km · '+(navigating?'GPS 길 안내 중':'실제 경로 미리 보기'):'실제 도로 경로를 찾아보세요');
    uiText('realGuidanceMode',tracker?.status==='arrived'?'안내 완료':navigating?'GPS 길 안내 중':route?'경로 미리 보기':'길 안내 준비');
    $('realRouteOptions').innerHTML=routes.map((r,i)=>'<button class="route-option '+(selected===i?'selected':'')+'" data-real-route="'+i+'" '+(navigating?'disabled':'')+' aria-pressed="'+(selected===i)+'"><span class="radio-indicator"></span><span class="route-detail"><strong>실제 경로 '+(i+1)+'</strong><small>'+(r.distanceMeters/1000).toFixed(1)+'km · 교통 미반영</small></span><span class="route-minutes">'+Math.ceil(r.durationSeconds/60)+'<small>분</small></span></button>').join('');
    $('realRouteSummary').textContent=route?(route.distanceMeters/1000).toFixed(1)+'km · 약 '+Math.ceil(route.durationSeconds/60)+'분 · 실시간 교통 미반영':'선택한 경로 없음';
    $('realSteps').innerHTML=route?route.steps.map((s,i)=>'<li class="'+(tracker&&i===tracker.nextStepIndex?'current':'')+'"><span>'+symbol(s.maneuver)+'</span><div>'+esc(s.cue)+'<small>'+esc(s.name||'도로명 없음')+' · '+Math.round(s.distanceMeters)+'m</small></div></li>').join(''):'';
    if(tracker&&navigating||tracker?.status==='arrived'){
      const arrived=tracker.status==='arrived',degraded=['offroute','poor-accuracy','stale'].includes(tracker.status);
      $('realTurnSymbol').textContent=arrived?'✓':degraded?'!':symbol(tracker.nextManeuver);
      $('realTurnDistance').textContent=arrived?'목적지 도착':degraded?'GPS 확인 필요':Math.round(tracker.nextDistanceMeters)+'m';
      $('realTurnCue').textContent=degraded?({offroute:'경로 이탈이 감지됐습니다.', 'poor-accuracy':'위치 오차가 커 안내를 보류합니다.',stale:'최신 위치를 기다립니다.'}[tracker.status]):tracker.nextCue;
      $('realTrackingQuality').textContent=arrived?'GPS 위치 추적을 종료했습니다.':(tracker.remainingMeters/1000).toFixed(1)+'km 남음 · 약 '+Math.ceil(tracker.remainingSeconds/60)+'분 · 교통 미반영';
    }else{
      const next=route?.steps.find(s=>s.maneuver.type!=='depart');$('realTurnSymbol').textContent=next?symbol(next.maneuver):'↱';$('realTurnDistance').textContent=route?Math.round(route.steps[0].distanceMeters)+'m':'목적지 선택';$('realTurnCue').textContent=next?.cue||'경로를 찾고 편하게 출발하세요.';$('realTrackingQuality').textContent=route?'미리 보기 · GPS 연결 후 실제 위치로 안내':'지도를 눌러 목적지를 선택할 수 있어요.';
    }
  }
  $('realRouteForm').addEventListener('submit',e=>{e.preventDefault();fetchRoute();});
  $('realPlaceSearch')?.addEventListener('click',searchPlaces);
  $('realPlaceQuery')?.addEventListener('input',()=>invalidatePlaceSearch('검색 버튼을 눌러 주소·장소를 찾아보세요.'));
  $('realPlaceQuery')?.addEventListener('keydown',e=>{if(e.key==='Enter'){e.preventDefault();e.stopPropagation?.();searchPlaces();}});
  $('realPlaceResults')?.addEventListener('click',e=>{const button=e.target.closest('[data-real-place]');if(button)selectPlace(Number(button.dataset.realPlace));});
  $('realTripEditor')?.addEventListener('toggle',()=>{if(!$('realTripEditor').open)invalidatePlaceSearch();else closeNearby(false);});
  $('realNearbyFuel')?.addEventListener('click',()=>openNearby('fuel'));$('realNearbyParking')?.addEventListener('click',()=>openNearby('parking'));
  $('realNearbyKindFuel')?.addEventListener('click',()=>{nearbyKind='fuel';invalidateNearby();});$('realNearbyKindParking')?.addEventListener('click',()=>{nearbyKind='parking';invalidateNearby();});
  $('realNearbyClose')?.addEventListener('click',()=>closeNearby());
  $('realNearbyPanel')?.addEventListener('keydown',e=>{if(e.key==='Escape'){e.preventDefault();closeNearby();}});
  $('realNearbyCenter')?.addEventListener('change',()=>{nearbyCenterMode=$('realNearbyCenter').value==='current'?'current':'destination';invalidateNearby();});
  $('realNearbyRadius')?.addEventListener('change',()=>{nearbyRadius=$('realNearbyRadius').value==='1000'?1000:3000;invalidateNearby();});
  $('realNearbyGps')?.addEventListener('click',startGps);$('realNearbySearch')?.addEventListener('click',searchNearby);
  $('realNearbyConnection')?.addEventListener('click',checkNearbyConnection);
  $('realNearbyResults')?.addEventListener('click',e=>{const button=e.target.closest('[data-real-nearby]');if(button)useDestination(nearbyResults[Number(button.dataset.realNearby)]);});
  $('realGpsToggle').addEventListener('click',startGps);$('realStart').addEventListener('click',beginNavigation);$('realStop').addEventListener('click',()=>stopGps());$('realReroute').addEventListener('click',()=>fetchRoute(true));
  $('realFollow').addEventListener('click',()=>{if(!lastFix)return;follow=!follow;drawGps();renderButtons();});
  $('realFit').addEventListener('click',()=>{follow=false;drawRoutes(true);renderButtons();});
  $('realMapStyleRoad')?.addEventListener('click',()=>setMapStyle('road'));$('realMapStyleSatellite')?.addEventListener('click',()=>setMapStyle('satellite'));$('realMapStyleHighRes')?.addEventListener('click',()=>setMapStyle('vworld'));
  $('realMapSettings')?.addEventListener('click',openMapSettings);$('realMapConfigClose')?.addEventListener('click',closeMapSettings);$('realMapConfigCheck')?.addEventListener('click',checkMapConfiguration);
  $('realMapConfig')?.addEventListener('keydown',e=>{if(e.key==='Escape'){e.preventDefault();closeMapSettings();$('realMapSettings')?.focus?.();}});
  document.querySelectorAll('[data-real-point]').forEach(b=>b.addEventListener('click',()=>{pointMode=b.dataset.realPoint;document.querySelectorAll('[data-real-point]').forEach(x=>{x.classList.toggle('selected',x===b);x.setAttribute('aria-pressed',String(x===b));});if($('realTripEditor'))$('realTripEditor').open=false;status('지도에서 '+(pointMode==='origin'?'출발지':'목적지')+'를 클릭하세요.');}));
  $('realRouteOptions').addEventListener('click',e=>{const b=e.target.closest('[data-real-route]');if(!b||navigating)return;selected=Number(b.dataset.realRoute);tracker=null;drawRoutes(false);render();});
  for(const id of ['realOrigin','realDestination'])$(id).addEventListener('input',()=>{if(!navigating){if(id==='realDestination'){selectedPlace=null;invalidateNearby('목적지가 바뀌었습니다. 다시 주변 찾기를 눌러주세요.');}invalidatePlaceSearch();clearRoute();drawEndpoints();}});
  for(const id of ['realOrigin','realDestination'])$(id).addEventListener('invalid',()=>{if($('realTripEditor'))$('realTripEditor').open=true;if($('realCoordinateEditor'))$('realCoordinateEditor').open=true;});
  $('realSample').addEventListener('click',()=>{if(navigating)return;selectedPlace=null;invalidatePlaceSearch();if($('realPlaceQuery'))$('realPlaceQuery').value='';$('realOrigin').value=coord(sample.origin);$('realDestination').value=coord(sample.destination);invalidateNearby('공개 샘플 목적지로 변경했습니다. 내 위치가 아닙니다.');clearRoute();drawEndpoints();map?.setView([37.572,126.978],14);status('서울 중심의 공개 샘플 좌표를 넣었습니다. 실제 경로 탐색을 눌러주세요.');});
  document.addEventListener('rm-view-changed',e=>{if(e.detail.view==='real'){initMap();setTimeout(()=>map?.invalidateSize(),0);}else{closeMapSettings();invalidatePlaceSearch();closeNearby(false);if(active())stopGps('다른 화면으로 이동해 GPS 위치 추적을 중지했습니다.');}});
  document.addEventListener('visibilitychange',()=>{if(document.hidden){invalidatePlaceSearch();closeNearby(false);if(active())stopGps('창이 숨겨져 GPS 위치 추적을 중지했습니다.');}});
  window.addEventListener('pagehide',()=>{closeMapSettings();invalidatePlaceSearch();closeNearby(false);clearGeo(true);});
  window.RMRealRoadUI={isActive:active};
  if(app.getCurrentView()==='real')initMap();
  if(!['http:','https:'].includes(location.protocol))$('realServerNotice').hidden=false;
  render();
})();
