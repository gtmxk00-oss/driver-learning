(function(root,factory){
  const tracker=factory();
  if(typeof module==='object'&&module.exports)module.exports=tracker;
  else root.RMRoadTracker=tracker;
})(typeof globalThis!=='undefined'?globalThis:this,function(){
  'use strict';
  const R=6371008.8,rad=Math.PI/180;
  const finite=n=>typeof n==='number'&&Number.isFinite(n);
  const point=p=>Array.isArray(p)&&p.length>=2&&finite(p[0])&&finite(p[1])&&Math.abs(p[0])<=180&&Math.abs(p[1])<=90;
  const clamp=(n,a,b)=>Math.max(a,Math.min(b,n));
  function distance(a,b){
    const p1=a[1]*rad,p2=b[1]*rad,dp=(b[1]-a[1])*rad,dl=(b[0]-a[0])*rad;
    const h=Math.sin(dp/2)**2+Math.cos(p1)*Math.cos(p2)*Math.sin(dl/2)**2;
    return 2*R*Math.atan2(Math.sqrt(h),Math.sqrt(Math.max(0,1-h)));
  }
  function geometry(raw){
    if(!Array.isArray(raw)||raw.length<2||raw.length>100000||raw.some(p=>!point(p)))throw new TypeError('올바른 실제 도로 경로가 필요합니다.');
    const points=raw.map(p=>[p[0],p[1]]),segments=[];let total=0;
    for(let i=0;i<points.length-1;i++){
      const length=distance(points[i],points[i+1]);
      if(length>0.001){segments.push({a:points[i],b:points[i+1],start:total,length});total+=length;}
    }
    if(total<1||!segments.length)throw new TypeError('이동 가능한 도로 경로가 필요합니다.');
    return {points,segments,total};
  }
  function project(track,location,low=0,high=track.total,preferred=low){
    let best=null;
    const cos=Math.cos(location[1]*rad),scaleX=R*rad*cos,scaleY=R*rad;
    for(const s of track.segments){
      if(s.start+s.length<low||s.start>high)continue;
      const ax=(s.a[0]-location[0])*scaleX,ay=(s.a[1]-location[1])*scaleY;
      const dx=(s.b[0]-s.a[0])*scaleX,dy=(s.b[1]-s.a[1])*scaleY;
      const square=dx*dx+dy*dy;
      const minT=clamp((low-s.start)/s.length,0,1),maxT=clamp((high-s.start)/s.length,0,1);
      const t=clamp(square?-(ax*dx+ay*dy)/square:0,minT,maxT);
      const at=s.start+t*s.length,lon=s.a[0]+t*(s.b[0]-s.a[0]),lat=s.a[1]+t*(s.b[1]-s.a[1]);
      const d=distance(location,[lon,lat]);
      if(!best||d<best.distance-0.1||(Math.abs(d-best.distance)<=0.1&&Math.abs(at-preferred)<Math.abs(best.at-preferred)))best={at,distance:d,lon,lat};
    }
    return best;
  }
  function normalizedSteps(route,track){
    const raw=Array.isArray(route.steps)?route.steps:[];
    if(raw.length>2000)throw new TypeError('경로 안내 정보가 너무 많습니다.');
    const steps=raw.filter(s=>s&&typeof s==='object'&&point(s.maneuver?.location)).map((s,i)=>({
      id:typeof s.id==='string'?s.id.slice(0,100):'step-'+i,
      name:typeof s.name==='string'?s.name.slice(0,200):'',
      distanceMeters:finite(s.distanceMeters)&&s.distanceMeters>=0?s.distanceMeters:0,
      durationSeconds:finite(s.durationSeconds)&&s.durationSeconds>=0?s.durationSeconds:0,
      cue:typeof s.cue==='string'?s.cue.slice(0,400):'안내 경로를 따라 이동하세요.',
      maneuver:{type:typeof s.maneuver.type==='string'?s.maneuver.type:'continue',modifier:typeof s.maneuver.modifier==='string'?s.maneuver.modifier:'',location:s.maneuver.location.slice(0,2),
        bearingBefore:finite(s.maneuver.bearingBefore)?s.maneuver.bearingBefore:null,bearingAfter:finite(s.maneuver.bearingAfter)?s.maneuver.bearingAfter:null,exit:Number.isInteger(s.maneuver.exit)?s.maneuver.exit:null}
    }));
    if(!steps.length||steps.at(-1).maneuver.type!=='arrive')steps.push({id:'route-arrival',name:'목적지',distanceMeters:0,durationSeconds:0,cue:'목적지까지 남은 경로를 따라 이동하세요.',maneuver:{type:'arrive',modifier:'',location:track.points.at(-1).slice()}});
    const sum=steps.reduce((n,s)=>n+s.distanceMeters,0);let expected=0,previous=0;
    const targets=steps.map((s,i)=>{
      const desired=sum?expected/sum*track.total:0;expected+=s.distanceMeters;
      let at;
      if(s.maneuver.type==='arrive'&&i===steps.length-1)at=track.total;
      else {const projection=project(track,s.maneuver.location,Math.max(0,previous-5),track.total,desired);at=projection&&projection.distance<=30?projection.at:desired;}
      at=clamp(Math.max(previous,at),0,track.total);previous=at;return at;
    });
    return {steps,targets};
  }
  function create(route,options={}){
    if(!route||typeof route!=='object'||!finite(route.distanceMeters)||route.distanceMeters<=0||!finite(route.durationSeconds)||route.durationSeconds<=0)throw new TypeError('거리와 시간이 있는 실제 경로가 필요합니다.');
    const track=geometry(route.geometry),guidance=normalizedSteps(route,track);
    const clean={id:typeof route.id==='string'?route.id.slice(0,100):'real-route',distanceMeters:route.distanceMeters,durationSeconds:route.durationSeconds,geometry:track.points,steps:guidance.steps};
    const lead=finite(options?.preferredLeadMeters)?clamp(options.preferredLeadMeters,100,900):300;
    const session={route:clean,status:'waiting',reason:'waiting-for-gps',progressMeters:0,remainingMeters:clean.distanceMeters,remainingSeconds:clean.durationSeconds,
      nextStepIndex:0,nextCue:'GPS 위치를 기다립니다.',nextDistanceMeters:clean.distanceMeters,nextManeuver:null,position:null,snapped:null,distanceFromRoute:null,spoken:'',arrivalCount:0,offRouteCount:0,
      preferredLeadMeters:lead,personalization:'road-history-not-linked',_track:track,_targets:guidance.targets,_progress:0,_seenAt:null,_accepted:null,_arrivalFixes:0,_announced:new Set()};
    updateGuidance(session);return session;
  }
  function updateGuidance(session){
    const steps=session.route.steps,targets=session._targets,progress=session._progress;
    let index=0;
    while(index<steps.length-1&&(steps[index].maneuver.type==='depart'||targets[index]+8<progress))index++;
    if(steps[index].maneuver.type==='depart'&&steps.length>1)index++;
    const next=steps[index];
    session.nextStepIndex=index;session.nextCue=next.maneuver.type==='arrive'?'목적지까지 남은 경로를 따라 이동하세요.':next.cue;
    session.nextDistanceMeters=Math.max(0,(targets[index]-progress)/session._track.total*session.route.distanceMeters);
    session.nextManeuver={...next.maneuver,location:next.maneuver.location.slice()};
  }
  function degraded(session,status,reason){session.status=status;session.reason=reason;session._arrivalFixes=0;session.offRouteCount=0;return session;}
  function update(session,fix,now=Date.now()){
    session.spoken='';
    if(session.status==='stopped'||session.status==='arrived')return session;
    if(!finite(now))now=Date.now();
    if(!fix||!finite(fix.lat)||!finite(fix.lon)||Math.abs(fix.lat)>90||Math.abs(fix.lon)>180||!finite(fix.accuracy)||fix.accuracy<0||!finite(fix.timestamp))return degraded(session,'poor-accuracy','invalid-fix');
    if(session._seenAt!==null&&fix.timestamp<=session._seenAt){
      if(now-session._seenAt>15000)return degraded(session,'stale','gps-update-expired');
      session.reason='duplicate-or-old-fix';return session;
    }
    if(fix.timestamp>now+5000)return degraded(session,'stale','future-fix');
    session._seenAt=fix.timestamp;
    if(now-fix.timestamp>15000)return degraded(session,'stale','gps-update-expired');
    if(fix.accuracy>100){session.position={lat:fix.lat,lon:fix.lon,accuracy:fix.accuracy};return degraded(session,'poor-accuracy','accuracy-over-100m');}
    const previous=session._accepted;
    const elapsed=previous?Math.max(0,(fix.timestamp-previous.timestamp)/1000):0;
    const allowance=previous?70*elapsed+fix.accuracy+previous.accuracy+25:Infinity;
    if(previous&&distance([previous.lon,previous.lat],[fix.lon,fix.lat])>allowance)return degraded(session,'poor-accuracy','implausible-jump');
    session.position={lat:fix.lat,lon:fix.lon,accuracy:fix.accuracy};
    const low=previous?Math.max(0,session._progress-Math.max(80,fix.accuracy*2)):0;
    const high=previous?Math.min(session._track.total,session._progress+Math.min(2500,Math.max(120,allowance))):session._track.total;
    const projection=project(session._track,[fix.lon,fix.lat],low,high,session._progress);
    if(!projection)return degraded(session,'poor-accuracy','no-local-route-match');
    session.snapped={lat:projection.lat,lon:projection.lon};session.distanceFromRoute=projection.distance;
    session._accepted={lat:fix.lat,lon:fix.lon,accuracy:fix.accuracy,timestamp:fix.timestamp};
    const tolerance=Math.max(40,fix.accuracy*2);
    if(projection.distance>tolerance){
      session.offRouteCount++;session._arrivalFixes=0;session.status=session.offRouteCount>=3?'offroute':'tracking';session.reason=session.status==='offroute'?'confirmed-deviation':'possible-deviation';return session;
    }
    session.offRouteCount=0;session.status='tracking';session.reason='gps-on-route';session._progress=projection.at;
    session.progressMeters=projection.at/session._track.total*session.route.distanceMeters;
    session.remainingMeters=Math.max(0,session.route.distanceMeters-session.progressMeters);
    session.remainingSeconds=session.route.durationSeconds*(session.remainingMeters/session.route.distanceMeters);
    updateGuidance(session);
    const endpoint=session._track.points.at(-1),near=distance([fix.lon,fix.lat],endpoint)<=40;
    if(fix.accuracy<=30&&near&&session.remainingMeters<=50)session._arrivalFixes++;else session._arrivalFixes=0;
    if(session._arrivalFixes>=2){
      session.status='arrived';session.reason='destination-confirmed';session.arrivalCount=1;session.remainingMeters=0;session.remainingSeconds=0;session.progressMeters=session.route.distanceMeters;
      session.nextCue='목적지에 도착했습니다.';session.nextDistanceMeters=0;session.spoken=session.nextCue;return session;
    }
    if(session.nextDistanceMeters<=session.preferredLeadMeters&&!session._announced.has(session.nextStepIndex)){
      session._announced.add(session.nextStepIndex);
      session.spoken=session.nextManeuver.type==='arrive'?Math.max(10,Math.round(session.nextDistanceMeters/10)*10)+'m 뒤 목적지 주변입니다.':session.nextCue;
    }
    return session;
  }
  function stop(session){session.status='stopped';session.reason='user-stopped';session.spoken='';session._arrivalFixes=0;session.offRouteCount=0;session._accepted=null;session._seenAt=null;session.position=null;session.snapped=null;session.distanceFromRoute=null;session.nextCue='GPS 길 안내를 종료했습니다.';session.nextManeuver=null;session.nextDistanceMeters=0;return session;}
  return Object.freeze({create,update,stop,distanceMeters:distance});
});
