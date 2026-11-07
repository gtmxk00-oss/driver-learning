package kr.routememory.shared;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import kr.routememory.core.RoadRoute;
import kr.routememory.core.RoadTracker;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Public OSRM prototype adapter. Coordinates are sent only for a requested route and never persisted. */
public final class OsrmClient {
    private static final Object REQUEST_LOCK = new Object();
    private static final int MAX_BODY = 2 * 1024 * 1024;
    private static long lastRequestNanos = System.nanoTime() - 1000000000L;

    /** Call from a worker. A single app-wide lock enforces at most one upstream request per second. */
    public RoadRoute route(RoadRoute.Point origin, RoadRoute.Point destination) throws IOException {
        if (origin == null || destination == null || RoadTracker.distance(origin, destination) < 1)
            throw new IOException("출발지와 목적지를 다르게 선택해주세요.");
        synchronized (REQUEST_LOCK) {
            long wait = 1000 - (System.nanoTime() - lastRequestNanos) / 1000000;
            if (wait > 0) {
                try { Thread.sleep(wait); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("경로 요청을 취소했습니다."); }
            }
            if (Thread.currentThread().isInterrupted()) throw new IOException("경로 요청을 취소했습니다.");
            lastRequestNanos = System.nanoTime();
            String coordinates = origin.lon + "," + origin.lat + ";" + destination.lon + "," + destination.lat;
            HttpURLConnection connection = null;
            try {
                URL url = new URL("https://router.project-osrm.org/route/v1/driving/" + coordinates +
                    "?overview=full&geometries=geojson&steps=true&alternatives=false&continue_straight=true");
                connection = (HttpURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(12000);
                connection.setUseCaches(false);
                connection.setRequestProperty("User-Agent", "RouteMemory/0.2.0 (Android; kr.routememory; navigation prototype)");
                connection.setRequestProperty("Accept", "application/json");
                int status = connection.getResponseCode();
                if (status == 429) throw new IOException("공개 경로 서버가 요청을 제한했습니다. 잠시 후 다시 시도해주세요.");
                if (status != 200) throw new IOException("실제 도로 경로 서버에 연결하지 못했습니다. 잠시 후 다시 시도해주세요.");
                if (connection.getContentLengthLong() > MAX_BODY) throw new IOException("경로 정보가 너무 큽니다. 가까운 목적지를 선택해주세요.");
                String payload;
                long deadline = System.nanoTime() + 15000000000L;
                try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("경로 요청을 취소했습니다.");
                        if (System.nanoTime() > deadline) throw new IOException("경로 서버의 응답 시간이 초과되었습니다.");
                        if (out.size() + count > MAX_BODY) throw new IOException("경로 정보가 너무 큽니다. 가까운 목적지를 선택해주세요.");
                        out.write(buffer, 0, count);
                    }
                    payload = out.toString(StandardCharsets.UTF_8.name());
                }
                RoadRoute result = parse(payload);
                // Reject surprising provider snaps, independently from its reported waypoint distances.
                if (RoadTracker.distance(origin, result.geometry.get(0)) > 1050 ||
                    RoadTracker.distance(destination, result.geometry.get(result.geometry.size() - 1)) > 1050)
                    throw new IOException("선택한 위치에서 이용 가능한 도로가 너무 멉니다. 도로 가까운 곳을 선택해주세요.");
                return result;
            } catch (java.net.SocketTimeoutException e) {
                throw new IOException("경로 서버의 응답 시간이 초과되었습니다. 다시 시도해주세요.");
            } catch (java.net.UnknownHostException | javax.net.ssl.SSLException e) {
                throw new IOException("경로 서버에 연결하지 못했습니다. 인터넷 연결을 확인해주세요.");
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
    }

    static RoadRoute parse(String payload) throws IOException {
        try {
            if (payload == null || payload.length() > MAX_BODY) throw invalid();
            JSONObject root = new JSONObject(payload);
            String code = root.optString("code");
            if (!code.equals("Ok")) {
                if (code.equals("NoRoute") || code.equals("NoSegment"))
                    throw new IOException("선택한 좌표 사이의 도로 경로를 찾지 못했습니다. 가까운 도로를 선택해주세요.");
                throw invalid();
            }
            JSONArray waypoints = root.getJSONArray("waypoints");
            if (waypoints.length() != 2) throw invalid();
            RoadRoute.Point[] snaps = new RoadRoute.Point[2];
            for (int i = 0; i < 2; i++) {
                JSONObject waypoint = waypoints.getJSONObject(i);
                snaps[i] = point(waypoint.getJSONArray("location"));
                double snapDistance = nonnegative(waypoint, "distance");
                if (snapDistance > 1000) throw new IOException("선택한 위치에서 이용 가능한 도로가 너무 멉니다. 도로 가까운 곳을 선택해주세요.");
            }
            JSONArray routes = root.getJSONArray("routes");
            if (routes.length() < 1) throw invalid();
            JSONObject raw = routes.getJSONObject(0);
            double distance = positive(raw, "distance"), duration = positive(raw, "duration");
            List<RoadRoute.Point> geometry = line(raw.getJSONObject("geometry"));
            if (RoadTracker.distance(geometry.get(0), snaps[0]) > 50 ||
                RoadTracker.distance(geometry.get(geometry.size() - 1), snaps[1]) > 50) throw invalid();
            JSONArray legs = raw.getJSONArray("legs");
            if (legs.length() != 1) throw invalid();
            JSONArray rawSteps = legs.getJSONObject(0).getJSONArray("steps");
            if (rawSteps.length() < 2 || rawSteps.length() > 2000) throw invalid();
            List<RoadRoute.Step> steps = new ArrayList<>();
            int count = 0;
            double sum = 0;
            for (int i = 0; i < rawSteps.length(); i++) {
                JSONObject step = rawSteps.getJSONObject(i), maneuver = step.getJSONObject("maneuver");
                List<RoadRoute.Point> points = line(step.getJSONObject("geometry"));
                count += points.size();
                if (count > 100000) throw invalid();
                RoadRoute.Point location = point(maneuver.getJSONArray("location"));
                if (RoadTracker.distance(points.get(0), location) > 50 ||
                    i > 0 && RoadTracker.distance(steps.get(i - 1).geometry.get(steps.get(i - 1).geometry.size() - 1), points.get(0)) > 25)
                    throw invalid();
                String name = text(step, "name"), type = text(maneuver, "type"), modifier = text(maneuver, "modifier");
                if (type.isEmpty()) throw invalid();
                double stepDistance = nonnegative(step, "distance"), stepDuration = nonnegative(step, "duration");
                sum += stepDistance;
                steps.add(new RoadRoute.Step(name, koreanCue(type, modifier, maneuver.optInt("exit", 0), name),
                    type, modifier, stepDistance, stepDuration, location, points));
            }
            RoadRoute.Step first = steps.get(0), last = steps.get(steps.size() - 1);
            if (!first.type.equals("depart") || !last.type.equals("arrive") ||
                RoadTracker.distance(first.geometry.get(0), geometry.get(0)) > 25 ||
                RoadTracker.distance(last.geometry.get(last.geometry.size() - 1), geometry.get(geometry.size() - 1)) > 25 ||
                Math.abs(sum - distance) > Math.max(100, distance * .1)) throw invalid();
            return new RoadRoute("osrm-real-road", distance, duration, geometry, steps);
        } catch (JSONException | IllegalArgumentException e) {
            throw invalid();
        }
    }

    private static IOException invalid() { return new IOException("올바른 경로와 회전 안내를 받지 못했습니다. 잠시 후 다시 요청해주세요."); }
    private static String text(JSONObject object, String key) {
        Object raw = object.opt(key);
        String value = raw instanceof String ? ((String) raw).trim() : "";
        return value.substring(0, Math.min(200, value.length()));
    }
    private static double nonnegative(JSONObject object, String key) throws JSONException, IOException {
        Object value = object.get(key);
        if (!(value instanceof Number)) throw invalid();
        double n = ((Number) value).doubleValue();
        if (!Double.isFinite(n) || n < 0) throw invalid();
        return n;
    }
    private static double positive(JSONObject object, String key) throws JSONException, IOException {
        double n = nonnegative(object, key);
        if (n <= 0) throw invalid();
        return n;
    }
    private static RoadRoute.Point point(JSONArray array) throws JSONException, IOException {
        if (array.length() < 2 || !(array.get(0) instanceof Number) || !(array.get(1) instanceof Number)) throw invalid();
        return new RoadRoute.Point(array.getDouble(1), array.getDouble(0));
    }
    private static List<RoadRoute.Point> line(JSONObject raw) throws JSONException, IOException {
        if (!raw.getString("type").equals("LineString")) throw invalid();
        JSONArray coordinates = raw.getJSONArray("coordinates");
        if (coordinates.length() < 2 || coordinates.length() > 100000) throw invalid();
        List<RoadRoute.Point> points = new ArrayList<>();
        for (int i = 0; i < coordinates.length(); i++) points.add(point(coordinates.getJSONArray(i)));
        return points;
    }

    static String koreanCue(String type, String modifier, int exit, String name) {
        String road = name.isEmpty() ? "" : name + " 방면으로 ";
        String turn;
        switch (modifier) {
            case "left": turn = "좌회전"; break;
            case "right": turn = "우회전"; break;
            case "slight left": turn = "왼쪽 방향"; break;
            case "slight right": turn = "오른쪽 방향"; break;
            case "sharp left": turn = "크게 좌회전"; break;
            case "sharp right": turn = "크게 우회전"; break;
            case "uturn": turn = "유턴"; break;
            case "straight": turn = "직진"; break;
            default: turn = "";
        }
        if (type.equals("arrive")) return "목적지에 도착합니다";
        if (type.equals("depart")) return road + "출발하세요";
        if (type.equals("roundabout") || type.equals("rotary") || type.equals("roundabout turn"))
            return "회전교차로에서 " + (exit > 0 && exit < 100 ? exit + "번째 출구로 나가세요" : road + "경로를 따라 이동하세요");
        if (type.equals("exit roundabout") || type.equals("exit rotary")) return "회전교차로를 나와 " + road + "이동하세요";
        if (type.equals("fork")) return "갈림길에서 " + (turn.isEmpty() ? "" : turn + "하여 ") + road + "이동하세요";
        if (type.equals("merge")) return road + "합류 구간으로 이동하세요";
        if (type.equals("on ramp")) return road + "진입로를 따라 이동하세요";
        if (type.equals("off ramp")) return road + "진출로를 따라 이동하세요";
        if ((type.equals("turn") || type.equals("end of road") || type.equals("continue")) && !turn.isEmpty())
            return (type.equals("end of road") ? "도로 끝에서 " : "") + turn + "하여 " + road + "이동하세요";
        return road + "경로를 따라 이동하세요";
    }
}
