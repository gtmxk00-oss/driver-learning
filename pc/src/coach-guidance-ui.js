(function(){
  'use strict';
  // The car-link screen on the PC: the same rules as Android GuidanceActivity (coach-guidance.js = GuidanceSession),
  // fed by a virtual vehicle console shaped like the Pleos Virtual Console. In the car the Pleos SDKs feed the same calls:
  // Vehicle (signal, gear, seat), NaviHelper (turns, reroutes, destination), the car's speed, FusedLocation, SpeechToText.
  const $=id=>document.getElementById(id);
  const C=window.RMCoach,G=window.RMCoachGuidance;
  const esc=s=>String(s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
  const STORAGE='rm-guidance',SPEAK_MS=4400,FLASH_MS=4000,STEP_MS=1500;
  const BADGE='PC 차량 연동 · 입력은 가상 차량 콘솔 · Android에서는 같은 자리에 Pleos SDK 이벤트';
  const NOTE='Android 차량 연동 화면과 같은 규칙입니다. 차량 신호는 Pleos Virtual Console처럼 아래 가상 차량 콘솔로 넣고, 공식 에뮬레이터·실차에서는 Vehicle SDK와 NaviHelper SDK 이벤트, 차량 속도, FusedLocation 위치, SpeechToText 음성이 이 자리에 들어옵니다.';
  const SIGNALS={off:'꺼짐',left:'왼쪽',right:'오른쪽'};
  const FAMILIAR={unknown:'모름',new:'처음 가는 길','known':'몇 번 가 본 길',familiar:'익숙한 길'};
  let saved={driverType:'general',memory:{}},session,spoken=null,flash=null,timer=0,steps=[],listening=null;

  // What the driver confirmed stays in this browser, per driver type, as on the device.
  function load(){
    try{const s=JSON.parse(localStorage.getItem(STORAGE)||'{}')||{};saved={driverType:s.driverType||'general',memory:s.memory&&typeof s.memory==='object'?s.memory:{}};}catch{}
    open(Object.prototype.hasOwnProperty.call(C.driverTypes,saved.driverType)?saved.driverType:'general');
  }
  function open(type){
    const profile=C.createProfile('car','차량',type);
    C.restore(profile,saved.memory[type]||'');
    session=G.create(profile);saved.driverType=type;spoken=null;flash=null;
  }
  function save(){saved.memory[session.profile.driverType]=C.snapshot(session.profile);try{localStorage.setItem(STORAGE,JSON.stringify(saved));}catch{}}
  const on=()=>document.body.classList.contains('coach-guidance');
  const now=()=>Date.now();
  function voiceOn(){return $('soundToggle')?.getAttribute('aria-pressed')==='true'&&'speechSynthesis'in window;}
  function say(text){if(!voiceOn())return;const v=new SpeechSynthesisUtterance(text);v.lang='ko-KR';speechSynthesis.speak(v);}
  function note(label,text,key='note'){flash={label,text,key,at:now()};}

  // Every outcome of the rules lands here, as the Android host does.
  function take(outcome){
    if(!outcome)return;
    const e=outcome.event;
    if(e==='speak'||e==='question'||e==='lower-question'||e==='briefing'||e==='summary'){spoken={text:outcome.speech,at:now(),key:e};say(outcome.speech);}
    else if(e==='memo'){spoken={text:outcome.speech,at:now(),key:e};say(outcome.speech);}
    else if(e==='silent')note('말하지 않음',outcome.decision&&outcome.decision.reason==='rarely-signals'?'이 운전자는 평소 방향지시등을 잘 켜지 않아, 꺼진 것만으로는 말하지 않습니다':outcome.decision&&outcome.decision.reason==='familiar-road'?'익숙한 길이라 약한 유형은 말하지 않습니다':'방향지시등을 켰거나 이미 준비한 회전이라 조용히 있습니다','silent');
    else if(e==='miss-candidate')note('놓침 후보','재탐색이 일어났어요. 정차하면 놓쳤는지 묻습니다');
    else if(e==='passed')note('무사히 통과','지켜보던 회전을 잘 지나갔어요');
    else if(e==='none'&&outcome.decision&&outcome.decision.reason==='familiar-road')note('익숙한 길','여러 번 다닌 길이라 약한 유형은 말하지 않습니다. 기억해 둔 곳만 말합니다','silent');
    save();render();
  }
  // Virtual vehicle console: the same calls the Pleos bridge makes.
  const placeId=(type,place)=>type.replace(/-/g,'_')+'|'+(place||'가상 콘솔');
  const gapOf=()=>{const v=Number($('guidanceGap')?.value);return Number.isFinite(v)&&v>0?Math.round(v):-1;};
  function sendTurn(type,meters,place,detail){take(G.onTurn(session,{id:placeId(type,place),type,detail:detail||''},meters,now(),gapOf()));}
  function clearTurn(){take(G.onTurn(session,null,-1,now()));}
  function setSignal(signal){take(G.onSignal(session,signal,now()));}
  function setGear(parked){take(G.onGear(session,parked));}
  function setNavigating(flag){take(G.onNavigationStatus(session,flag,now()));}
  function reroute(){take(G.onReroute(session,now()));}
  function setSpeed(kph){const v=Math.max(0,Math.round(Number(kph)||0));if($('guidanceSpeed'))$('guidanceSpeed').value=String(v);take(G.onSpeed(session,v));}
  function setFamiliarity(value){if($('guidanceFamiliarity'))$('guidanceFamiliarity').value=value;take(G.setFamiliarity(session,value));}
  function startRoute(name){if($('guidanceDestination'))$('guidanceDestination').value=name;take(G.onDestination(session,name));}
  function memo(text){const words=(text||$('guidanceMemoText')?.value||'').trim();if(!words){note('메모','메모할 말을 적거나 말로 하기를 눌러 주세요');render();return;}take(G.memo(session,words));if($('guidanceMemoText'))$('guidanceMemoText').value='';}
  function answer(confirmed){
    if(session.lowerAsked){G.answerLower(session,confirmed);spoken=null;note('확인',confirmed?'이 유형 알림을 줄였어요':'알림을 그대로 둡니다');}
    else{G.answer(session,confirmed);spoken=null;note('확인',confirmed?'놓친 곳으로 기억했어요':'기록하지 않았어요');}
    save();render();
    // Whatever waited behind the question (another question, the summary) comes a moment later.
    setTimeout(()=>take(G.afterAnswer(session)),1200);
  }
  // Voice: the browser's own recognition stands in for the Pleos SpeechToText SDK. Yes/no answers a pending question;
  // anything else becomes a memo in the driver's words.
  function hear(text){
    const words=text.trim();
    if(session.question){if(/^(네|예|응|맞아|놓쳤|줄여|그래)/.test(words))return answer(true);if(/^(아니|아뇨|괜찮|그냥)/.test(words))return answer(false);}
    memo(words);
  }
  function listen(){
    const Rec=window.SpeechRecognition||window.webkitSpeechRecognition;
    if(listening){try{listening.stop();}catch{}return;}
    if(!Rec){note('음성 입력','이 브라우저는 음성 인식을 지원하지 않아 메모 칸에 적어 주세요');render();return;}
    const r=new Rec();r.lang='ko-KR';r.interimResults=false;r.maxAlternatives=1;listening=r;$('guidanceVoice')?.classList.add('listening');$('guidanceVoice').textContent='듣는 중…';
    const done=()=>{listening=null;$('guidanceVoice')?.classList.remove('listening');if($('guidanceVoice'))$('guidanceVoice').textContent='말로 하기';render();};
    r.onresult=e=>{const t=e.results?.[0]?.[0]?.transcript||'';if(t)hear(t);};
    r.onerror=e=>{note('음성 입력',e.error==='not-allowed'?'마이크 권한이 없어요':'듣지 못했어요: '+e.error);};
    r.onend=done;
    try{r.start();}catch{done();}
    render();
  }

  // Canned runs so the whole loop can be watched without clicking each signal.
  function run(list){stop();let at=0;for(const step of list){steps.push(setTimeout(()=>{step();render();},at));at+=STEP_MS;}}
  function stop(){for(const t of steps)clearTimeout(t);steps=[];}
  const turnOf=()=>$('guidanceTurnType').value,placeOf=()=>$('guidancePlace').value.trim(),detailOf=()=>$('guidanceDetail')?.value.trim()||'';
  const missRun=()=>run([()=>setGear(false),()=>setSignal('off'),()=>setNavigating(true),()=>sendTurn(turnOf(),600,placeOf(),detailOf()),()=>sendTurn(turnOf(),300,placeOf(),detailOf()),()=>sendTurn(turnOf(),150,placeOf(),detailOf()),()=>sendTurn(turnOf(),60,placeOf(),detailOf()),()=>clearTurn(),()=>reroute(),()=>setGear(true)]);
  const againRun=()=>run([()=>setGear(false),()=>setSignal('off'),()=>setNavigating(true),()=>sendTurn(turnOf(),600,placeOf(),detailOf()),()=>sendTurn(turnOf(),300,placeOf(),detailOf()),()=>sendTurn(turnOf(),150,placeOf(),detailOf())]);
  // A short day: two signalled turns, one unsignalled, then park. The rules then ask, offer and summarise in turn.
  const tripRun=()=>run([()=>setGear(false),()=>setNavigating(true),()=>setSignal('off'),
    ()=>sendTurn('exit-right',400,'1번 출구'),()=>setSignal('right'),()=>sendTurn('exit-right',60,'1번 출구'),()=>setSignal('off'),
    ()=>sendTurn('turn-left',400,'시장 앞'),()=>setSignal('left'),()=>sendTurn('turn-left',50,'시장 앞'),()=>setSignal('off'),
    ()=>sendTurn(turnOf(),400,placeOf(),detailOf()),()=>sendTurn(turnOf(),60,placeOf(),detailOf()),()=>clearTurn(),()=>setNavigating(false),()=>setGear(true)]);

  // The lane strip under the turn, as on the demo screen: four lanes, the one to be in lit, and when to move.
  function lanesHtml(turn,meters,limit){
    const h=C.laneHint(turn,meters,limit);
    const boxes=[0,1,2,3].map(i=>'<i class="'+((h.side==='left'&&i===0)||(h.side==='right'&&i===3)?'on':'')+'"></i>').join('');
    return'<div class="coach-lanes" aria-label="추천 차선"><span class="coach-lanes-label">추천 차선</span><div class="coach-lanes-strip">'+boxes+'</div><span>'+esc(h.text)+'</span></div>';
  }
  function statusOf(t){
    const cur=session.current;
    if(spoken&&t-spoken.at<SPEAK_MS&&!session.question)return{key:spoken.key==='summary'||spoken.key==='briefing'?'note':spoken.key==='memo'?'note':'speak',label:spoken.key==='summary'?'오늘 주행 요약':spoken.key==='briefing'?'출발 전 브리핑':spoken.key==='memo'?'메모':'말하는 중',text:spoken.text};
    if(session.question)return{key:'note',label:session.lowerAsked?'정차 중 제안':'정차 중 확인',text:session.question,answers:true};
    if(flash&&t-flash.at<FLASH_MS)return flash;
    if(!cur)return{key:'wait',label:'지켜보는 중',text:'내비가 다음 회전을 알려 주면 판단합니다'};
    const type=C.turnTypes[cur.type];
    if(C.remembers(session.profile,cur.id))return{key:'wait',label:'지켜보는 중',text:'기억해 둔 곳이에요. 방향지시등이 꺼진 채 가까워지면 말합니다'};
    if(C.isWeak(session.profile,cur.type))return{key:'wait',label:'지켜보는 중',text:type.label+'은 약한 유형이에요. 방향지시등이 꺼진 채 가까워지면 말합니다'};
    return{key:'wait',label:'지켜보는 중',text:'이 회전은 아직 기록이 없습니다'};
  }
  function render(){
    if(!on()||!session)return;
    const t=now(),p=session.profile,driver=C.driverTypes[p.driverType],status=statusOf(t),cur=session.current;
    const row=(dt,dd)=>'<div><dt>'+esc(dt)+'</dt><dd>'+esc(dd)+'</dd></div>';
    const weak=Object.keys(C.turnTypes).filter(k=>C.isWeak(p,k)).map(k=>C.turnTypes[k].label);
    const speedText=session.speedKph>=0?session.speedKph+' km/h':'모름';
    const metrics=row('방향지시등',SIGNALS[session.signal]||session.signal)+row('기어',session.parked?'P':'D')+row('속도',speedText)+row('내비 상태',session.navigating?'안내 중'+(session.destination?' · '+session.destination:''):'없음')+row('길 익숙도',FAMILIAR[session.familiarity]||session.familiarity)+row('Vehicle · NaviHelper SDK','가상 콘솔');
    const yes=session.lowerAsked?'네, 줄여 주세요':'네, 놓쳤어요';
    const answers=status.answers?'<div class="coach-answers"><button type="button" class="primary" data-answer="yes">'+yes+'</button><button type="button" data-answer="no">아니요</button></div>':'';
    // The app sits inside a self-drawn mock of the car's home screen (status bar, cluster, dock), so the PC shows the
    // same picture as the car. The cluster's gear letters, speed and signal arrows follow the console, as the real one would.
    const d=new Date(t),hour=d.getHours(),clock=String(hour%12||12).padStart(2,'0')+':'+String(d.getMinutes()).padStart(2,'0')+' '+(hour<12?'오전':'오후');
    const arrow=side=>'<i class="ivi-arrow '+side+(session.signal===side?' on':'')+'"></i>';
    const prnd=['P','R','N','D'].map(g=>'<b class="'+((g==='P'&&session.parked)||(g==='D'&&!session.parked)?'on':'')+'">'+g+'</b>').join('');
    const car='<svg class="ivi-car" viewBox="0 0 120 220" aria-hidden="true"><rect x="22" y="14" width="76" height="192" rx="30" fill="#d9d9de"/><path d="M34 60Q60 44 86 60L80 86H40Z" fill="#aeb0b8"/><path d="M38 150H82L86 178Q60 190 34 178Z" fill="#aeb0b8"/><rect x="14" y="40" width="12" height="30" rx="5" fill="#2a2b30"/><rect x="94" y="40" width="12" height="30" rx="5" fill="#2a2b30"/><rect x="14" y="150" width="12" height="30" rx="5" fill="#2a2b30"/><rect x="94" y="150" width="12" height="30" rx="5" fill="#2a2b30"/></svg>';
    const clusterSpeed=session.parked?'0 km/h':session.speedKph>=0?session.speedKph+' km/h':'주행 중';
    const limit=C.decisionMeters(p,cur||{type:'exit-left'},session.speedKph,session.familiarity);
    const rate=C.signalRate(p);
    $('coachStage').innerHTML='<div class="ivi"><div class="ivi-top"><span class="ivi-glyph lock"></span><span class="ivi-glyph search"></span><em class="ivi-pill">'+BADGE+'</em><span class="ivi-driver">운</span><span class="ivi-glyph bt"></span><span class="ivi-glyph wifi"></span><time>'+clock+'</time></div>'+
      '<div class="ivi-body"><div class="ivi-cluster"><div class="ivi-cluster-top"><span class="ivi-fuel"><i></i>500 km</span><span class="ivi-speed">'+clusterSpeed+'</span><span class="ivi-prnd">'+prnd+'</span></div><div class="ivi-signals">'+arrow('left')+arrow('right')+'</div>'+car+'<div class="ivi-quick"><i></i><i></i><i></i><i></i></div></div>'+
      '<div class="ivi-task"><span class="ivi-close"></span><div class="ivi-badge">Pleos SDK 연동 · 방향지시등·기어·속도·위치·내비 이벤트 · PC에서는 가상 차량 콘솔</div><div class="coach-screen ivi-screen"><div class="coach-navi"><div class="coach-navi-head"><span>다음 회전</span><strong>'+esc(cur?C.turnTypes[cur.type].label+(cur.detail?' · '+cur.detail:''):'다음 회전 없음')+'</strong><b>'+(cur&&session.meters>=0?session.meters+'m':'')+'</b></div>'+lanesHtml(cur,session.meters,limit)+'<dl class="coach-live-body">'+metrics+'</dl></div>'+
      '<aside class="coach-app"><div class="coach-app-brand">운전자학습</div><div class="coach-tag">차량 연동 · '+esc(driver.label)+'</div><div class="coach-chips"><span>'+esc(driver.label)+' 유형</span><span class="on">연결됨 · 가상 콘솔</span></div>'+
      '<div class="coach-status '+esc(status.key)+'"><small>'+esc(status.label)+'</small><p>'+esc(status.text)+'</p>'+answers+'</div>'+
      '<dl class="coach-facts"><div><dt>약한 유형</dt><dd>'+esc(weak.length?weak.join(', '):'없음')+'</dd></div><div><dt>기억 지점</dt><dd>'+p.places.length+'곳 · 확인 대기 '+p.pending.length+'건 · 길 '+Object.keys(p.roadCells).length+'칸</dd></div><div><dt>판단 거리</dt><dd>'+limit+'m <span class="coach-fact-sub">· 깜빡이 '+(rate<0?'기록 중':Math.round(rate*100)+'%')+'</span></dd></div></dl></aside></div></div></div>'+
      '<div class="ivi-dock"><span class="ivi-temp">22.0°<small>A/C</small></span><span class="ivi-apps"><i></i><i></i><i></i><i></i><i class="on"></i></span><span class="ivi-temp">22.0°<small>A/C</small></span></div></div>';
    for(const b of $('coachStage').querySelectorAll('[data-answer]'))b.addEventListener('click',()=>answer(b.dataset.answer==='yes'));
    for(const [id,active] of [['guidanceSignalOff',session.signal==='off'],['guidanceSignalLeft',session.signal==='left'],['guidanceSignalRight',session.signal==='right'],['guidanceGearP',session.parked],['guidanceGearD',!session.parked],['guidanceNav',session.navigating]])$(id)?.classList.toggle('on',active);
    $('guidanceNav').textContent=session.navigating?'내비 안내 중':'내비 안내 없음';
    $('guidanceDriver').value=p.driverType;$('guidancePassenger').checked=session.passenger;
    if($('guidanceSpeedOut'))$('guidanceSpeedOut').textContent=(session.speedKph>=0?session.speedKph:0)+' km/h';
    $('guidanceForget').disabled=!(p.places.length||weak.length||p.pending.length||Object.keys(p.roadCells).length);
  }
  function enter(){
    if(document.body.classList.contains('coach-live')&&$('liveToggle')?.textContent==='위치 끄기')$('liveToggle').click();
    document.body.classList.remove('coach-live');document.body.classList.add('coach-guidance');
    for(const [id,active] of [['coachModeDemo',false],['coachModeLive',false],['coachModeGuidance',true]]){$(id).classList.toggle('active',active);$(id).setAttribute('aria-pressed',String(active));}
    $('coachDemoPanel').hidden=true;$('coachLivePanel').hidden=true;$('coachGuidancePanel').hidden=false;$('coachNote').textContent=NOTE;
    document.dispatchEvent(new CustomEvent('rm-coach-mode',{detail:{mode:'guidance'}}));
    clearInterval(timer);timer=setInterval(render,1000);render();
  }
  function leave(){stop();clearInterval(timer);timer=0;document.body.classList.remove('coach-guidance');}
  load();
  $('coachModeGuidance')?.addEventListener('click',enter);
  $('coachModeDemo')?.addEventListener('click',leave);$('coachModeLive')?.addEventListener('click',leave);
  $('guidanceSignalOff')?.addEventListener('click',()=>setSignal('off'));
  $('guidanceSignalLeft')?.addEventListener('click',()=>setSignal('left'));
  $('guidanceSignalRight')?.addEventListener('click',()=>setSignal('right'));
  $('guidanceGearP')?.addEventListener('click',()=>setGear(true));
  $('guidanceGearD')?.addEventListener('click',()=>setGear(false));
  $('guidanceNav')?.addEventListener('click',()=>setNavigating(!session.navigating));
  $('guidanceSendTurn')?.addEventListener('click',()=>sendTurn(turnOf(),Math.max(0,Math.round(Number($('guidanceMeters').value)||0)),placeOf(),detailOf()));
  $('guidanceClearTurn')?.addEventListener('click',clearTurn);
  $('guidanceReroute')?.addEventListener('click',reroute);
  $('guidanceSpeed')?.addEventListener('input',e=>setSpeed(e.target.value));
  $('guidanceFamiliarity')?.addEventListener('change',e=>setFamiliarity(e.target.value));
  $('guidanceRouteStart')?.addEventListener('click',()=>{startRoute($('guidanceDestination').value.trim());if(!session.navigating)setNavigating(true);});
  $('guidanceDestination')?.addEventListener('keydown',e=>{if(e.key==='Enter'){e.preventDefault();$('guidanceRouteStart').click();}});
  $('guidanceMemo')?.addEventListener('click',()=>memo());
  $('guidanceMemoText')?.addEventListener('keydown',e=>{if(e.key==='Enter'){e.preventDefault();memo();}});
  $('guidanceVoice')?.addEventListener('click',listen);
  $('guidancePassenger')?.addEventListener('change',e=>{session.passenger=e.target.checked;render();});
  $('guidanceDriver')?.addEventListener('change',e=>{stop();save();open(e.target.value);save();render();});
  $('guidanceMiss')?.addEventListener('click',missRun);
  $('guidanceAgain')?.addEventListener('click',againRun);
  $('guidanceTrip')?.addEventListener('click',tripRun);
  $('guidanceForget')?.addEventListener('click',()=>{stop();C.clearMemory(session.profile);session.question=null;session.lowerAsked=null;save();note('기록','이 유형의 기억을 모두 지웠어요');render();});
  document.addEventListener('rm-view-changed',e=>{if(e.detail.view!=='coach')stop();else if(on())render();});
})();
