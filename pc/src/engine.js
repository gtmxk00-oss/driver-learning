(function (root, factory) {
  const engine = factory();
  if (typeof module === 'object' && module.exports) module.exports = engine;
  else root.RM = engine;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  const route = (id, name, minutes, segments) => Object.freeze({
    id, name, minutes, segments: Object.freeze(segments.map(Object.freeze))
  });
  const routes = Object.freeze({
    fast: route('fast', '빠른 길', 18, [
      { id: 'exit-a', name: '가상 한빛 분기점', kind: 'exit', meters: 1200, cue: '이번 분기점은 오른쪽 도로입니다' },
      { id: 'fork-b', name: '가상 두빛 갈림길', kind: 'sequence', meters: 900, cue: '첫 번째는 직진, 다음 갈림길에서 오른쪽입니다' },
      { id: 'merge-c', name: '가상 새빛 합류', kind: 'merge', meters: 1000, cue: '합류 구간입니다. 주변 교통을 확인하세요' }
    ]),
    easy: route('easy', '편안한 길', 21, [
      { id: 'simple-a', name: '가상 중앙대로', kind: 'simple', meters: 1600, cue: '다음 교차로까지 직진입니다' },
      { id: 'simple-b', name: '가상 공원 입구', kind: 'simple', meters: 1800, cue: '다음 교차로에서 오른쪽입니다' }
    ]),
    recovery: route('recovery', '시연 대체 경로', 8, [
      { id: 'recovery-a', name: '가상 우회 연결로', kind: 'simple', meters: 700, cue: '다음 교차로까지 직진입니다' },
      { id: 'recovery-b', name: '가상 공원 연결로', kind: 'simple', meters: 900, cue: '다음 교차로에서 오른쪽입니다' }
    ])
  });
  const knownSegments = new Set(Object.values(routes).flatMap(r => r.segments.map(s => s.id)));
  const isObject = value => value !== null && typeof value === 'object' && !Array.isArray(value);
  const integer = (value, min, max) => Number.isInteger(value) && value >= min && value <= max;
  const owns = (object, key) => Object.prototype.hasOwnProperty.call(object, key);

  function createProfile(id, name) {
    return { id: String(id), name: String(name), confirmedMisses: {}, avoid: [], notes: {}, completedTrips: 0 };
  }
  function misses(profile, id) {
    const count = profile.confirmedMisses[id];
    return Number.isInteger(count) && count >= 0 ? count : 0;
  }
  function leadMeters(profile, segment) {
    const base = segment.kind === 'sequence' ? 450 : 300;
    return Math.min(900, base + Math.min(4, misses(profile, segment.id)) * 150);
  }
  function burden(profile, selectedRoute) {
    return selectedRoute.segments.reduce((sum, s) => sum +
      (s.kind === 'simple' ? 0 : 2) + Math.min(4, misses(profile, s.id)) * 2 +
      (profile.avoid.includes(s.id) ? 12 : 0), 0);
  }
  function recommend(profile) {
    return routes.fast.minutes + 0.4 * burden(profile, routes.fast) <=
      routes.easy.minutes + 0.4 * burden(profile, routes.easy) ? routes.fast : routes.easy;
  }
  function createTrip(profile, selectedRoute, injectMiss = false) {
    if (!selectedRoute || !owns(routes, selectedRoute.id)) throw new TypeError('Unknown route');
    const canonical = routes[selectedRoute.id];
    return {
      profileId: profile.id, routeId: canonical.id, activeRouteId: canonical.id,
      injectMiss: Boolean(injectMiss), status: 'running', index: 0,
      remaining: canonical.segments[0].meters, tickCount: 0,
      message: '시연을 시작합니다', speech: '', announced: false,
      recoveryTicks: 0, pending: [], reviewed: false
    };
  }
  function segment(trip) {
    return routes[trip.activeRouteId].segments[trip.index];
  }
  function metersLeft(trip) {
    if (trip.status === 'finished') return 0;
    return trip.remaining + routes[trip.activeRouteId].segments.slice(trip.index + 1)
      .reduce((sum, s) => sum + s.meters, 0);
  }
  function advance(trip, profile) {
    const activeRoute = routes[trip.activeRouteId];
    trip.index++;
    if (trip.index >= activeRoute.segments.length) {
      trip.index = activeRoute.segments.length - 1;
      trip.remaining = 0;
      trip.status = 'finished';
      profile.completedTrips++;
      trip.message = '도착했습니다. 정차 후 주행을 돌아보세요.';
      trip.speech = trip.message;
    } else {
      trip.remaining = segment(trip).meters;
      trip.announced = false;
      trip.message = '다음 구간: ' + segment(trip).name;
    }
  }
  function tick(trip, profile, meters = 100) {
    if (meters !== 50 && meters !== 100) throw new RangeError('Simulation step must be 50 or 100 meters');
    if (trip.profileId !== profile.id) throw new TypeError('Trip belongs to another driver');
    trip.speech = '';
    if (trip.status === 'finished' || trip.status === 'cancelled') return trip;
    trip.tickCount++;
    if (trip.status === 'rerouting') {
      if (--trip.recoveryTicks <= 0) {
        trip.status = 'running'; trip.activeRouteId = 'recovery'; trip.index = 0;
        trip.remaining = segment(trip).meters; trip.announced = false;
        trip.message = '시연 대체 경로로 연결했습니다';
      }
      return trip;
    }
    trip.remaining = Math.max(0, trip.remaining - meters);
    if (!trip.announced && trip.remaining <= leadMeters(profile, segment(trip))) {
      trip.announced = true;
      trip.message = segment(trip).cue + '.';
      trip.speech = trip.message;
    }
    if (trip.remaining === 0) {
      if (trip.injectMiss && trip.index === 0 && trip.activeRouteId === 'fast') {
        if (!trip.pending.includes(segment(trip).id)) trip.pending.push(segment(trip).id);
        trip.status = 'rerouting'; trip.recoveryTicks = 3;
        trip.message = '다음 경로로 안내하겠습니다.'; trip.speech = trip.message;
      } else advance(trip, profile);
    }
    return trip;
  }
  function cancel(trip) {
    if (trip.status === 'running' || trip.status === 'rerouting') {
      trip.status = 'cancelled'; trip.speech = ''; trip.message = '시연을 종료했습니다.';
    }
    return trip;
  }
  function review(trip, profile, wasMistake) {
    if (trip.profileId !== profile.id || trip.status !== 'finished' || trip.reviewed) return false;
    trip.reviewed = true;
    if (wasMistake) for (const id of trip.pending)
      profile.confirmedMisses[id] = Math.min(100, misses(profile, id) + 1);
    return true;
  }

  // Storage is untrusted. Copy only recognized, bounded values; never reuse raw objects.
  function validateProfile(raw, id, name) {
    const clean = createProfile(id, name);
    if (!isObject(raw) || raw.id !== id) return clean;
    if (isObject(raw.confirmedMisses)) for (const [key, value] of Object.entries(raw.confirmedMisses)) {
      if (knownSegments.has(key) && Number.isInteger(value) && value >= 0)
        clean.confirmedMisses[key] = Math.min(100, value);
    }
    if (Array.isArray(raw.avoid)) clean.avoid = [...new Set(raw.avoid.filter(key => knownSegments.has(key)))];
    if (isObject(raw.notes)) for (const [key, value] of Object.entries(raw.notes)) {
      if (knownSegments.has(key) && typeof value === 'string') clean.notes[key] = value.trim().slice(0, 200);
    }
    if (Number.isInteger(raw.completedTrips) && raw.completedTrips >= 0)
      clean.completedTrips = Math.min(1000000, raw.completedTrips);
    return clean;
  }
  function validateTrip(raw, profile) {
    if (!isObject(raw) || !isObject(profile) || typeof profile.id !== 'string' ||
        raw.profileId !== profile.id || !owns(routes, raw.routeId) || !owns(routes, raw.activeRouteId)) return null;
    if (!['running', 'rerouting', 'finished', 'cancelled'].includes(raw.status)) return null;
    const active = routes[raw.activeRouteId];
    if (!integer(raw.index, 0, active.segments.length - 1) ||
        !integer(raw.remaining, 0, active.segments[raw.index].meters) || raw.remaining % 50 !== 0 ||
        !integer(raw.tickCount, 0, 10000) || !integer(raw.recoveryTicks, 0, 3)) return null;
    if (typeof raw.injectMiss !== 'boolean' || typeof raw.announced !== 'boolean' || typeof raw.reviewed !== 'boolean' ||
        typeof raw.message !== 'string' || raw.message.length > 500 || typeof raw.speech !== 'string' || raw.speech.length > 500) return null;
    if (!Array.isArray(raw.pending) || raw.pending.length > 1 || raw.pending.some(id => id !== 'exit-a')) return null;
    const missed = raw.pending.length === 1;
    if (missed && (!raw.injectMiss || raw.routeId !== 'fast')) return null;
    if (raw.activeRouteId !== raw.routeId && !(raw.routeId === 'fast' && raw.activeRouteId === 'recovery' && missed)) return null;
    if (raw.activeRouteId === raw.routeId && missed && !['rerouting', 'cancelled'].includes(raw.status)) return null;
    if (raw.injectMiss && raw.routeId === 'fast' && raw.activeRouteId === 'fast' &&
        (raw.index > 0 || raw.status === 'finished')) return null;
    if (raw.status === 'running' && (raw.remaining === 0 || raw.recoveryTicks !== 0 || raw.reviewed)) return null;
    if (raw.status === 'rerouting' && (!missed || raw.activeRouteId !== 'fast' || raw.index !== 0 ||
        raw.remaining !== 0 || raw.recoveryTicks < 1 || !raw.announced || raw.reviewed)) return null;
    if (raw.recoveryTicks > 0 && !['rerouting', 'cancelled'].includes(raw.status)) return null;
    if (raw.status === 'cancelled' && raw.recoveryTicks > 0 &&
        (!missed || raw.activeRouteId !== 'fast' || raw.index !== 0 || raw.remaining !== 0)) return null;
    if (raw.status === 'finished' && (raw.remaining !== 0 || raw.index !== active.segments.length - 1 || raw.recoveryTicks !== 0)) return null;
    if (raw.reviewed && raw.status !== 'finished') return null;
    return {
      profileId: profile.id, routeId: raw.routeId, activeRouteId: raw.activeRouteId,
      injectMiss: raw.injectMiss, status: raw.status, index: raw.index, remaining: raw.remaining,
      tickCount: raw.tickCount, message: raw.message, speech: '', announced: raw.announced,
      recoveryTicks: raw.recoveryTicks, pending: [...raw.pending], reviewed: raw.reviewed
    };
  }
  return Object.freeze({ routes, createProfile, misses, leadMeters, burden, recommend,
    createTrip, segment, metersLeft, tick, cancel, review, validateProfile, validateTrip });
});
