(function (root, factory) {
  const guidance = factory(typeof module === 'object' && module.exports ? require('./coach.js') : root.RMCoach);
  if (typeof module === 'object' && module.exports) module.exports = guidance;
  else root.RMCoachGuidance = guidance;
})(typeof globalThis !== 'undefined' ? globalThis : this, function (C) {
  'use strict';

  // Same rules as Android core GuidanceSession.java. Change both together.
  // Turns real navigation and vehicle events into the companion's decisions: next turn with remaining metres,
  // turn signal, gear, reroute, caution points, speed, position, destination, and the extra signals a Pleos car gives
  // (brake, steering angle, wiper, low beam, seat belt, trip distance, the road's speed limit, arrival, a remembered
  // spot coming near). The vehicle SDK bridge maps vendor types onto these calls. What the car cannot give stays unknown.
  const MISS_WINDOW_MS = 90000, MISS_NEAR_METERS = 150, QUESTION = '아까 그 출구, 놓치셨나요?';
  // The wheel turned this far while this close to the turn: the driver took it, whatever the navigation says next.
  const STEER_TURN_DEG = 45, STEER_NEAR_METERS = 300;
  // Brake held at walking pace or less counts as stopped.
  const STOPPED_KPH = 3;
  const none = () => ({ event: 'none', speech: '', decision: null });
  const matches = (signal, type) => Boolean(C.turnTypes[type].side) && signal === C.turnTypes[type].side;

  function create(profile) {
    return { profile, current: null, meters: -1, announced: false, signalOnMeters: 0, turnedNear: false, signal: 'off', passenger: false, parked: true,
      navigating: false, lastPassed: null, lastPassedJudged: false, question: null, lowerAsked: null,
      speedKph: -1, nextGapMeters: -1, familiarity: 'unknown', destination: null, lastCell: null, tripCells: [],
      pendingLower: null, queued: [], tripPassed: 0, tripMisses: 0, tripSignalOff: 0, tripSpoken: 0,
      // The extra car signals; each stays at "unknown" until the car reports it.
      rain: false, night: false, brake: false, arrived: false, steerDeg: 0, tripKm: -1, roadLimitKph: -1,
      lastLat: NaN, lastLon: NaN, pendingCoords: {} };
  }
  const stopped = s => s.brake && s.speedKph >= 0 && s.speedKph <= STOPPED_KPH;
  const canAsk = s => s.parked || s.arrived && stopped(s);
  const brief = s => s.speedKph >= C.BRIEF_SPEED_KPH || s.nextGapMeters > 0 && s.nextGapMeters <= C.BRIEF_GAP_METERS || s.roadLimitKph >= C.BRIEF_LIMIT_KPH;
  function judge(s) {
    const d = C.decide(s.profile, s.current, { meters: s.meters, signal: s.signal, passenger: s.passenger, speedKph: s.speedKph, familiarity: s.familiarity, brief: brief(s), rain: s.rain, night: s.night });
    // A driver who signalled once for this turn has prepared, even if the signal went off again.
    if (d.action === 'silent' || s.signalOnMeters > 0 && d.action === 'speak') return { event: 'silent', speech: '', decision: d };
    if (d.action === 'speak' && !s.announced) { s.announced = true; s.tripSpoken++; return { event: 'speak', speech: d.speech, decision: d }; }
    return { event: 'none', speech: '', decision: d };
  }
  // The last turn left behind was neither rerouted nor missed, so it counts as a pass.
  function settle(s) {
    if (!s.lastPassed || s.lastPassedJudged) return none();
    const previous = s.lastPassed; s.lastPassedJudged = true;
    const watched = C.isWeak(s.profile, previous.turn.type) || C.remembers(s.profile, previous.turn.id);
    if (C.turnTypes[previous.turn.type].side && previous.signalOnMeters === 0) s.tripSignalOff++;
    if (C.notePass(s.profile, previous.turn, previous.signalOnMeters).offerLower) s.pendingLower = previous.turn.type;
    if (!watched) return none();
    s.tripPassed++;
    return { event: 'passed', speech: '', decision: null };
  }
  function passTurn(s, now) {
    const settled = settle(s);
    s.lastPassed = { turn: s.current, at: now, signalOnMeters: s.signalOnMeters, turned: s.turnedNear }; s.lastPassedJudged = false;
    return settled;
  }
  function clearTurn(s, now) {
    const passed = s.current ? passTurn(s, now) : none();
    s.current = null; s.meters = -1; s.announced = false; s.signalOnMeters = 0; s.turnedNear = false;
    return passed;
  }
  function reviewIfPossible(s) {
    if (!C.canReview(s.profile, { parked: canAsk(s), passenger: s.passenger })) return none();
    s.question = QUESTION; s.lowerAsked = null;
    return { event: 'question', speech: QUESTION, decision: null };
  }
  // The next turn from the navigation, with the distance still to go, and optionally the distance on to the turn after it.
  function onTurn(s, turn, remainingMeters, now, gapToNextMeters = -1) {
    s.nextGapMeters = Number.isFinite(gapToNextMeters) ? gapToNextMeters : -1;
    if (!turn) return clearTurn(s, now);
    const changed = !s.current || s.current.id !== turn.id || remainingMeters > s.meters + MISS_NEAR_METERS;
    if (changed) {
      const passed = s.current ? passTurn(s, now) : null;
      s.current = turn; s.announced = false; s.signalOnMeters = 0; s.turnedNear = false;
      if (passed && passed.event === 'passed') { s.meters = remainingMeters; return passed; }
    }
    s.meters = remainingMeters;
    if (matches(s.signal, turn.type) && s.signalOnMeters === 0) s.signalOnMeters = Math.max(1, remainingMeters);
    return judge(s);
  }
  const onCaution = (s, id, remainingMeters, now) => onTurn(s, { id, type: 'narrow-road' }, remainingMeters, now);
  function onSignal(s, signal, now) {
    s.signal = signal;
    if (s.current && matches(signal, s.current.type) && s.signalOnMeters === 0) s.signalOnMeters = Math.max(1, s.meters);
    return s.current ? judge(s) : none();
  }
  // The car's speed in km/h; a change can bring the turn inside the decision distance.
  function onSpeed(s, kph) { s.speedKph = Number.isFinite(kph) && kph >= 0 ? Math.round(kph) : -1; return s.current ? judge(s) : none(); }
  // The car's position: counts the road cell once per trip and tells how familiar this road is.
  function onLocation(s, lat, lon) {
    s.lastLat = lat; s.lastLon = lon;
    const cell = C.cellKey(lat, lon);
    if (cell === s.lastCell) return none();
    s.lastCell = cell;
    s.familiarity = C.familiarity(s.profile, cell);
    if (!s.parked && !s.tripCells.includes(cell)) { s.tripCells.push(cell); C.noteRoad(s.profile, cell); }
    return s.current ? judge(s) : none();
  }
  // Sets the familiarity directly, for a console or a test that has no positions to give.
  function setFamiliarity(s, value) { s.familiarity = C.familiarities.includes(value) ? value : 'unknown'; return s.current ? judge(s) : none(); }
  // The destination the navigation is heading for: while parked, the briefing of what was learned on earlier trips there.
  function onDestination(s, name) {
    s.destination = typeof name === 'string' && name.trim() ? name.trim() : null;
    if (!s.destination) return none();
    const b = C.briefingFor(s.profile, s.destination, { parked: s.parked, passenger: s.passenger });
    return b.speech ? { event: 'briefing', speech: b.speech, decision: null } : none();
  }
  function tripStart(s) { s.tripPassed = 0; s.tripMisses = 0; s.tripSignalOff = 0; s.tripSpoken = 0; s.tripCells = []; s.lastCell = null; s.queued = []; s.arrived = false; s.tripKm = -1; s.turnedNear = false; }
  // Parked after driving: the miss question first, then the offer to lower a type, then the summary, one at a time.
  function tripEnd(s) {
    s.queued = [];
    settle(s);
    const review = reviewIfPossible(s);
    if (review.event === 'question') { s.question = null; s.queued.push(review); }
    if (s.pendingLower && C.isWeak(s.profile, s.pendingLower) && !s.passenger) s.queued.push({ event: 'lower-question', speech: C.LOWER_QUESTION, decision: null });
    const summary = s.passenger || C.driverTypes[s.profile.driverType].neutralOnly ? '' : C.summarySpeech(s.tripPassed + s.tripMisses, s.tripPassed, s.tripMisses, s.tripSignalOff, s.tripKm);
    if (summary) s.queued.push({ event: 'summary', speech: summary, decision: null });
    const first = s.queued.shift();
    if (!first) return none();
    if (first.event === 'question') s.question = first.speech;
    if (first.event === 'lower-question') { s.question = first.speech; s.lowerAsked = s.pendingLower; s.pendingLower = null; }
    return first;
  }
  function onGear(s, parked) {
    const was = s.parked; s.parked = parked;
    if (was && !parked) { tripStart(s); return none(); }
    if (!was && parked) return tripEnd(s);
    return parked ? reviewIfPossible(s) : none();
  }
  function onNavigationStatus(s, routeDrive, now) { s.navigating = routeDrive; return routeDrive ? none() : clearTurn(s, now); }
  // The navigation computed a new route on its own: the driver may have left the planned one.
  function onReroute(s, now) {
    let suspect = null;
    if (s.lastPassed && !s.lastPassedJudged && now - s.lastPassed.at <= MISS_WINDOW_MS && s.lastPassed.signalOnMeters === 0) {
      // The wheel turned at that exit: the driver took it, so the new route is not a miss.
      if (s.lastPassed.turned) return settle(s);
      suspect = s.lastPassed.turn;
    } else if (s.current && s.meters >= 0 && s.meters <= MISS_NEAR_METERS && s.signalOnMeters === 0) {
      if (s.turnedNear) return none();
      suspect = s.current;
    }
    if (!suspect) return none();
    C.noteMiss(s.profile, suspect); s.tripMisses++;
    if (Number.isFinite(s.lastLat)) s.pendingCoords[suspect.id] = [s.lastLat, s.lastLon];
    // Blamed on the current turn, the turn before it was passed after all.
    if (suspect === s.current) settle(s);
    s.lastPassedJudged = true; s.current = null; s.meters = -1; s.announced = false; s.signalOnMeters = 0; s.turnedNear = false;
    const asked = reviewIfPossible(s);
    return asked.event === 'question' ? asked : { event: 'miss-candidate', speech: '', decision: null };
  }
  // The driver answered the parked question about a miss. A confirmed miss keeps where it happened, for the proximity alert.
  function answer(s, confirmed) {
    s.question = null; s.lowerAsked = null;
    const r = C.review(s.profile, confirmed, s.destination);
    if (r) { const at = s.pendingCoords[r.id]; delete s.pendingCoords[r.id]; if (r.confirmed && at) C.placeCoord(s.profile, r.id, at[0], at[1]); }
    return r;
  }
  // The driver answered the offer to lower a weak type.
  function answerLower(s, lower) { if (lower && s.lowerAsked) C.lowerType(s.profile, s.lowerAsked); s.question = null; s.lowerAsked = null; }
  // What else was waiting behind the question just answered: the next question, or the trip summary.
  function afterAnswer(s) {
    const next = s.queued.shift();
    if (!next) return none();
    if (next.event === 'question') s.question = next.speech;
    if (next.event === 'lower-question') { s.question = next.speech; s.lowerAsked = s.pendingLower; s.pendingLower = null; }
    return next;
  }
  // The driver marks this spot as confusing, by voice or by a button; it joins the memory with its own words and its position.
  function memo(s, text) {
    const words = typeof text === 'string' ? text.trim() : '';
    const placeId = s.current ? s.current.id : s.lastCell ? 'memo|' + s.lastCell : null;
    if (!placeId || !words) return none();
    C.addMemo(s.profile, placeId); C.labelPlace(s.profile, placeId, words);
    C.rememberFor(s.profile, s.destination, placeId);
    if (Number.isFinite(s.lastLat)) C.placeCoord(s.profile, placeId, s.lastLat, s.lastLon);
    return { event: 'memo', speech: '기억해 둘게요. ' + words, decision: null };
  }

  // The extra car signals.
  // The front wiper: rain means the decision distance grows.
  function onWiper(s, on) { s.rain = Boolean(on); return s.current ? judge(s) : none(); }
  // The low beam: night means the decision distance grows a little.
  function onLowBeam(s, on) { s.night = Boolean(on); return s.current ? judge(s) : none(); }
  // The steering wheel angle in degrees; a real turn near the watched turn means it was taken.
  function onSteering(s, degrees) {
    s.steerDeg = Number.isFinite(degrees) ? degrees : 0;
    if (s.current && Math.abs(s.steerDeg) >= STEER_TURN_DEG && s.meters >= 0 && s.meters <= STEER_NEAR_METERS) s.turnedNear = true;
    return none();
  }
  // The brake pedal; held after arriving, the car is stopped enough to be asked.
  function onBrake(s, pressed) { s.brake = Boolean(pressed); return s.arrived && stopped(s) ? reviewIfPossible(s) : none(); }
  // The navigation says the destination is reached: the question may come at this stop, before the gear goes to P.
  function onArrival(s) { s.arrived = true; return canAsk(s) ? reviewIfPossible(s) : none(); }
  // The driver's seat belt coming off at a standstill ends the trip like parking does.
  function onSeatBelt(s, buckled) { return !buckled && !s.parked && s.speedKph >= 0 && s.speedKph <= STOPPED_KPH ? onGear(s, true) : none(); }
  // The trip distance from the odometer, in km, for the summary.
  function onTripDistance(s, km) { s.tripKm = Number.isFinite(km) && km >= 0 ? km : -1; return none(); }
  // The road's speed limit from the navigation: on a fast road the sentence is cut to its core.
  function onSpeedLimit(s, kph) { s.roadLimitKph = Number.isFinite(kph) && kph > 0 ? kph : -1; return s.current ? judge(s) : none(); }
  // A remembered spot came within reach (a proximity alert) while no route is active: say it, as for a bus without a route.
  function onProximity(s, placeId) {
    if (s.navigating || s.parked || s.passenger) return none();
    const d = C.decidePoint(s.profile, placeId);
    if (d.action !== 'speak') return none();
    s.tripSpoken++;
    return { event: 'speak', speech: d.speech, decision: d };
  }
  return Object.freeze({ MISS_WINDOW_MS, MISS_NEAR_METERS, STEER_TURN_DEG, STEER_NEAR_METERS, STOPPED_KPH, QUESTION, create, onTurn, onCaution, onSignal, onSpeed, onLocation, setFamiliarity,
    onDestination, onGear, onNavigationStatus, onReroute, answer, answerLower, afterAnswer, memo,
    onWiper, onLowBeam, onSteering, onBrake, onArrival, onSeatBelt, onTripDistance, onSpeedLimit, onProximity });
});
