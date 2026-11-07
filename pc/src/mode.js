(function(){
  'use strict';
  // Auto follows the system setting; an explicit choice is kept in this browser only.
  const root=document.documentElement,button=document.getElementById('modeToggle'),label=document.getElementById('modeLabel');
  const ORDER=['auto','light','dark'],NAMES={auto:'자동',light:'밝게',dark:'어둡게'};
  const read=()=>{try{const m=localStorage.getItem('rm-mode');return ORDER.includes(m)?m:'auto';}catch{return 'auto';}};
  function apply(mode){
    if(mode==='auto')delete root.dataset.mode;else root.dataset.mode=mode;
    try{if(mode==='auto')localStorage.removeItem('rm-mode');else localStorage.setItem('rm-mode',mode);}catch{}
    if(label)label.textContent=NAMES[mode];
    if(button)button.setAttribute('aria-label','화면 모드 '+NAMES[mode]+', 눌러서 바꾸기');
  }
  if(button)button.addEventListener('click',()=>apply(ORDER[(ORDER.indexOf(read())+1)%ORDER.length]));
  apply(read());
})();
