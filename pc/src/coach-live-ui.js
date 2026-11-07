(function(){
  'use strict';
  const $=id=>document.getElementById(id);
  const C=window.RMCoach,L=window.RMCoachLive;
  const esc=s=>String(s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
  const STORAGE='rm-live',SPEAK_MS=4400,FLASH_MS=4000;
  const BADGE='실제 위치 · 내비·방향지시등 정보 없음 · Pleos SDK 연동 전';
  const NOTE={demo:'운전 유형별 판단 규칙을 가상 신호로 보여 주는 시연입니다. 실제 차량 신호와 Pleos Connect SDK는 아직 연결되지 않았습니다.',
    live:'이 기기의 위치로 동작합니다. 헷갈린 곳에서 여기 헷갈려를 누르면 기억 지점으로 저장하고, 다음에 다가가면 음성으로 알립니다. 위치는 서버로 보내지 않고 기억 지점은 이 브라우저에만 저장합니다.'};
  let session,watchId=null,timer=0,generation=0,waiting=false,denied=false,flash=null;

  function load(){
    let saved={};try{saved=JSON.parse(localStorage.getItem(STORAGE)||'{}')||{};}catch{}
    const type=Object.prototype.hasOwnProperty.call(C.driverTypes,saved.driverType)?saved.driverType:'general';
    try{session=L.createSession(C.createProfile('live','실제 위치',type),Array.isArray(saved.points)?saved.points:[]);}
    catch{session=L.createSession(C.createProfile('live','실제 위치',type));}
  }
  function save(){try{localStorage.setItem(STORAGE,JSON.stringify({driverType:session.profile.driverType,points:L.serialize(session)}));}catch{}}
  const live=()=>document.body.classList.contains('coach-live');
  const active=()=>watchId!==null;
  function voiceOn(){return $('soundToggle')?.getAttribute('aria-pressed')==='true'&&'speechSynthesis'in window;}
  function say(text){if(!voiceOn())return;const voice=new SpeechSynthesisUtterance(text);voice.lang='ko-KR';speechSynthesis.speak(voice);}
  function note(label,text,key='note'){flash={label,text,key,at:Date.now()};}

  // Location starts only from the button, as the real map does.
  function start(){
    if(active())return;
    if(!navigator.geolocation){note('위치 사용 불가','이 브라우저에서 위치 기능을 지원하지 않습니다');render();return;}
    if(!window.isSecureContext){note('위치 사용 불가','GPS는 HTTPS 또는 localhost에서 사용할 수 있습니다');render();return;}
    denied=false;waiting=true;const g=++generation;
    watchId=navigator.geolocation.watchPosition(pos=>{
      if(g!==generation)return;
      waiting=false;const c=pos.coords;
      const seen=L.observe(session,{lat:c.latitude,lon:c.longitude,accuracy:c.accuracy,speed:c.speed,timestamp:pos.timestamp});
      if(seen.accepted&&seen.decision.action==='speak')say(seen.decision.speech);
      render();
    },err=>{
      if(g!==generation)return;
      waiting=false;
      if(err.code===1){denied=true;stop();return;}
      note('위치 대기',err.code===2?'위치를 확인하지 못했습니다. 기기의 위치 설정을 확인해주세요':'위치 응답을 기다리고 있습니다');render();
    },{enableHighAccuracy:true,maximumAge:1000,timeout:12000});
    timer=setInterval(render,1000);render();
  }
  function stop(){
    generation++;
    if(watchId!==null)navigator.geolocation.clearWatch(watchId);
    watchId=null;clearInterval(timer);timer=0;waiting=false;render();
  }
  function statusOf(st,near,limit,now){
    const fix=session.last;
    // What the app is saying outranks every note.
    if(session.spoken&&now-session.spoken.at<SPEAK_MS)return{key:'speak',label:'말하는 중',text:session.spoken.text};
    if(flash&&now-flash.at<FLASH_MS)return flash;
    if(denied)return{key:'wait',label:'위치 권한 없음',text:'브라우저에서 위치 권한을 허용하면 다시 켤 수 있습니다'};
    if(!active())return{key:'wait',label:'위치 꺼짐',text:'위치 켜기를 누르면 현재 위치를 따라갑니다'};
    if(!fix)return{key:'wait',label:'위치 확인 중',text:waiting?'브라우저의 위치 권한 요청을 확인해주세요':'위치를 기다리고 있습니다'};
    if(!st.fresh)return{key:'wait',label:'위치 대기',text:'최근 15초 동안 새 위치가 없습니다'};
    if(!st.trusted)return{key:'wait',label:'정확도 낮음',text:'정확도 ±'+Math.round(fix.accuracy)+'m라 기억 지점을 알리지 않습니다'};
    if(!near)return{key:'wait',label:'지켜보는 중',text:'기억 지점이 없습니다. 헷갈린 곳에서 여기 헷갈려를 누르세요'};
    if(near.meters<=limit&&session.announced[near.point.id])return{key:'note',label:'알림 완료',text:session.spoken?session.spoken.text:near.point.label+' 근처입니다'};
    if(near.meters<=limit&&!st.moving)return{key:'wait',label:st.parked?'정차 중':'지켜보는 중',text:'출발하면 '+near.point.label+'을 알립니다'};
    return{key:'wait',label:'지켜보는 중',text:'가장 가까운 기억 지점까지 '+Math.round(near.meters)+'m'};
  }
  function render(){
    if(!live()||!session)return;
    const now=Date.now(),st=L.status(session,now),fix=session.last,near=fix?L.nearest(session,fix):null,limit=L.limitMeters(session);
    const driver=C.driverTypes[session.profile.driverType],status=statusOf(st,near,limit,now);
    const kmh=st.fresh&&session.speed!==null?Math.round(session.speed*3.6)+' km/h':'';
    const headline=!active()?'위치 꺼짐':!fix?'위치 확인 중':!st.fresh?'위치 대기':st.parked?'정차 중':st.moving?'주행 중':'느리게 이동';
    const row=(dt,dd)=>'<div><dt>'+esc(dt)+'</dt><dd>'+esc(dd)+'</dd></div>';
    const metrics=row('정확도',fix?'±'+Math.round(fix.accuracy)+'m':'-')+row('마지막 갱신',fix?Math.max(0,Math.round(st.ageMs/1000))+'초 전':'-')+
      row('위치',fix?fix.lat.toFixed(5)+', '+fix.lon.toFixed(5):'-')+row('가장 가까운 기억 지점',near?near.point.label+' · '+Math.round(near.meters)+'m':'없음');
    $('coachStage').innerHTML='<div class="coach-badge">'+BADGE+'</div><div class="coach-screen"><div class="coach-navi"><div class="coach-navi-head"><span>현재 위치</span><strong>'+esc(headline)+'</strong><b>'+esc(kmh)+'</b></div><dl class="coach-live-body">'+metrics+'</dl></div>'+
      '<aside class="coach-app"><div class="coach-app-brand">길기억</div><div class="coach-tag">실제 위치 · '+esc(driver.label)+'</div><div class="coach-chips"><span>'+esc(driver.label)+' 유형</span><span class="'+(active()?'on':'')+'">'+(active()?'위치 켜짐':'위치 꺼짐')+'</span></div>'+
      '<div class="coach-status '+esc(status.key)+'"><small>'+esc(status.label)+'</small><p>'+esc(status.text)+'</p></div>'+
      '<dl class="coach-facts"><div><dt>기억 지점</dt><dd>'+session.points.length+'곳</dd></div><div><dt>판단 거리</dt><dd>'+limit+'m</dd></div><div><dt>알림 조건</dt><dd>주행 중 · 정확도 ±'+L.TRUSTED_ACCURACY+'m 이내</dd></div></dl></aside></div>';
    $('liveToggle').textContent=active()?'위치 끄기':'위치 켜기';
    $('liveMemo').disabled=!(active()&&fix&&st.fresh&&st.trusted);
    $('liveForget').disabled=!session.points.length;
    $('liveDriver').value=session.profile.driverType;
  }
  function setMode(mode){
    const isLive=mode==='live';
    if(!isLive)stop();
    document.body.classList.toggle('coach-live',isLive);
    $('coachModeDemo').classList.toggle('active',!isLive);$('coachModeDemo').setAttribute('aria-pressed',String(!isLive));
    $('coachModeLive').classList.toggle('active',isLive);$('coachModeLive').setAttribute('aria-pressed',String(isLive));
    $('coachDemoPanel').hidden=isLive;$('coachLivePanel').hidden=!isLive;
    $('coachNote').textContent=NOTE[isLive?'live':'demo'];
    document.dispatchEvent(new CustomEvent('rm-coach-mode',{detail:{mode}}));
    if(isLive)render();
  }
  load();
  $('coachModeDemo')?.addEventListener('click',()=>setMode('demo'));
  $('coachModeLive')?.addEventListener('click',()=>setMode('live'));
  $('liveToggle')?.addEventListener('click',()=>active()?stop():start());
  $('liveMemo')?.addEventListener('click',()=>{
    const saved=L.remember(session,Date.now());
    if(!saved.ok)note('기록',saved.reason==='poor-accuracy'?'정확도가 낮아 저장하지 않았습니다':'최신 위치가 없어 저장하지 않았습니다');
    else if(saved.merged)note('기록','이미 저장된 지점입니다 ('+saved.point.label+')');
    else note('기록',saved.point.label+'을 기억 지점으로 저장했습니다');
    save();render();
  });
  $('liveForget')?.addEventListener('click',()=>{L.forget(session);save();note('기록','이 브라우저의 기억 지점을 모두 지웠습니다');render();});
  $('liveDriver')?.addEventListener('change',e=>{L.setDriverType(session,e.target.value);save();render();});
  document.addEventListener('rm-view-changed',e=>{if(e.detail.view!=='coach')stop();else if(live())render();});
  document.addEventListener('visibilitychange',()=>{if(document.hidden)stop();});
  window.addEventListener('pagehide',()=>stop());
})();
