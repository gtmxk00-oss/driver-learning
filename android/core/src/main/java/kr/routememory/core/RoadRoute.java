package kr.routememory.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Actual public-road data. This type never shares identifiers with the learning demo. */
public final class RoadRoute {
    public final String id;
    public final double distanceMeters, durationSeconds;
    public final List<Point> geometry;
    public final List<Step> steps;

    public RoadRoute(String id, double distanceMeters, double durationSeconds,
                     List<Point> geometry, List<Step> steps) {
        if (!Double.isFinite(distanceMeters) || distanceMeters <= 0 ||
            !Double.isFinite(durationSeconds) || durationSeconds <= 0)
            throw new IllegalArgumentException("이동 가능한 도로 경로가 필요합니다.");
        this.id = clean(id, 100);
        this.distanceMeters = distanceMeters;
        this.durationSeconds = durationSeconds;
        this.geometry = line(geometry);
        if (steps == null || steps.size() < 2 || steps.size() > 2000)
            throw new IllegalArgumentException("경로의 회전 안내가 필요합니다.");
        for (Step step : steps) if (step == null) throw new IllegalArgumentException("경로의 회전 안내가 필요합니다.");
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
    }

    static String clean(String value, int limit) {
        if (value == null) return "";
        return value.substring(0, Math.min(limit, value.length())).trim();
    }

    private static List<Point> line(List<Point> points) {
        if (points == null || points.size() < 2 || points.size() > 100000)
            throw new IllegalArgumentException("올바른 도로 좌표가 필요합니다.");
        for (Point point : points) if (point == null) throw new IllegalArgumentException("올바른 도로 좌표가 필요합니다.");
        return Collections.unmodifiableList(new ArrayList<>(points));
    }

    public static final class Point {
        public final double lat, lon;
        public Point(double lat, double lon) {
            if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180)
                throw new IllegalArgumentException("올바른 위도와 경도가 필요합니다.");
            this.lat = lat;
            this.lon = lon;
        }
    }

    public static final class Step {
        public final String name, cue, type, modifier;
        public final double distanceMeters, durationSeconds;
        public final Point location;
        public final List<Point> geometry;
        public Step(String name, String cue, String type, String modifier,
                    double distanceMeters, double durationSeconds, Point location, List<Point> geometry) {
            if (!Double.isFinite(distanceMeters) || distanceMeters < 0 ||
                !Double.isFinite(durationSeconds) || durationSeconds < 0 || location == null)
                throw new IllegalArgumentException("올바른 회전 안내가 필요합니다.");
            this.name = clean(name, 200);
            this.cue = clean(cue, 400);
            this.type = clean(type, 100);
            this.modifier = clean(modifier, 100);
            this.distanceMeters = distanceMeters;
            this.durationSeconds = durationSeconds;
            this.location = location;
            this.geometry = line(geometry);
        }
    }
}
