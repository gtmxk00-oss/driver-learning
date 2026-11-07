(function (root, factory) {
  const live = factory(typeof module === 'object' && module.exports ? require('./coach.js') : root.RMCoach);
  if (typeof module === 'object' && module.exports) module.exports = live;
  else root.RMCoachLive = live;
})(typeof globalThis !== 'undefined' ? globalThis : this, function (C) {
  'use strict';

  // Same rules as Android core LiveCoach.java. Change both together.
  // Only position, speed and accuracy are real. There is no turn signal and no navigation route yet,
  // so live mode remembers points the driver marks and reminds them on the next approach, nothing more.
  const EARTH_RADIUS = 6371008.8, TRUSTED_ACCURACY = 100, MERGE_METERS = 30, FRESH_MS = 15000;
  const MOVING_SPEED = 2, PARKED_SPEED = 0.8, PARKED_AFTER_MS = 8000, RELEASE_FACTOR = 1.5;
  const finite = Number.isFinite;

  function distanceMeters(a, b) {
    const rad = d => d * Math.PI / 180;
    const dLat = rad(b.lat - a.lat), dLon = rad(b.lon - a.lon);
    const s = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLon / 2) ** 2;
    return 2 * EARTH_RADIUS * Math.asin(Math.min(1, Math.sqrt(s)));
  }
  // Takes what a receiver reports; anything malformed is dropped rather than guessed.
  function validFix(fix) {
    return Boolean(fix) && finite(fix.lat) && finite(fix.lon) && Math.abs(fix.lat) <= 90 && Math.abs(fix.lon) <= 180
      && finite(fix.accuracy) && fix.accuracy >= 0 && finite(fix.timestamp);
  }
  const trusted = fix => fix.accuracy <= TRUSTED_ACCURACY;

  function createSession(profile, points = []) {
    const session = { profile, points: [], last: null, speed: null, stillSince: null, announced: {}, spoken: null };
    for (const point of points) addPoint(session, point);
    return session;
  }
  function addPoint(session, point) {
    if (!finite(point.lat) || !finite(point.lon)) return null;
    const stored = { id: String(point.id), lat: point.lat, lon: point.lon, label: String(point.label || ''), at: finite(point.at) ? point.at : 0 };
    session.points.push(stored);
    C.addMemo(session.profile, stored.id);
    return stored;
  }
  function limitMeters(session) { return C.driverTypes[session.profile.driverType].baseMeters; }
  function setDriverType(session, type) {
    if (!Object.prototype.hasOwnProperty.call(C.driverTypes, type)) throw new TypeError('Unknown driver type');
    session.profile.driverType = type;
  }
  function nearest(session, fix) {
    let best = null;
    for (const point of session.points) {
      const meters = distanceMeters(fix, point);
      if (!best || meters < best.meters) best = { point, meters };
    }
    return best;
  }
  // Speed comes from the receiver when it reports one, otherwise from the last two trusted fixes.
  function speedOf(session, fix) {
    if (finite(fix.speed) && fix.speed >= 0) return fix.speed;
    const last = session.last;
    if (!last || !trusted(last) || !trusted(fix)) return null;
    const seconds = (fix.timestamp - last.timestamp) / 1000;
    return seconds >= 1 ? distanceMeters(last, fix) / seconds : null;
  }
  function status(session, now) {
    const last = session.last;
    if (!last) return { ageMs: null, fresh: false, moving: false, parked: false, trusted: false };
    const ageMs = now - last.timestamp, fresh = ageMs >= -2000 && ageMs <= FRESH_MS;
    return { ageMs, fresh, trusted: trusted(last),
      moving: fresh && session.speed !== null && session.speed >= MOVING_SPEED,
      parked: fresh && session.stillSince !== null && now - session.stillSince >= PARKED_AFTER_MS };
  }
  function observe(session, fix) {
    if (!validFix(fix)) return { accepted: false, reason: 'invalid' };
    if (session.last && fix.timestamp <= session.last.timestamp) return { accepted: false, reason: 'stale' };
    const speed = speedOf(session, fix), now = fix.timestamp;
    if (speed !== null && speed >= MOVING_SPEED) session.stillSince = null;
    else if (speed !== null && speed < PARKED_SPEED && session.stillSince === null) session.stillSince = now;
    session.last = { lat: fix.lat, lon: fix.lon, accuracy: fix.accuracy, timestamp: now };
    session.speed = speed;
    const limit = limitMeters(session), near = nearest(session, session.last), moving = speed !== null && speed >= MOVING_SPEED;
    // A point said once stays quiet until the car has clearly left it.
    for (const id of Object.keys(session.announced)) {
      const point = session.points.find(p => p.id === id);
      if (!point || distanceMeters(session.last, point) > limit * RELEASE_FACTOR) delete session.announced[id];
    }
    let decision;
    if (!trusted(session.last)) decision = { action: 'wait', reason: 'poor-accuracy', speech: '', limit };
    else if (!near) decision = { action: 'none', reason: 'no-points', speech: '', limit };
    else if (near.meters > limit) decision = { action: 'wait', reason: 'too-far', speech: '', limit };
    else if (session.announced[near.point.id]) decision = { action: 'wait', reason: 'already-said', speech: '', limit };
    else if (!moving) decision = { action: 'wait', reason: 'not-moving', speech: '', limit };
    else {
      decision = { action: 'speak', reason: 'memory-point', speech: C.decidePoint(session.profile, near.point.id).speech, limit, point: near.point };
      session.announced[near.point.id] = now;
      session.spoken = { text: decision.speech, at: now, point: near.point };
    }
    return { accepted: true, fix: session.last, speed, nearest: near, decision, ...status(session, now) };
  }
  // Marks where the car is now. A fresh, trusted fix is required; a point within 30 m is reused.
  function remember(session, now) {
    const last = session.last;
    if (!last) return { ok: false, reason: 'no-fix' };
    if (!trusted(last)) return { ok: false, reason: 'poor-accuracy' };
    if (now - last.timestamp > FRESH_MS) return { ok: false, reason: 'stale' };
    const near = nearest(session, last);
    if (near && near.meters <= MERGE_METERS) return { ok: true, merged: true, point: near.point };
    const point = addPoint(session, { id: 'live-' + now, lat: last.lat, lon: last.lon, label: '지점 ' + (session.points.length + 1), at: now });
    return { ok: true, merged: false, point };
  }
  function forget(session) {
    const ids = new Set(session.points.map(p => p.id));
    session.points = [];
    session.profile.places = session.profile.places.filter(id => !ids.has(id));
    session.announced = {};
    session.spoken = null;
  }
  const serialize = session => session.points.map(p => ({ ...p }));
  return Object.freeze({ TRUSTED_ACCURACY, MERGE_METERS, FRESH_MS, MOVING_SPEED, PARKED_AFTER_MS,
    distanceMeters, validFix, createSession, addPoint, limitMeters, setDriverType, nearest, status, observe, remember, forget, serialize });
});
