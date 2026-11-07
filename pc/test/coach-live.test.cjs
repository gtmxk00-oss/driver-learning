'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const C = require('../src/coach.js');
const L = require('../src/coach-live.js');

// One degree of latitude is about 111.2 km; moving north by d degrees is a clean distance.
const north = (meters, base = { lat: 37.5, lon: 127 }) => ({ lat: base.lat + meters / 111195, lon: base.lon });
const fix = (at, position, extra = {}) => ({ lat: position.lat, lon: position.lon, accuracy: 8, timestamp: at, ...extra });
const session = (type = 'general', points = []) => L.createSession(C.createProfile('live', '실제 위치', type), points);

test('distance follows the great circle', () => {
  assert.ok(Math.abs(L.distanceMeters({ lat: 0, lon: 0 }, { lat: 1, lon: 0 }) - 111195) < 2);
  assert.equal(L.distanceMeters({ lat: 37.5, lon: 127 }, { lat: 37.5, lon: 127 }), 0);
});
test('malformed and out-of-order fixes are dropped', () => {
  const s = session();
  for (const bad of [null, { lat: 91, lon: 0, accuracy: 5, timestamp: 1 }, { lat: 0, lon: 0, accuracy: -1, timestamp: 1 }, { lat: 0, lon: 0, accuracy: 5 }])
    assert.equal(L.observe(s, bad).accepted, false);
  assert.equal(L.observe(s, fix(5000, north(0))).accepted, true);
  assert.equal(L.observe(s, fix(5000, north(10))).reason, 'stale');
  assert.equal(s.last.timestamp, 5000);
});
test('speed is derived from two trusted fixes when the receiver reports none', () => {
  const s = session();
  L.observe(s, fix(0, north(0)));
  const seen = L.observe(s, fix(10000, north(111.195)));
  assert.ok(Math.abs(seen.speed - 11.1195) < 0.01);
  assert.equal(seen.moving, true);
  assert.equal(L.observe(s, fix(20000, north(200), { speed: 0.5 })).speed, 0.5);
});
test('standing still for eight seconds counts as parked', () => {
  const s = session();
  L.observe(s, fix(0, north(0), { speed: 0 }));
  assert.equal(L.status(s, 5000).parked, false);
  assert.equal(L.status(s, 8000).parked, true);
  assert.equal(L.status(s, 30000).fresh, false);
  assert.equal(L.status(s, 30000).parked, false);
  L.observe(s, fix(31000, north(50), { speed: 5 }));
  assert.equal(L.status(s, 31000).moving, true);
  assert.equal(L.status(s, 40000).parked, false);
});
test('remember needs a fresh trusted fix and reuses a point within 30 m', () => {
  const s = session();
  assert.equal(L.remember(s, 0).reason, 'no-fix');
  L.observe(s, fix(1000, north(0), { accuracy: 150 }));
  assert.equal(L.remember(s, 1000).reason, 'poor-accuracy');
  L.observe(s, fix(2000, north(0)));
  assert.equal(L.remember(s, 2000 + L.FRESH_MS + 1).reason, 'stale');
  const first = L.remember(s, 2000);
  assert.equal(first.merged, false);
  assert.equal(first.point.label, '지점 1');
  L.observe(s, fix(3000, north(20)));
  assert.equal(L.remember(s, 3000).merged, true);
  L.observe(s, fix(4000, north(100)));
  assert.equal(L.remember(s, 4000).point.label, '지점 2');
  assert.equal(s.points.length, 2);
  assert.equal(C.remembers(s.profile, s.points[0].id), true);
});
test('a remembered point is announced once per approach, only while moving and trusted', () => {
  const s = session();
  L.observe(s, fix(0, north(0)));
  L.remember(s, 0);
  assert.equal(L.observe(s, fix(1000, north(1000), { speed: 10 })).decision.reason, 'too-far');
  assert.equal(L.observe(s, fix(2000, north(250), { speed: 10, accuracy: 200 })).decision.reason, 'poor-accuracy');
  assert.equal(L.observe(s, fix(3000, north(250), { speed: 0 })).decision.reason, 'not-moving');
  const said = L.observe(s, fix(4000, north(250), { speed: 10 }));
  assert.equal(said.decision.action, 'speak');
  assert.equal(said.decision.speech, '헷갈렸던 지점입니다. 미리 확인하세요.');
  assert.equal(L.observe(s, fix(5000, north(100), { speed: 10 })).decision.reason, 'already-said');
  L.observe(s, fix(6000, north(400), { speed: 10 }));
  assert.equal(L.observe(s, fix(7000, north(200), { speed: 10 })).decision.reason, 'already-said');
  L.observe(s, fix(8000, north(500), { speed: 10 }));
  assert.equal(L.observe(s, fix(9000, north(200), { speed: 10 })).decision.action, 'speak');
});
test('the driver type sets the reminder distance', () => {
  const s = session('truck');
  assert.equal(L.limitMeters(s), 600);
  L.observe(s, fix(0, north(0)));
  L.remember(s, 0);
  assert.equal(L.observe(s, fix(1000, north(550), { speed: 10 })).decision.action, 'speak');
  L.setDriverType(s, 'general');
  assert.equal(L.limitMeters(s), 300);
  assert.throws(() => L.setDriverType(s, 'plane'), TypeError);
});
test('forgetting clears the points and the profile memory together', () => {
  const s = session();
  L.observe(s, fix(0, north(0)));
  const { point } = L.remember(s, 0);
  L.forget(s);
  assert.equal(s.points.length, 0);
  assert.equal(C.remembers(s.profile, point.id), false);
  assert.equal(L.observe(s, fix(1000, north(10), { speed: 10 })).decision.reason, 'no-points');
});
test('saved points come back as they were', () => {
  const s = session();
  L.observe(s, fix(0, north(0)));
  L.remember(s, 0);
  const copy = session('general', L.serialize(s));
  assert.deepEqual(L.serialize(copy), L.serialize(s));
  assert.equal(C.remembers(copy.profile, s.points[0].id), true);
});
