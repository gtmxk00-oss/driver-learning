package kr.routememory.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deterministic GPS matching. No Android, network, permissions, storage, or timer side effects. */
public final class RoadTracker {
    private static final double R = 6371008.8, RAD = Math.PI / 180;
    public enum Status { WAITING, TRACKING, OFF_ROUTE, POOR_ACCURACY, STALE, ARRIVED, STOPPED }
    public final RoadRoute route;
    public Status status = Status.WAITING;
    public String reason = "waiting-for-gps", nextCue = "GPS 위치를 기다립니다.", spoken = "";
    public double progressMeters, remainingMeters, remainingSeconds, nextDistanceMeters;
    public double distanceFromRoute = Double.NaN;
    public int nextStepIndex, offRouteCount, arrivalCount;
    public Fix accepted, position;
    public RoadRoute.Point snapped;
    private final List<Segment> segments = new ArrayList<>();
    private final double[] targets;
    private final Set<Integer> announced = new HashSet<>();
    private double length, progress;
    private long seenAt = Long.MIN_VALUE;
    private int arrivalFixes;

    public RoadTracker(RoadRoute route) {
        if (route == null) throw new IllegalArgumentException("실제 경로가 필요합니다.");
        this.route = route;
        for (int i = 0; i < route.geometry.size() - 1; i++) {
            RoadRoute.Point a = route.geometry.get(i), b = route.geometry.get(i + 1);
            double d = distance(a, b);
            if (d > .001) { segments.add(new Segment(a, b, length, d)); length += d; }
        }
        if (length < 1) throw new IllegalArgumentException("이동 가능한 도로 경로가 필요합니다.");
        targets = new double[route.steps.size()];
        double stepDistance = 0, expected = 0, previous = 0;
        for (RoadRoute.Step s : route.steps) stepDistance += s.distanceMeters;
        for (int i = 0; i < targets.length; i++) {
            RoadRoute.Step step = route.steps.get(i);
            double desired = stepDistance > 0 ? expected / stepDistance * length : 0;
            expected += step.distanceMeters;
            Projection p = project(step.location, Math.max(0, previous - 5), length, desired);
            double at = p != null && p.distance <= 30 ? p.at : desired;
            if (i == targets.length - 1 && step.type.equals("arrive")) at = length;
            targets[i] = clamp(Math.max(previous, at), 0, length);
            previous = targets[i];
        }
        remainingMeters = route.distanceMeters;
        remainingSeconds = route.durationSeconds;
        guidance();
    }

    public RoadRoute.Step nextStep() { return route.steps.get(nextStepIndex); }

    public RoadTracker update(Fix fix, long nowMillis) {
        spoken = "";
        if (status == Status.STOPPED || status == Status.ARRIVED) return this;
        if (fix == null || !Double.isFinite(fix.lat) || !Double.isFinite(fix.lon) ||
            Math.abs(fix.lat) > 90 || Math.abs(fix.lon) > 180 ||
            !Double.isFinite(fix.accuracy) || fix.accuracy < 0)
            return degraded(Status.POOR_ACCURACY, "invalid-fix");
        if (seenAt != Long.MIN_VALUE && fix.timestamp <= seenAt) {
            if (nowMillis - seenAt > 15000) return degraded(Status.STALE, "gps-update-expired");
            reason = "duplicate-or-old-fix";
            return this;
        }
        if (fix.timestamp > nowMillis + 5000) return degraded(Status.STALE, "future-fix");
        seenAt = fix.timestamp;
        if (nowMillis - fix.timestamp > 15000) return degraded(Status.STALE, "gps-update-expired");
        if (fix.accuracy > 100) {
            position = fix;
            return degraded(Status.POOR_ACCURACY, "accuracy-over-100m");
        }
        double elapsed = accepted == null ? 0 : Math.max(0, (fix.timestamp - accepted.timestamp) / 1000.0);
        double allowance = accepted == null ? Double.POSITIVE_INFINITY :
            70 * elapsed + fix.accuracy + accepted.accuracy + 25;
        RoadRoute.Point point = fix.point();
        if (accepted != null && distance(accepted.point(), point) > allowance)
            return degraded(Status.POOR_ACCURACY, "implausible-jump");
        double low = accepted == null ? 0 : Math.max(0, progress - Math.max(80, fix.accuracy * 2));
        double high = accepted == null ? length : Math.min(length, progress + Math.min(2500, Math.max(120, allowance)));
        Projection p = project(point, low, high, progress);
        if (p == null) return degraded(Status.POOR_ACCURACY, "no-local-route-match");
        accepted = fix;
        position = fix;
        snapped = p.point;
        distanceFromRoute = p.distance;
        if (p.distance > Math.max(40, fix.accuracy * 2)) {
            offRouteCount++;
            arrivalFixes = 0;
            status = offRouteCount >= 3 ? Status.OFF_ROUTE : Status.TRACKING;
            reason = offRouteCount >= 3 ? "confirmed-deviation" : "possible-deviation";
            return this;
        }
        offRouteCount = 0;
        status = Status.TRACKING;
        reason = "gps-on-route";
        progress = p.at;
        progressMeters = progress / length * route.distanceMeters;
        remainingMeters = Math.max(0, route.distanceMeters - progressMeters);
        remainingSeconds = route.durationSeconds * remainingMeters / route.distanceMeters;
        guidance();
        RoadRoute.Point endpoint = route.geometry.get(route.geometry.size() - 1);
        if (fix.accuracy <= 30 && distance(point, endpoint) <= 40 && remainingMeters <= 50) arrivalFixes++;
        else arrivalFixes = 0;
        if (arrivalFixes >= 2) {
            status = Status.ARRIVED;
            reason = "destination-confirmed";
            arrivalCount = 1;
            remainingMeters = remainingSeconds = nextDistanceMeters = 0;
            progressMeters = route.distanceMeters;
            nextCue = spoken = "목적지에 도착했습니다.";
            return this;
        }
        if (nextDistanceMeters <= 300 && announced.add(nextStepIndex)) {
            spoken = nextStep().type.equals("arrive")
                ? Math.max(10, Math.round(nextDistanceMeters / 10) * 10) + "m 뒤 목적지 주변입니다."
                : nextCue;
        }
        return this;
    }

    /** A watchdog can expire a fix without inventing a new GPS observation. */
    public RoadTracker checkFreshness(long nowMillis) {
        spoken = "";
        if (status != Status.STOPPED && status != Status.ARRIVED && seenAt != Long.MIN_VALUE && nowMillis - seenAt > 15000)
            return degraded(Status.STALE, "gps-update-expired");
        return this;
    }

    public RoadTracker stop() {
        status = Status.STOPPED;
        reason = "user-stopped";
        nextCue = "GPS 길 안내를 종료했습니다.";
        spoken = "";
        accepted = position = null;
        snapped = null;
        seenAt = Long.MIN_VALUE;
        distanceFromRoute = Double.NaN;
        arrivalFixes = offRouteCount = 0;
        nextDistanceMeters = 0;
        return this;
    }

    private RoadTracker degraded(Status next, String why) {
        status = next;
        reason = why;
        arrivalFixes = offRouteCount = 0;
        return this;
    }

    private void guidance() {
        int i = 0;
        while (i < targets.length - 1 && (route.steps.get(i).type.equals("depart") || targets[i] + 8 < progress)) i++;
        nextStepIndex = i;
        RoadRoute.Step step = nextStep();
        nextCue = step.type.equals("arrive") ? "목적지까지 남은 경로를 따라 이동하세요." : step.cue;
        nextDistanceMeters = Math.max(0, (targets[i] - progress) / length * route.distanceMeters);
    }

    private Projection project(RoadRoute.Point point, double low, double high, double preferred) {
        Projection best = null;
        double sx = R * RAD * Math.cos(point.lat * RAD), sy = R * RAD;
        for (Segment s : segments) {
            if (s.start + s.length < low || s.start > high) continue;
            double ax = (s.a.lon - point.lon) * sx, ay = (s.a.lat - point.lat) * sy;
            double dx = (s.b.lon - s.a.lon) * sx, dy = (s.b.lat - s.a.lat) * sy;
            double square = dx * dx + dy * dy;
            double minT = clamp((low - s.start) / s.length, 0, 1), maxT = clamp((high - s.start) / s.length, 0, 1);
            double t = clamp(square > 0 ? -(ax * dx + ay * dy) / square : 0, minT, maxT);
            RoadRoute.Point matched = new RoadRoute.Point(s.a.lat + t * (s.b.lat - s.a.lat), s.a.lon + t * (s.b.lon - s.a.lon));
            double d = distance(point, matched), at = s.start + t * s.length;
            if (best == null || d < best.distance - .1 ||
                Math.abs(d - best.distance) <= .1 && Math.abs(at - preferred) < Math.abs(best.at - preferred))
                best = new Projection(at, d, matched);
        }
        return best;
    }

    public static double distance(RoadRoute.Point a, RoadRoute.Point b) {
        double dLat = (b.lat - a.lat) * RAD, dLon = (b.lon - a.lon) * RAD;
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(a.lat * RAD) * Math.cos(b.lat * RAD) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * R * Math.atan2(Math.sqrt(Math.max(0, h)), Math.sqrt(Math.max(0, 1 - h)));
    }
    private static double clamp(double n, double low, double high) { return Math.max(low, Math.min(high, n)); }
    private static final class Segment {
        final RoadRoute.Point a, b;
        final double start, length;
        Segment(RoadRoute.Point a, RoadRoute.Point b, double start, double length) {
            this.a = a; this.b = b; this.start = start; this.length = length;
        }
    }
    private static final class Projection {
        final double at, distance;
        final RoadRoute.Point point;
        Projection(double at, double distance, RoadRoute.Point point) { this.at = at; this.distance = distance; this.point = point; }
    }
    public static final class Fix {
        public final double lat, lon, accuracy;
        public final long timestamp;
        public Fix(double lat, double lon, double accuracy, long timestamp) {
            this.lat = lat; this.lon = lon; this.accuracy = accuracy; this.timestamp = timestamp;
        }
        public RoadRoute.Point point() { return new RoadRoute.Point(lat, lon); }
    }
}
