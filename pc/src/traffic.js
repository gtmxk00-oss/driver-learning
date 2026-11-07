(function (root, factory) {
  const traffic = factory();
  if (typeof module === 'object' && module.exports) module.exports = traffic;
  else root.RMTraffic = traffic;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  // These are explicitly synthetic traffic cases, never a live traffic feed.
  const baseline = Object.freeze({
    fast: Object.freeze({ minutes: 18, segments: Object.freeze([1200, 900, 1000]) }),
    easy: Object.freeze({ minutes: 21, segments: Object.freeze([1600, 1800]) }),
    recovery: Object.freeze({ minutes: 8, segments: Object.freeze([700, 900]) })
  });
  const labels = Object.freeze({ clear: '원활', slow: '서행', jam: '정체' });
  const road = (delayMinutes, status, speedKph) => Object.freeze({ delayMinutes, status, speedKph, label: labels[status] });
  const scenario = (id, label, fast, easy, recovery) => Object.freeze({
    id, label, source: 'simulation',
    routes: Object.freeze({ fast, easy, recovery })
  });
  const scenarios = Object.freeze({
    clear: scenario('clear', '모든 경로 원활', road(0, 'clear', 50), road(0, 'clear', 45), road(0, 'clear', 40)),
    'fast-jam': scenario('fast-jam', '빠른 길 정체', road(12, 'jam', 15), road(2, 'slow', 35), road(1, 'slow', 30)),
    'all-slow': scenario('all-slow', '두 경로 모두 지연', road(10, 'jam', 18), road(6, 'slow', 25), road(3, 'slow', 25)),
    'easy-jam': scenario('easy-jam', '편안한 길 정체', road(0, 'clear', 50), road(12, 'jam', 12), road(2, 'slow', 30))
  });
  const owns = (object, key) => Object.prototype.hasOwnProperty.call(object, key);

  function getScenario(id) {
    const key = typeof id === 'string' ? id : id && typeof id === 'object' ? id.id : null;
    return owns(scenarios, key) ? scenarios[key] : scenarios.clear;
  }
  function routeId(route) {
    const id = typeof route === 'string' ? route : route && route.id;
    if (!owns(baseline, id)) throw new RangeError('Unknown simulation route');
    return id;
  }
  function roadStatus(id, selectedScenario) {
    return getScenario(selectedScenario).routes[routeId(id)];
  }
  function estimateMinutes(route, selectedScenario) {
    const id = routeId(route);
    return baseline[id].minutes + roadStatus(id, selectedScenario).delayMinutes;
  }
  function remainingMinutes(trip, selectedScenario) {
    if (!trip || !['running', 'rerouting'].includes(trip.status) || !owns(baseline, trip.activeRouteId)) return 0;
    const current = baseline[trip.activeRouteId];
    if (!Number.isInteger(trip.index) || trip.index < 0 || trip.index >= current.segments.length ||
        !Number.isFinite(trip.remaining)) return 0;
    const total = current.segments.reduce((sum, meters) => sum + meters, 0);
    const remaining = Math.max(0, Math.min(current.segments[trip.index], trip.remaining)) +
      current.segments.slice(trip.index + 1).reduce((sum, meters) => sum + meters, 0);
    return Math.ceil(estimateMinutes(trip.activeRouteId, selectedScenario) * Math.min(1, remaining / total));
  }
  function recommend(profile, selectedScenario, RM) {
    const score = route => estimateMinutes(route, selectedScenario) + 0.4 * RM.burden(profile, route);
    return score(RM.routes.fast) <= score(RM.routes.easy) ? RM.routes.fast : RM.routes.easy;
  }
  function explain(profile, selectedRoute, selectedScenario, RM) {
    const selected = typeof selectedRoute === 'string' ? RM.routes[routeId(selectedRoute)] : selectedRoute;
    const suggested = recommend(profile, selectedScenario, RM);
    const currentTraffic = roadStatus(selected.id, selectedScenario);
    const delay = currentTraffic.delayMinutes;
    const prefix = delay ? selected.name + '은 교통 지연 ' + delay + '분을 반영했어요. ' : '';
    return prefix + '교통과 개인 부담을 함께 보면 ' + suggested.name + '이 유리해요.';
  }
  function snapshotTraffic(selectedScenario) {
    const selected = getScenario(selectedScenario);
    return { id: selected.id, label: selected.label, capturedAt: new Date().toISOString(), source: 'simulation' };
  }
  return Object.freeze({ scenarios, getScenario, estimateMinutes, remainingMinutes, recommend, explain,
    roadStatus, snapshotTraffic });
});
