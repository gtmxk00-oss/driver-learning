(function (root, factory) {
  const guidance = factory(typeof module === 'object' && module.exports ? require('./coach.js') : root.RMCoach);
  if (typeof module === 'object' && module.exports) module.exports = guidance;
  else root.RMCoachGuidance = guidance;
})(typeof globalThis !== 'undefined' ? globalThis : this, function (C) {
  'use strict';

  // Same rules as Android core GuidanceSession.java. Change both together.
  // Turns real navigation and vehicle events into the companion's decisions: next turn with remaining metres,
  // turn signal, gear, reroute, caution points. The vehicle SDK bridge maps vendor types onto these calls.
  const MISS_WINDOW_MS = 90000, MISS_NEAR_METERS = 150, QUESTION = '아까 그 출구, 놓치셨나요?';
  const none = () => ({ event: 'none', speech: '', decision: null });
  const matches = (signal, type) => Boolean(C.turnTypes[type].side) && signal === C.turnTypes[type].side;

  function create(profile) {
    return { profile, current: null, meters: -1, announced: false, signalOnMeters: 0, signal: 'off', passenger: false, parked: true,
      navigating: false, lastPassed: null, lastPassedJudged: false, question: null };
  }
  function judge(s) {
    const d = C.decide(s.profile, s.current, { meters: s.meters, signal: s.signal, passenger: s.passenger });
    // A driver who signalled once for this turn has prepared, even if the signal went off again.
    if (d.action === 'silent' || s.signalOnMeters > 0 && d.action === 'speak') return { event: 'silent', speech: '', decision: d };
    if (d.action === 'speak' && !s.announced) { s.announced = true; return { event: 'speak', speech: d.speech, decision: d }; }
    return { event: 'none', speech: '', decision: d };
  }
  function passTurn(s, now) {
    const previous = s.lastPassedJudged ? null : s.lastPassed;
    s.lastPassed = { turn: s.current, at: now, signalOnMeters: s.signalOnMeters }; s.lastPassedJudged = false;
    if (!previous) return none();
    // The turn before this one was neither rerouted nor missed, so it counts as a pass.
    const watched = C.isWeak(s.profile, previous.turn.type) || C.remembers(s.profile, previous.turn.id);
    C.notePass(s.profile, previous.turn, previous.signalOnMeters);
    return watched ? { event: 'passed', speech: '', decision: null } : none();
  }
  function clearTurn(s, now) {
    const passed = s.current ? passTurn(s, now) : none();
    s.current = null; s.meters = -1; s.announced = false; s.signalOnMeters = 0;
    return passed;
  }
  function reviewIfPossible(s) {
    if (!C.canReview(s.profile, { parked: s.parked, passenger: s.passenger })) return none();
    s.question = QUESTION;
    return { event: 'question', speech: QUESTION, decision: null };
  }
  // The next turn from the navigation, with the distance still to go.
  function onTurn(s, turn, remainingMeters, now) {
    if (!turn) return clearTurn(s, now);
    const changed = !s.current || s.current.id !== turn.id || remainingMeters > s.meters + MISS_NEAR_METERS;
    if (changed) {
      const passed = s.current ? passTurn(s, now) : null;
      s.current = turn; s.announced = false; s.signalOnMeters = 0;
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
  function onGear(s, parked) { s.parked = parked; return parked ? reviewIfPossible(s) : none(); }
  function onNavigationStatus(s, routeDrive, now) { s.navigating = routeDrive; return routeDrive ? none() : clearTurn(s, now); }
  // The navigation computed a new route on its own: the driver may have left the planned one.
  function onReroute(s, now) {
    let suspect = null;
    if (s.lastPassed && !s.lastPassedJudged && now - s.lastPassed.at <= MISS_WINDOW_MS && s.lastPassed.signalOnMeters === 0) suspect = s.lastPassed.turn;
    else if (s.current && s.meters >= 0 && s.meters <= MISS_NEAR_METERS && s.signalOnMeters === 0) suspect = s.current;
    if (!suspect) return none();
    C.noteMiss(s.profile, suspect);
    s.lastPassedJudged = true; s.current = null; s.meters = -1; s.announced = false; s.signalOnMeters = 0;
    const asked = reviewIfPossible(s);
    return asked.event === 'question' ? asked : { event: 'miss-candidate', speech: '', decision: null };
  }
  // The driver answered the parked question.
  function answer(s, confirmed) { s.question = null; return C.review(s.profile, confirmed); }
  return Object.freeze({ MISS_WINDOW_MS, MISS_NEAR_METERS, create, onTurn, onCaution, onSignal, onGear, onNavigationStatus, onReroute, answer });
});
