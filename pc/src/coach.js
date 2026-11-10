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
  // How well this driver knows the road under the car; 'unknown' when no position has been seen.
  const familiarities = Object.freeze(['unknown', 'new', 'known', 'familiar']);
  const WEAK_AFTER = 2, LOWER_AFTER = 3, STEP_METERS = 150, MAX_STEPS = 4, BRIEF_MAX = 3;
  // The decision distance grows with speed: the DRIVE II simulator work put the last turn message about 137 m ahead
  // at 64 km/h and further out for every km/h more, and the AR-HUD study on multi-branch roads found 1.5 km best.
  // The distance is scaled around 60 km/h, between half and double, and a road never driven before adds 30%.
  const SPEED_REF_KPH = 60, SPEED_MIN = 0.5, SPEED_MAX = 2.0, NEW_ROAD_FACTOR = 1.3;
  // Rain (wiper on) and night (low beam on) push the decision distance out: stopping distances grow and signs are read later.
  const RAIN_FACTOR = 1.25, NIGHT_FACTOR = 1.15;
  // Road segments (the same cells of about 100 m) carry three counts from the turns approached through them: passed,
  // missed, passed without a signal. Plain numbers kept on the road, not a model. A segment with this many outcomes of
  // which half or more were misses is a hard one: the decision distance grows and the driver is told even when the
  // turn type is not one of their weak ones.
  const SEGMENT_SAMPLES = 2, SEGMENT_FACTOR = 1.25;
  // A road cell driven on this many separate trips is familiar; weak types stay quiet there, remembered spots do not.
  const FAMILIAR_AFTER = 3;
  // Lane changes are signalled only about two thirds of the time (ensemble LSTM study); a driver who rarely signals
  // gets no "signal off" sentence for weak types, because the signal says nothing about their preparation.
  const SIGNAL_SAMPLES = 5, SIGNAL_RATE_MIN = 0.3;
  // Under load (fast, or another turn right behind) the sentence is cut to its core (AdaptiveVoice, CHI 2024).
  const BRIEF_SPEED_KPH = 80, BRIEF_GAP_METERS = 500;
  // A road whose limit is this high is a fast road: the sentence is cut to its core there too.
  const BRIEF_LIMIT_KPH = 80;
  const LOWER_QUESTION = '같은 유형을 세 번 잘 지나셨어요. 이 유형 알림을 줄일까요?';
  const owns = (object, key) => Object.prototype.hasOwnProperty.call(object, key);
  const typeOf = id => {
    if (!owns(turnTypes, id)) throw new TypeError('Unknown turn type');
    return turnTypes[id];
  };
  const detailOf = turn => typeof turn.detail === 'string' && turn.detail.trim() ? turn.detail.trim() : null;

  function createProfile(id, name, driverType = 'general') {
    if (!owns(driverTypes, driverType)) throw new TypeError('Unknown driver type');
    return { id: String(id), name: String(name), driverType, typeMisses: {}, passStreak: {},
      chosenWeak: [], places: [], habitMeters: null, habitSamples: 0, pending: [],
      signalTurns: 0, signalOnTurns: 0, destPlaces: {}, placeLabels: {}, roadCells: {}, placeCoords: {}, segments: {} };
  }
  function misses(profile, type) { return profile.typeMisses[type] || 0; }
  function chooseWeak(profile, type) {
    typeOf(type);
    if (!profile.chosenWeak.includes(type)) profile.chosenWeak.push(type);
  }
  function isWeak(profile, type) { return profile.chosenWeak.includes(type) || misses(profile, type) >= WEAK_AFTER; }
  function remembers(profile, placeId) { return profile.places.includes(placeId); }
  // The decision distance for the speed the car is doing and the road it is on; a negative speed means unknown.
  function decisionMeters(profile, turn, speedKph = -1, familiarity = 'unknown', rain = false, night = false, hardSegment = false) {
    const driver = driverTypes[profile.driverType];
    const start = Math.max(driver.baseMeters, profile.habitMeters || 0);
    const base = Math.min(driver.maxMeters, start + Math.min(MAX_STEPS, misses(profile, turn.type)) * STEP_METERS);
    let factor = Number.isFinite(speedKph) && speedKph >= 0 ? Math.max(SPEED_MIN, Math.min(SPEED_MAX, speedKph / SPEED_REF_KPH)) : 1;
    if (familiarity === 'new') factor *= NEW_ROAD_FACTOR;
    if (rain) factor *= RAIN_FACTOR;
    if (night) factor *= NIGHT_FACTOR;
    if (hardSegment) factor *= SEGMENT_FACTOR;
    return Math.max(100, Math.min(driver.maxMeters * 2, Math.round(base * factor)));
  }
  // The driver's own signalling rate over passed turns, or -1 until there are enough.
  function signalRate(profile) { return profile.signalTurns < SIGNAL_SAMPLES ? -1 : profile.signalOnTurns / profile.signalTurns; }
  function rarelySignals(profile) { const rate = signalRate(profile); return rate >= 0 && rate < SIGNAL_RATE_MIN; }
  // "예요" after a vowel, "이에요" after a final consonant: 왼쪽 출구예요, 좌회전이에요.
  const copula = word => { const c = word.charCodeAt(word.length - 1); return c >= 0xAC00 && c <= 0xD7A3 && (c - 0xAC00) % 28 !== 0 ? '이에요' : '예요'; };
  function speechFor(profile, turn, passenger, brief, hardSegment) {
    const type = typeOf(turn.type), detail = detailOf(turn);
    if (brief) return detail ? type.label + ', ' + detail + '.' : type.label + ' 준비.';
    const withDetail = detail ? detail + '입니다. ' : '';
    if (driverTypes[profile.driverType].neutralOnly || passenger) return type.label + ' 준비하세요.' + (detail ? ' ' + detail + '입니다.' : '');
    if (remembers(profile, turn.id)) return '지난번 놓친 ' + type.label + copula(type.label) + '. ' + withDetail + '미리 준비하세요.';
    // Said of the road, never of who missed it: the counts may be another profile's.
    if (hardSegment) return '놓치기 쉬운 ' + type.label + copula(type.label) + '. ' + withDetail + '미리 준비하세요.';
    return type.subject + ' 곧 나옵니다. ' + withDetail + '미리 준비하세요.';
  }
  // action: none = not this driver's concern, wait = too far, silent = already prepared, speak = say one sentence.
  // state: meters, signal, passenger, and when the car gives them speedKph, familiarity, brief.
  function decide(profile, turn, state) {
    const type = typeOf(turn.type);
    const speedKph = Number.isFinite(state.speedKph) ? state.speedKph : -1;
    const familiarity = familiarities.includes(state.familiarity) ? state.familiarity : 'unknown';
    const hard = Boolean(state.hardSegment);
    const limit = decisionMeters(profile, turn, speedKph, familiarity, Boolean(state.rain), Boolean(state.night), hard);
    const remembered = remembers(profile, turn.id);
    // A hard segment is worth a word whatever the turn type; a road the driver knows well still goes by their own memory.
    if (!isWeak(profile, turn.type) && !remembered && !hard) return { action: 'none', reason: 'not-weak', speech: '', limit };
    // A road driven many times is known; only a spot actually missed there is worth a word.
    if (!remembered && familiarity === 'familiar') return { action: 'none', reason: 'familiar-road', speech: '', limit };
    if (state.meters > limit) return { action: 'wait', reason: 'too-far', speech: '', limit };
    if (type.side && state.signal === type.side) return { action: 'silent', reason: 'prepared', speech: '', limit };
    if (!remembered && type.side && rarelySignals(profile)) return { action: 'silent', reason: 'rarely-signals', speech: '', limit };
    return { action: 'speak', reason: state.signal === 'off' ? 'signal-off' : 'needs-attention',
      speech: speechFor(profile, turn, Boolean(state.passenger), Boolean(state.brief), hard), limit };
  }
  // For a driver who is not sure which lane to take: the lane for the next turn and whether to move now. Korean lanes
  // count from the left (the 1st lane is the leftmost). Far from the turn: move early; inside the decision distance: now.
  function laneHint(turn, meters, limit) {
    if (!turn) return { side: 'any', lane: '', text: '어느 차선이든 괜찮아요' };
    const known = Number.isFinite(meters) && meters >= 0, when = !known ? '' : meters > limit ? ' 미리 옮겨 두세요' : ' 지금 옮기세요';
    const side = typeOf(turn.type).side;
    if (side === 'left') return { side: 'left', lane: '1차선', text: '1차선(왼쪽 끝)으로' + when };
    if (side === 'right') return { side: 'right', lane: '맨 오른쪽 차선', text: '맨 오른쪽 차선으로' + when };
    if (turn.type === 'roundabout') return { side: 'right', lane: '오른쪽 차선', text: '오른쪽 차선에서 천천히 진입' + (known && meters <= limit ? ', 속도 줄이기' : '') };
    return { side: 'any', lane: '차선 유지', text: '차선 유지, 속도 줄이기' };
  }
  // Works without an active route, for drivers such as route buses.
  function decidePoint(profile, placeId) {
    if (!remembers(profile, placeId)) return { action: 'none', reason: 'unknown-place', speech: '' };
    return { action: 'speak', reason: 'memory-point', speech: '헷갈렸던 지점입니다. 미리 확인하세요.' };
  }
  function addMemo(profile, placeId) { if (!remembers(profile, placeId)) profile.places.push(placeId); }
  // Where a remembered place is; kept so the car can be told when it comes near without a route.
  function placeCoord(profile, placeId, lat, lon) { if (placeId && Number.isFinite(lat) && Number.isFinite(lon)) profile.placeCoords[placeId] = [lat, lon]; }
  function placeCoordOf(profile, placeId) { return owns(profile.placeCoords, placeId) ? profile.placeCoords[placeId].slice() : null; }
  // Every remembered place that has a position: id to [lat, lon].
  function placeCoords(profile) { const out = {}; for (const id of Object.keys(profile.placeCoords)) if (remembers(profile, id)) out[id] = profile.placeCoords[id].slice(); return out; }
  function labelPlace(profile, placeId, label) {
    if (typeof label !== 'string' || !label.trim()) return;
    profile.placeLabels[placeId] = label.trim().replace(/[\t\n]/g, ' ');
  }
  function placeLabel(profile, placeId) {
    if (owns(profile.placeLabels, placeId)) return profile.placeLabels[placeId];
    const bar = placeId.indexOf('|');
    const head = bar < 0 ? placeId : placeId.slice(0, bar), tail = bar < 0 ? '' : placeId.slice(bar + 1).trim();
    const type = Object.keys(turnTypes).find(key => key.replace(/-/g, '_') === head.toLowerCase());
    if (type) return tail ? tail + ' ' + turnTypes[type].label : turnTypes[type].label;
    return tail || placeId;
  }
  // A place is tied to the destination it was learned on, so the next trip there can start with it.
  function rememberFor(profile, destination, placeId) {
    if (typeof destination !== 'string' || !destination.trim()) return;
    const key = destination.trim();
    if (!owns(profile.destPlaces, key)) profile.destPlaces[key] = [];
    if (!profile.destPlaces[key].includes(placeId)) profile.destPlaces[key].push(placeId);
  }
  function placesFor(profile, destination) {
    const key = typeof destination === 'string' ? destination.trim() : '';
    const ids = owns(profile.destPlaces, key) ? profile.destPlaces[key] : [];
    return ids.filter(id => remembers(profile, id)).map(id => ({ id, label: placeLabel(profile, id) }));
  }
  function noteMiss(profile, turn) {
    if (!profile.pending.some(item => item.id === turn.id)) profile.pending.push({ id: turn.id, type: turn.type, detail: detailOf(turn) });
  }
  // A question about a past mistake must never be asked in front of a passenger or while moving.
  function canReview(profile, state) { return profile.pending.length > 0 && Boolean(state.parked) && !state.passenger; }
  function review(profile, confirmed, destination) {
    const item = profile.pending.shift();
    if (!item) return null;
    const wasWeak = isWeak(profile, item.type);
    if (confirmed) {
      profile.typeMisses[item.type] = misses(profile, item.type) + 1;
      profile.passStreak[item.type] = 0;
      addMemo(profile, item.id);
      if (item.detail) labelPlace(profile, item.id, item.detail + ' ' + turnTypes[item.type].label);
      rememberFor(profile, destination, item.id);
    }
    return { id: item.id, type: item.type, confirmed: Boolean(confirmed), becameWeak: !wasWeak && isWeak(profile, item.type) };
  }
  function notePass(profile, turn, signalOnMeters) {
    profile.passStreak[turn.type] = (profile.passStreak[turn.type] || 0) + 1;
    const signalled = Number.isFinite(signalOnMeters) && signalOnMeters > 0;
    if (typeOf(turn.type).side) { profile.signalTurns++; if (signalled) profile.signalOnTurns++; }
    if (signalled) {
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
  // Road familiarity: the world in cells of about 100 m, each counted once per trip.
  function cellKey(lat, lon) {
    const step = 0.0009;
    return Math.floor(lat / step) + ',' + Math.floor(lon * Math.cos(lat * Math.PI / 180) / step);
  }
  function noteRoad(profile, cell) { profile.roadCells[cell] = (profile.roadCells[cell] || 0) + 1; }
  function familiarity(profile, cell) {
    const n = profile.roadCells[cell] || 0;
    return n === 0 ? 'new' : n < FAMILIAR_AFTER ? 'known' : 'familiar';
  }
  // Road segments: what happened at the turns approached through each cell, as three counts [passed, missed, no signal].
  function noteSegment(profile, cell, passed, noSignal) {
    if (typeof cell !== 'string' || !cell) return;
    const v = owns(profile.segments, cell) ? profile.segments[cell] : (profile.segments[cell] = [0, 0, 0]);
    if (passed) v[0]++; else v[1]++;
    if (noSignal) v[2]++;
  }
  function segment(profile, cell) { return owns(profile.segments, cell) ? profile.segments[cell].slice() : null; }
  // The counts of several profiles added up per segment: numbers only, nothing that says whose they were.
  function sharedSegments(profiles) {
    const out = {};
    for (const p of profiles) for (const cell of Object.keys(p.segments || {})) { const v = owns(out, cell) ? out[cell] : (out[cell] = [0, 0, 0]); for (let i = 0; i < 3; i++) v[i] += p.segments[cell][i]; }
    return out;
  }
  // A hard segment by this driver's own counts when there are any, otherwise by the shared ones.
  function hardSegment(profile, shared, cell) {
    if (typeof cell !== 'string' || !cell) return false;
    let v = owns(profile.segments, cell) ? profile.segments[cell] : null;
    if (!v || v[0] + v[1] === 0) v = shared && owns(shared, cell) ? shared[cell] : null;
    return Boolean(v) && v[0] + v[1] >= SEGMENT_SAMPLES && v[1] * 2 >= v[0] + v[1];
  }
  // One sentence when the car is parked after a trip; nothing when there was nothing to watch.
  function summarySpeech(watched, passed, missCandidates, signalOff, tripKm = -1) {
    if (!watched && !signalOff) return '';
    // Opens with the trip's distance when the odometer gave one.
    const km = Number.isFinite(tripKm) && tripKm >= 0.5;
    let text = km ? '오늘 ' + (tripKm >= 10 ? String(Math.round(tripKm)) : tripKm.toFixed(1)) + 'km를 달렸어요. ' : '';
    text += watched ? (km ? '지켜본 회전 ' : '오늘 지켜본 회전 ') + watched + '곳 중 잘 지난 곳 ' + passed + ', 놓침 후보 ' + missCandidates + '입니다.' : '';
    if (signalOff) text += (watched ? ' ' : '') + '방향지시등 없이 지난 회전이 ' + signalOff + '번 있었어요.';
    return text;
  }
  const privateMoment = state => Boolean(state.parked) && !state.passenger;
  // Read before departure, and only for remembered points on this route.
  function briefing(profile, routePlaces, state) {
    if (!privateMoment(state)) return { points: [], speech: '' };
    const points = routePlaces.filter(place => remembers(profile, place.id)).slice(0, BRIEF_MAX);
    if (!points.length) return { points, speech: '' };
    return { points, speech: '이번 길에 기억해 둔 곳이 ' + points.length + '곳 있습니다. ' + points.map(place => place.label).join(', ') + '입니다.' };
  }
  // The briefing for a destination the navigation just received, from what was learned on earlier trips there.
  function briefingFor(profile, destination, state) { return briefing(profile, placesFor(profile, destination), state); }
  // Erases what this profile learned. Other profiles keep theirs.
  function clearMemory(profile) {
    Object.assign(profile, { typeMisses: {}, passStreak: {}, chosenWeak: [], places: [], habitMeters: null, habitSamples: 0, pending: [],
      signalTurns: 0, signalOnTurns: 0, destPlaces: {}, placeLabels: {}, roadCells: {}, placeCoords: {}, segments: {} });
  }
  // What a profile learned, as JSON, so it can be kept in the browser. Garbage is skipped rather than trusted.
  function snapshot(profile) {
    const { typeMisses, passStreak, chosenWeak, places, habitMeters, habitSamples, signalTurns, signalOnTurns, destPlaces, placeLabels, roadCells, placeCoords, segments } = profile;
    return JSON.stringify({ typeMisses, passStreak, chosenWeak, places, habitMeters, habitSamples, signalTurns, signalOnTurns, destPlaces, placeLabels, roadCells, placeCoords, segments });
  }
  function restore(profile, text) {
    let data; try { data = JSON.parse(text || ''); } catch { return; }
    if (!data || typeof data !== 'object') return;
    const count = value => Number.isInteger(value) && value >= 0 ? value : 0;
    const plain = value => value && typeof value === 'object' && !Array.isArray(value) ? value : {};
    for (const type of Object.keys(plain(data.typeMisses))) if (owns(turnTypes, type)) profile.typeMisses[type] = count(data.typeMisses[type]);
    for (const type of Object.keys(plain(data.passStreak))) if (owns(turnTypes, type)) profile.passStreak[type] = count(data.passStreak[type]);
    for (const type of Array.isArray(data.chosenWeak) ? data.chosenWeak : []) if (owns(turnTypes, type) && !profile.chosenWeak.includes(type)) profile.chosenWeak.push(type);
    for (const id of Array.isArray(data.places) ? data.places : []) if (typeof id === 'string' && id && !profile.places.includes(id)) profile.places.push(id);
    if (Number.isFinite(data.habitMeters) && data.habitMeters > 0) { profile.habitMeters = Math.round(data.habitMeters); profile.habitSamples = count(data.habitSamples); }
    profile.signalTurns = count(data.signalTurns); profile.signalOnTurns = Math.min(profile.signalTurns, count(data.signalOnTurns));
    for (const destination of Object.keys(plain(data.destPlaces))) for (const id of Array.isArray(data.destPlaces[destination]) ? data.destPlaces[destination] : []) if (typeof id === 'string' && id) rememberFor(profile, destination, id);
    for (const id of Object.keys(plain(data.placeLabels))) labelPlace(profile, id, data.placeLabels[id]);
    for (const cell of Object.keys(plain(data.roadCells))) if (/^-?\d+,-?\d+$/.test(cell) && count(data.roadCells[cell]) > 0) profile.roadCells[cell] = data.roadCells[cell];
    for (const id of Object.keys(plain(data.placeCoords))) { const c = data.placeCoords[id]; if (Array.isArray(c) && c.length === 2) placeCoord(profile, id, Number(c[0]), Number(c[1])); }
    for (const cell of Object.keys(plain(data.segments))) { const v = data.segments[cell]; if (/^-?\d+,-?\d+$/.test(cell) && Array.isArray(v) && v.length === 3 && v.every(n => Number.isInteger(n) && n >= 0) && v[0] + v[1] > 0) profile.segments[cell] = [v[0], v[1], Math.min(v[2], v[0] + v[1])]; }
  }
  return Object.freeze({ driverTypes, turnTypes, familiarities, LOWER_QUESTION, SPEED_REF_KPH, FAMILIAR_AFTER, BRIEF_SPEED_KPH, BRIEF_GAP_METERS, BRIEF_LIMIT_KPH, placeCoord, placeCoordOf, placeCoords,
    createProfile, misses, chooseWeak, isWeak, remembers, decisionMeters, signalRate, rarelySignals,
    decide, decidePoint, addMemo, labelPlace, placeLabel, rememberFor, placesFor, noteMiss, canReview, review, notePass, lowerType,
    cellKey, noteRoad, familiarity, summarySpeech, SEGMENT_SAMPLES, noteSegment, segment, sharedSegments, hardSegment,
    briefing, briefingFor, laneHint, clearMemory, snapshot, restore });
});
