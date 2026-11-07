package kr.routememory.shared;

import android.app.Application;
import kr.routememory.core.RoadRoute;
import kr.routememory.core.RoadTracker;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.util.Arrays;
import static org.junit.Assert.*;

/** Uses synthetic coordinates only; never requests hardware GPS or an external route. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=29)
public final class RoadControllerTest {
    private Application app;
    private RoadController state;
    private final RoadRoute.Point start=new RoadRoute.Point(37.5665,126.9780);
    private final RoadRoute.Point end=new RoadRoute.Point(37.5765,126.9780);

    @Before public void setup(){
        app=RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION,android.Manifest.permission.ACCESS_COARSE_LOCATION);
        state=RoadController.get(app);state.stopAll();state.useSeoulSample();
        DemoController.get(app).stop();DemoController.get(app).trip=null;
        while(Shadows.shadowOf(app).getNextStartedService()!=null){}
    }
    @After public void cleanup(){state.stopAll();}
    private RoadRoute road(){
        return new RoadRoute("synthetic-test",1112,120,Arrays.asList(start,end),Arrays.asList(
            new RoadRoute.Step("test","직진","depart","straight",1112,120,start,Arrays.asList(start,end)),
            new RoadRoute.Step("test","도착","arrive","",0,0,end,Arrays.asList(end,end))));
    }
    private long drive(){
        long stamp=System.currentTimeMillis()-3000;
        state.gpsEnabled=true;
        state.onFix(new RoadTracker.Fix(start.lat,start.lon,5,stamp));
        state.route=road();state.startGuidance();
        assertTrue(state.guiding);return stamp;
    }
    @Test public void initializationDoesNotCollectLocation(){
        assertFalse(state.gpsEnabled);assertFalse(state.guiding);assertNull(state.fix);
        assertNull(Shadows.shadowOf(app).getNextStartedService());
    }
    @Test public void permissionDenialDoesNotStartGpsService(){
        assertFalse(state.hasPermission());state.startGps();
        assertFalse(state.gpsEnabled);assertNull(state.fix);
        assertNull(Shadows.shadowOf(app).getNextStartedService());
    }
    @Test public void farAwayFixCannotStartGuidance(){
        state.gpsEnabled=true;
        state.onFix(new RoadTracker.Fix(37.0,126.9780,5,System.currentTimeMillis()-1000));
        state.route=road();state.startGuidance();
        assertFalse(state.guiding);assertTrue(state.notice.contains("다릅니다"));
    }
    @Test public void destinationChangeInvalidatesRouteAndGuidance(){
        drive();state.setDestination(new RoadRoute.Point(37.58,126.98));
        assertNull(state.route);assertNull(state.tracker);assertFalse(state.guiding);
        assertTrue(state.gpsEnabled);assertFalse(state.loading);
    }
    @Test public void inaccurateAndStaleFixesDoNotAdvanceGuidance(){
        long stamp=drive();double before=state.tracker.progressMeters;
        state.onFix(new RoadTracker.Fix(37.5715,126.9780,300,stamp+1000));
        assertEquals(before,state.tracker.progressMeters,.01);
        state.onFix(new RoadTracker.Fix(37.5715,126.9780,5,stamp-60000));
        assertEquals(before,state.tracker.progressMeters,.01);
    }
    @Test public void implausibleJumpCannotBecomeRerouteOrigin(){
        long stamp=drive();
        state.onFix(new RoadTracker.Fix(37.7,126.9780,5,stamp+1000));
        assertEquals(start.lat,state.fix.lat,.000001);
        assertEquals(0,state.tracker.progressMeters,.01);
    }
    @Test public void poorAccuracyCannotResetConfidentJumpBaseline(){
        long stamp=drive();
        state.onFix(new RoadTracker.Fix(37.7,126.9780,20000,stamp+1000));
        state.onFix(new RoadTracker.Fix(37.7,126.9780,5,stamp+2000));
        assertEquals(start.lat,state.fix.lat,.000001);
        assertEquals(0,state.tracker.progressMeters,.01);
    }
    @Test public void malformedFixIsIgnoredWithoutThrowing(){
        state.gpsEnabled=true;
        state.onFix(new RoadTracker.Fix(Double.NaN,126.9,5,System.currentTimeMillis()-1000));
        state.onFix(new RoadTracker.Fix(95,126.9,5,System.currentTimeMillis()-1000));
        assertNull(state.fix);assertFalse(state.guiding);
    }
    @Test public void stopRejectsLateLocationCallbacks(){
        long stamp=drive();state.stopAll();
        state.onFix(new RoadTracker.Fix(start.lat,start.lon,5,stamp+1000));
        assertFalse(state.gpsEnabled);assertFalse(state.guiding);assertNull(state.fix);
        assertEquals(RoadTracker.Status.STOPPED,state.tracker.status);
    }
    @Test public void serviceTerminationClearsLocationAndGuidance(){
        drive();state.serviceEnded("수신 중지");
        assertFalse(state.gpsEnabled);assertFalse(state.guiding);assertNull(state.fix);
        assertEquals("수신 중지",state.notice);
    }
    @Test public void learningDemoCannotRunAlongsideRealGps(){
        state.gpsEnabled=true;DemoController demo=DemoController.get(app);demo.start(false);
        assertFalse(demo.running());assertTrue(demo.notice.contains("GPS"));
    }
    @Test public void realGpsCannotStartWhileLearningDemoRuns(){
        DemoController demo=DemoController.get(app);demo.start(false);demo.pause(true);
        assertTrue(demo.running());state.startGps();assertFalse(state.gpsEnabled);
        assertTrue(state.notice.contains("가상"));demo.stop();demo.trip=null;
    }
}
