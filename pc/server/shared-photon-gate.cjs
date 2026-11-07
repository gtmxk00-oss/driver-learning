'use strict';

// Address and nearby searches share this process-wide start gate. No coordinates
// or queries enter the gate; it only remembers the most recent request time.
function createPhotonGate({now=()=>Date.now(),sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms)),minIntervalMs=1000}={}){
  let queue=Promise.resolve(),lastStarted=-Infinity;
  return {wait(){
    const operation=queue.catch(()=>{}).then(async()=>{
      const delay=Math.max(0,minIntervalMs-(now()-lastStarted));
      if(delay)await sleep(delay);
      lastStarted=now();
    });
    queue=operation.catch(()=>{});return operation;
  }};
}
const sharedPhotonGate=createPhotonGate();
module.exports={createPhotonGate,sharedPhotonGate};
