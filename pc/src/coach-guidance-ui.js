(function(){
  'use strict';
  // The car-link screen on the PC: the same rules as Android GuidanceActivity (coach-guidance.js = GuidanceSession),
  // fed by a virtual vehicle console shaped like the Pleos Virtual Console. In the car the Pleos SDKs feed the same calls.
  const $=id=>document.getElementById(id);
  const C=window.RMCoach,G=window.RMCoachGuidance;
  const esc=s=>String(s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
  const STORAGE='rm-guidance',SPEAK_MS=4400,FLASH_MS=4000,STEP_MS=1500;
  const BADGE='PC 차량 연동 · 입력은 가상 차량 콘솔 · Android에서는 같은 자리에 Pleos SDK 이벤트';
  const NOTE='Android 차량 연동 화면과 같은 규칙입니다. 차량 신호는 Pleos Virtual Console처럼 아래 가상 차량 콘솔로 넣고, 공식 에뮬레이터·실차에서는 Vehicle SDK와 NaviHelper SDK 이벤트가 이 자리에 들어옵니다.';
  const SIGNALS={off:'꺼짐',left:'왼쪽',right:'오른쪽'};
  let saved={driverType:'general',memory:{}},session,spoken=null,flash=null,timer=0,steps=[];

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
    if(outcome.event==='speak'||outcome.event==='question'){spoken={text:outcome.speech,at:now()};say(outcome.speech);}
    else if(outcome.event==='silent')note('말하지 않음','방향지시등을 켰거나 이미 준비한 회전이라 조용히 있습니다','silent');
    else if(outcome.event==='miss-candidate')note('놓침 후보','재탐색이 일어났어요. 정차하면 놓쳤는지 묻습니다');
    else if(outcome.event==='passed')note('무사히 통과','지켜보던 회전을 잘 지나갔어요');
    save();render();
  }
  // Virtual vehicle console: the same calls the Pleos bridge makes.
  const placeId=(type,place)=>type.replace(/-/g,'_')+'|'+(place||'가상 콘솔');
  function sendTurn(type,meters,place){take(G.onTurn(session,{id:placeId(type,place),type},meters,now()));}
  function clearTurn(){take(G.onTurn(session,null,-1,now()));}
  function setSignal(signal){take(G.onSignal(session,signal,now()));}
  function setGear(parked){take(G.onGear(session,parked));}
  function setNavigating(flag){take(G.onNavigationStatus(session,flag,now()));}
  function reroute(){take(G.onReroute(session,now()));}
  function answer(confirmed){G.answer(session,confirmed);spoken=null;note('확인',confirmed?'놓친 곳으로 기억했어요':'기록하지 않았어요');save();render();}

  // Two canned runs so the whole loop can be watched without clicking each signal.
  function run(list){stop();let at=0;for(const step of list){steps.push(setTimeout(()=>{step();render();},at));at+=STEP_MS;}}
  function stop(){for(const t of steps)clearTimeout(t);steps=[];}
  const turnOf=()=>$('guidanceTurnType').value,placeOf=()=>$('guidancePlace').value.trim();
  const missRun=()=>run([()=>setGear(false),()=>setSignal('off'),()=>setNavigating(true),()=>sendTurn(turnOf(),600,placeOf()),()=>sendTurn(turnOf(),300,placeOf()),()=>sendTurn(turnOf(),150,placeOf()),()=>sendTurn(turnOf(),60,placeOf()),()=>clearTurn(),()=>reroute(),()=>setGear(true)]);
  const againRun=()=>run([()=>setGear(false),()=>setSignal('off'),()=>setNavigating(true),()=>sendTurn(turnOf(),600,placeOf()),()=>sendTurn(turnOf(),300,placeOf()),()=>sendTurn(turnOf(),150,placeOf())]);

  function statusOf(t){
    const cur=session.current;
    if(spoken&&t-spoken.at<SPEAK_MS&&!session.question)return{key:'speak',label:'말하는 중',text:spoken.text};
    if(session.question)return{key:'note',label:'정차 중 확인',text:session.question,answers:true};
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
    const metrics=row('방향지시등',SIGNALS[session.signal]||session.signal)+row('기어',session.parked?'P':'D')+row('내비 상태',session.navigating?'안내 중':'없음')+row('Vehicle SDK','가상 콘솔')+row('NaviHelper SDK','가상 콘솔');
    const answers=status.answers?'<div class="coach-answers"><button type="button" class="primary" data-answer="yes">네, 놓쳤어요</button><button type="button" data-answer="no">아니요</button></div>':'';
    // The app sits inside a self-drawn mock of the car's home screen (status bar, cluster, dock), so the PC shows the
    // same picture as the car. The cluster's gear letters and signal arrows follow the console, as the real one would.
    const d=new Date(t),hour=d.getHours(),clock=String(hour%12||12).padStart(2,'0')+':'+String(d.getMinutes()).padStart(2,'0')+' '+(hour<12?'오전':'오후');
    const arrow=side=>'<i class="ivi-arrow '+side+(session.signal===side?' on':'')+'"></i>';
    const prnd=['P','R','N','D'].map(g=>'<b class="'+((g==='P'&&session.parked)||(g==='D'&&!session.parked)?'on':'')+'">'+g+'</b>').join('');
    const car='<svg class="ivi-car" viewBox="0 0 120 220" aria-hidden="true"><rect x="22" y="14" width="76" height="192" rx="30" fill="#d9d9de"/><path d="M34 60Q60 44 86 60L80 86H40Z" fill="#aeb0b8"/><path d="M38 150H82L86 178Q60 190 34 178Z" fill="#aeb0b8"/><rect x="14" y="40" width="12" height="30" rx="5" fill="#2a2b30"/><rect x="94" y="40" width="12" height="30" rx="5" fill="#2a2b30"/><rect x="14" y="150" width="12" height="30" rx="5" fill="#2a2b30"/><rect x="94" y="150" width="12" height="30" rx="5" fill="#2a2b30"/></svg>';
    $('coachStage').innerHTML='<div class="ivi"><div class="ivi-top"><span class="ivi-glyph lock"></span><span class="ivi-glyph search"></span><em class="ivi-pill">'+BADGE+'</em><span class="ivi-driver">운</span><span class="ivi-glyph bt"></span><span class="ivi-glyph wifi"></span><time>'+clock+'</time></div>'+
      '<div class="ivi-body"><div class="ivi-cluster"><div class="ivi-cluster-top"><span class="ivi-fuel"><i></i>500 km</span><span class="ivi-speed">'+(session.parked?'0 km/h':'주행 중')+'</span><span class="ivi-prnd">'+prnd+'</span></div><div class="ivi-signals">'+arrow('left')+arrow('right')+'</div>'+car+'<div class="ivi-quick"><i></i><i></i><i></i><i></i></div></div>'+
      '<div class="ivi-task"><span class="ivi-close"></span><div class="ivi-badge">Pleos SDK 연동 · 방향지시등·기어·내비 이벤트 · PC에서는 가상 차량 콘솔</div><div class="coach-screen ivi-screen"><div class="coach-navi"><div class="coach-navi-head"><span>다음 회전</span><strong>'+esc(cur?C.turnTypes[cur.type].label:'다음 회전 없음')+'</strong><b>'+(cur&&session.meters>=0?session.meters+'m':'')+'</b></div><dl class="coach-live-body">'+metrics+'</dl></div>'+
      '<aside class="coach-app"><div class="coach-app-brand">길기억</div><div class="coach-tag">차량 연동 · '+esc(driver.label)+'</div><div class="coach-chips"><span>'+esc(driver.label)+' 유형</span><span class="on">연결됨 · 가상 콘솔</span></div>'+
      '<div class="coach-status '+esc(status.key)+'"><small>'+esc(status.label)+'</small><p>'+esc(status.text)+'</p>'+answers+'</div>'+
      '<dl class="coach-facts"><div><dt>약한 유형</dt><dd>'+esc(weak.length?weak.join(', '):'없음')+'</dd></div><div><dt>기억 지점</dt><dd>'+p.places.length+'곳 · 확인 대기 '+p.pending.length+'건</dd></div><div><dt>판단 거리</dt><dd>'+C.decisionMeters(p,cur||{type:'exit-left'})+'m</dd></div></dl></aside></div></div></div>'+
      '<div class="ivi-dock"><span class="ivi-temp">22.0°<small>A/C</small></span><span class="ivi-apps"><i></i><i></i><i></i><i></i><i class="on"></i></span><span class="ivi-temp">22.0°<small>A/C</small></span></div></div>';
    for(const b of $('coachStage').querySelectorAll('[data-answer]'))b.addEventListener('click',()=>answer(b.dataset.answer==='yes'));
    for(const [id,active] of [['guidanceSignalOff',session.signal==='off'],['guidanceSignalLeft',session.signal==='left'],['guidanceSignalRight',session.signal==='right'],['guidanceGearP',session.parked],['guidanceGearD',!session.parked],['guidanceNav',session.navigating]])$(id)?.classList.toggle('on',active);
    $('guidanceNav').textContent=session.navigating?'내비 안내 중':'내비 안내 없음';
    $('guidanceDriver').value=p.driverType;$('guidancePassenger').checked=session.passenger;
    $('guidanceForget').disabled=!(p.places.length||weak.length||p.pending.length);
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
  $('guidanceSendTurn')?.addEventListener('click',()=>sendTurn(turnOf(),Math.max(0,Math.round(Number($('guidanceMeters').value)||0)),placeOf()));
  $('guidanceClearTurn')?.addEventListener('click',clearTurn);
  $('guidanceReroute')?.addEventListener('click',reroute);
  $('guidancePassenger')?.addEventListener('change',e=>{session.passenger=e.target.checked;render();});
  $('guidanceDriver')?.addEventListener('change',e=>{stop();save();open(e.target.value);save();render();});
  $('guidanceMiss')?.addEventListener('click',missRun);
  $('guidanceAgain')?.addEventListener('click',againRun);
  $('guidanceForget')?.addEventListener('click',()=>{stop();C.clearMemory(session.profile);session.question=null;save();note('기록','이 유형의 기억을 모두 지웠어요');render();});
  document.addEventListener('rm-view-changed',e=>{if(e.detail.view!=='coach')stop();else if(on())render();});
})();
