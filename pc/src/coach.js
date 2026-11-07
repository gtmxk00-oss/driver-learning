(function (root, factory) {
  const coach = factory();
  if (typeof module === 'object' && module.exports) module.exports = coach;
  else root.RMCoach = coach;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  // Same rules as Android core DriveCoach.java. Change both together.
  const driverTypes = Object.freeze({
    general: Object.freeze({ label: '일반', baseMeters: 300, maxMeters: 900, neutralOnly: false }),
    taxi: Object.freeze({ label: '택시', baseMeters: 300, maxMeters: 900, neutralOnly: false }),
    truck: Object.freeze({ label: '화물', baseMeters: 600, maxMeters: 1200, neutralOnly: false }),
    bus: Object.freeze({ label: '버스', baseMeters: 450, maxMeters: 900, neutralOnly: true })
  });
  const turnTypes = Object.freeze({
    'exit-left': Object.freeze({ label: '왼쪽 출구', subject: '왼쪽 출구가', side: 'left' }),
    'exit-right': Object.freeze({ label: '오른쪽 출구', subject: '오른쪽 출구가', side: 'right' }),
    'keep-left': Object.freeze({ label: '왼쪽 분기', subject: '왼쪽 분기가', side: 'left' }),
    'keep-right': Object.freeze({ label: '오른쪽 분기', subject: '오른쪽 분기가', side: 'right' }),
    'turn-left': Object.freeze({ label: '좌회전', subject: '좌회전이', side: 'left' }),
    'turn-right': Object.freeze({ label: '우회전', subject: '우회전이', side: 'right' }),
    roundabout: Object.freeze({ label: '회전교차로', subject: '회전교차로가', side: null }),
    'narrow-road': Object.freeze({ label: '좁은 길', subject: '좁은 길이', side: null })
  });
  const WEAK_AFTER = 2, LOWER_AFTER = 3, STEP_METERS = 150, MAX_STEPS = 4, BRIEF_MAX = 3;
  const owns = (object, key) => Object.prototype.hasOwnProperty.call(object, key);
  const typeOf = id => {
    if (!owns(turnTypes, id)) throw new TypeError('Unknown turn type');
    return turnTypes[id];
  };

  function createProfile(id, name, driverType = 'general') {
    if (!owns(driverTypes, driverType)) throw new TypeError('Unknown driver type');
    return { id: String(id), name: String(name), driverType, typeMisses: {}, passStreak: {},
      chosenWeak: [], places: [], habitMeters: null, habitSamples: 0, pending: [], comfortAsks: [], comfortRoutes: [] };
  }
  function misses(profile, type) { return profile.typeMisses[type] || 0; }
  function chooseWeak(profile, type) {
    typeOf(type);
    if (!profile.chosenWeak.includes(type)) profile.chosenWeak.push(type);
  }
  function isWeak(profile, type) { return profile.chosenWeak.includes(type) || misses(profile, type) >= WEAK_AFTER; }
  function remembers(profile, placeId) { return profile.places.includes(placeId); }
  function decisionMeters(profile, turn) {
    const driver = driverTypes[profile.driverType];
    const start = Math.max(driver.baseMeters, profile.habitMeters || 0);
    return Math.min(driver.maxMeters, start + Math.min(MAX_STEPS, misses(profile, turn.type)) * STEP_METERS);
  }
  function neutralSpeech(type) { return type.label + ' 준비하세요.'; }
  function speechFor(profile, turn, passenger) {
    const type = typeOf(turn.type);
    if (driverTypes[profile.driverType].neutralOnly || passenger) return neutralSpeech(type);
    if (remembers(profile, turn.id)) return '지난번 놓친 ' + type.label + '예요. 미리 준비하세요.';
    return type.subject + ' 곧 나옵니다. 미리 준비하세요.';
  }
  // action: none = not this driver's concern, wait = too far, silent = already prepared, speak = say one sentence.
  function decide(profile, turn, state) {
    const type = typeOf(turn.type);
    const limit = decisionMeters(profile, turn);
    if (!isWeak(profile, turn.type) && !remembers(profile, turn.id))
      return { action: 'none', reason: 'not-weak', speech: '', limit };
    if (state.meters > limit) return { action: 'wait', reason: 'too-far', speech: '', limit };
    if (type.side && state.signal === type.side) return { action: 'silent', reason: 'prepared', speech: '', limit };
    return { action: 'speak', reason: state.signal === 'off' ? 'signal-off' : 'needs-attention',
      speech: speechFor(profile, turn, Boolean(state.passenger)), limit };
  }
  // Works without an active route, for drivers such as route buses.
  function decidePoint(profile, placeId) {
    if (!remembers(profile, placeId)) return { action: 'none', reason: 'unknown-place', speech: '' };
    return { action: 'speak', reason: 'memory-point', speech: '헷갈렸던 지점입니다. 미리 확인하세요.' };
  }
  function addMemo(profile, placeId) { if (!remembers(profile, placeId)) profile.places.push(placeId); }
  function noteMiss(profile, turn) {
    if (!profile.pending.some(item => item.id === turn.id)) profile.pending.push({ id: turn.id, type: turn.type });
  }
  // A question about a past mistake must never be asked in front of a passenger or while moving.
  function canReview(profile, state) { return profile.pending.length > 0 && Boolean(state.parked) && !state.passenger; }
  function review(profile, confirmed) {
    const item = profile.pending.shift();
    if (!item) return null;
    const wasWeak = isWeak(profile, item.type);
    if (confirmed) {
      profile.typeMisses[item.type] = misses(profile, item.type) + 1;
      profile.passStreak[item.type] = 0;
      addMemo(profile, item.id);
    }
    return { type: item.type, confirmed: Boolean(confirmed), becameWeak: !wasWeak && isWeak(profile, item.type) };
  }
  function notePass(profile, turn, signalOnMeters) {
    profile.passStreak[turn.type] = (profile.passStreak[turn.type] || 0) + 1;
    if (Number.isFinite(signalOnMeters) && signalOnMeters > 0) {
      profile.habitMeters = Math.round(((profile.habitMeters || 0) * profile.habitSamples + signalOnMeters) / (profile.habitSamples + 1));
      profile.habitSamples++;
    }
    return { offerLower: isWeak(profile, turn.type) && profile.passStreak[turn.type] >= LOWER_AFTER };
  }
  function lowerType(profile, type) {
    profile.chosenWeak = profile.chosenWeak.filter(item => item !== type);
    delete profile.typeMisses[type];
    delete profile.passStreak[type];
  }
  const privateMoment = state => Boolean(state.parked) && !state.passenger;
  // Read before departure, and only for remembered points on this route.
  function briefing(profile, routePlaces, state) {
    if (!privateMoment(state)) return { points: [], speech: '' };
    const points = routePlaces.filter(place => remembers(profile, place.id)).slice(0, BRIEF_MAX);
    if (!points.length) return { points, speech: '' };
    return { points, speech: '이번 길에 기억해 둔 곳이 ' + points.length + '곳 있습니다. ' + points.map(place => place.label).join(', ') + '입니다.' };
  }
  function noteHardMerge(profile, destination) { if (!profile.comfortAsks.includes(destination)) profile.comfortAsks.push(destination); }
  function usesComfortRoute(profile, destination) { return profile.comfortRoutes.includes(destination); }
  function comfortOffer(profile, destination, state) {
    if (!privateMoment(state) || !profile.comfortAsks.includes(destination) || usesComfortRoute(profile, destination)) return '';
    return '지난번 어려웠던 합류를 피하는 편한 길로 바꿀까요?';
  }
  function acceptComfort(profile, destination) { if (!usesComfortRoute(profile, destination)) profile.comfortRoutes.push(destination); }
  // Erases what this profile learned. Other profiles keep theirs.
  function clearMemory(profile) {
    Object.assign(profile, { typeMisses: {}, passStreak: {}, chosenWeak: [], places: [], habitMeters: null, habitSamples: 0, pending: [], comfortAsks: [], comfortRoutes: [] });
  }
  // What a profile learned, as JSON, so it can be kept in the browser. Garbage is skipped rather than trusted.
  function snapshot(profile) {
    const { typeMisses, passStreak, chosenWeak, places, habitMeters, habitSamples } = profile;
    return JSON.stringify({ typeMisses, passStreak, chosenWeak, places, habitMeters, habitSamples });
  }
  function restore(profile, text) {
    let data; try { data = JSON.parse(text || ''); } catch { return; }
    if (!data || typeof data !== 'object') return;
    const count = value => Number.isInteger(value) && value >= 0 ? value : 0;
    for (const type of Object.keys(data.typeMisses || {})) if (owns(turnTypes, type)) profile.typeMisses[type] = count(data.typeMisses[type]);
    for (const type of Object.keys(data.passStreak || {})) if (owns(turnTypes, type)) profile.passStreak[type] = count(data.passStreak[type]);
    for (const type of Array.isArray(data.chosenWeak) ? data.chosenWeak : []) if (owns(turnTypes, type) && !profile.chosenWeak.includes(type)) profile.chosenWeak.push(type);
    for (const id of Array.isArray(data.places) ? data.places : []) if (typeof id === 'string' && id && !profile.places.includes(id)) profile.places.push(id);
    if (Number.isFinite(data.habitMeters) && data.habitMeters > 0) { profile.habitMeters = Math.round(data.habitMeters); profile.habitSamples = count(data.habitSamples); }
  }
  return Object.freeze({ driverTypes, turnTypes, createProfile, misses, chooseWeak, isWeak, remembers, decisionMeters,
    decide, decidePoint, addMemo, noteMiss, canReview, review, notePass, lowerType,
    briefing, noteHardMerge, comfortOffer, acceptComfort, usesComfortRoute, clearMemory, snapshot, restore });
});
