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

/** Synthetic POIs only. No network, GPS request or real price/vacancy value is used. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=29)
public final class NearbyCarScreenTest {
    private TestCarContext car;
    private RoadController state;
    private RoadCarScreen root;
    private NearbyCarScreen screen;
    private final AtomicInteger lookups=new AtomicInteger(),routes=new AtomicInteger();
    @Before public void setup(){
        car=TestCarContext.createCarContext(RuntimeEnvironment.getApplication());state=RoadController.get(car);state.useSeoulSample();
        car.getLifecycleOwner().getRegistry().setCurrentState(androidx.lifecycle.Lifecycle.State.CREATED);
        root=new RoadCarScreen(car);car.getCarService(ScreenManager.class).push(root);
    }
    @After public void cleanup(){if(screen!=null)screen.onDestroy(screen);root.onDestroy(root);}
    private NearbySearchClient.NearbyPlace place(){return new NearbySearchClient.NearbyPlace("test-W-1","검사 주유소","서울 검사 주소","fuel",37.58,126.98,300,null,null);}
    private NearbySearchClient.Result answer(kr.routememory.core.RoadRoute.Point center,String kind,int radius){
        lookups.incrementAndGet();return new NearbySearchClient.Result(kind,"test",center,radius,"",Arrays.asList(place()),"");
    }
    private void create(){
        screen=new NearbyCarScreen(car,state,root.getMarker(),this::answer,Runnable::run,()->routes.incrementAndGet());
        car.getCarService(ScreenManager.class).push(screen);
    }
    private void click(String title){
        for(Item item:((ListTemplate)screen.onGetTemplate()).getSingleList().getItems()){
            Row row=(Row)item;if(row.getTitle().toCharSequence().toString().equals(title)){
                assertTrue(row.getOnClickDelegate().isParkedOnly());row.getOnClickDelegate().sendClick(new OnDoneCallback(){});
                Shadows.shadowOf(Looper.getMainLooper()).idle();return;
            }
        }
        fail("Missing action "+title);
    }
    @Test public void openingAndChangingConfigurationDoNotSearchOrEnableGps(){
        create();click("분류: 주유소");click("반경 1km");assertEquals(0,lookups.get());assertFalse(state.gpsEnabled);assertEquals(0,routes.get());
    }
    @Test public void destinationLookupIsExplicitAndNeverStartsGps(){
        create();click("이 조건으로 조회");assertEquals(1,lookups.get());assertEquals(1,((ListTemplate)screen.onGetTemplate()).getSingleList().getItems().size());
        assertFalse(state.gpsEnabled);assertEquals(0,routes.get());assertTrue(NearbyCarScreen.detail(place(),"fuel").contains("유가 연동 대기"));
        assertTrue(NearbyCarScreen.detail(place(),"parking").contains("잔여 면수 미제공"));
    }
    @Test public void currentCenterWithoutAlreadyEnabledFreshGpsDoesNotSendCoordinates(){
        create();click("목적지 주변");click("이 조건으로 조회");assertEquals(0,lookups.get());assertFalse(state.gpsEnabled);assertNull(state.fix);
    }
    @Test public void choosingResultPreparesNamedRouteWithoutStartingGuidance(){
        create();click("이 조건으로 조회");click("검사 주유소");assertEquals(1,routes.get());assertEquals("검사 주유소",state.destinationName);
        assertEquals("서울 검사 주소",state.destinationAddress);assertFalse(state.gpsEnabled);assertFalse(state.guiding);
    }
    @Test public void stoppedScreenRejectsLateResult(){
        List<Runnable> jobs=new ArrayList<>();screen=new NearbyCarScreen(car,state,root.getMarker(),this::answer,jobs::add,()->routes.incrementAndGet());
        click("이 조건으로 조회");screen.onStop(screen);jobs.get(0).run();Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(5,((ListTemplate)screen.onGetTemplate()).getSingleList().getItems().size());assertEquals(0,routes.get());assertFalse(state.gpsEnabled);
    }
    @Test public void activeGuidanceCannotReplaceDestination(){
        create();state.guiding=true;click("이 조건으로 조회");assertEquals(0,lookups.get());assertEquals(0,routes.get());
    }
}
