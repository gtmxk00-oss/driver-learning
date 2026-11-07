'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const C = require('../src/coach.js');
const G = require('../src/coach-guidance.js');

const exitLeft = id => ({ id, type: 'exit-left' });
const fresh = () => G.create(C.createProfile('car', '차량'));

test('a reroute right after passing an exit with the signal off is a miss, and parking asks about it', () => {
  const s = fresh();
  assert.equal(G.onTurn(s, exitLeft('e1'), 900, 0).event, 'none');
  G.onTurn(s, exitLeft('e1'), 200, 5000);
  G.onGear(s, false);
  assert.equal(G.onTurn(s, exitLeft('next'), 1500, 8000).event, 'none');
  assert.equal(G.onReroute(s, 9000).event, 'miss-candidate');
  assert.equal(s.question, null);
  assert.equal(G.onGear(s, true).event, 'question');
  assert.equal(G.answer(s, true).confirmed, true);
  assert.equal(C.remembers(s.profile, 'e1'), true);
});
test('saying no records nothing', () => {
  const s = fresh();
  G.onTurn(s, exitLeft('e2'), 100, 0); G.onGear(s, false);
  G.onReroute(s, 1000); G.onGear(s, true); G.answer(s, false);
  assert.equal(C.remembers(s.profile, 'e2'), false);
});
test('two confirmed misses make the type weak; a new road gets one sentence unless the driver signalled', () => {
  const s = fresh();
  for (const id of ['a', 'b']) { G.onGear(s, false); G.onTurn(s, exitLeft(id), 100, 0); G.onReroute(s, 1000); G.onGear(s, true); G.answer(s, true); }
  assert.equal(C.isWeak(s.profile, 'exit-left'), true);
  G.onGear(s, false);
  assert.equal(G.onTurn(s, exitLeft('new'), 800, 3000).event, 'none');
  const spoke = G.onTurn(s, exitLeft('new'), 400, 4000);
  assert.equal(spoke.event, 'speak');
  assert.match(spoke.speech, /왼쪽 출구가 곧 나옵니다/);
  assert.equal(G.onTurn(s, exitLeft('new'), 300, 5000).event, 'none');
  G.onTurn(s, exitLeft('quiet'), 700, 6000);
  G.onSignal(s, 'left', 6500);
  assert.equal(G.onTurn(s, exitLeft('quiet'), 400, 7000).event, 'silent');
  G.onSignal(s, 'off', 7500);
  assert.equal(G.onTurn(s, exitLeft('quiet'), 300, 8000).event, 'silent');
});
test('a late reroute, or one with the signal on, is not blamed on the turn', () => {
  let s = fresh();
  G.onGear(s, false); G.onTurn(s, exitLeft('x'), 100, 0); G.onTurn(s, exitLeft('y'), 2000, 1000);
  assert.equal(G.onReroute(s, 1000 + G.MISS_WINDOW_MS + 1).event, 'none');
  s = fresh();
  G.onGear(s, false); G.onSignal(s, 'left', 0); G.onTurn(s, exitLeft('z'), 100, 0);
  assert.equal(G.onReroute(s, 500).event, 'none');
});
test('clean passes of a weak type are reported and accumulate', () => {
  const s = fresh();
  C.chooseWeak(s.profile, 'exit-left');
  G.onGear(s, false);
  for (let i = 0; i < 3; i++) { G.onTurn(s, exitLeft('p' + i), 200, i * 10000); G.onSignal(s, 'left', i * 10000 + 1); G.onTurn(s, exitLeft('p' + i), 50, i * 10000 + 2); G.onSignal(s, 'off', i * 10000 + 3); }
  assert.equal(G.onTurn(s, exitLeft('p3'), 900, 40000).event, 'passed');
  assert.ok(s.profile.passStreak['exit-left'] >= 2);
});
test('caution points use the narrow-road rule and ending the route clears the turn', () => {
  const s = fresh();
  C.chooseWeak(s.profile, 'narrow-road');
  G.onGear(s, false);
  assert.equal(G.onCaution(s, 'narrow-1', 250, 0).event, 'speak');
  assert.notEqual(G.onNavigationStatus(s, false, 1000).event, 'speak');
  assert.equal(s.current, null);
});
test('memory survives a restart through the snapshot, and garbage is ignored', () => {
  const kept = C.createProfile('k', 'K', 'taxi');
  C.chooseWeak(kept, 'roundabout'); C.noteMiss(kept, exitLeft('exit_left|강변북로 진출')); C.review(kept, true); C.notePass(kept, exitLeft('p'), 420);
  const back = C.createProfile('k', 'K', 'taxi');
  C.restore(back, C.snapshot(kept));
  assert.equal(C.isWeak(back, 'roundabout'), true);
  assert.equal(C.remembers(back, 'exit_left|강변북로 진출'), true);
  assert.equal(C.misses(back, 'exit-left'), 1);
  assert.equal(back.habitMeters, 420);
  C.restore(back, '{"chosenWeak":["nope"],"places":[3],"typeMisses":{"exit-left":-5}}');
  assert.equal(back.places.length, 1);
  C.restore(back, 'not json');
});
test('passengers change the sentence and hold the question', () => {
  const s = fresh();
  s.passenger = true;
  C.chooseWeak(s.profile, 'exit-left');
  G.onGear(s, false);
  assert.equal(G.onTurn(s, exitLeft('t'), 120, 0).speech, '왼쪽 출구 준비하세요.');
  G.onReroute(s, 500);
  assert.equal(G.onGear(s, true).event, 'none');
  s.passenger = false;
  assert.equal(G.onGear(s, true).event, 'question');
});
