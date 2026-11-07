'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),T=require('../src/road-tracker.js');
const now=Date.UTC(2026,10,4,0,0,0);
const route=()=>({id:'road',distanceMeters:2000,durationSeconds:200,geometry:[[127,37],[127.01,37],[127.01,37.01]],steps:[
  {id:'depart',name:'첫 도로',distanceMeters:900,durationSeconds:90,cue:'출발합니다',maneuver:{type:'depart',modifier:'',location:[127,37]}},
  {id:'turn',name:'다음 도로',distanceMeters:1100,durationSeconds:110,cue:'다음 교차로에서 좌회전하세요',maneuver:{type:'turn',modifier:'left',location:[127.01,37]}},
  {id:'arrive',name:'목적지',distanceMeters:0,durationSeconds:0,cue:'목적지 도착',maneuver:{type:'arrive',modifier:'',location:[127.01,37.01]}}
]});
const fix=(lon,lat,t=0,accuracy=10)=>({lon,lat,accuracy,timestamp:now+t*1000});
function update(s,lon,lat,t=0,accuracy=10){return T.update(s,fix(lon,lat,t,accuracy),now+t*1000);}
test('create clones the route and targets the upcoming maneuver rather than departure',()=>{
  const r=route(),s=T.create(r);assert.equal(s.status,'waiting');assert.equal(s.nextStepIndex,1);assert.equal(s.nextManeuver.type,'turn');
  assert.equal(s.nextCue,'다음 교차로에서 좌회전하세요');assert.ok(s.nextDistanceMeters>800);
  r.geometry[0][0]=0;assert.equal(s.route.geometry[0][0],127);
});
test('GPS projection tracks the road geometry and scales remaining distance and ETA',()=>{
  const s=T.create(route());update(s,127.005,37);
  assert.equal(s.status,'tracking');assert.ok(Math.abs(s.snapped.lon-127.005)<1e-6);assert.ok(s.distanceFromRoute<1);
  assert.ok(s.remainingMeters<1600&&s.remainingMeters>1450);assert.ok(Math.abs(s.remainingSeconds-s.remainingMeters/10)<1e-6);
  assert.ok(s.nextDistanceMeters>400&&s.nextDistanceMeters<500);
});
test('turn cue is issued once and advances only after passing the turn checkpoint',()=>{
  const s=T.create(route());update(s,127.0075,37);assert.equal(s.spoken,'다음 교차로에서 좌회전하세요');
  update(s,127.008,37,2);assert.equal(s.spoken,'');assert.equal(s.nextStepIndex,1);
  update(s,127.01,37.0002,6);assert.equal(s.nextStepIndex,2);assert.equal(s.nextManeuver.type,'arrive');
});
test('poor accuracy and stale fixes cannot advance or trigger arrival',()=>{
  const s=T.create(route());update(s,127.0099,37.0099,0,150);assert.equal(s.status,'poor-accuracy');assert.equal(s.progressMeters,0);assert.equal(s.arrivalCount,0);
  T.update(s,fix(127.01,37.01,1),now+30000);assert.equal(s.status,'stale');assert.equal(s.progressMeters,0);assert.equal(s.spoken,'');
});
test('duplicate and out of order fixes do not move the route or replay speech',()=>{
  const s=T.create(route());update(s,127.0075,37,2);const at=s.progressMeters;assert.ok(s.spoken);
  update(s,127.009,37,2);assert.equal(s.progressMeters,at);assert.equal(s.spoken,'');
  update(s,127.009,37,1);assert.equal(s.progressMeters,at);assert.equal(s.reason,'duplicate-or-old-fix');
  T.update(s,fix(127.0075,37,2),now+18000);assert.equal(s.status,'stale');assert.equal(s.progressMeters,at);
});
test('a deviation needs three distinct fresh accurate fixes and does not advance progress',()=>{
  const s=T.create(route());update(s,127.005,37.002);assert.equal(s.offRouteCount,1);assert.equal(s.status,'tracking');
  update(s,127.005,37.002,1);assert.equal(s.offRouteCount,2);assert.equal(s.status,'tracking');
  update(s,127.005,37.002,2);assert.equal(s.status,'offroute');assert.equal(s.progressMeters,0);assert.equal(s.spoken,'');
  update(s,127.005,37.002,3,120);assert.equal(s.offRouteCount,0);assert.equal(s.status,'poor-accuracy');
});
test('an impossible physical jump and a distant parallel route segment do not skip guidance',()=>{
  const s=T.create(route());update(s,127,37);update(s,127.01,37.01,1);
  assert.equal(s.status,'poor-accuracy');assert.equal(s.reason,'implausible-jump');assert.equal(s.progressMeters,0);assert.equal(s.arrivalCount,0);
  const loop={distanceMeters:4000,durationSeconds:400,geometry:[[127,37],[127.02,37],[127.02,37.0001],[127,37.0001]],steps:[]};
  const t=T.create(loop);update(t,127.002,37);const before=t.progressMeters;update(t,127.0021,37.0001,1);
  assert.ok(Math.abs(t.progressMeters-before)<100);assert.ok(t.remainingMeters>3000);
});
test('being near a repeated endpoint in the middle of a route cannot cause early arrival',()=>{
  const loop={distanceMeters:3000,durationSeconds:300,geometry:[[127,37],[127.01,37],[127.01,37.01],[127,37]],steps:[]};
  const s=T.create(loop);update(s,127,37);update(s,127,37,1);assert.equal(s.arrivalCount,0);assert.equal(s.status,'tracking');assert.ok(s.remainingMeters>2900);
});
test('arrival requires two confident distinct fixes, happens once, and stops repeated speech',()=>{
  const s=T.create(route());update(s,127.01,37.0099,0);assert.equal(s.status,'tracking');assert.equal(s.arrivalCount,0);
  update(s,127.01,37.00995,1);assert.equal(s.status,'arrived');assert.equal(s.arrivalCount,1);assert.equal(s.remainingMeters,0);assert.equal(s.spoken,'목적지에 도착했습니다.');
  update(s,127.01,37.01,2);assert.equal(s.spoken,'');assert.equal(s.arrivalCount,1);
});
test('accuracy over 30m cannot confirm arrival even when route position is close',()=>{
  const s=T.create(route());update(s,127.01,37.0099,0,50);update(s,127.01,37.00995,1,50);assert.notEqual(s.status,'arrived');assert.equal(s.arrivalCount,0);
});
test('stop is terminal and tracker never reads fake-road learning or mutates profile',()=>{
  const p={confirmedMisses:{'exit-a':99},avoid:['merge-c'],completedTrips:7},copy=JSON.stringify(p);
  const s=T.create(route(),p);assert.equal(s.preferredLeadMeters,300);update(s,127.005,37);T.stop(s);const at=s.progressMeters;
  update(s,127.01,37.01,30);assert.equal(s.status,'stopped');assert.equal(s.progressMeters,at);assert.equal(s.spoken,'');assert.equal(JSON.stringify(p),copy);
  assert.equal(s.position,null);assert.equal(s.snapped,null);
  assert.equal(T.create(route(),{preferredLeadMeters:450}).preferredLeadMeters,450);
});
test('invalid route, coordinate and timestamp data never produces fabricated movement',()=>{
  assert.throws(()=>T.create({distanceMeters:1,durationSeconds:1,geometry:[[0,0],[NaN,1]]}),/경로/);
  const s=T.create(route());T.update(s,{lat:NaN,lon:127,accuracy:10,timestamp:now},now);assert.equal(s.status,'poor-accuracy');assert.equal(s.progressMeters,0);
  T.update(s,fix(127,37,20),now);assert.equal(s.status,'stale');assert.equal(s.reason,'future-fix');assert.equal(s.arrivalCount,0);
});
