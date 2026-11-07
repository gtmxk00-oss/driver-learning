'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const N=require('../src/navigation.js');
const profile=(id='me')=>({id,confirmedMisses:{},avoid:[],notes:{},completedTrips:0});
const plan=(options={})=>N.plan({originId:'home',destinationId:'park',profile:profile(),...options});
const finish=(s,p)=>{for(let i=0;i<1000&&s.status!=='arrived';i++)N.advance(s,p,50);assert.equal(s.status,'arrived');return s;};

test('virtual place search is literal, bounded and does not claim geocoding',()=>{
  assert.equal(N.source,'virtual');assert.equal(N.places.length,6);
  assert.equal(N.searchPlaces('공원')[0].id,'park');assert.equal(N.searchPlaces('중앙')[0].id,'station');
  assert.deepEqual(N.searchPlaces('서울시 실제 주소'),[]);assert.deepEqual(N.searchPlaces('.*'),[]);
});
test('endpoint changes produce connected distinct routes and matching geometry',()=>{
  const a=plan(),b=plan({destinationId:'hospital'}),c=plan({originId:'station'});
  assert.notEqual(a.id,b.id);assert.notEqual(a.id,c.id);
  for(const r of [a,b,c]){
    assert.equal(r.edges[0].from,r.originId);assert.equal(r.edges.at(-1).to,r.destinationId);
    for(let i=1;i<r.edges.length;i++)assert.equal(r.edges[i-1].to,r.edges[i].from);
    const start=N.nodes.find(n=>n.id===r.originId),end=N.nodes.find(n=>n.id===r.destinationId);
    assert.deepEqual(r.points[0],{x:start.x,y:start.y});assert.deepEqual(r.points.at(-1),{x:end.x,y:end.y});
    assert.equal(r.distanceMeters,r.edges.reduce((n,e)=>n+e.meters,0));
  }
});
test('time and easy preferences choose different real graph paths',()=>{
  const fastest=plan(),easy=plan({preference:'easy'});
  assert.notEqual(fastest.id,easy.id);assert.ok(fastest.durationSeconds<easy.durationSeconds);
  assert.ok(fastest.burden>easy.burden);assert.ok(fastest.edges.some(e=>e.id==='exit-a'));
});
test('traffic changes optimal path without modifying a driver profile',()=>{
  const p=profile(),before=JSON.stringify(p),clear=plan({profile:p}),jam=plan({profile:p,trafficId:'fast-jam'});
  assert.notEqual(clear.id,jam.id);assert.equal(jam.trafficId,'fast-jam');assert.equal(JSON.stringify(p),before);
  assert.equal(plan({trafficId:'bad'}).trafficId,'clear');
});
test('unknown endpoints, identical places and invalid preferences are rejected',()=>{
  assert.throws(()=>plan({destinationId:'unknown'}),/출발지와 목적지/);
  assert.throws(()=>plan({destinationId:'home'}),/다르게/);
  assert.throws(()=>plan({preference:'magic'}),/기준/);
  assert.throws(()=>plan({avoidEdgeId:'unknown'}),/도로/);
});
test('session progresses on graph geometry, arrives once, and leaves driver counts unchanged',()=>{
  const p=profile(),r=plan({profile:p}),s=N.createSession(r,p.id),before=JSON.stringify(p);
  assert.equal(s.status,'ready');assert.deepEqual(N.position(s),{x:180,y:390,heading:90});
  N.start(s);N.advance(s,p,50);assert.equal(s.status,'running');assert.equal(s.travelledMeters,50);
  assert.equal(N.position(s).y,390);assert.ok(N.position(s).x>180&&N.position(s).x<370);
  assert.ok(N.remainingMeters(s)>0);assert.ok(N.remainingSeconds(s)>0);
  finish(s,p);assert.equal(s.arrivalCount,1);assert.equal(N.remainingMeters(s),0);assert.equal(N.remainingSeconds(s),0);
  assert.deepEqual(N.position(s),{x:590,y:80,heading:0});
  N.advance(s,p,50);assert.equal(s.arrivalCount,1);assert.equal(JSON.stringify(p),before);
});
test('pause and cancel are terminal with no hidden movement or premature arrival',()=>{
  const p=profile(),s=N.createSession(plan(),p.id);N.start(s);N.advance(s,p);N.pause(s);
  const at=N.position(s),distance=s.travelledMeters;N.advance(s,p,1000);
  assert.deepEqual(N.position(s),at);assert.equal(s.travelledMeters,distance);assert.equal(s.arrivalCount,0);
  N.resume(s);N.advance(s,p);assert.ok(s.travelledMeters>distance);N.cancel(s);
  const cancelled=JSON.stringify(s);N.start(s);N.resume(s);N.advance(s,p);assert.equal(s.status,'cancelled');
  assert.equal(s.travelledMeters,JSON.parse(cancelled).travelledMeters);assert.equal(s.arrivalCount,0);
});
test('personalized thresholds announce at 450m and never repeat on a road',()=>{
  const p=profile();p.confirmedMisses['exit-a']=1;
  const r=N.plan({originId:'central',destinationId:'park',profile:p}),s=N.createSession(r,p.id);N.start(s);
  let heard=0,where;
  while(s.edgeIndex===0&&s.status==='running'){
    N.advance(s,p,50);if(s.spoken===r.steps[0].cue+'.'){heard++;where=s.edgeRemainingMeters;}
  }
  assert.equal(heard,1);assert.equal(where,450);assert.equal(p.confirmedMisses['exit-a'],1);
});
test('reroute waits for next node, preserving continuous position and destination',()=>{
  const p=profile(),s=N.createSession(plan(),p.id);N.start(s);N.advance(s,p,200);
  const oldPosition=N.position(s),oldDestination=s.route.destinationId;
  N.queueReroute(s,p,{trafficId:'fast-jam',avoidEdgeId:'exit-a'});
  assert.deepEqual(N.position(s),oldPosition);assert.ok(s.pendingReroute);
  const remainder=s.edgeRemainingMeters;N.advance(s,p,remainder);
  assert.equal(s.rerouteCount,1);assert.equal(s.pendingReroute,null);assert.equal(s.route.destinationId,oldDestination);
  assert.equal(s.route.trafficId,'fast-jam');assert.ok(!s.route.edges.slice(s.edgeIndex).some(e=>e.id==='exit-a'));
  assert.deepEqual(N.position(s),{x:370,y:390,heading:90});finish(s,p);
});
test('reroute from ready state recalculates before departure and preserves origin',()=>{
  const p=profile(),s=N.createSession(plan(),p.id),old=s.route.id;
  N.queueReroute(s,p,{trafficId:'fast-jam'});assert.equal(s.status,'ready');assert.equal(s.route.originId,'home');
  assert.notEqual(s.route.id,old);assert.equal(s.travelledMeters,0);assert.deepEqual(N.position(s),{x:180,y:390,heading:0});
});
test('serialized sessions restore canonical geometry, block corruption, and do not replay speech',()=>{
  const p=profile(),s=N.createSession(plan(),p.id);N.start(s);N.advance(s,p,250);N.pause(s);
  const restored=N.validateSession(N.serializeSession(s));assert.ok(restored);assert.equal(restored.spoken,'');
  assert.deepEqual(N.position(restored),N.position(s));assert.equal(restored.profileId,p.id);
  const mutate=fn=>{const raw=JSON.parse(JSON.stringify(s));fn(raw);return N.validateSession(raw);};
  assert.equal(mutate(raw=>raw.edgeIndex=99),null);assert.equal(mutate(raw=>raw.travelledMeters++),null);
  assert.equal(mutate(raw=>raw.route.edges[0].id='private-road'),null);assert.equal(mutate(raw=>raw.route.edges[1].id='east-park'),null);
  assert.equal(mutate(raw=>raw.status='arrived'),null);assert.equal(mutate(raw=>raw.pendingReroute={trafficId:'unknown',avoidEdgeId:null}),null);
  const canonical=mutate(raw=>raw.route.edges[0].points=[{x:0,y:0}]);assert.ok(canonical);assert.equal(canonical.route.edges[0].points[0].x,180);
});
test('every generated state can be restored including reroute and arrival',()=>{
  const p=profile(),s=N.createSession(plan(),p.id);assert.ok(N.validateSession(s));N.start(s);
  N.queueReroute(s,p,{trafficId:'all-slow'});
  for(let i=0;i<1000&&s.status!=='arrived';i++){assert.ok(N.validateSession(s));N.advance(s,p,50);}
  assert.equal(s.status,'arrived');assert.ok(N.validateSession(s));
});
test('driver mismatch and invalid movement cannot mutate a session',()=>{
  const p=profile(),s=N.createSession(plan(),p.id);N.start(s);
  assert.throws(()=>N.advance(s,profile('guest')),/운전자/);assert.throws(()=>N.queueReroute(s,profile('guest')),/운전자/);
  for(const meters of [0,-1,1001,1.5,NaN,'50'])assert.throws(()=>N.advance(s,p,meters),/이동 거리/);
  assert.equal(s.travelledMeters,0);
});
test('restoration can refresh personalized cue thresholds without accepting fabricated instructions',()=>{
  const p=profile();p.confirmedMisses['exit-a']=1;
  const s=N.createSession(N.plan({originId:'central',destinationId:'park',profile:p}),p.id);
  const raw=JSON.parse(JSON.stringify(s));raw.route.steps[0].cue='<script>untrusted</script>';raw.route.steps[0].leadMeters=99999;
  const restored=N.validateSession(raw,p);assert.ok(restored);
  assert.equal(restored.route.steps[0].leadMeters,450);assert.ok(!restored.route.steps[0].cue.includes('script'));
  assert.equal(N.validateSession(raw,profile('guest')),null);
});
test('queued traffic reroute corrects the upcoming turn before the junction and speaks that cue once',()=>{
  const p=profile(),s=N.createSession(N.plan({originId:'office',destinationId:'station',preference:'time',profile:p}),p.id);
  N.start(s);const oldCue=s.route.steps[0].cue;assert.equal(s.route.edges[0].id,'office-central');
  assert.equal(s.route.steps[0].maneuver,'straight');
  const before=N.position(s);N.queueReroute(s,p,{trafficId:'fast-jam'});
  const corrected=s.route.steps[0].cue;
  assert.notEqual(corrected,oldCue);assert.equal(s.route.steps[0].maneuver,'left');assert.ok(corrected.includes('중앙대로'));
  assert.deepEqual(N.position(s),before);assert.equal(s.spoken,'');
  const queued=s.pendingReroute.edgeIds.slice();let count=0;
  while(s.edgeIndex===0){N.advance(s,p,50);if(s.spoken===corrected+'.')count++;}
  assert.equal(count,1);assert.deepEqual(s.route.edges.slice(s.edgeIndex).map(e=>e.id),queued);
  assert.equal(s.route.steps[0].cue,corrected);assert.equal(s.rerouteCount,1);
});
test('an already announced stale turn is corrected immediately and is not replayed at advance',()=>{
  const p=profile(),s=N.createSession(N.plan({originId:'office',destinationId:'station',profile:p}),p.id);
  N.start(s);N.advance(s,p,250);assert.equal(s.edgeRemainingMeters,300);assert.equal(s.announcedForEdge,true);
  const stale=s.spoken,before=N.position(s);N.queueReroute(s,p,{trafficId:'fast-jam'});
  const corrected=s.route.steps[0].cue+'.';assert.notEqual(corrected,stale);assert.equal(s.spoken,corrected);
  assert.deepEqual(N.position(s),before);N.advance(s,p,50);assert.equal(s.spoken,'');
});
test('queued reroute roundtrip derives the corrected cue from canonical pending roads',()=>{
  const p=profile(),s=N.createSession(N.plan({originId:'office',destinationId:'station',profile:p}),p.id);
  N.start(s);N.advance(s,p,250);N.queueReroute(s,p,{trafficId:'fast-jam'});
  const corrected=s.route.steps[0].cue,pendingIds=s.pendingReroute.edgeIds.slice();
  const raw=JSON.parse(N.serializeSession(s));raw.route.steps[0].cue='직진해서 잘못된 길로 가세요';raw.route.steps[0].maneuver='straight';raw.lastMessage='이전 직진 안내';
  const restored=N.validateSession(raw,p);assert.ok(restored);assert.equal(restored.spoken,'');
  assert.equal(restored.route.steps[0].cue,corrected);assert.equal(restored.route.steps[0].maneuver,'left');
  assert.equal(restored.lastMessage,corrected+'.');assert.deepEqual(restored.pendingReroute.edgeIds,pendingIds);
  assert.deepEqual(N.position(restored),N.position(s));
  raw.pendingReroute.edgeIds=['not-a-canonical-road'];assert.equal(N.validateSession(raw,p),null);
});
