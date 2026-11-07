(function(){
  'use strict';
  const $=id=>document.getElementById(id), KEY='route-memory-pc-v1';
  const names={me:'나의 운전',guest:'새 운전자'};
  const paths={fast:'M366 393V244Q366 211 404 211H552Q594 211 594 168V75',easy:'M366 393H212V158H594V75',recovery:'M366 281H212V158H594V75'};
  const icon=name=>'<svg aria-hidden="true"><use href="#i-'+name+'"/></svg>';
  const escape=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  let profiles={me:RM.createProfile('me',names.me),guest:RM.createProfile('guest',names.guest)};
  let active='me',routeId='fast',trip=null,tripMeta=null,history=[],paused=false,speed=1,timer=null,currentView='drive',sound=false,toastTimer;
  let storageOK=true,restoreNotice='';
  let trafficId='clear',liveReady=false,liveLoading=false,liveStatusGeneration=0;
  const traffic=()=>RMTraffic.getScenario(trafficId);
  const profile=()=>profiles[active];
  const running=()=>trip&&['running','rerouting'].includes(trip.status);
  const needsReview=()=>trip&&trip.status==='finished'&&trip.pending.length>0&&!trip.reviewed;
  const lead=()=>RM.leadMeters(profile(),RM.routes.fast.segments[0]);
  function save(){
    try{localStorage.setItem(KEY,JSON.stringify({version:1,active,routeId,profiles,trip,tripMeta,history,trafficId}));}
    catch(e){storageOK=false;$('storageNotice').hidden=false;$('storageNotice').textContent='브라우저 저장 공간을 사용할 수 없어 이번 화면에서만 기록이 유지됩니다.';}
  }
  function restore(){
    try{
      const raw=localStorage.getItem(KEY);if(!raw)return;
      const data=JSON.parse(raw);if(!data||data.version!==1)throw new Error('schema');
      for(const id of ['me','guest'])profiles[id]=RM.validateProfile(data.profiles?.[id],id,names[id]);
      active=data.active==='guest'?'guest':'me';routeId=data.routeId==='easy'?'easy':'fast';
      trafficId=RMTraffic.getScenario(data.trafficId).id;
      history=Array.isArray(data.history)?data.history.filter(h=>h&&['me','guest'].includes(h.profileId)&&['fast','easy'].includes(h.routeId)&&typeof h.id==='string'&&Number.isFinite(Date.parse(h.at))).slice(-50).map(h=>({id:h.id.slice(0,80),profileId:h.profileId,routeId:h.routeId,at:h.at,status:['confirmed','intentional','complete','cancelled','pending'].includes(h.status)?h.status:'complete',lead:Number.isFinite(h.lead)?Math.max(300,Math.min(900,h.lead)):300,events:Array.isArray(h.events)?h.events.filter(e=>e&&typeof e.text==='string').slice(0,30).map(e=>({text:e.text.slice(0,200),distance:Math.max(0,Math.min(5000,Number(e.distance)||0))})):[]})):[];
      trip=RM.validateTrip(data.trip,profile());
      if(trip){
        const rawMeta=data.tripMeta;
        tripMeta={id:typeof rawMeta?.id==='string'?rawMeta.id.slice(0,80):'restored-'+Date.now(),at:typeof rawMeta?.at==='string'&&Number.isFinite(Date.parse(rawMeta.at))?rawMeta.at:new Date().toISOString(),lead:lead(),events:[]};
        if(Array.isArray(rawMeta?.events))tripMeta.events=rawMeta.events.filter(e=>e&&typeof e.text==='string').slice(0,30).map(e=>({text:e.text.slice(0,200),distance:Math.max(0,Math.min(5000,Number(e.distance)||0))}));
        if(running()){paused=true;restoreNotice='이전 시연을 일시정지 상태로 불러왔습니다. 계속하거나 종료할 수 있어요.';}
      }
    }catch(e){restoreNotice='저장된 기록을 읽지 못해 새 시연으로 시작합니다.';}
  }
  function toast(message){clearTimeout(toastTimer);$('toast').textContent=message;$('toast').classList.add('show');toastTimer=setTimeout(()=>$('toast').classList.remove('show'),4300);}
  function say(text){
    if(!sound||!('speechSynthesis'in window)||!text)return;
    const utterance=new SpeechSynthesisUtterance(text);utterance.lang='ko-KR';utterance.rate=1;
    const korean=speechSynthesis.getVoices().find(v=>v.lang.startsWith('ko'));if(korean)utterance.voice=korean;
    utterance.onerror=e=>{if(!['interrupted','canceled'].includes(e.error))toast('음성을 재생하지 못했습니다. 화면 안내로 계속할 수 있어요.');};
    speechSynthesis.cancel();speechSynthesis.speak(utterance);
  }
  function stopSpeech(){if('speechSynthesis'in window)speechSynthesis.cancel();}
  function schedule(){clearTimeout(timer);if(running()&&!paused)timer=setTimeout(()=>{tick();schedule();},600/speed);}
  function tick(){
    if(!running())return;
    const old=trip.status;
    RM.tick(trip,profile(),50);
    if(trip.speech){tripMeta.events.push({text:trip.speech,distance:trip.remaining});say(trip.speech);}
    if(trip.status==='finished'&&old!=='finished'){
      history.push({id:tripMeta.id,at:tripMeta.at,profileId:active,routeId:trip.routeId,status:needsReview()?'pending':'complete',lead:tripMeta.lead,events:tripMeta.events.slice(0,30)});history=history.slice(-50);
      paused=false;renderStatic();renderControls();
      if(needsReview())showReview();else toast('시연이 끝났습니다. 나의 주행 기록에서 안내 내용을 확인하세요.');
    }
    save();renderLive();
  }
  function start(inject){
    if(running()||needsReview())return;
    if(window.RMNavigatorUI?.isActive()){toast('네비게이션 안내를 종료한 뒤 학습 시연을 시작해주세요.');return;}
    if(window.RMRealRoadUI?.isActive()){toast('GPS 위치 추적을 종료한 뒤 학습 시연을 시작해주세요.');return;}
    if(inject)routeId='fast';
    trip=RM.createTrip(profile(),RM.routes[routeId],inject);
    tripMeta={id:'trip-'+Date.now()+'-'+Math.random().toString(36).slice(2,7),at:new Date().toISOString(),lead:lead(),events:[]};
    tripMeta.events.push({text:'출발 시 교통 시연: '+traffic().label+' · 예상 '+RMTraffic.estimateMinutes(RM.routes[routeId],traffic())+'분',distance:trip.remaining});
    paused=false;stopSpeech();save();renderStatic();renderControls();renderLive();schedule();
  }
  function setPause(value){paused=value;stopSpeech();schedule();renderControls();renderLive();save();}
  function skip(){
    if(!running())return;
    paused=true;clearTimeout(timer);stopSpeech();
    const status=trip.status,index=trip.index,activeRoute=trip.activeRouteId;
    for(let i=0;i<200&&running();i++){
      tick();if(trip.speech||trip.status!==status||trip.index!==index||trip.activeRouteId!==activeRoute)break;
    }
    renderControls();renderLive();
  }
  function stop(){
    if(!running())return;clearTimeout(timer);stopSpeech();RM.cancel(trip);
    history.push({id:tripMeta.id,at:tripMeta.at,profileId:active,routeId:trip.routeId,status:'cancelled',lead:tripMeta.lead,events:tripMeta.events.slice(0,30)});history=history.slice(-50);
    paused=false;save();renderStatic();renderControls();renderLive();toast('시연을 종료했습니다. 완료하지 않은 주행은 학습하지 않습니다.');
  }
  function showReview(){
    $('reviewBefore').textContent=tripMeta.lead+'m';$('reviewAfter').textContent=Math.min(900,lead()+150)+'m';
    if(!$('reviewDialog').open)$('reviewDialog').showModal();
  }
  function review(wasMistake){
    if(!RM.review(trip,profile(),wasMistake))return;
    const item=history.find(h=>h.id===tripMeta.id);if(item)item.status=wasMistake?'confirmed':'intentional';
    save();$('reviewDialog').close();renderStatic();renderControls();renderLive();
    toast(wasMistake?'기억했습니다. 다음 출구 안내는 '+lead()+'m 전입니다.':'의도한 경로 변경은 실수로 학습하지 않았습니다.');
  }
  function renderStatic(){
    const p=profile(),distance=lead(),busy=!!(running()||needsReview()||window.RMNavigatorUI?.isActive());
    $('driverSelect').value=active;$('driverSelect').disabled=busy;
    document.querySelector('.avatar').textContent=active==='me'?'나':'새';
    $('leadValue').textContent=distance;$('leadBar').style.width=((distance-300)/600*100)+'%';
    const misses=RM.misses(p,'exit-a');$('missCount').textContent=misses+'회';$('learningBadge').textContent=misses?'나에게 맞춤':'학습 전';
    $('personalExplanation').innerHTML=misses?'확인한 출구 놓침 '+misses+'회를 기억했어요.<br>다음 한빛 분기점은 '+distance+'m 전부터 안내합니다.':'첫 주행에서는 기본 시점으로 안내합니다.<br>확인한 실수만 다음 주행에 반영해요.';
    const recommendation=RMTraffic.recommend(p,traffic(),RM).id;
    $('routeOptions').innerHTML=['fast','easy'].map(id=>{
      const r=RM.routes[id],complex=r.segments.filter(s=>s.kind!=='simple').length,selected=routeId===id;
      const road=RMTraffic.roadStatus(id,traffic());
      return '<button class="route-option '+(selected?'selected':'')+'" data-route="'+id+'" aria-pressed="'+selected+'" '+(busy?'disabled':'')+'><span class="radio-indicator"></span><span class="route-detail"><strong>'+r.name+'</strong>'+(recommendation===id?'<span class="recommended">추천</span>':'')+'<small>복잡한 구간 '+complex+'곳 · 개인 부담 '+RM.burden(p,r)+'</small><small class="traffic-state '+road.status+'">'+road.label+' · 지연 +'+road.delayMinutes+'분</small></span><span class="route-minutes">'+RMTraffic.estimateMinutes(r,traffic())+'<small>분 · 교통 반영</small></span></button>';
    }).join('');
    const route=RM.routes[routeId];$('briefCount').textContent='미리 볼 구간 '+route.segments.length+'곳';
    $('briefList').innerHTML=route.segments.map((s,i)=>'<article class="brief-item"><span class="brief-number">0'+(i+1)+' / '+({exit:'분기',sequence:'연속 갈림길',merge:'합류',simple:'교차로'}[s.kind])+'</span><strong>'+escape(s.name.replace('가상 ',''))+'</strong><p>'+escape(s.cue)+'</p><span class="lead-caption">'+RM.leadMeters(p,s)+'m 전 안내</span></article>').join('');
    $('memoOpen').disabled=busy;$('memoSummary').textContent=p.avoid.includes('merge-c')?'새빛 합류 · 회피 선호 반영 중':p.notes['merge-c']?'저장한 메모가 있어요':'다음 경로 선택에 반영해요';
    renderTraffic();
    if(currentView==='history')renderHistory();
    document.dispatchEvent(new CustomEvent('rm-context-updated'));
  }
  function renderControls(){
    if(running()){
      $('driveControls').innerHTML='<button class="button primary" data-action="pause">'+icon(paused?'play':'pause')+(paused?'시연 계속':'일시정지')+'</button><button class="button secondary" data-action="skip">'+icon('next')+'다음 상황</button><button class="stop-button" data-action="stop">시연 종료</button><select id="speedSelect" class="speed-select" aria-label="시연 속도"><option value="1">1배속</option><option value="3">3배속</option><option value="6">6배속</option></select>';
      $('speedSelect').value=String(speed);
    }else{
      $('driveControls').innerHTML='<button class="button primary" data-action="start" '+(needsReview()?'disabled':'')+'>'+icon('play')+(trip?.status==='finished'?'같은 길 다시 시연':'주행 시연 시작')+'</button><button class="button secondary" data-action="miss" '+(needsReview()?'disabled':'')+'>'+icon('route')+'출구 놓침 시연</button><span class="control-hint">설치 없이, 바로 경험해보세요</span>';
    }
  }
  function renderLive(){
    const activeRoute=RM.routes[trip?.activeRouteId||routeId],isRunning=running();
    const routePath=paths[activeRoute.id];$('routeLine').setAttribute('d',routePath);$('routeGlow').setAttribute('d',routePath);
    const color={clear:'var(--sim-good)',slow:'var(--sim-warn)',jam:'var(--danger)'};
    const road=RMTraffic.roadStatus(activeRoute.id,traffic());
    $('routeLine').style.stroke=color[road.status];$('routeGlow').style.stroke=color[road.status];
    $('alternativeLine').style.stroke=color[RMTraffic.roadStatus(activeRoute.id==='fast'?'easy':'fast',traffic()).status];
    $('alternativeLine').setAttribute('d',activeRoute.id==='fast'?paths.easy:paths.fast);
    const total=activeRoute.segments.reduce((n,s)=>n+s.meters,0),remaining=trip?RM.metersLeft(trip):total;
    const progress=trip?Math.max(0,Math.min(1,1-remaining/total)):0;
    const curve=$('routeLine');const point=curve.getPointAtLength(curve.getTotalLength()*progress);
    $('carMarker').setAttribute('transform','translate('+point.x+' '+point.y+')');$('progressBar').style.width=(progress*100)+'%';
    const labels={running:paused?'시연 일시정지':'가상 주행 중',rerouting:paused?'대체 경로 연결 · 일시정지':'대체 경로 연결 중',finished:'주행 시연 완료',cancelled:'시연 종료'};
    $('driveStatus').classList.toggle('running',!!isRunning&&!paused);
    $('driveStatus').lastChild.textContent=trip?labels[trip.status]:'출발 전 준비';
    const seconds=Math.floor((trip?.tickCount||0)*.6);$('simTime').textContent=String(Math.floor(seconds/60)).padStart(2,'0')+':'+String(seconds%60).padStart(2,'0');
    $('guidanceDistance').textContent=!trip?'출발 준비':trip.status==='rerouting'?'경로 재탐색':trip.status==='finished'?'도착했습니다':trip.status==='cancelled'?'시연 종료':trip.remaining+'m';
    $('guidanceText').textContent=!trip?'선택한 경로를 미리 살펴보세요.':trip.message;
    $('guidanceReason').textContent=!trip?'어려운 구간에 앞서, 나에게 맞는 타이밍으로.':trip.status==='running'?(trip.announced?'나의 안내 시점 '+RM.leadMeters(profile(),RM.segment(trip))+'m · 개인화 반영':'구간에 진입하기 전 짧게 알려드릴게요.'):trip.status==='rerouting'?'당황하지 않아도 괜찮아요. 가상 대체 경로를 연결합니다.':trip.status==='finished'?'정차 후 확인한 기록만 다음 주행에 반영합니다.':'이 주행은 실수로 학습하지 않습니다.';
    $('turnSymbol').textContent=trip?.status==='rerouting'?'↻':trip?.status==='finished'?'✓':'↱';
    $('activeRoad').textContent=trip?RM.segment(trip).name:activeRoute.id==='fast'?'가상 한빛 분기점 경유':'가상 중앙대로 경유';
    const minutes=trip?RMTraffic.remainingMinutes(trip,traffic()):RMTraffic.estimateMinutes(activeRoute,traffic());
    $('etaText').innerHTML=minutes+'<span>분</span>';$('remainingText').textContent=(remaining/1000).toFixed(1)+' km · '+road.label+' · 교통 시연';
    if(isRunning&&road.status!=='clear')$('guidanceReason').textContent+=' '+road.label+' · 예상 시간에 지연 반영 중.';
  }
  function renderTraffic(){
    const s=traffic(),p=profile();$('trafficScenario').value=s.id;
    $('trafficOverview').innerHTML=['fast','easy'].map(id=>{const r=RM.routes[id],road=RMTraffic.roadStatus(id,s);return '<div class="traffic-route"><span class="traffic-light '+road.status+'"></span><span><strong>'+r.name+'</strong><small>'+road.label+' · 시연 속도 '+road.speedKph+'km/h</small></span><b>'+RMTraffic.estimateMinutes(r,s)+'<small>분</small></b><em>+'+road.delayMinutes+'분</em></div>';}).join('');
    $('trafficExplanation').textContent=RMTraffic.explain(p,RM.routes[routeId],s,RM);
    $('applyTrafficRoute').disabled=!!(running()||needsReview())||routeId===RMTraffic.recommend(p,s,RM).id;
    $('trafficScenario').disabled=!!needsReview();
    $('trafficNote').textContent=running()?'시연 중 교통 변경은 예상 시간과 다음 경로 추천에 반영합니다. 현재 주행 경로를 자동으로 바꾸지 않습니다.':'교통 지연과 개인 부담을 함께 반영합니다. 정체 자체는 운전자의 실수로 학습하지 않아요.';
  }
  function renderHistory(){
    const p=profile(),items=history.filter(h=>h.profileId===active).slice().reverse();
    $('historySummary').innerHTML=[['완료한 학습 시연',p.completedTrips+'회'],['확인한 출구 놓침',RM.misses(p,'exit-a')+'회'],['다음 출구 안내',lead()+'m 전']].map(([label,value])=>'<div class="history-stat"><small>'+label+'</small><strong>'+value+'</strong></div>').join('');
    const outcomes={pending:'확인 대기',confirmed:'출구 놓침 학습',intentional:'의도한 변경 · 학습 제외',complete:'정상 도착',cancelled:'중도 종료 · 학습 제외'};
    $('historyList').innerHTML=items.length?items.map(h=>'<article><div class="history-row"><span class="history-icon">'+icon(h.status==='confirmed'?'route':'check')+'</span><div><strong>'+RM.routes[h.routeId].name+' 주행 시연</strong><small>'+escape(new Date(h.at).toLocaleString('ko-KR'))+' · 출구 안내 기준 '+h.lead+'m</small></div><div class="history-result">'+outcomes[h.status]+'</div></div>'+(h.events.length?'<details class="history-events"><summary>안내 내용 '+h.events.length+'개 보기</summary>'+h.events.map(e=>'<p><span>'+e.distance+'m</span> '+escape(e.text)+'</p>').join('')+'</details>':'')+'</article>').join(''):'<div class="empty-state">'+icon('history')+'<h3>다음 주행을 위한 첫 기억</h3><p>시연을 완료하면 이곳에 주행과 안내 기록이 쌓입니다.</p><button class="button secondary" data-go-drive>첫 주행 시연하기</button></div>';
    $('exportButton').disabled=!items.length&&!window.RMNavigatorUI?.getRecords().length;$('resetOpen').disabled=!!(running()||needsReview()||window.RMNavigatorUI?.isActive());
    window.RMNavigatorUI?.renderHistory();
  }
  function setView(view){
    if(!['real','coach','navigation','drive','history','guide'].includes(view))return;
    currentView=view;
    document.body?.classList.toggle('map-mode',view==='real');
    for(const id of ['real','coach','navigation','drive','history','guide'])$(id+'View').hidden=view!==id;
    document.querySelectorAll('[data-view]').forEach(b=>b.classList.toggle('active',b.dataset.view===view));
    const titles={navigation:['길 안내 시연','길 안내 시연','가상 도시에서 출발지와 목적지를 고르고 안내를 시작합니다.'],drive:['나의 학습','나의 학습','어려웠던 순간을 기억해 다음 안내를 바꿉니다. 교통은 가상 시나리오입니다.'],history:['주행 기록','주행 기록','확인한 실수와 어려웠던 구간을 모아 봅니다.'],guide:['사용 방법','사용 방법','실수를 겪고, 확인하고, 달라진 안내를 비교합니다.']};
    titles.coach=['동반앱 시연','동반앱 시연','운전 유형별 판단을 가상 신호로 보여 줍니다.'];titles.real=['실제 지도','실제 지도','목적지를 고르고 GPS 위치를 연결합니다.'];
    $('breadcrumb').textContent=titles[view][0];$('pageTitle').textContent=titles[view][1];$('pageSubtitle').textContent=titles[view][2];
    document.querySelector('.demo-label').innerHTML=view==='real'?'실제 도로 지도<small>GPS는 직접 연결</small>':'가상 주행 모드<small>실제 위치 미사용</small>';
    if(view==='history')renderHistory();
    document.dispatchEvent(new CustomEvent('rm-view-changed',{detail:{view}}));
  }
  $('driveControls').addEventListener('click',e=>{const b=e.target.closest('[data-action]');if(!b)return;({start:()=>start(false),miss:()=>start(true),pause:()=>setPause(!paused),skip,stop}[b.dataset.action])?.();});
  $('driveControls').addEventListener('change',e=>{if(e.target.id==='speedSelect'){speed=Number(e.target.value);schedule();}});
  $('routeOptions').addEventListener('click',e=>{const b=e.target.closest('[data-route]');if(!b||running()||needsReview())return;routeId=b.dataset.route;trip=null;tripMeta=null;save();renderStatic();renderControls();renderLive();});
  $('trafficScenario').addEventListener('change',e=>{
    if(needsReview())return;trafficId=RMTraffic.getScenario(e.target.value).id;
    const msg='교통 시연 변경: '+traffic().label+' · '+RM.routes[routeId].name+' 예상 '+RMTraffic.estimateMinutes(RM.routes[routeId],traffic())+'분';
    if(running()){tripMeta.events.push({text:msg,distance:trip.remaining});say(msg);}
    save();renderStatic();renderLive();toast(msg);
  });
  $('applyTrafficRoute').addEventListener('click',()=>{if(running()||needsReview())return;routeId=RMTraffic.recommend(profile(),traffic(),RM).id;trip=null;tripMeta=null;save();renderStatic();renderControls();renderLive();toast(RM.routes[routeId].name+'을 선택했습니다.');});
  $('liveTrafficOpen').addEventListener('click',async()=>{
    if(running())setPause(true);
    $('liveTrafficDialog').showModal();liveReady=false;$('liveTrafficFetch').disabled=true;
    const generation=++liveStatusGeneration;
    if(!['http:','https:'].includes(location.protocol)){
      $('liveTrafficStatus').textContent='파일 실행 모드 · 교통 시연은 사용 가능합니다. 실제 조회는 API 키를 설정한 로컬 서버에서 사용할 수 있어요.';return;
    }
    $('liveTrafficStatus').textContent='로컬 서버의 API 연결 설정을 확인하고 있습니다…';
    try{
      const response=await fetch('/api/traffic/status',{cache:'no-store',signal:AbortSignal.timeout(5000)});
      if(!response.ok)throw new Error('status');const data=await response.json();if(generation!==liveStatusGeneration)return;
      liveReady=data.configured===true&&data.provider==='kakao';
      $('liveTrafficStatus').textContent=liveReady?'API 키 설정됨 · 조회 버튼을 누를 때만 실제 교통정보를 요청합니다.':'API 키 미설정 · 현재는 교통 시연 모드입니다. 키를 설정한 뒤 서버를 다시 실행하면 실제 조회를 사용할 수 있어요.';
      $('liveTrafficFetch').disabled=!liveReady;
    }catch(e){if(generation===liveStatusGeneration)$('liveTrafficStatus').textContent='실시간 조회 서버에 연결되지 않았습니다. 교통 시연은 그대로 사용할 수 있어요. 소스의 연결 안내를 확인해주세요.';}
  });
  $('liveTrafficForm').addEventListener('submit',async e=>{
    e.preventDefault();if(!liveReady||liveLoading)return;
    const origin={x:Number($('originX').value),y:Number($('originY').value)},destination={x:Number($('destinationX').value),y:Number($('destinationY').value)};
    if([origin,destination].some(p=>!Number.isFinite(p.x)||!Number.isFinite(p.y)||Math.abs(p.x)>180||Math.abs(p.y)>90))return;
    liveLoading=true;$('liveTrafficFetch').disabled=true;$('liveTrafficResults').textContent='교통정보를 조회하고 있습니다…';
    try{
      const response=await fetch('/api/traffic/routes',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({origin,destination}),signal:AbortSignal.timeout(12000)});
      const data=await response.json();
      if(!response.ok)throw new Error(data.error?.message||data.message||'교통정보 조회에 실패했습니다.');
      if(data.source!=='live'||data.provider!=='kakao'||!Array.isArray(data.routes)||!Number.isFinite(Date.parse(data.fetchedAt)))throw new Error('올바른 교통정보 응답을 받지 못했습니다.');
      const routes=data.routes.filter(r=>r&&Number.isFinite(r.durationSeconds)&&r.durationSeconds>0&&Number.isFinite(r.distanceMeters)&&r.distanceMeters>=0).slice(0,5);
      if(!routes.length)throw new Error('조회 가능한 경로가 없습니다.');
      $('liveTrafficResults').innerHTML='<div class="live-result-header"><strong>실제 경로 조회 결과</strong><small>카카오모빌리티 · 조회 '+escape(new Date(data.fetchedAt).toLocaleString('ko-KR'))+'</small></div>'+routes.map((r,i)=>'<article class="live-route-result"><h3>경로 후보 '+(i+1)+' <span>'+Math.ceil(r.durationSeconds/60)+'분 · '+(r.distanceMeters/1000).toFixed(1)+'km</span></h3><details><summary>도로별 교통 속도</summary>'+((Array.isArray(r.roads)?r.roads:[]).slice(0,30).map(road=>'<p><span>'+escape(road.name||'이름 없는 도로')+'</span><b>'+(typeof road.speedKph==='number'&&Number.isFinite(road.speedKph)?road.speedKph+' km/h':'정보 없음')+'</b></p>').join('')||'<p>도로별 교통정보가 없습니다.</p>')+'</details></article>').join('')+'<p class="traffic-note">조회 결과는 가상 시연 경로와 별도입니다. 실제 도로 구간 ID 기반의 개인화와 GPS 주행 안내는 아직 연결하지 않았습니다. 조회 시각은 공급자의 원천 교통정보 측정 시각과 다를 수 있습니다.</p>';
    }catch(e){$('liveTrafficResults').textContent=e.name==='TimeoutError'?'응답 시간이 초과됐습니다. 잠시 후 다시 조회해주세요.':e.message;}
    finally{liveLoading=false;$('liveTrafficFetch').disabled=!liveReady;}
  });
  $('driverSelect').addEventListener('change',e=>{if(running()||needsReview()||window.RMNavigatorUI?.isActive())return;active=e.target.value;trip=null;tripMeta=null;save();renderStatic();renderControls();renderLive();toast(names[active]+'의 독립적인 기록을 불러왔습니다.');});
  $('soundToggle').addEventListener('click',()=>{
    if(!('speechSynthesis'in window)){toast('이 브라우저는 음성 안내를 지원하지 않습니다.');return;}
    sound=!sound;$('soundToggle').setAttribute('aria-pressed',String(sound));$('soundToggle').setAttribute('aria-label',sound?'음성 안내 끄기':'음성 안내 켜기');$('soundLabel').textContent=sound?'음성 켜짐':'음성 꺼짐';
    if(sound)say('길기억 음성 안내를 시작합니다.');else stopSpeech();
  });
  $('memoOpen').addEventListener('click',()=>{if(running()||needsReview())return;$('memoText').value=profile().notes['merge-c']||'';$('memoAvoid').checked=profile().avoid.includes('merge-c');$('memoDialog').showModal();});
  $('memoForm').addEventListener('submit',e=>{e.preventDefault();if(running()||needsReview())return;const p=profile();p.notes['merge-c']=$('memoText').value.trim().slice(0,200);p.avoid=p.avoid.filter(id=>id!=='merge-c');if($('memoAvoid').checked)p.avoid.push('merge-c');save();$('memoDialog').close();renderStatic();toast('메모를 저장했습니다. 경로 추천에 반영했어요.');});
  document.querySelectorAll('[data-close]').forEach(b=>b.addEventListener('click',()=>$(b.dataset.close).close()));
  $('reviewDialog').addEventListener('cancel',e=>e.preventDefault());$('confirmMistake').addEventListener('click',()=>review(true));$('confirmIntentional').addEventListener('click',()=>review(false));
  document.querySelectorAll('[data-view]').forEach(b=>b.addEventListener('click',()=>setView(b.dataset.view)));
  document.querySelector('.brand').addEventListener('click',e=>{e.preventDefault();setView('real');});
  $('openGuide').addEventListener('click',()=>setView('guide'));$('guideStart').addEventListener('click',()=>setView('drive'));
  $('historyList').addEventListener('click',e=>{if(e.target.closest('[data-go-drive]'))setView('drive');});
  $('resetOpen').addEventListener('click',()=>{if(!running()&&!needsReview()&&!window.RMNavigatorUI?.isActive())$('resetDialog').showModal();});
  $('resetConfirm').addEventListener('click',()=>{if(running()||needsReview()||window.RMNavigatorUI?.isActive())return;profiles[active]=RM.createProfile(active,names[active]);history=history.filter(h=>h.profileId!==active);trip=null;tripMeta=null;window.RMNavigatorUI?.clearCurrentDriver();save();$('resetDialog').close();renderStatic();renderControls();renderLive();toast('현재 운전자의 기록을 초기화했습니다.');});
  $('exportButton').addEventListener('click',()=>{const blob=new Blob([JSON.stringify({app:'Route Memory PC',version:1,profile:profile(),history:history.filter(h=>h.profileId===active),navigationHistory:window.RMNavigatorUI?.getRecords()||[]},null,2)],{type:'application/json'}),url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download='RouteMemory-'+active+'-records.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);});
  window.addEventListener('pagehide',()=>{save();stopSpeech();});
  document.addEventListener('visibilitychange',()=>{if(document.hidden&&running())setPause(true);});
  restore();renderStatic();renderControls();renderLive();
  window.RMApp={getProfileSnapshot:()=>RM.validateProfile(profile(),profile().id,profile().name),isLearningBusy:()=>!!(running()||needsReview()),getCurrentView:()=>currentView,notify:toast,speak:say,stopSpeech};
  document.addEventListener('rm-nav-state-changed',()=>{const busy=!!(running()||needsReview()||window.RMNavigatorUI?.isActive());$('driverSelect').disabled=busy;$('resetOpen').disabled=busy;$('memoOpen').disabled=busy;if(currentView==='history')renderHistory();});
  setView('real');
  if(needsReview())showReview();if(restoreNotice)toast(restoreNotice);
})();
