package kr.routememory.shared;

import android.os.Looper;
import androidx.car.app.*;
import androidx.car.app.model.*;
import androidx.car.app.testing.TestCarContext;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** In-memory gateway status only. Never uses a key, real server, tile or GPS. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=29)
public final class MapGatewayCarScreenTest {
    private TestCarContext car;
    private RoadController state;
    private RoadCarScreen root;
    private MapGatewayCarScreen screen;
    private final AtomicInteger checks=new AtomicInteger();
    @Before public void setup(){
        car=TestCarContext.createCarContext(RuntimeEnvironment.getApplication());state=RoadController.get(car);state.useSeoulSample();state.disconnectHighResolution();state.setMapStyle(RoadMapStyle.ROAD);
        car.getLifecycleOwner().getRegistry().setCurrentState(androidx.lifecycle.Lifecycle.State.CREATED);root=new RoadCarScreen(car);car.getCarService(ScreenManager.class).push(root);
    }
    @After public void cleanup(){if(screen!=null)screen.onDestroy(screen);root.onDestroy(root);state.disconnectHighResolution();}
    private RoadMapGateway status(String origin,boolean configured)throws java.io.IOException{return RoadMapGateway.parse(origin,"{\"provider\":\"vworld\",\"configured\":"+configured+",\"maxNativeZoom\":18}",true);}
    private void create(boolean configured){screen=new MapGatewayCarScreen(car,state,root.getMarker(),origin->{checks.incrementAndGet();return status(origin,configured);},Runnable::run);car.getCarService(ScreenManager.class).push(screen);}
    private void submit(String value){((SearchTemplate)screen.onGetTemplate()).getSearchCallbackDelegate().sendSearchSubmitted(value,new OnDoneCallback(){});Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private void type(String value){((SearchTemplate)screen.onGetTemplate()).getSearchCallbackDelegate().sendSearchTextChanged(value,new OnDoneCallback(){});Shadows.shadowOf(Looper.getMainLooper()).idle();}
    @Test public void openingAndTypingDoNotCheckServerOrEnableGps(){create(true);type("https://maps.example");assertEquals(0,checks.get());assertFalse(state.highResolutionReady());assertFalse(state.gpsEnabled);assertFalse(state.loading);}
    @Test public void unsupportedOrCredentialBearingOriginDoesNotReachLookup(){create(true);submit("http://maps.example");submit("https://key@maps.example");submit("https://maps.example/?key=private");assertEquals(0,checks.get());assertFalse(state.highResolutionReady());}
    @Test public void keylessStatusNeverOffersHighResolutionSelection(){create(false);submit("https://maps.example");assertEquals(1,checks.get());assertTrue(((SearchTemplate)screen.onGetTemplate()).getItemList().getItems().isEmpty());assertFalse(state.highResolutionReady());assertEquals(RoadMapStyle.ROAD,state.mapStyle);}
    @Test public void manualParkedSelectionConnectsWithoutChangingDestinationOrGps(){
        create(true);kr.routememory.core.RoadRoute.Point destination=state.destination;submit("https://maps.example");assertFalse(state.highResolutionReady());
        Row row=(Row)((SearchTemplate)screen.onGetTemplate()).getItemList().getItems().get(0);assertTrue(row.getOnClickDelegate().isParkedOnly());row.getOnClickDelegate().sendClick(new OnDoneCallback(){});Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(state.highResolutionReady());assertEquals(RoadMapStyle.SATELLITE_HD,state.mapStyle);assertSame(destination,state.destination);assertFalse(state.gpsEnabled);assertFalse(state.guiding);assertFalse(state.loading);
    }
    @Test public void changedInputAndStoppedScreenRejectLateChecks(){
        List<Runnable> jobs=new ArrayList<>();screen=new MapGatewayCarScreen(car,state,root.getMarker(),origin->status(origin,true),jobs::add);submit("https://maps.example");type("https://other.example");jobs.get(0).run();Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(((SearchTemplate)screen.onGetTemplate()).getItemList().getItems().isEmpty());submit("https://other.example");screen.onStop(screen);jobs.get(1).run();Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(state.highResolutionReady());
    }
    @Test public void disconnectInvalidatesPendingCheckImmediately()throws Exception{
        state.setHighResolutionGateway(status("https://old.example",true));List<Runnable> jobs=new ArrayList<>();screen=new MapGatewayCarScreen(car,state,root.getMarker(),origin->status(origin,true),jobs::add);
        submit("https://maps.example");Action action=((SearchTemplate)screen.onGetTemplate()).getActionStrip().getActions().get(0);assertTrue(action.getOnClickDelegate().isParkedOnly());action.getOnClickDelegate().sendClick(new OnDoneCallback(){});Shadows.shadowOf(Looper.getMainLooper()).idle();
        jobs.get(0).run();Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(state.highResolutionReady());assertTrue(((SearchTemplate)screen.onGetTemplate()).getItemList().getItems().isEmpty());assertFalse(state.gpsEnabled);
    }
}
