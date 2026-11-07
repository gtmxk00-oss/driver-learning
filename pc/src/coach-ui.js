(function(){
  'use strict';
  const $=id=>document.getElementById(id);
  const D=window.RMCoachDemo,C=window.RMCoach;
  const esc=s=>String(s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
  let line=D.timeline(),position=0,playing=false,lastFrame=0,raf=0,spokenUpTo=-1;

  const phaseText=state=>({
    rerouting:'내비게이션이 재탐색 중입니다',
    'question-waiting':'승객이 내릴 때까지 질문을 미룹니다',
    question:state.scene.question,
    learned:'운전자가 확인했습니다',
    declined:'운전자가 아니요라고 답해 기록하지 않았습니다',
    memo:'“'+(state.scene.memoText||'')+'”',
    'memo-saved':'기억 지점으로 저장했습니다'
  }[state.phase]||'');
  function statusOf(state){
    if(state.phase==='parked')return{key:state.step.speak?'speak':'note',label:state.step.label,text:state.step.text};
    if(state.speaking)return{key:'speak',label:'말하는 중',text:state.spoken};
    const text=phaseText(state);
    if(text)return{key:state.phase==='question'?'speak':'note',label:state.phase==='question'?'정차 중 질문':'기록',text};
    const action=state.decision?.action;
    if(action==='silent')return{key:'silent',label:'말하지 않음',text:'방향지시등이 켜져 있어 준비 중으로 판단했습니다'};
    if(action==='wait')return{key:'wait',label:'지켜보는 중',text:'판단 거리 전입니다'};
    if(action==='speak')return{key:'note',label:'알림 완료',text:state.spoken};
    return{key:'wait',label:'지켜보는 중',text:'이 회전은 아직 기록이 없습니다'};
  }
  function roadSvg(state){
    const type=state.scene.turn?C.turnTypes[state.scene.turn.type]:null;
    const left=type?.side!=='right',from=state.scene.from||1,progress=state.meters===null?0:1-state.meters/from;
    const branch=type?.side?(left?'M240 120 C200 80 120 56 -80 40':'M240 120 C280 80 360 56 560 40'):'';
    const noRoute=state.scene.noRoute,missed=['rerouting','question-waiting','question','learned','declined'].includes(state.phase),took=state.phase==='passed'&&Boolean(branch);
    const y=missed?56:took?71:290-progress*170,x=took?(left?140:340):240;
    return'<svg viewBox="0 0 480 360" aria-hidden="true"><path class="coach-road" d="M240 360 L240 0"/>'+(branch?'<path class="coach-road" d="'+branch+'"/>':'')+
      '<path class="coach-lane" d="M240 360 L240 0"/>'+(noRoute?'':branch?'<path class="coach-route" d="M240 360 L240 120 '+branch.slice(branch.indexOf('C'))+'"/>':'<path class="coach-route" d="M240 360 L240 0"/>')+
      '<circle class="coach-car" cx="'+x+'" cy="'+y.toFixed(1)+'" r="13"/></svg>';
  }
  function signalHtml(state){
    const lamp=(side,mark)=>'<span class="coach-lamp'+(state.signal===side?' on':'')+'">'+mark+'</span>';
    return'<div class="coach-signal" aria-label="방향지시등">'+lamp('left','◀')+'<small>방향지시등</small>'+lamp('right','▶')+'</div>';
  }
  function card(state){
    return'<div class="coach-card-scene"><div class="coach-card-brand">길기억</div><h2>'+esc(state.scene.title)+'</h2><p>'+esc(state.scene.lead)+'</p></div>';
  }
  function drive(state){
    const status=statusOf(state),turn=state.scene.turn?C.turnTypes[state.scene.turn.type]:null;
    const heading=state.phase==='parked'?'정차 중':state.scene.noRoute?'내비게이션 경로 없음':(turn?turn.label:'');
    const distance=state.meters===null?'':state.meters+'m';
    const learned=state.learned||{weak:[],places:0};
    return'<div class="coach-navi"><div class="coach-navi-head"><span>가상 내비게이션</span><strong>'+esc(heading)+'</strong><b>'+esc(distance)+'</b></div><div class="coach-road-wrap">'+roadSvg(state)+'</div>'+signalHtml(state)+'</div>'+
      '<aside class="coach-app"><div class="coach-app-brand">길기억</div><div class="coach-tag">'+esc(state.scene.tag)+'</div><div class="coach-chips"><span>'+esc(state.driver.label)+' 유형</span><span class="'+(state.passenger?'on':'')+'">'+(state.passenger?'승객 탑승':'승객 없음')+'</span></div>'+
      '<div class="coach-status '+status.key+'"><small>'+esc(status.label)+'</small><p>'+esc(status.text)+'</p></div>'+
      '<dl class="coach-facts"><div><dt>약한 유형</dt><dd>'+esc(learned.weak.join(', ')||'없음')+'</dd></div><div><dt>기억 지점</dt><dd>'+learned.places+'곳</dd></div><div><dt>판단 거리</dt><dd>'+(state.limit?state.limit+'m':'-')+'</dd></div></dl></aside>';
  }
  function subtitle(state){
    if(!document.body.classList.contains('coach-fullscreen')||state.seconds<D.narrationSeconds(state.scene))return'';
    return'<div class="coach-sub">'+esc(state.scene.narration)+'</div>';
  }
  function render(seconds){
    position=seconds;
    const state=D.stateAt(seconds,line);
    const stage=$('coachStage');
    if(!stage||!state)return;
    stage.innerHTML='<div class="coach-badge">가상 시연 · Pleos SDK 연동 전 · 신호는 시뮬레이션</div>'+(state.phase==='card'?card(state):'<div class="coach-screen">'+drive(state)+'</div>')+subtitle(state);
    const bar=$('coachProgress');if(bar)bar.style.width=(seconds/line.total*100).toFixed(2)+'%';
    document.querySelectorAll('[data-coach-scene]').forEach((b,i)=>b.classList.toggle('active',i===state.index));
    const cap=$('coachCaption');if(cap)cap.textContent=state.scene.narration;
  }
  function voiceOn(){return $('soundToggle')?.getAttribute('aria-pressed')==='true'&&'speechSynthesis'in window;}
  function sayDue(from,to){
    if(!voiceOn())return;
    for(const event of D.events(line))if(event.kind==='alert'&&event.at>from&&event.at<=to){
      const voice=new SpeechSynthesisUtterance(event.text);voice.lang='ko-KR';speechSynthesis.speak(voice);
    }
  }
  function loop(now){
    if(!playing)return;
    const next=position+(now-lastFrame)/1000;lastFrame=now;
    sayDue(spokenUpTo,next);spokenUpTo=next;
    if(next>=line.total-0.05){setPlaying(false);render(line.total-0.05);return;}
    render(next);raf=requestAnimationFrame(loop);
  }
  function setPlaying(value){
    playing=value;cancelAnimationFrame(raf);
    const button=$('coachPlay');if(button)button.textContent=value?'일시정지':'재생';
    if(value){lastFrame=performance.now();spokenUpTo=position;raf=requestAnimationFrame(loop);}
  }
  function jump(index){setPlaying(false);render(line.items[index].start+0.01);}
  $('coachPlay')?.addEventListener('click',()=>{if(position>=line.total-0.1)render(0);setPlaying(!playing);});
  $('coachRestart')?.addEventListener('click',()=>{setPlaying(false);render(0);});
  $('coachFull')?.addEventListener('click',()=>document.body.classList.toggle('coach-fullscreen'));
  const list=$('coachScenes');
  if(list){
    list.innerHTML=D.scenes.map((s,i)=>'<button type="button" data-coach-scene="'+i+'">'+esc(s.tag||s.title)+'</button>').join('');
    list.addEventListener('click',e=>{const b=e.target.closest('[data-coach-scene]');if(b)jump(Number(b.dataset.coachScene));});
  }
  // The live mode owns the stage while it is on.
  const liveMode=()=>document.body.classList.contains('coach-live');
  document.addEventListener('rm-view-changed',e=>{if(e.detail.view==='coach'){if(!liveMode())render(position);}else setPlaying(false);});
  document.addEventListener('rm-coach-mode',e=>{if(e.detail.mode==='demo')render(position);else setPlaying(false);});
  document.addEventListener('keydown',e=>{if(e.key==='Escape')document.body.classList.remove('coach-fullscreen');});
  // Frame-accurate entry point used to record the submission video.
  window.RMCoachStage={seek:render,setDurations(durations){line=D.timeline(durations);return line.total;},events:()=>D.events(line),total:()=>line.total};
})();
