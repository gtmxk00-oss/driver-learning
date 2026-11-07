(function(root,factory){
  const navigation=factory();
  if(typeof module==='object'&&module.exports)module.exports=navigation;
  else root.RMNavigation=navigation;
})(typeof globalThis!=='undefined'?globalThis:this,function(){
  'use strict';
  const freeze=value=>Object.freeze(value);
  const point=(x,y)=>freeze({x,y});
  const nodes=freeze([
    {id:'home',name:'우리 집',x:180,y:390,category:'집'},
    {id:'office',name:'한빛 오피스',x:370,y:390,category:'업무'},
    {id:'park',name:'두빛 공원',x:590,y:80,category:'공원'},
    {id:'hospital',name:'새빛 병원',x:700,y:290,category:'병원'},
    {id:'station',name:'중앙역',x:170,y:100,category:'역'},
    {id:'museum',name:'두빛 미술관',x:650,y:420,category:'문화'},
    {id:'west',name:'중앙 교차로',x:180,y:280},
    {id:'central',name:'한빛 분기점',x:370,y:280},
    {id:'north',name:'두빛 갈림길',x:370,y:160},
    {id:'east',name:'공원 교차로',x:590,y:160},
    {id:'southeast',name:'새빛 합류점',x:590,y:290},
    {id:'south',name:'문화 교차로',x:590,y:390}
  ].map(freeze));
  const byNode=Object.fromEntries(nodes.map(n=>[n.id,n]));
  const places=freeze(nodes.filter(n=>n.category));
  const scenarios=freeze(['clear','fast-jam','all-slow','easy-jam']);
  const factors=freeze({
    clear:freeze({fast:1,easy:1,local:1}),
    'fast-jam':freeze({fast:30/18,easy:23/21,local:1}),
    'all-slow':freeze({fast:28/18,easy:27/21,local:1.2}),
    'easy-jam':freeze({fast:1,easy:33/21,local:1})
  });
  const roadDefinitions=[
    ['home-office','home','office','한빛 생활도로','simple',700,100,'fast'],
    ['home-west','home','west','서쪽 생활도로','simple',400,70,'easy'],
    ['west-station','west','station','중앙역로','simple',600,90,'easy'],
    ['west-central','west','central','중앙대로','simple',650,100,'easy'],
    ['station-north','station','north','중앙 북로','simple',900,140,'easy',[point(170,100),point(370,100),point(370,160)]],
    ['office-central','office','central','한빛 진입로','simple',550,50,'fast'],
    ['exit-a','central','north','한빛 분기점','exit',650,50,'fast'],
    ['fork-b','north','east','두빛 연결로','sequence',900,70,'fast'],
    ['east-park','east','park','두빛 공원길','simple',350,35,'fast'],
    ['merge-c','east','southeast','새빛 합류로','merge',600,55,'fast'],
    ['southeast-hospital','southeast','hospital','새빛 병원길','simple',500,55,'local'],
    ['southeast-south','southeast','south','문화대로','simple',450,55,'easy'],
    ['office-south','office','south','남쪽 순환로','simple',900,120,'easy'],
    ['south-museum','south','museum','미술관 진입로','simple',350,50,'local',[point(590,390),point(650,390),point(650,420)]],
    ['museum-hospital','museum','hospital','새빛 우회로','simple',700,100,'easy',[point(650,420),point(700,420),point(700,290)]],
    ['central-southeast','central','southeast','새빛 중앙로','simple',1000,150,'easy',[point(370,280),point(590,280),point(590,290)]],
    ['station-park','station','park','북쪽 공원로','simple',1600,210,'easy',[point(170,100),point(590,100),point(590,80)]]
  ];
  const roads=freeze(roadDefinitions.flatMap(([id,from,to,name,kind,meters,baseSeconds,family,geometry])=>{
    const points=geometry||[point(byNode[from].x,byNode[from].y),point(byNode[to].x,byNode[to].y)];
    const common={roadId:id,name,kind,meters,lengthMeters:meters,baseSeconds,family};
    return [freeze({...common,id,from,to,points:freeze(points)}),freeze({...common,id:id+'-reverse',from:to,to:from,points:freeze([...points].reverse())})];
  }));
  const byEdge=Object.fromEntries(roads.map(r=>[r.id,r]));
  const outgoing=Object.fromEntries(nodes.map(n=>[n.id,roads.filter(r=>r.from===n.id)]));
  const validId=id=>typeof id==='string'&&Object.prototype.hasOwnProperty.call(byNode,id);
  const traffic=id=>scenarios.includes(id)?id:'clear';
  const profileValue=p=>p&&typeof p==='object'?p:{};
  function missCount(profile,id){
    const value=profileValue(profile).confirmedMisses?.[id];
    return Number.isInteger(value)&&value>0?Math.min(100,value):0;
  }
  function edgeBurden(profile,edge){
    const p=profileValue(profile);
    const avoid=Array.isArray(p.avoid)?p.avoid:[];
    return (edge.kind==='simple'?0:2)+Math.min(4,missCount(p,edge.id))*2+(avoid.includes(edge.id)||avoid.includes(edge.roadId)?12:0);
  }
  function leadMeters(profile,edge){return Math.min(900,(edge.kind==='sequence'?450:300)+Math.min(4,missCount(profile,edge.id))*150);}
  function duration(edge,trafficId){return Math.ceil(edge.baseSeconds*factors[traffic(trafficId)][edge.family]);}
  function searchPlaces(query=''){
    const clean=String(query).trim().toLocaleLowerCase('ko-KR').slice(0,100);
    return places.filter(p=>!clean||(p.name+' '+p.category).toLocaleLowerCase('ko-KR').includes(clean));
  }
  function turn(edge,next){
    if(!next)return 'arrive';
    const a=edge.points[edge.points.length-2],b=edge.points[edge.points.length-1];
    const c=next.points[0],d=next.points[1];
    const vx=b.x-a.x,vy=b.y-a.y,wx=d.x-c.x,wy=d.y-c.y;
    const angle=Math.atan2(vx*wy-vy*wx,vx*wx+vy*wy);
    if(Math.abs(angle)<0.45)return 'straight';
    return angle>0?'right':'left';
  }
  function cue(edge,next,destination){
    const maneuver=turn(edge,next);
    if(maneuver==='arrive')return destination.name+'에 도착합니다';
    const direction={straight:'직진',left:'좌회전',right:'우회전'}[maneuver];
    return '다음 교차로에서 '+direction+'하여 '+next.name+'로 이동하세요';
  }
  function routeFromEdges({originId,destinationId,preference='time',profile,trafficId='clear'},edges){
    let expected=originId;
    for(const edge of edges){if(edge.from!==expected)throw new RangeError('Disconnected route');expected=edge.to;}
    if(!edges.length||expected!==destinationId)throw new RangeError('No connected route');
    const scenario=traffic(trafficId),destination=byNode[destinationId];
    const points=[];for(const edge of edges)for(const p of edge.points){const last=points[points.length-1];if(!last||last.x!==p.x||last.y!==p.y)points.push({x:p.x,y:p.y});}
    const steps=edges.map((edge,i)=>({edgeId:edge.id,name:edge.name,cue:cue(edge,edges[i+1],destination),maneuver:turn(edge,edges[i+1]),meters:edge.meters,leadMeters:leadMeters(profile,edge),durationSeconds:duration(edge,scenario)}));
    return {id:'virtual-'+originId+'-'+destinationId+'-'+edges.map(e=>e.id).join('.'),source:'virtual',originId,destinationId,preference,trafficId:scenario,
      edges:[...edges],distanceMeters:edges.reduce((n,e)=>n+e.meters,0),durationSeconds:edges.reduce((n,e)=>n+duration(e,scenario),0),
      burden:edges.reduce((n,e)=>n+edgeBurden(profile,e),0),points,steps};
  }
  function plan({originId,destinationId,preference='time',profile,trafficId='clear',avoidEdgeId=null}={}){
    if(!validId(originId)||!validId(destinationId))throw new RangeError('가상 도시의 출발지와 목적지를 선택해주세요.');
    if(originId===destinationId)throw new RangeError('출발지와 목적지를 다르게 선택해주세요.');
    if(!['time','easy'].includes(preference))throw new RangeError('지원하지 않는 경로 기준입니다.');
    if(avoidEdgeId!==null&&!Object.prototype.hasOwnProperty.call(byEdge,avoidEdgeId))throw new RangeError('알 수 없는 도로입니다.');
    const scenario=traffic(trafficId),costs=new Map([[originId,0]]),previous=new Map(),unvisited=new Set(nodes.map(n=>n.id));
    while(unvisited.size){
      let current=null,best=Infinity;
      for(const id of unvisited){const cost=costs.get(id)??Infinity;if(cost<best){best=cost;current=id;}}
      if(current===null)break;
      unvisited.delete(current);if(current===destinationId)break;
      for(const edge of outgoing[current]){
        if(!unvisited.has(edge.to)||edge.id===avoidEdgeId||edge.roadId===avoidEdgeId)continue;
        const penalty=preference==='easy'?edgeBurden(profile,edge)*90:0;
        const next=best+duration(edge,scenario)+penalty;
        if(next<(costs.get(edge.to)??Infinity)){costs.set(edge.to,next);previous.set(edge.to,edge);}
      }
    }
    if(!previous.has(destinationId))throw new RangeError('연결 가능한 가상 경로를 찾지 못했습니다.');
    const edges=[];for(let node=destinationId;node!==originId;){const edge=previous.get(node);if(!edge)throw new RangeError('연결 가능한 가상 경로를 찾지 못했습니다.');edges.unshift(edge);node=edge.from;}
    return routeFromEdges({originId,destinationId,preference,profile,trafficId:scenario},edges);
  }
  function validateRoute(raw,profile){
    if(!raw||typeof raw!=='object'||!validId(raw.originId)||!validId(raw.destinationId)||raw.originId===raw.destinationId||
      !['time','easy'].includes(raw.preference)||!scenarios.includes(raw.trafficId)||!Array.isArray(raw.edges)||raw.edges.length<1||raw.edges.length>100)return null;
    const edges=[];
    for(const e of raw.edges){if(!e||typeof e.id!=='string'||!Object.prototype.hasOwnProperty.call(byEdge,e.id))return null;edges.push(byEdge[e.id]);}
    try{return routeFromEdges({originId:raw.originId,destinationId:raw.destinationId,preference:raw.preference,trafficId:raw.trafficId,profile},edges);}catch{return null;}
  }
  function createSession(route,profileId){
    const checked=validateRoute(route);
    if(!checked||typeof profileId!=='string'||!profileId||profileId.length>80)throw new TypeError('올바른 가상 경로와 운전자를 선택해주세요.');
    // The route's own lead thresholds retain the planned profile; advance also evaluates the current profile.
    if(Array.isArray(route.steps)&&route.steps.length===checked.steps.length)
      checked.steps=checked.steps.map((step,i)=>({...step,leadMeters:Number.isInteger(route.steps[i]?.leadMeters)&&route.steps[i].leadMeters>=300&&route.steps[i].leadMeters<=900?route.steps[i].leadMeters:step.leadMeters}));
    checked.burden=Number.isFinite(route.burden)?Math.max(0,route.burden):0;
    return {route:checked,profileId,status:'ready',edgeIndex:0,edgeRemainingMeters:checked.edges[0].meters,travelledMeters:0,
      announcedForEdge:false,spoken:'',lastMessage:'가상 경로를 확인하고 안내를 시작하세요.',rerouteCount:0,pendingReroute:null,arrivalCount:0};
  }
  function start(session){if(session.status==='ready'){session.status='running';session.spoken='가상 경로 안내를 시작합니다.';session.lastMessage=session.spoken;}return session;}
  function pause(session){if(session.status==='running'){session.status='paused';session.spoken='';session.lastMessage='가상 길 안내를 일시정지했습니다.';}return session;}
  function resume(session){if(session.status==='paused'){session.status='running';session.spoken='';session.lastMessage='가상 길 안내를 계속합니다.';}return session;}
  function cancel(session){if(['ready','running','paused'].includes(session.status)){session.status='cancelled';session.spoken='';session.pendingReroute=null;session.lastMessage='가상 길 안내를 종료했습니다.';}return session;}
  function assertDriver(session,profile){if(profile?.id!==session.profileId)throw new TypeError('다른 운전자의 길 안내를 변경할 수 없습니다.');}
  function remainingMeters(session){
    if(session.status==='arrived'||session.status==='cancelled')return 0;
    return session.edgeRemainingMeters+session.route.edges.slice(session.edgeIndex+1).reduce((n,e)=>n+e.meters,0);
  }
  function remainingSeconds(session){
    if(session.status==='arrived'||session.status==='cancelled')return 0;
    const edge=session.route.edges[session.edgeIndex];
    return Math.ceil(duration(edge,session.route.trafficId)*session.edgeRemainingMeters/edge.meters+
      session.route.edges.slice(session.edgeIndex+1).reduce((n,e)=>n+duration(e,session.route.trafficId),0));
  }
  function position(session){
    const edge=session.route.edges[session.edgeIndex],points=edge.points;
    const ratio=Math.max(0,Math.min(1,1-session.edgeRemainingMeters/edge.meters));
    const lengths=points.slice(1).map((p,i)=>Math.hypot(p.x-points[i].x,p.y-points[i].y));
    let left=lengths.reduce((n,v)=>n+v,0)*ratio;
    for(let i=0;i<lengths.length;i++){
      if(left<=lengths[i]||i===lengths.length-1){
        const a=points[i],b=points[i+1],fraction=lengths[i]?Math.max(0,Math.min(1,left/lengths[i])):0;
        return {x:a.x+(b.x-a.x)*fraction,y:a.y+(b.y-a.y)*fraction,heading:(Math.atan2(b.x-a.x,-(b.y-a.y))*180/Math.PI+360)%360};
      }
      left-=lengths[i];
    }
    return {x:points[0].x,y:points[0].y,heading:0};
  }
  function pendingSuffix(route,index,pending,profile){
    const edge=route.edges[index];
    if(edge.to===route.destinationId)return [];
    return plan({originId:edge.to,destinationId:route.destinationId,preference:route.preference,profile,
      trafficId:pending.trafficId,avoidEdgeId:pending.avoidEdgeId}).edges;
  }
  function setPendingCue(route,index,pending,profile){
    const edge=route.edges[index],next=pending.edgeIds.length?byEdge[pending.edgeIds[0]]:null;
    const current=route.steps[index];
    route.steps[index]={...current,cue:cue(edge,next,byNode[route.destinationId]),maneuver:turn(edge,next),leadMeters:leadMeters(profile,edge)};
    return route.steps[index];
  }
  function queueReroute(session,profile,{trafficId=session.route.trafficId,avoidEdgeId=null}={}){
    assertDriver(session,profile);
    if(!['ready','running','paused'].includes(session.status))throw new RangeError('진행 중인 가상 길 안내에서만 재탐색할 수 있습니다.');
    const scenario=traffic(trafficId),edge=session.route.edges[session.edgeIndex];
    if(avoidEdgeId!==null&&!Object.prototype.hasOwnProperty.call(byEdge,avoidEdgeId))throw new RangeError('알 수 없는 도로입니다.');
    const atOrigin=session.status==='ready';
    const originId=atOrigin?session.route.originId:edge.to;
    const suffix=originId!==session.route.destinationId?plan({originId,destinationId:session.route.destinationId,preference:session.route.preference,profile,trafficId:scenario,avoidEdgeId}):null;
    if(atOrigin){
      session.route=plan({originId,destinationId:session.route.destinationId,preference:session.route.preference,profile,trafficId:scenario,avoidEdgeId});
      session.edgeIndex=0;session.edgeRemainingMeters=session.route.edges[0].meters;session.announcedForEdge=false;session.rerouteCount++;
      session.lastMessage='교통 상황을 반영해 가상 경로를 다시 찾았습니다.';
    }else{
      const oldCue=session.route.steps[session.edgeIndex].cue;
      session.pendingReroute={trafficId:scenario,avoidEdgeId,edgeIds:suffix?suffix.edges.map(e=>e.id):[]};
      const updated=setPendingCue(session.route,session.edgeIndex,session.pendingReroute,profile);
      if(updated.cue!==oldCue)session.announcedForEdge=false;
      session.lastMessage='다음 교차로에서 교통 상황을 반영한 경로로 안내합니다.';
      session.spoken='';
      // A changed turn must reach the driver before the junction, even if the old cue already played.
      if(!session.announcedForEdge&&session.edgeRemainingMeters<=leadMeters(profile,edge)){
        session.announcedForEdge=true;session.lastMessage=updated.cue+'.';session.spoken=session.lastMessage;
      }
      return session;
    }
    session.spoken=session.lastMessage;return session;
  }
  function advance(session,profile,meters=50){
    assertDriver(session,profile);
    if(!Number.isInteger(meters)||meters<1||meters>1000)throw new RangeError('가상 이동 거리는 1~1000m 정수여야 합니다.');
    session.spoken='';if(session.status!=='running')return session;
    let available=meters;
    while(available>0&&session.status==='running'){
      const edge=session.route.edges[session.edgeIndex],moved=Math.min(available,session.edgeRemainingMeters);
      session.edgeRemainingMeters-=moved;session.travelledMeters+=moved;available-=moved;
      if(!session.announcedForEdge&&session.edgeRemainingMeters<=leadMeters(profile,edge)){
        session.announcedForEdge=true;session.lastMessage=session.route.steps[session.edgeIndex].cue+'.';session.spoken=session.lastMessage;
      }
      if(session.edgeRemainingMeters>0)break;
      if(session.edgeIndex===session.route.edges.length-1){
        session.status='arrived';session.arrivalCount=1;session.pendingReroute=null;
        session.lastMessage=byNode[session.route.destinationId].name+'에 도착했습니다.';session.spoken=session.lastMessage;break;
      }
      if(session.pendingReroute){
        const pending=session.pendingReroute,prefix=session.route.edges.slice(0,session.edgeIndex+1);
        const suffix=pending.edgeIds.map(id=>byEdge[id]);
        session.route=routeFromEdges({...session.route,profile,trafficId:pending.trafficId},prefix.concat(suffix));
        session.pendingReroute=null;session.rerouteCount++;
        session.lastMessage='교통 상황을 반영한 새 가상 경로로 안내합니다.';session.spoken=session.lastMessage;
      }
      session.edgeIndex++;session.edgeRemainingMeters=session.route.edges[session.edgeIndex].meters;session.announcedForEdge=false;
    }
    return session;
  }
  function validateSession(raw,profile){
    if(typeof raw==='string'){if(raw.length>100000)return null;try{raw=JSON.parse(raw);}catch{return null;}}
    if(!raw||typeof raw!=='object'||typeof raw.profileId!=='string'||!raw.profileId||raw.profileId.length>80)return null;
    if(profile&&profile.id!==raw.profileId)return null;
    const route=validateRoute(raw.route,profile);if(!route||!['ready','running','paused','arrived','cancelled'].includes(raw.status))return null;
    if(!Number.isInteger(raw.edgeIndex)||raw.edgeIndex<0||raw.edgeIndex>=route.edges.length)return null;
    const edge=route.edges[raw.edgeIndex];
    if(!Number.isInteger(raw.edgeRemainingMeters)||raw.edgeRemainingMeters<0||raw.edgeRemainingMeters>edge.meters||
      typeof raw.announcedForEdge!=='boolean'||!Number.isInteger(raw.rerouteCount)||raw.rerouteCount<0||raw.rerouteCount>1000||
      !Number.isInteger(raw.arrivalCount)||raw.arrivalCount<0||raw.arrivalCount>1||typeof raw.lastMessage!=='string'||raw.lastMessage.length>500)return null;
    const travelled=route.edges.slice(0,raw.edgeIndex).reduce((n,e)=>n+e.meters,0)+edge.meters-raw.edgeRemainingMeters;
    if(raw.travelledMeters!==travelled)return null;
    if(raw.status==='ready'&&(raw.edgeIndex!==0||raw.edgeRemainingMeters!==edge.meters||raw.announcedForEdge||raw.arrivalCount!==0))return null;
    if(raw.status==='arrived'&&(raw.edgeIndex!==route.edges.length-1||raw.edgeRemainingMeters!==0||raw.arrivalCount!==1))return null;
    if(raw.status!=='arrived'&&(raw.edgeRemainingMeters===0||raw.arrivalCount!==0))return null;
    let pendingReroute=null;
    if(raw.pendingReroute!==null){
      const p=raw.pendingReroute;
      if(!p||typeof p!=='object'||!['running','paused'].includes(raw.status)||!scenarios.includes(p.trafficId)||
        !(p.avoidEdgeId===null||Object.prototype.hasOwnProperty.call(byEdge,p.avoidEdgeId)))return null;
      let suffix;
      try{
        if(Array.isArray(p.edgeIds)){
          if(p.edgeIds.length>100||p.edgeIds.some(id=>typeof id!=='string'||!Object.prototype.hasOwnProperty.call(byEdge,id)))return null;
          suffix=p.edgeIds.map(id=>byEdge[id]);
          if(suffix.some(e=>e.id===p.avoidEdgeId||e.roadId===p.avoidEdgeId))return null;
          if(edge.to===route.destinationId){if(suffix.length)return null;}
          else routeFromEdges({originId:edge.to,destinationId:route.destinationId,preference:route.preference,trafficId:p.trafficId,profile},suffix);
        }else suffix=pendingSuffix(route,raw.edgeIndex,p,profile); // Upgrade precomputed-suffix-free saved sessions.
        if(profile)suffix=pendingSuffix(route,raw.edgeIndex,p,profile);
      }catch{return null;}
      pendingReroute={trafficId:p.trafficId,avoidEdgeId:p.avoidEdgeId,edgeIds:suffix.map(e=>e.id)};
      setPendingCue(route,raw.edgeIndex,pendingReroute,profile);
    }
    // Rebuild every road, geometry, instruction and ETA from canonical graph data.
    return {route,profileId:raw.profileId,status:raw.status,edgeIndex:raw.edgeIndex,edgeRemainingMeters:raw.edgeRemainingMeters,travelledMeters:travelled,
      announcedForEdge:raw.announcedForEdge,spoken:'',lastMessage:pendingReroute?(raw.announcedForEdge?route.steps[raw.edgeIndex].cue+'.':'다음 교차로에서 교통 상황을 반영한 경로로 안내합니다.'):raw.lastMessage,
      rerouteCount:raw.rerouteCount,pendingReroute,arrivalCount:raw.arrivalCount};
  }
  function serializeSession(session){const clean=validateSession(session);if(!clean)throw new TypeError('저장할 수 없는 가상 길 안내 상태입니다.');return JSON.stringify(clean);}
  return freeze({source:'virtual',places,nodes,roads,scenarios,searchPlaces,plan,validateRoute,createSession,start,pause,resume,cancel,advance,queueReroute,
    position,remainingMeters,remainingSeconds,leadMeters,validateSession,serializeSession});
});
