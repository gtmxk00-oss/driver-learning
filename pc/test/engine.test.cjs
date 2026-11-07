'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const RM = require('../src/engine.js');
const profile = (id = 'me') => RM.createProfile(id, id === 'me' ? '나의 운전' : '새 운전자');
function finish(trip, driver) {
  for (let i = 0; i < 100 && trip.status !== 'finished'; i++) RM.tick(trip, driver);
  assert.equal(trip.status, 'finished');
  return trip;
}

test('new driver thresholds match the Android app', () => {
  const p = profile();
  assert.equal(RM.leadMeters(p, RM.routes.fast.segments[0]), 300);
  assert.equal(RM.leadMeters(p, RM.routes.fast.segments[1]), 450);
  assert.equal(RM.recommend(p), RM.routes.fast);
});
test('one confirmed miss produces an earlier, single announcement on the next trip', () => {
  const p = profile();
  const first = finish(RM.createTrip(p, RM.routes.fast, true), p);
  assert.equal(RM.misses(p, 'exit-a'), 0);
  assert.equal(RM.review(first, p, true), true);
  assert.equal(RM.leadMeters(p, RM.routes.fast.segments[0]), 450);
  const second = RM.createTrip(p, RM.routes.fast);
  const cue = RM.routes.fast.segments[0].cue + '.';
  let count = 0, announcedAt;
  for (let i = 0; i < 12; i++) {
    RM.tick(second, p);
    if (second.speech === cue) { count++; announcedAt = second.remaining; }
  }
  assert.equal(count, 1);
  assert.equal(announcedAt, 400); // 100m discrete ticks cross the 450m threshold at 400m.
});
test('repeated history has bounded lead distance and independent drivers', () => {
  const a = profile(), b = profile('guest');
  a.confirmedMisses['exit-a'] = 100;
  assert.equal(RM.leadMeters(a, RM.routes.fast.segments[0]), 900);
  assert.equal(RM.leadMeters(b, RM.routes.fast.segments[0]), 300);
});
test('PC 50m steps announce exactly at the personalized 450m threshold', () => {
  const p = profile(); p.confirmedMisses['exit-a'] = 1;
  const t = RM.createTrip(p, RM.routes.fast);
  const cue = RM.routes.fast.segments[0].cue + '.';
  let count = 0, announcedAt;
  for (let i = 0; i < 24; i++) {
    RM.tick(t, p, 50);
    assert.ok(RM.validateTrip(t, p));
    if (t.speech === cue) { count++; announcedAt = t.remaining; }
  }
  assert.equal(count, 1); assert.equal(announcedAt, 450);
  for (const step of [0, -50, 1, 75, 101, NaN, '50'])
    assert.throws(() => RM.tick(t, p, step), /50 or 100/);
});
test('review is idempotent, unavailable during driving, and driver-bound', () => {
  const p = profile(), t = RM.createTrip(p, RM.routes.fast, true);
  assert.equal(RM.review(t, p, true), false);
  finish(t, p);
  assert.equal(RM.review(t, profile('guest'), true), false);
  assert.equal(RM.review(t, p, true), true);
  assert.equal(RM.review(t, p, true), false);
  assert.equal(RM.misses(p, 'exit-a'), 1);
});
test('intentional route change does not train mistakes', () => {
  const p = profile(), t = finish(RM.createTrip(p, RM.routes.fast, true), p);
  assert.equal(RM.review(t, p, false), true);
  assert.equal(RM.misses(p, 'exit-a'), 0);
});
test('missed exit transitions to a different virtual route and completes once', () => {
  const p = profile(), t = RM.createTrip(p, RM.routes.fast, true);
  for (let i = 0; i < 12; i++) RM.tick(t, p);
  assert.equal(t.status, 'rerouting');
  assert.equal(t.message, '다음 경로로 안내하겠습니다.');
  assert.deepEqual(t.pending, ['exit-a']);
  for (let i = 0; i < 3; i++) RM.tick(t, p);
  assert.equal(t.activeRouteId, 'recovery');
  assert.equal(t.remaining, 700);
  finish(t, p);
  assert.equal(t.tickCount, 31);
  assert.equal(p.completedTrips, 1);
  assert.equal(RM.metersLeft(t), 0);
  RM.tick(t, p);
  assert.equal(p.completedTrips, 1);
});
test('easy route never receives an injected exit mistake', () => {
  const p = profile(), t = finish(RM.createTrip(p, RM.routes.easy, true), p);
  assert.deepEqual(t.pending, []);
  assert.equal(t.activeRouteId, 'easy');
  assert.equal(t.tickCount, 34);
});
test('cancelling is terminal and cannot train a partial drive', () => {
  const p = profile(), t = RM.createTrip(p, RM.routes.fast, true);
  RM.tick(t, p); RM.cancel(t); const remaining = t.remaining;
  RM.tick(t, p);
  assert.equal(t.status, 'cancelled'); assert.equal(t.remaining, remaining);
  assert.equal(RM.review(t, p, true), false); assert.equal(p.completedTrips, 0);
});
test('merge avoidance changes the recommendation through the same burden formula', () => {
  const p = profile();
  assert.equal(RM.burden(p, RM.routes.fast), 6);
  p.avoid.push('merge-c'); p.notes['merge-c'] = '여기 합류하기 어려워';
  assert.equal(RM.burden(p, RM.routes.fast), 18);
  assert.equal(RM.recommend(p), RM.routes.easy);
  p.avoid = [];
  assert.equal(RM.recommend(p), RM.routes.fast);
});
test('profile restoration strips unknown and unsafe data while retaining valid records', () => {
  const p = RM.validateProfile({
    id: 'me', name: '<script>', confirmedMisses: { 'exit-a': 1000, 'fork-b': -1, unknown: 8 },
    avoid: ['merge-c', 'unknown', 'merge-c'], notes: { 'merge-c': ' x '.repeat(100), unknown: 'bad' },
    completedTrips: 7
  }, 'me', '나의 운전');
  assert.equal(p.name, '나의 운전'); assert.deepEqual(p.confirmedMisses, { 'exit-a': 100 });
  assert.deepEqual(p.avoid, ['merge-c']); assert.equal(p.notes['merge-c'].length, 200);
  assert.equal(p.completedTrips, 7);
  assert.deepEqual(RM.validateProfile({ id: 'guest', confirmedMisses: { 'exit-a': 4 } }, 'me', '나'), RM.createProfile('me', '나'));
  assert.equal(RM.validateProfile(null, 'me', '나').completedTrips, 0);
});
test('pending review survives JSON round trip without replaying speech or losing ownership', () => {
  const p = profile(), t = finish(RM.createTrip(p, RM.routes.fast, true), p);
  const savedP = RM.validateProfile(JSON.parse(JSON.stringify(p)), p.id, p.name);
  const savedT = RM.validateTrip(JSON.parse(JSON.stringify(t)), savedP);
  assert.ok(savedT); assert.equal(savedT.speech, ''); assert.equal(savedT.reviewed, false);
  assert.equal(RM.review(savedT, savedP, true), true);
  assert.equal(RM.misses(savedP, 'exit-a'), 1);
  assert.equal(RM.validateTrip(savedT, profile('guest')), null);
});
test('all generated states are restorable, malformed trip states are rejected', () => {
  const p = profile(), t = RM.createTrip(p, RM.routes.fast, true);
  assert.ok(RM.validateTrip(t, p));
  for (let i = 0; i < 40; i++) { RM.tick(t, p); assert.ok(RM.validateTrip(t, p)); }
  for (const mutation of [
    { activeRouteId: '__proto__' }, { routeId: 'unknown' }, { index: -1 }, { index: 99 },
    { remaining: 99999 }, { remaining: 42 }, { status: 'teleporting' }, { profileId: 'guest' },
    { pending: ['unknown'] }, { remaining: NaN }, { tickCount: -1 }, { reviewed: 'yes' }
  ]) assert.equal(RM.validateTrip({ ...t, ...mutation }, p), null);
  assert.equal(RM.validateTrip(t, null), null);
  const rerouting = RM.createTrip(p, RM.routes.fast, true);
  for (let i = 0; i < 12; i++) RM.tick(rerouting, p);
  assert.equal(RM.validateTrip({ ...rerouting, announced: false }, p), null);
  const skippedExit = { ...RM.createTrip(p, RM.routes.fast, true), index: 1, remaining: 900 };
  assert.equal(RM.validateTrip(skippedExit, p), null);
  const fakeRecovery = { ...RM.createTrip(p, RM.routes.easy), status: 'cancelled', recoveryTicks: 2 };
  assert.equal(RM.validateTrip(fakeRecovery, p), null);
});
test('tick refuses to mutate a trip with another driver profile', () => {
  const p = profile(), t = RM.createTrip(p, RM.routes.fast);
  assert.throws(() => RM.tick(t, profile('guest')), /another driver/);
  assert.equal(t.tickCount, 0);
});
