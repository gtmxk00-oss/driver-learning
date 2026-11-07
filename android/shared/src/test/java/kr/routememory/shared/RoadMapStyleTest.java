package kr.routememory.shared;

import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import kr.routememory.core.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Source/camera/state checks with no tile or GPS request. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=29)
public final class RoadMapStyleTest {
    private RoadController state;
    private ActivityController<RoadActivity> activity;
    @Before public void setup(){state=RoadController.get(RuntimeEnvironment.getApplication());state.useSeoulSample();state.setMapStyle(RoadMapStyle.ROAD);}
    @After public void cleanup(){if(activity!=null)activity.pause().stop().destroy();state.stopAll();state.setMapStyle(RoadMapStyle.ROAD);}
    private TextView find(View view,String text){
        if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return (TextView)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView found=find(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}
        return null;
    }
    @Test public void fixedSourceUrlsUseCorrectRowColumnAndRejectNativeOverzoom(){
        assertEquals("https://tile.openstreetmap.org/14/13970/6344.png",RoadMapStyle.ROAD.tileUrl(14,13970,6344));
        assertEquals("https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2025_3857/default/GoogleMapsCompatible/14/6344/13970.jpg",RoadMapStyle.SATELLITE.tileUrl(14,13970,6344));
        assertEquals(RoadMapStyle.SATELLITE.tileUrl(14,16383,1),RoadMapStyle.SATELLITE.tileUrl(14,-1,1));
        try{RoadMapStyle.SATELLITE.tileUrl(15,1,1);fail("must crop parent tile locally");}catch(IllegalArgumentException expected){}
        try{RoadMapStyle.ROAD.tileUrl(14,1,-1);fail();}catch(IllegalArgumentException expected){}
        assertFalse(RoadMapStyle.SATELLITE.tileUrl(14,13970,6344).contains("key="));
    }
    @Test public void providerAndImageYearIsolateAllTileCacheIdentities(){
        assertNotEquals(RoadMapStyle.ROAD.tileKey(14,13970,6344),RoadMapStyle.SATELLITE.tileKey(14,13970,6344));
        assertTrue(RoadMapStyle.SATELLITE.tileKey(14,13970,6344).contains("eox2025"));
        String credits=String.join(" ",RoadMapStyle.SATELLITE.attributionLines());
        assertTrue(credits.contains("EOxCloudless https://cloudless.eox.at by EOX IT Services GmbH"));
        assertTrue(credits.contains("Contains modified Copernicus Sentinel data 2025"));assertTrue(credits.contains("CC BY-NC-SA 4.0"));
        assertTrue(RoadMapStyle.SATELLITE.imageNotice().contains("10m"));assertTrue(RoadMapStyle.SATELLITE.imageNotice().contains("실시간 아님"));
    }
    @Test public void basemapChangeRetainsRouteAndGuidanceSession(){
        RoadRoute.Point a=state.origin,b=state.destination;
        RoadRoute road=new RoadRoute("test",1000,120,Arrays.asList(a,b),Arrays.asList(new RoadRoute.Step("test","직진","depart","straight",1000,120,a,Arrays.asList(a,b)),new RoadRoute.Step("test","도착","arrive","",0,0,b,Arrays.asList(b,b))));
        state.route=road;state.tracker=new RoadTracker(road);state.guiding=true;state.gpsEnabled=true;
        RoadTracker tracker=state.tracker;String notice=state.notice;state.setMapStyle(RoadMapStyle.SATELLITE);
        assertSame(road,state.route);assertSame(tracker,state.tracker);assertSame(a,state.origin);assertSame(b,state.destination);
        assertTrue(state.guiding);assertTrue(state.gpsEnabled);assertEquals(notice,state.notice);
    }
    @Test public void phoneButtonSwitchesBasemapWithoutEnablingGpsOrRouteRequest(){
        activity=Robolectric.buildActivity(RoadActivity.class).create().start().resume();RoadActivity app=activity.get();
        TextView button=find(app.getWindow().getDecorView(),"일반지도");assertNotNull(button);button.performClick();
        android.app.AlertDialog choices=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        choices.getListView().performItemClick(choices.getListView().getChildAt(1),1,1);
        assertEquals(RoadMapStyle.SATELLITE,state.mapStyle);assertNotNull(find(app.getWindow().getDecorView(),"위성지도"));
        assertFalse(state.gpsEnabled);assertFalse(state.guiding);assertFalse(state.loading);assertNull(state.fix);assertNull(state.route);
        state.setMapStyle(RoadMapStyle.ROAD);assertNotNull(find(app.getWindow().getDecorView(),"일반지도"));
    }
    @Test public void painterStyleSwitchDoesNotResetManualCamera()throws Exception{
        RoadMapPainter painter=new RoadMapPainter(RuntimeEnvironment.getApplication(),()->{});
        try{
            Field manual=RoadMapPainter.class.getDeclaredField("manualCamera"),mx=RoadMapPainter.class.getDeclaredField("manualX"),delta=RoadMapPainter.class.getDeclaredField("zoomDelta");
            for(Field field:new Field[]{manual,mx,delta})field.setAccessible(true);manual.setBoolean(painter,true);mx.setDouble(painter,.8527);delta.setInt(painter,3);
            painter.setMapStyle(RoadMapStyle.SATELLITE);assertTrue(manual.getBoolean(painter));assertEquals(.8527,mx.getDouble(painter),0);assertEquals(3,delta.getInt(painter));
            assertEquals(RoadMapStyle.SATELLITE,painter.getMapStyle());
        }finally{painter.close();}
    }
    @Test @SuppressWarnings("unchecked") public void abandonedQueuedSourceJobReleasesPendingEntryWithoutNetworkOrCallback()throws Exception{
        Constructor<RoadTileStore> constructor=RoadTileStore.class.getDeclaredConstructor(android.content.Context.class);constructor.setAccessible(true);RoadTileStore store=constructor.newInstance(RuntimeEnvironment.getApplication());
        Field queued=RoadTileStore.class.getDeclaredField("pending");queued.setAccessible(true);Map<String,List<Runnable>> pending=(Map<String,List<Runnable>>)queued.get(store);
        String key=RoadMapStyle.ROAD.tileKey(14,13970,6344);AtomicInteger callbacks=new AtomicInteger();pending.put(key,new ArrayList<>(Arrays.asList(callbacks::incrementAndGet)));
        store.useStyle(RoadMapStyle.SATELLITE);
        Method load=RoadTileStore.class.getDeclaredMethod("load",RoadMapStyle.class,String.class,int.class,int.class,int.class);load.setAccessible(true);load.invoke(store,RoadMapStyle.ROAD,key,14,13970,6344);
        assertFalse("An abandoned old provider request cannot block a later revisit",pending.containsKey(key));assertEquals(0,callbacks.get());
    }
}
