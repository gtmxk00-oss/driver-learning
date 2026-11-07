package kr.routememory.core;

import java.util.Arrays;
import java.util.List;

/** Runs without a device, network, or GPS access. */
public final class RoadTrackerTest {
    private static int checks;
    private static final long NOW = 1800000000000L;
    private static final RoadRoute.Point A = new RoadRoute.Point(0, 0), B = new RoadRoute.Point(0, .005), C = new RoadRoute.Point(.005, .005);
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static RoadTracker.Fix fix(double lat, double lon, double accuracy, long seconds) {
        return new RoadTracker.Fix(lat, lon, accuracy, NOW + seconds * 1000);
    }
    private static RoadRoute route() {
        double half = RoadTracker.distance(A, B);
        RoadRoute.Step depart = new RoadRoute.Step("출발 도로", "출발하세요", "depart", "straight", half, 50, A, Arrays.asList(A, B));
        RoadRoute.Step turn = new RoadRoute.Step("다음 도로", "좌회전하세요", "turn", "left", half, 50, B, Arrays.asList(B, C));
        RoadRoute.Step arrive = new RoadRoute.Step("", "도착합니다", "arrive", "", 0, 0, C, Arrays.asList(C, C));
        return new RoadRoute("actual-test", half * 2, 100, Arrays.asList(A, B, C), Arrays.asList(depart, turn, arrive));
    }
    private static void update(RoadTracker t, double lat, double lon, double accuracy, long seconds) {
        t.update(fix(lat, lon, accuracy, seconds), NOW + seconds * 1000);
    }
    public static void main(String[] args) {
        RoadTracker t = new RoadTracker(route());
        check(t.status == RoadTracker.Status.WAITING && t.accepted == null, "no invented GPS on creation");
        check(t.nextStepIndex == 1 && t.nextCue.equals("좌회전하세요"), "targets next maneuver, not depart");
        update(t, 0, .0001, 5, 0);
        check(t.status == RoadTracker.Status.TRACKING && t.progressMeters > 10 && t.nextDistanceMeters > 500, "projects real road progress");
        check(t.spoken.isEmpty(), "does not announce distant turn");
        update(t, 0, .003, 5, 10);
        check(t.nextStepIndex == 1 && t.spoken.equals("좌회전하세요"), "announces approaching maneuver once");
        update(t, 0, .0031, 5, 11);
        check(t.spoken.isEmpty(), "no repeated announcement");
        update(t, .001, .005, 5, 21);
        check(t.nextStepIndex == 2 && t.nextStep().type.equals("arrive"), "advances cue after turn");
        double progress = t.progressMeters;
        RoadTracker.Fix accepted = t.accepted;
        update(t, .005, .005, 200, 31);
        check(t.status == RoadTracker.Status.POOR_ACCURACY && t.progressMeters == progress && t.accepted == accepted, "poor accuracy cannot advance or become accepted");
        update(t, .1, .1, 5, 32);
        check(t.reason.equals("implausible-jump") && t.accepted == accepted && t.progressMeters == progress, "teleport rejected without poisoning accepted position");
        t.checkFreshness(NOW + 48 * 1000);
        check(t.status == RoadTracker.Status.STALE && t.reason.equals("gps-update-expired"), "watchdog expires GPS without inventing an observation");
        update(t, .002, .005, 5, 49);
        check(t.status == RoadTracker.Status.TRACKING, "new confident fix recovers after outage");

        t = new RoadTracker(route());
        update(t, .001, .001, 5, 0);
        update(t, .001, .0011, 5, 10);
        check(t.offRouteCount == 2 && t.status == RoadTracker.Status.TRACKING, "one or two offroute fixes are only candidates");
        t.update(fix(.001, .0011, 5, 10), NOW + 11000);
        check(t.offRouteCount == 2, "duplicate fix cannot create deviation evidence");
        update(t, .001, .0012, 5, 20);
        check(t.status == RoadTracker.Status.OFF_ROUTE && t.offRouteCount == 3 && t.progressMeters == 0, "requires three distinct fixes for offroute and freezes progress");
        update(t, 0, .0012, 5, 30);
        check(t.status == RoadTracker.Status.TRACKING && t.offRouteCount == 0, "returns to road after transient detour");

        t = new RoadTracker(route());
        t.update(fix(0, .003, 5, 0), NOW + 16000);
        check(t.status == RoadTracker.Status.STALE && t.progressMeters == 0 && t.accepted == null, "cached stale fix cannot start guidance");
        t = new RoadTracker(route());
        t.update(fix(0, .003, 5, 10), NOW);
        check(t.status == RoadTracker.Status.STALE && t.accepted == null, "future clock fix rejected");
        t = new RoadTracker(route());
        t.update(fix(Double.NaN, 0, 5, 0), NOW);
        check(t.status == RoadTracker.Status.POOR_ACCURACY && t.accepted == null, "invalid fix rejected without projection");

        t = new RoadTracker(route());
        update(t, .0049, .005, 5, 0);
        check(t.status != RoadTracker.Status.ARRIVED && t.arrivalCount == 0, "single endpoint observation cannot confirm arrival");
        t.update(fix(.0049, .005, 5, 0), NOW + 1000);
        check(t.status != RoadTracker.Status.ARRIVED, "duplicate endpoint observation cannot confirm arrival");
        update(t, .005, .005, 5, 2);
        check(t.status == RoadTracker.Status.ARRIVED && t.remainingMeters == 0 && t.arrivalCount == 1 && t.spoken.equals("목적지에 도착했습니다."), "two distinct accurate endpoint observations confirm arrival once");
        update(t, 0, 0, 5, 12);
        check(t.status == RoadTracker.Status.ARRIVED && t.spoken.isEmpty(), "arrival is terminal and speech is one shot");

        t = new RoadTracker(route());
        update(t, .0049, .005, 60, 0);
        update(t, .005, .005, 60, 2);
        check(t.status != RoadTracker.Status.ARRIVED, "coarse location cannot establish arrival");
        t.stop();
        update(t, 0, 0, 5, 12);
        check(t.status == RoadTracker.Status.STOPPED && t.accepted == null && t.position == null && t.snapped == null, "stop clears GPS data and rejects late callbacks");
        check(RoadTracker.distance(new RoadRoute.Point(37, 127), new RoadRoute.Point(37, 127)) == 0, "distance uses explicit lat/lon points");
        boolean invalid = false;
        try { new RoadRoute.Point(91, 0); } catch (IllegalArgumentException expected) { invalid = true; }
        check(invalid, "invalid point cannot enter route data");
        boolean immutable = false;
        try { route().geometry.add(A); } catch (UnsupportedOperationException expected) { immutable = true; }
        check(immutable, "route geometry cannot be mutated after tracking starts");
        System.out.println("PASS: " + checks + " actual-road tracker checks");
    }
}
