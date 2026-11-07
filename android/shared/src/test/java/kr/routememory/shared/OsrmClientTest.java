package kr.routememory.shared;

import java.io.IOException;
import kr.routememory.core.RoadRoute;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Exercises external-data validation without network or a user's location. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=29)
public final class OsrmClientTest {
    private static JSONArray p(double lon, double lat) throws Exception { return new JSONArray().put(lon).put(lat); }
    private static JSONObject line(JSONArray a, JSONArray b) throws Exception {
        return new JSONObject().put("type", "LineString").put("coordinates", new JSONArray().put(a).put(b));
    }
    private static JSONObject step(String type, String modifier, JSONArray a, JSONArray b, double distance) throws Exception {
        return new JSONObject().put("name", "실제 도로").put("distance", distance).put("duration", distance / 10)
            .put("geometry", line(a,b)).put("maneuver", new JSONObject().put("type",type).put("modifier",modifier).put("location",a));
    }
    private static JSONObject payload() throws Exception {
        JSONArray a=p(0,0), b=p(.005,0), c=p(.005,.005);
        JSONArray steps=new JSONArray().put(step("depart","straight",a,b,556))
            .put(step("turn","left",b,c,556)).put(step("arrive","",c,c,0));
        JSONObject route=new JSONObject().put("distance",1112).put("duration",111.2)
            .put("geometry",new JSONObject().put("type","LineString").put("coordinates",new JSONArray().put(a).put(b).put(c)))
            .put("legs",new JSONArray().put(new JSONObject().put("steps",steps)));
        return new JSONObject().put("code","Ok").put("routes",new JSONArray().put(route))
            .put("waypoints",new JSONArray().put(new JSONObject().put("location",a).put("distance",0))
                .put(new JSONObject().put("location",c).put("distance",0)));
    }
    private static void rejects(JSONObject payload) throws Exception {
        try { OsrmClient.parse(payload.toString()); fail("invalid external data accepted"); }
        catch(IOException expected) { assertFalse(expected.getMessage().isEmpty()); }
    }
    private static JSONObject route(JSONObject p) throws Exception { return p.getJSONArray("routes").getJSONObject(0); }
    private static JSONArray steps(JSONObject p) throws Exception { return route(p).getJSONArray("legs").getJSONObject(0).getJSONArray("steps"); }
    @Test public void validRouteRetainsRealGeometryAndUpcomingCue() throws Exception {
        RoadRoute r=OsrmClient.parse(payload().toString());
        assertEquals(1112,r.distanceMeters,.001);assertEquals(3,r.geometry.size());
        assertEquals(.005,r.geometry.get(1).lon,.00001);assertEquals(0,r.geometry.get(1).lat,.00001);
        assertEquals("turn",r.steps.get(1).type);assertTrue(r.steps.get(1).cue.contains("좌회전"));
    }
    @Test public void missingRouteIsAnErrorWithoutFakeFallback() throws Exception {
        rejects(new JSONObject().put("code","NoRoute"));
    }
    @Test public void snapOverOneKilometerRejected() throws Exception {
        JSONObject p=payload();p.getJSONArray("waypoints").getJSONObject(0).put("distance",1001);rejects(p);
    }
    @Test public void outOfRangeCoordinatesRejected() throws Exception {
        JSONObject p=payload();route(p).getJSONObject("geometry").getJSONArray("coordinates").put(1,p(181,0));rejects(p);
    }
    @Test public void stringNumericDistanceRejected() throws Exception {
        JSONObject p=payload();route(p).put("distance","1112");rejects(p);
    }
    @Test public void discontinuousStepsRejected() throws Exception {
        JSONObject p=payload();JSONObject s=steps(p).getJSONObject(1);JSONArray distant=p(.1,.1);
        s.put("geometry",line(distant,p(.005,.005)));s.getJSONObject("maneuver").put("location",distant);rejects(p);
    }
    @Test public void mismatchedManeuverLocationRejected() throws Exception {
        JSONObject p=payload();steps(p).getJSONObject(1).getJSONObject("maneuver").put("location",p(.2,.2));rejects(p);
    }
    @Test public void missingArrivalRejected() throws Exception {
        JSONObject p=payload();steps(p).getJSONObject(2).getJSONObject("maneuver").put("type","turn");rejects(p);
    }
    @Test public void mismatchedDistanceTotalsRejected() throws Exception {
        JSONObject p=payload();route(p).put("distance",5000);rejects(p);
    }
    @Test public void geometryEndpointMustMatchRoadSnap() throws Exception {
        JSONObject p=payload();p.getJSONArray("waypoints").getJSONObject(1).put("location",p(.1,.1));rejects(p);
    }
    @Test public void roundaboutExitCueExplainsSequence() {
        assertTrue(OsrmClient.koreanCue("roundabout","right",3,"").contains("3번째 출구"));
    }
    @Test public void malformedJsonRejected() {
        try { OsrmClient.parse("<html>offline</html>");fail("html accepted"); }
        catch(IOException expected) { assertFalse(expected.getMessage().contains("html")); }
    }
}
