package kr.routememory.shared;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import kr.routememory.core.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Public Seoul sample captured from OSRM; no real-time request or user GPS in this test. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=29)
public final class RealRoadFixtureTest {
    @Test public void actualSeoulResponseBuildsNativeRouteAndTracker() throws Exception {
        try(InputStream stream=getClass().getResourceAsStream("/osrm-seoul.json")){
            assertNotNull(stream);
            RoadRoute route=OsrmClient.parse(new String(stream.readAllBytes(),StandardCharsets.UTF_8));
            assertEquals(1920.9,route.distanceMeters,.2);assertEquals(289.9,route.durationSeconds,.2);
            assertEquals(8,route.steps.size());assertEquals(82,route.geometry.size());
            RoadTracker tracker=new RoadTracker(route);
            assertEquals(1,tracker.nextStepIndex);assertEquals("continue",tracker.nextStep().type);
            assertEquals("세종대로",tracker.nextStep().name);
            assertFalse(tracker.nextCue.isEmpty());assertEquals(route.distanceMeters,tracker.remainingMeters,.01);
        }
    }
}
