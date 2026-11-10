'use strict';
// Same expectations as Android core GuidanceSessionTest.java (the second half): speed, road familiarity, the
// navigation's detail, the driver's signalling rate, the parked summary, the lowering offer, destinations, memos.
const test = require('node:test');
const assert = require('node:assert/strict');
const C = require('../src/coach.js');
const G = require('../src/coach-guidance.js');

const exitLeft = id => ({ id, type: 'exit-left' });
const fresh = () => G.create(C.createProfile('car', '차량'));
// One clean pass of a signalled turn: the next turn arriving closes it.
function cleanPass(s, id, at) { G.onTurn(s, exitLeft(id), 200, at); G.onSignal(s, 'left', at + 1); G.onTurn(s, exitLeft(id), 50, at + 2); G.onSignal(s, 'off', at + 3); }

test('the decision distance follows the speed and the road: half at 30 km/h, double at 120, 30% more on a new road', () => {
  const g = C.createProfile('g', 'G'), e = exitLeft('e');
  assert.equal(C.decisionMeters(g, e), 300);
  assert.equal(C.decisionMeters(g, e, 120), 600);
  assert.equal(C.decisionMeters(g, e, 30), 150);
  assert.equal(C.decisionMeters(g, e, 10), 150);
  assert.equal(C.decisionMeters(g, e, -1, 'new'), 390);
  assert.equal(C.decisionMeters(g, e, -1, 'familiar'), 300);
  assert.equal(C.decisionMeters(C.createProfile('t', 'T', 'truck'), e, 120, 'new'), 1560);
  const s = fresh();
  C.chooseWeak(s.profile, 'exit-left');
  G.onGear(s, false);
  assert.equal(G.onTurn(s, exitLeft('fast'), 500, 0).event, 'none');
  assert.equal(G.onSpeed(s, 120).event, 'speak');
});
test("the navigation's detail goes into the sentence; under load the sentence is cut to its core", () => {
  const s = fresh();
  C.chooseWeak(s.profile, 'roundabout');
  G.onGear(s, false);
  assert.equal(G.onTurn(s, { id: 'r1', type: 'roundabout', detail: '3시 방향 출구' }, 200, 0).speech, '회전교차로가 곧 나옵니다. 3시 방향 출구입니다. 미리 준비하세요.');
  G.onSpeed(s, 90);
  assert.equal(G.onTurn(s, { id: 'r2', type: 'roundabout', detail: '3시 방향 출구' }, 200, 1000).speech, '회전교차로, 3시 방향 출구.');
  G.onSpeed(s, 50);
  G.onTurn(s, { id: 'r3', type: 'roundabout' }, 200, 2000, 400);
  assert.equal(G.onTurn(s, { id: 'r3', type: 'roundabout' }, 190, 2100, 400).speech, '회전교차로 준비.');
});
test('on a familiar road a weak type stays quiet, a remembered spot does not; a new road is judged earlier', () => {
  const s = fresh();
  C.chooseWeak(s.profile, 'exit-left'); C.addMemo(s.profile, 'known-miss');
  G.onGear(s, false); G.setFamiliarity(s, 'familiar');
  const quiet = G.onTurn(s, exitLeft('f1'), 200, 0);
  assert.equal(quiet.event, 'none'); assert.equal(quiet.decision.reason, 'familiar-road');
  assert.equal(G.onTurn(s, exitLeft('known-miss'), 200, 1000).event, 'speak');
  G.setFamiliarity(s, 'new');
  G.onTurn(s, exitLeft('f2'), 360, 2000);
  assert.equal(G.onTurn(s, exitLeft('f2'), 350, 2100).event, 'speak');
});
test('positions count a road cell once per trip; three trips make it familiar', () => {
  const s = fresh();
  for (let trip = 0; trip < 3; trip++) { G.onGear(s, false); G.onLocation(s, 37.5665, 126.9780); G.onLocation(s, 37.5700, 126.9780); G.onGear(s, true); }
  G.onGear(s, false); G.onLocation(s, 37.5665, 126.9780);
  assert.equal(s.familiarity, 'familiar');
  G.onLocation(s, 37.6000, 126.9780);
  assert.equal(s.familiarity, 'new');
  assert.equal(Object.keys(s.profile.roadCells).length, 3);
});
test('a driver who rarely signals gets no "signal off" sentence for a weak type, but still for a remembered spot', () => {
  const s = fresh();
  C.chooseWeak(s.profile, 'exit-left'); C.addMemo(s.profile, 'spot');
  for (let i = 0; i < 6; i++) C.notePass(s.profile, exitLeft('n' + i), i === 0 ? 300 : 0);
  assert.equal(C.rarelySignals(s.profile), true);
  G.onGear(s, false);
  const rare = G.onTurn(s, exitLeft('w1'), 200, 0);
  assert.equal(rare.event, 'silent'); assert.equal(rare.decision.reason, 'rarely-signals');
  const spot1 = G.onTurn(s, exitLeft('spot'), 210, 1000), spot2 = G.onTurn(s, exitLeft('spot'), 200, 1100);
  assert.ok(spot1.event === 'speak' || spot2.event === 'speak');
});
test('parking after a trip: the miss question first, then the summary of the trip', () => {
  const s = fresh();
  C.chooseWeak(s.profile, 'exit-left');
  G.onGear(s, false);
  cleanPass(s, 'q0', 0); cleanPass(s, 'q1', 10000);
  G.onTurn(s, exitLeft('q2'), 100, 20000);
  assert.equal(G.onReroute(s, 20500).event, 'miss-candidate');
  assert.equal(G.onGear(s, true).event, 'question');
  G.answer(s, true);
  const second = G.afterAnswer(s);
  assert.equal(second.event, 'summary');
  assert.equal(second.speech, '오늘 지켜본 회전 3곳 중 잘 지난 곳 2, 놓침 후보 1입니다.');
  assert.equal(G.afterAnswer(s).event, 'none');
  assert.equal(C.remembers(s.profile, 'q2'), true);
});
test('unsignalled turns are counted for the summary even when nothing was watched, never in front of a passenger', () => {
  let s = fresh();
  G.onGear(s, false);
  G.onTurn(s, exitLeft('u0'), 100, 0); G.onTurn(s, exitLeft('u1'), 900, 1000); G.onTurn(s, exitLeft('u2'), 900, 2000);
  const only = G.onGear(s, true);
  assert.equal(only.event, 'summary');
  assert.equal(only.speech, '방향지시등 없이 지난 회전이 2번 있었어요.');
  s = fresh(); s.passenger = true; G.onGear(s, false); G.onTurn(s, exitLeft('v0'), 100, 0); G.onTurn(s, exitLeft('v1'), 900, 1000);
  assert.equal(G.onGear(s, true).event, 'none');
});
test('three clean passes of a weak type: parked, the offer to lower it comes before the summary; yes lowers it', () => {
  const s = fresh();
  C.chooseWeak(s.profile, 'exit-left');
  G.onGear(s, false);
  for (let i = 0; i < 3; i++) cleanPass(s, 'l' + i, i * 10000);
  G.onTurn(s, exitLeft('l3'), 900, 40000);
  const offer = G.onGear(s, true);
  assert.equal(offer.event, 'lower-question'); assert.equal(offer.speech, C.LOWER_QUESTION);
  assert.equal(s.lowerAsked, 'exit-left'); assert.equal(s.question, C.LOWER_QUESTION);
  G.answerLower(s, true);
  assert.equal(C.isWeak(s.profile, 'exit-left'), false);
  assert.equal(G.afterAnswer(s).event, 'summary');
});
test('the destination ties remembered spots to it, so the next trip there starts with a briefing', () => {
  const keep = C.createProfile('car', '차량');
  let s = G.create(keep);
  assert.equal(G.onDestination(s, '서울병원').event, 'none');
  G.onGear(s, false);
  G.onTurn(s, { id: 'exit_left|강변북로 진출', type: 'exit-left', detail: '강변북로 진출' }, 100, 0);
  G.onReroute(s, 500);
  assert.equal(G.onGear(s, true).event, 'question');
  G.answer(s, true);
  s = G.create(keep);
  const briefing = G.onDestination(s, '서울병원');
  assert.equal(briefing.event, 'briefing');
  assert.equal(briefing.speech, '이번 길에 기억해 둔 곳이 1곳 있습니다. 강변북로 진출 왼쪽 출구입니다.');
  G.onGear(s, false);
  assert.equal(G.onDestination(s, '서울병원').event, 'none');
  assert.equal(G.onDestination(s, '다른 곳').event, 'none');
});
test("a memo by voice or button remembers the current turn with the driver's own words", () => {
  const s = fresh();
  G.onDestination(s, '물류센터'); G.onGear(s, false);
  G.onTurn(s, exitLeft('m1'), 400, 0);
  const memo = G.memo(s, '여기 합류가 어려워');
  assert.equal(memo.event, 'memo'); assert.equal(memo.speech, '기억해 둘게요. 여기 합류가 어려워');
  assert.equal(C.remembers(s.profile, 'm1'), true);
  assert.equal(C.placeLabel(s.profile, 'm1'), '여기 합류가 어려워');
  assert.equal(G.memo(s, '   ').event, 'none');
});
test('everything new survives the snapshot too, and garbage is skipped', () => {
  const full = C.createProfile('f', 'F');
  C.notePass(full, exitLeft('s1'), 0); C.notePass(full, exitLeft('s2'), 250);
  C.addMemo(full, 'exit_left|x'); C.labelPlace(full, 'exit_left|x', 'x 왼쪽 출구'); C.rememberFor(full, '회사', 'exit_left|x');
  C.noteRoad(full, C.cellKey(37.5, 127.0)); C.noteRoad(full, C.cellKey(37.5, 127.0));
  const copy = C.createProfile('f', 'F');
  C.restore(copy, C.snapshot(full));
  assert.equal(copy.signalTurns, 2); assert.equal(copy.signalOnTurns, 1);
  assert.deepEqual(C.placesFor(copy, '회사'), [{ id: 'exit_left|x', label: 'x 왼쪽 출구' }]);
  assert.equal(C.familiarity(copy, C.cellKey(37.5, 127.0)), 'known');
  C.restore(copy, '{"roadCells":{"bad":3,"1,2":-1},"destPlaces":{"":["x"],"a":"no"},"placeLabels":{"y":5},"signalTurns":1,"signalOnTurns":9}');
  assert.equal(Object.keys(copy.roadCells).length, 1);
  assert.equal(copy.signalOnTurns, 1);
  assert.equal(C.placeLabel(copy, 'exit_left|강변북로'), '강변북로 왼쪽 출구');
  assert.equal(C.placeLabel(copy, 'memo|12,34'), '12,34');
});
test('rain and night push the decision distance out; a wheel turned at the exit is not a miss', () => {
  let s = fresh(); C.chooseWeak(s.profile, 'exit-left'); G.onGear(s, false);
  assert.equal(G.onTurn(s, exitLeft('w1'), 350, 0).event, 'none');
  assert.equal(G.onWiper(s, true).event, 'speak');
  s = fresh(); C.chooseWeak(s.profile, 'exit-left'); G.onGear(s, false);
  G.onTurn(s, exitLeft('n1'), 340, 0);
  assert.equal(G.onLowBeam(s, true).event, 'speak');
  assert.equal(C.decisionMeters(s.profile, exitLeft('x'), -1, 'unknown', true, true), 431);
  s = fresh(); G.onGear(s, false);
  G.onTurn(s, exitLeft('t1'), 250, 1000); G.onSteering(s, 60); G.onTurn(s, exitLeft('after'), 900, 2000);
  assert.notEqual(G.onReroute(s, 3000).event, 'miss-candidate'); assert.equal(s.profile.pending.length, 0);
  s = fresh(); G.onGear(s, false);
  G.onTurn(s, exitLeft('t2'), 120, 1000); G.onSteering(s, 70);
  assert.equal(G.onReroute(s, 1500).event, 'none'); assert.equal(s.profile.pending.length, 0);
  s = fresh(); G.onGear(s, false);
  G.onTurn(s, exitLeft('t3'), 250, 1000); G.onSteering(s, 10); G.onTurn(s, exitLeft('after'), 900, 2000);
  assert.equal(G.onReroute(s, 3000).event, 'miss-candidate');
});
test('arrival on the brake or the belt coming off brings the question; the limit and the odometer shape the words; a place near is spoken', () => {
  let s = fresh(); G.onGear(s, false);
  G.onTurn(s, exitLeft('a1'), 100, 0); G.onReroute(s, 1000);
  assert.equal(G.onArrival(s).event, 'none');
  G.onSpeed(s, 0);
  assert.equal(G.onBrake(s, true).event, 'question');
  s = fresh(); G.onGear(s, false); G.onTurn(s, exitLeft('b1'), 100, 0); G.onReroute(s, 1000); G.onSpeed(s, 0);
  assert.equal(G.onSeatBelt(s, false).event, 'question'); assert.equal(s.parked, true);
  s = fresh(); C.chooseWeak(s.profile, 'exit-left'); G.onGear(s, false);
  G.onSpeedLimit(s, 100);
  const fast = G.onTurn(s, { id: 'f1', type: 'exit-left', detail: '강변북로 방면' }, 250, 0);
  assert.equal(fast.event, 'speak'); assert.equal(fast.speech, '왼쪽 출구, 강변북로 방면.');
  G.onTripDistance(s, 42.3); G.onSignal(s, 'left', 100); G.onTurn(s, exitLeft('f2'), 900, 200);
  const ended = G.onGear(s, true);
  assert.equal(ended.event, 'summary'); assert.match(ended.speech, /^오늘 42km를 달렸어요\. 지켜본 회전 1곳/);
  s = fresh(); G.onGear(s, false); G.onLocation(s, 37.5, 127.0);
  G.onTurn(s, exitLeft('p1'), 100, 0); G.onReroute(s, 1000); G.onGear(s, true); G.answer(s, true);
  assert.deepEqual(C.placeCoordOf(s.profile, 'p1'), [37.5, 127.0]);
  const copy = C.createProfile('c', 'C'); C.restore(copy, C.snapshot(s.profile));
  assert.deepEqual(C.placeCoords(copy), { p1: [37.5, 127.0] });
  G.onGear(s, false);
  assert.equal(G.onProximity(s, 'p1').event, 'speak');
  G.onNavigationStatus(s, true, 0);
  assert.equal(G.onProximity(s, 'p1').event, 'none');
});
test('road segments carry the outcome of the turns approached through them; other profiles\' counts stand in where a driver has none', () => {
  const s = fresh(); G.onGear(s, false); G.onLocation(s, 37.5000, 127.0);
  G.onTurn(s, exitLeft('g1'), 500, 0); G.onLocation(s, 37.5010, 127.0); G.onTurn(s, exitLeft('g1'), 100, 200);
  G.onTurn(s, exitLeft('g2'), 2000, 300); G.onReroute(s, 1000); G.onGear(s, true);
  const c1 = C.cellKey(37.5000, 127.0), c2 = C.cellKey(37.5010, 127.0);
  assert.notEqual(c1, c2); assert.equal(C.segment(s.profile, c1), null);
  G.answer(s, true); G.afterAnswer(s);
  assert.deepEqual(C.segment(s.profile, c1), [0, 1, 1]); assert.deepEqual(C.segment(s.profile, c2), [0, 1, 1]);
  assert.equal(G.hardSegment(s), false);
  // A second confirmed miss on the same approach makes it a hard segment, whatever the turn is called.
  G.onGear(s, false); G.onLocation(s, 37.5000, 127.0);
  G.onTurn(s, exitLeft('g1b'), 500, 3000); G.onTurn(s, exitLeft('g3'), 2000, 3100); G.onReroute(s, 3500); G.onGear(s, true); G.answer(s, true); G.afterAnswer(s);
  G.onGear(s, false); G.onLocation(s, 37.5000, 127.0);
  assert.equal(G.hardSegment(s), true);
  const hard = G.onTurn(s, { id: 't9', type: 'turn-right' }, 360, 5000);
  assert.equal(hard.event, 'speak'); assert.equal(hard.speech, '놓치기 쉬운 우회전이에요. 미리 준비하세요.'); assert.equal(hard.decision.limit, 375);
  // Another profile on the same device has no counts of its own there; the shared numbers stand in.
  const other = G.create(C.createProfile('car', '차량', 'truck'));
  G.onGear(other, false); G.onLocation(other, 37.5000, 127.0);
  assert.equal(G.hardSegment(other), false);
  G.setSharedSegments(other, C.sharedSegments([s.profile]));
  assert.equal(G.hardSegment(other), true);
  const told = G.onTurn(other, { id: 't9', type: 'turn-right' }, 700, 0);
  assert.equal(told.event, 'speak'); assert.equal(told.speech, '놓치기 쉬운 우회전이에요. 미리 준비하세요.'); assert.equal(told.decision.limit, 975);
  // One clean pass of their own and this profile goes by its own count; the pass counts in the day's summary.
  G.onTurn(other, { id: 't9', type: 'turn-right' }, 500, 100); G.onSignal(other, 'right', 150); G.onTurn(other, { id: 'n2', type: 'turn-left' }, 3000, 200);
  const end = G.onGear(other, true);
  assert.equal(end.event, 'summary'); assert.equal(end.speech, '오늘 지켜본 회전 1곳 중 잘 지난 곳 1, 놓침 후보 0입니다.');
  G.onGear(other, false); G.onLocation(other, 37.5000, 127.0);
  assert.deepEqual(C.segment(other.profile, c1), [1, 0, 0]); assert.equal(G.hardSegment(other), false);
  const kept = C.createProfile('k', 'K'); C.restore(kept, C.snapshot(s.profile));
  assert.deepEqual(kept.segments, { [c1]: [0, 2, 2], [c2]: [0, 1, 1] });
  C.clearMemory(kept); assert.deepEqual(kept.segments, {});
  // "No" to the question leaves the road untouched; a pass without the signal is counted as such; no position, no segment.
  const chose = fresh(); G.onGear(chose, false); G.onLocation(chose, 37.6, 127.1); G.onTurn(chose, exitLeft('x1'), 100, 0); G.onReroute(chose, 500); G.onGear(chose, true); G.answer(chose, false);
  assert.deepEqual(chose.profile.segments, {});
  const quiet = fresh(); G.onGear(quiet, false); G.onLocation(quiet, 37.7, 127.2); G.onTurn(quiet, exitLeft('q1'), 300, 0); G.onTurn(quiet, exitLeft('q2'), 3000, 100); G.onGear(quiet, true);
  assert.deepEqual(C.segment(quiet.profile, C.cellKey(37.7, 127.2)), [1, 0, 1]);
  const blind = fresh(); G.onGear(blind, false); G.onTurn(blind, exitLeft('b9'), 300, 0); G.onTurn(blind, exitLeft('b10'), 3000, 100); G.onGear(blind, true);
  assert.deepEqual(blind.profile.segments, {}); assert.equal(G.hardSegment(blind), false);
});
