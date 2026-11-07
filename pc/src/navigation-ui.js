(function(){
  'use strict';
  const N=RMNavigation,$=id=>document.getElementById(id),app=window.RMApp;
  const esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const symbols={straight:'↑',left:'↰',right:'↱',arrive:'⚑'};
  let driverId=app.getProfileSnapshot().id,session=null,plans=[],preference='time',trafficId='clear',rate=1,timer=null,meta=null,recent=[],followCar=false;
  const active=()=>!!session&&['running','paused'].includes(session.status);
  const key=()=> 'route-memory-navigation-v1-'+driverId;
  const place=id=>N.places.find(p=>p.id===id);
  const path=points=>points.map((p,i)=>(i?'L':'M')+Number(p.x)+' '+Number(p.y)).join(' ');
  function notify(message){$('navPlannerMessage').textContent=message;}
  function save(){try{localStorage.setItem(key(),JSON.stringify({version:1,session,meta,recent,trafficId,preference}));}catch{notify('브라우저 저장 공간이 없어 이번 화면에서만 네비게이션 기록을 유지합니다.');}}
  function load(){
    session=null;meta=null;plans=[];recent=[];
    try{
      const data=JSON.parse(localStorage.getItem(key())||'null');if(!data||data.version!==1)return;
      trafficId=RMTraffic.getScenario(data.trafficId).id;preference=data.preference==='easy'?'easy':'time';
      recent=Array.isArray(data.recent)?data.recent.filter(r=>r&&place(r.originId)&&place(r.destinationId)&&typeof r.id==='string'&&Number.isFinite(Date.parse(r.at))&&['arrived','cancelled'].includes(r.status)).slice(-20).map(r=>({id:r.id.slice(0,80),at:r.at,originId:r.originId,destinationId:r.destinationId,status:r.status,meters:Math.max(0,Math.min(100000,Number(r.meters)||0))})):[];
      const candidate=N.validateSession(data.session,app.getProfileSnapshot());
      if(candidate?.profileId===driverId){session=candidate;if(session.status==='running')N.pause(session);meta={id:typeof data.meta?.id==='string'?data.meta.id:'restored-'+Date.now(),at:Number.isFinite(Date.parse(data.meta?.at))?data.meta.at:new Date().toISOString(),recorded:!!data.meta?.recorded};$('navOrigin').value=place(session.route.originId).name;$('navDestination').value=place(session.route.destinationId).name;preference=session.route.preference;if(session.status==='ready')plans=routesFor(place(session.route.originId),place(session.route.destinationId));}
    }catch{notify('저장된 길 안내를 복원하지 못했습니다. 출발지와 목적지를 다시 선택해주세요.');}
  }
  function resolve(text){
    const q=text.trim(),exact=N.places.find(p=>p.name===q);if(exact)return exact;
    const found=N.searchPlaces(q);return found.length===1?found[0]:null;
  }
  function routesFor(origin,destination){return ['time','easy'].map(pref=>N.plan({originId:origin.id,destinationId:destination.id,preference:pref,profile:app.getProfileSnapshot(),trafficId}));}
  function planRoute(pref){
    if(active())return;
    const origin=resolve($('navOrigin').value),destination=resolve($('navDestination').value);
    if(!origin||!destination){session=null;plans=[];notify('등록된 가상 장소를 선택해주세요. 예: 우리 집, 한빛 오피스, 두빛 공원, 새빛 병원, 중앙역, 두빛 미술관');render();return;}
    try{
      plans=routesFor(origin,destination);
      const score=r=>r.durationSeconds/60+0.4*r.burden;
      preference=pref|| (score(plans[0])<=score(plans[1])?'time':'easy');
      session=N.createSession(plans.find(r=>r.preference===preference),driverId);meta=null;followCar=false;
      $('navOrigin').value=origin.name;$('navDestination').value=destination.name;
      notify(origin.name+' → '+destination.name+' 경로를 찾았습니다. 경로를 고르고 길 안내를 시작하세요.');save();render();
    }catch(e){session=null;plans=[];notify(e.message||'이 장소 사이의 경로를 찾지 못했습니다.');render();}
  }
  function drawRoads(){
    const seen=new Set();
    $('navRoadLayer').innerHTML=N.roads.filter(e=>{const k=[e.from,e.to].sort().join(':');if(seen.has(k))return false;seen.add(k);return true;}).map(e=>'<path d="'+path(e.points)+'" fill="none" style="stroke:var(--sim-road)" stroke-width="19" stroke-linecap="round" stroke-linejoin="round"/><path d="'+path(e.points)+'" fill="none" style="stroke:var(--sim-lane)" stroke-width="1" stroke-dasharray="5 8"/>').join('');
  }
  function drawPois(){
    const o=session?.route.originId,d=session?.route.destinationId;
    $('navPoiLayer').innerHTML=N.places.map(p=>'<g transform="translate('+p.x+' '+p.y+')"><circle r="'+(p.id===o||p.id===d?10:6)+'" style="fill:'+(p.id===d?'var(--sim-route)':p.id===o?'var(--sim-ink)':'var(--sim-lane)')+';stroke:var(--sim-bg)" stroke-width="3"/><text x="'+(p.x>610?-12:12)+'" y="-12" text-anchor="'+(p.x>610?'end':'start')+'" style="fill:'+(p.id===d?'var(--sim-ink)':'var(--sim-ink-2)')+'" font-size="12">'+esc(p.name)+'</text></g>').join('');
  }
  function renderChoices(){
    if(!plans.length){$('navRouteChoices').innerHTML=session?'<div class="nav-empty"><strong>선택한 '+(session.route.preference==='easy'?'편안한 길':'빠른 길')+'</strong><br>'+(session.route.distanceMeters/1000).toFixed(1)+'km · '+Math.ceil(session.route.durationSeconds/60)+'분 가상 예상</div>':'<p class="nav-empty">출발지와 목적지를 선택하고 경로를 찾아보세요.</p>';return;}
    const same=plans[0].edges.map(e=>e.id).join('|')===plans[1].edges.map(e=>e.id).join('|');
    $('navRouteChoices').innerHTML=plans.map(r=>'<button class="route-option '+(preference===r.preference?'selected':'')+'" data-nav-pref="'+r.preference+'" aria-pressed="'+(preference===r.preference)+'" '+(active()?'disabled':'')+'><span class="radio-indicator"></span><span class="route-detail"><strong>'+(r.preference==='time'?'빠른 길':'편안한 길')+'</strong><small>'+(r.distanceMeters/1000).toFixed(1)+'km · 개인 부담 '+r.burden+'</small><small>'+(r.preference==='time'?'예상 시간 우선':'복잡한 구간 회피 우선')+'</small></span><span class="route-minutes">'+Math.ceil(r.durationSeconds/60)+'<small>분 · 가상 예상</small></span></button>').join('')+(same?'<p class="traffic-note">현재 조건에서는 두 기준의 경로가 같습니다.</p>':'');
  }
  function renderControls(){
    const status=session?.status;
    if(status==='running'||status==='paused')$('navControls').innerHTML='<button class="button primary" data-nav-action="pause">'+(status==='running'?'일시정지':'안내 계속')+'</button><button class="button secondary" data-nav-action="skip">다음 안내</button><button class="stop-button" data-nav-action="stop">안내 종료</button><select id="navRate" class="speed-select" aria-label="네비게이션 시연 속도"><option value="1">1배속</option><option value="3">3배속</option><option value="6">6배속</option></select>';
    else $('navControls').innerHTML='<button class="button primary" data-nav-action="start" '+(!session?'disabled':'')+'>'+(status==='arrived'||status==='cancelled'?'같은 경로 다시 안내':'길 안내 시작')+'</button><span class="control-hint">가상 주행 · 실제 길 안내 아님</span>';
    if($('navRate'))$('navRate').value=String(rate);
  }
  function render(){
    const busy=active();$('navOrigin').disabled=busy;$('navDestination').disabled=busy;$('navSwap').disabled=busy;$('navSearch').disabled=busy;
    $('navPlaceChips').querySelectorAll('button').forEach(b=>b.disabled=busy);
    $('navTraffic').value=trafficId;$('navReroute').disabled=!session||['arrived','cancelled'].includes(session.status)||!!session.pendingReroute;
    renderChoices();renderControls();renderLive();renderHistory();document.dispatchEvent(new CustomEvent('rm-nav-state-changed'));
  }
  function renderLive(){
    const r=session?.route,edge=r?.edges[session.edgeIndex],step=r?.steps[session.edgeIndex];
    const labels={ready:'안내 시작 준비',running:'가상 길 안내 중',paused:'길 안내 일시정지',arrived:'목적지 도착',cancelled:'길 안내 종료'};
    $('navStatus').lastChild.textContent=session?labels[session.status]:'경로 탐색 준비';$('navStatus').classList.toggle('running',session?.status==='running');
    const previewEdges=session?.pendingReroute?r.edges.slice(0,session.edgeIndex+1).concat(session.pendingReroute.edgeIds.map(id=>N.roads.find(e=>e.id===id)).filter(Boolean)):null;
    const previewPoints=previewEdges?previewEdges.flatMap((e,i)=>i?e.points.slice(1):e.points):r?.points;
    $('navRouteLine').setAttribute('d',previewPoints?path(previewPoints):'');
    const other=plans.find(p=>p.preference!==preference);$('navAlternativeLayer').innerHTML=other&&!active()?'<path d="'+path(other.points)+'" fill="none" style="stroke:var(--sim-lane)" stroke-width="4" stroke-dasharray="8 8" opacity=".55"/>':'';
    $('navCarMarker').style.display=session?'':'none';
    if(session){const pos=N.position(session);$('navCarMarker').setAttribute('transform','translate('+pos.x+' '+pos.y+')');$('navCarArrow').setAttribute('transform','rotate('+pos.heading+')');$('navMap').setAttribute('viewBox',followCar?(pos.x-230)+' '+(pos.y-170)+' 460 340':'0 0 800 470');}else $('navMap').setAttribute('viewBox','0 0 800 470');
    $('navRecenter').textContent=followCar?'경로 전체 보기':'차량 중심 보기';$('navRecenter').disabled=!session;
    drawPois();
    $('navDestinationLabel').textContent=r?place(r.destinationId).name:'목적지를 선택하세요';
    $('navCurrentRoad').textContent=session?.status==='arrived'?'가상 목적지에 도착했습니다':edge?edge.name:'경로 찾기를 눌러 시작하세요';
    $('navEta').innerHTML=r?Math.ceil(N.remainingSeconds(session)/60)+'<span>분</span>':'-';
    $('navRemaining').textContent=r?(N.remainingMeters(session)/1000).toFixed(1)+'km 남음 · 가상 예상':'경로 미선택';
    $('navProgress').style.width=r?(Math.max(0,Math.min(1,1-N.remainingMeters(session)/r.distanceMeters))*100)+'%':'0%';
    $('navNextDistance').textContent=!session?'목적지를 선택하세요':session.status==='arrived'?'목적지 도착':session.status==='cancelled'?'안내 종료':session.edgeRemainingMeters+'m';
    $('navCue').textContent=!session?'출발지와 목적지를 입력한 뒤 경로를 찾아보세요.':session.status==='arrived'?place(r.destinationId).name+'에 도착했습니다.':session.status==='cancelled'?'길 안내를 종료했습니다.':step?.cue||session.lastMessage;
    $('navManeuver').textContent=session?.status==='arrived'?'✓':symbols[step?.maneuver]||'↑';
    $('navFollowing').textContent=session?.pendingReroute?'다음 교차점에서 새 경로로 연결합니다.':r?.steps[session.edgeIndex+1]?'이어서: '+r.steps[session.edgeIndex+1].cue:'가상 도시 · 실제 도로 안내 아님';
    $('navStepCount').textContent=session?.pendingReroute?'다음 교차점에서 갱신':r?r.steps.length+'단계':'-';
    $('navSteps').innerHTML=r?r.steps.map((s,i)=>session.pendingReroute&&i>session.edgeIndex?'':'<li class="'+(i<session.edgeIndex?'passed':i===session.edgeIndex?'current':'')+'"><span>'+symbols[s.maneuver]+'</span><div><strong>'+esc(s.cue)+'</strong><small>'+esc(s.name)+' · '+s.meters+'m</small></div></li>').join('')+(session.pendingReroute?'<li><span>↻</span><div><strong>이후 안내는 교차점에서 갱신됩니다</strong><small>지도에는 새 예정 경로를 표시합니다.</small></div></li>':''):'';
    $('navReroute').disabled=!session||['arrived','cancelled'].includes(session.status)||!!session.pendingReroute;
    $('navRerouteNote').textContent=session?.pendingReroute?'재탐색 대기 중입니다. 다음 교차점까지 현재 길을 따라 안내합니다.':'안내 중에는 다음 교차점에서 경로를 바꿔 현재 위치가 갑자기 이동하지 않도록 합니다.';
  }
  function record(status){
    if(!session||!meta||meta.recorded)return;
    meta.recorded=true;recent.push({id:meta.id,at:meta.at,originId:session.route.originId,destinationId:session.route.destinationId,status,meters:session.travelledMeters});recent=recent.slice(-20);save();renderHistory();
  }
  function advance(){
    if(session?.status!=='running')return;
    const reroutes=session.rerouteCount;N.advance(session,app.getProfileSnapshot(),50);
    if(session.spoken)app.speak(session.spoken);
    if(session.rerouteCount!==reroutes){plans=[];notify('현재 교차점에서 교통 상황을 반영한 경로로 연결했습니다.');renderChoices();}
    if(session.status==='arrived'){clearTimeout(timer);record('arrived');notify(place(session.route.destinationId).name+'에 도착했습니다. 네비게이션 기록을 저장했어요.');render();}
    save();renderLive();
  }
  function schedule(){clearTimeout(timer);if(session?.status==='running')timer=setTimeout(()=>{advance();schedule();},600/rate);}
  function begin(){
    if(app.isLearningBusy()||window.RMRealRoadUI?.isActive()){app.notify('학습 시연 또는 GPS 위치 추적을 종료한 뒤 가상 길 안내를 시작해주세요.');return;}
    if(!session)return;if(['arrived','cancelled'].includes(session.status)){planRoute(preference);if(!session)return;}
    meta={id:'nav-'+Date.now(),at:new Date().toISOString(),recorded:false};followCar=true;N.start(session);app.speak(place(session.route.destinationId).name+'까지 가상 길 안내를 시작합니다.');save();render();schedule();
  }
  function togglePause(){if(session.status==='running'){N.pause(session);app.stopSpeech();}else N.resume(session);save();render();schedule();}
  function stop(){if(!active())return;N.cancel(session);clearTimeout(timer);app.stopSpeech();record('cancelled');notify('길 안내를 종료했습니다. 경로 중단을 운전 실수로 학습하지 않습니다.');save();render();}
  function skip(){
    if(!active())return;clearTimeout(timer);app.stopSpeech();if(session.status==='paused')N.resume(session);
    const index=session.edgeIndex,reroutes=session.rerouteCount;
    for(let i=0;i<250&&session.status==='running';i++){advance();if(session.spoken||session.edgeIndex!==index||session.rerouteCount!==reroutes)break;}
    if(session.status==='running')N.pause(session);save();render();
  }
  function renderHistory(){
    const host=$('navigationRecentHistory');if(!host)return;
    host.innerHTML='<div class="section-heading"><h2>네비게이션 시연 기록</h2><span>현재 운전자 · 최근 20회</span></div>'+(recent.length?recent.slice().reverse().map(item=>'<div class="history-row"><span class="history-icon">↱</span><div><strong>'+esc(place(item.originId).name)+' → '+esc(place(item.destinationId).name)+'</strong><small>'+esc(new Date(item.at).toLocaleString('ko-KR'))+' · '+(item.meters/1000).toFixed(1)+'km 가상 이동</small></div><span class="history-result">'+(item.status==='arrived'?'도착':'중도 종료')+'</span></div>').join(''):'<div class="nav-empty">길 안내를 완료하면 목적지별 기록이 이곳에 표시됩니다.</div>');
  }
  const historyHost=document.createElement('section');historyHost.id='navigationRecentHistory';historyHost.className='nav-history';$('historyView').appendChild(historyHost);
  $('navPlaces').innerHTML=N.places.map(p=>'<option value="'+esc(p.name)+'"></option>').join('');
  $('navPlaceChips').innerHTML=N.places.filter(p=>p.id!=='office').map(p=>'<button type="button" data-nav-place="'+p.id+'">'+esc(p.name)+'</button>').join('');
  $('navigationForm').addEventListener('submit',e=>{e.preventDefault();planRoute();});
  $('navSwap').addEventListener('click',()=>{if(active())return;const value=$('navOrigin').value;$('navOrigin').value=$('navDestination').value;$('navDestination').value=value;planRoute();});
  $('navPlaceChips').addEventListener('click',e=>{const b=e.target.closest('[data-nav-place]');if(!b||active())return;$('navDestination').value=place(b.dataset.navPlace).name;planRoute();});
  $('navRouteChoices').addEventListener('click',e=>{const b=e.target.closest('[data-nav-pref]');if(b&&!active())planRoute(b.dataset.navPref);});
  for(const id of ['navOrigin','navDestination'])$(id).addEventListener('input',()=>{if(active())return;session=null;plans=[];meta=null;notify('장소를 선택한 뒤 경로 찾기를 눌러주세요.');render();});
  $('navControls').addEventListener('click',e=>{const b=e.target.closest('[data-nav-action]');if(b)({start:begin,pause:togglePause,stop,skip}[b.dataset.navAction])?.();});
  $('navControls').addEventListener('change',e=>{if(e.target.id==='navRate'){rate=Number(e.target.value);schedule();}});
  $('navTraffic').addEventListener('change',e=>{trafficId=RMTraffic.getScenario(e.target.value).id;if(!active())planRoute(preference);else{notify('교통 시연 조건을 바꿨습니다. 교통 반영 재탐색을 누르면 다음 교차점에서 경로를 갱신합니다.');save();}});
  $('navReroute').addEventListener('click',()=>{
    if(!session)return;if(!active()){planRoute(preference);return;}
    try{app.stopSpeech();N.queueReroute(session,app.getProfileSnapshot(),{trafficId});if(session.spoken)app.speak(session.spoken);notify('다음 교차점에서 교통 상황을 반영해 경로를 재탐색합니다.');save();renderLive();}catch(e){notify(e.message||'재탐색할 경로를 찾지 못했습니다.');}
  });
  $('navRecenter').addEventListener('click',()=>{if(!session)return;followCar=!followCar;renderLive();notify(followCar?'차량 위치를 중심으로 지도를 표시합니다.':'전체 경로를 표시합니다.');});
  document.addEventListener('rm-context-updated',()=>{
    const next=app.getProfileSnapshot().id;
    if(next!==driverId){clearTimeout(timer);driverId=next;load();render();}
    else if(session?.status==='ready')planRoute(preference);
  });
  document.addEventListener('visibilitychange',()=>{if(document.hidden&&session?.status==='running'){N.pause(session);app.stopSpeech();clearTimeout(timer);save();render();}});
  window.addEventListener('pagehide',()=>{save();app.stopSpeech();});
  window.RMNavigatorUI={isActive:active,renderHistory,getRecords:()=>recent.map(r=>({...r})),clearCurrentDriver(){clearTimeout(timer);session=null;plans=[];meta=null;recent=[];save();render();}};
  drawRoads();load();render();
})();
