'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const RM = require('../src/engine.js');
const T = require('../src/traffic.js');

test('clear traffic preserves normal recommendation but a jam reranks routes', () => {
  const p = RM.createProfile('me', '나의 운전');
  assert.equal(T.recommend(p, 'clear', RM), RM.routes.fast);
  assert.equal(T.estimateMinutes(RM.routes.fast, 'fast-jam'), 30);
  assert.equal(T.estimateMinutes(RM.routes.easy, 'fast-jam'), 23);
  assert.equal(T.recommend(p, 'fast-jam', RM), RM.routes.easy);
  assert.equal(T.recommend(p, 'easy-jam', RM), RM.routes.fast);
});

test('personal burden remains part of traffic recommendation', () => {
  const p = RM.createProfile('me', '나의 운전');
  p.avoid.push('merge-c');
  assert.equal(T.recommend(p, 'clear', RM), RM.routes.easy);
  assert.equal(T.recommend(p, 'easy-jam', RM), RM.routes.fast);
});

test('all slow scenario has explicit route delays and speeds', () => {
  assert.equal(T.estimateMinutes('fast', 'all-slow'), 28);
  assert.equal(T.estimateMinutes('easy', 'all-slow'), 27);
  assert.equal(T.roadStatus('fast', 'all-slow').status, 'jam');
  assert.equal(T.roadStatus('easy', 'all-slow').speedKph, 25);
  assert.equal(T.roadStatus('fast', 'clear').label, '원활');
});

test('unknown scenario and serialized scenario metadata safely resolve to known data', () => {
  assert.equal(T.getScenario('missing'), T.scenarios.clear);
  assert.equal(T.getScenario('__proto__'), T.scenarios.clear);
  assert.equal(T.getScenario(null), T.scenarios.clear);
  assert.equal(T.getScenario({ id: 'fast-jam', routes: { fast: { delayMinutes: -999 } } }), T.scenarios['fast-jam']);
  assert.throws(() => T.estimateMinutes('unknown', 'clear'), /Unknown simulation route/);
});

test('traffic calculations never change driver mistakes, preferences or trip state', () => {
  const p = RM.createProfile('me', '나의 운전');
  p.confirmedMisses['exit-a'] = 2;
  const t = RM.createTrip(p, RM.routes.fast);
  const before = JSON.stringify({ p, t });
  for (const s of Object.values(T.scenarios)) {
    T.recommend(p, s, RM); T.explain(p, RM.routes.fast, s, RM); T.remainingMinutes(t, s);
  }
  assert.equal(JSON.stringify({ p, t }), before);
});

test('remaining ETA decreases with progress and switches to the recovery route baseline', () => {
  const p = RM.createProfile('me', '나의 운전');
  const t = RM.createTrip(p, RM.routes.fast, true);
  assert.equal(T.remainingMinutes(t, 'fast-jam'), 30);
  for (let i = 0; i < 6; i++) RM.tick(t, p);
  assert.ok(T.remainingMinutes(t, 'fast-jam') < 30);
  while (t.activeRouteId !== 'recovery') RM.tick(t, p);
  assert.equal(T.remainingMinutes(t, 'fast-jam'), 9);
  while (t.status !== 'finished') RM.tick(t, p);
  assert.equal(T.remainingMinutes(t, 'fast-jam'), 0);
});

test('terminal or malformed trips cannot expose a negative or invalid ETA', () => {
  assert.equal(T.remainingMinutes(null, 'clear'), 0);
  assert.equal(T.remainingMinutes({ status: 'running', activeRouteId: 'unknown' }, 'clear'), 0);
  const t = RM.createTrip(RM.createProfile('me', '나의 운전'), RM.routes.fast);
  t.remaining = Infinity; assert.equal(T.remainingMinutes(t, 'clear'), 0);
  t.remaining = 100000; assert.equal(T.remainingMinutes(t, 'clear'), 18);
  t.remaining = -10; assert.ok(T.remainingMinutes(t, 'clear') >= 0);
  t.status = 'cancelled'; assert.equal(T.remainingMinutes(t, 'clear'), 0);
});

test('explanations and snapshots explicitly describe synthetic traffic', () => {
  const p = RM.createProfile('me', '나의 운전');
  assert.match(T.explain(p, RM.routes.fast, 'fast-jam', RM), /12분/);
  assert.match(T.explain(p, RM.routes.fast, 'fast-jam', RM), /편안한 길/);
  const data = T.snapshotTraffic('fast-jam');
  assert.equal(data.source, 'simulation');
  assert.equal(data.id, 'fast-jam');
  assert.ok(Number.isFinite(Date.parse(data.capturedAt)));
  assert.deepEqual(JSON.parse(JSON.stringify(data)), data);
});
