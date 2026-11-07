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

/** Synthetic search results only. No real search, route, permission or location request. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=29)
public final class PlaceCarSearchScreenTest {
    private TestCarContext car;
    private RoadController state;
    private RoadCarScreen root;
    private PlaceCarSearchScreen screen;
    private final AtomicInteger lookups=new AtomicInteger(),routes=new AtomicInteger();
    @Before public void setup(){
        car=TestCarContext.createCarContext(RuntimeEnvironment.getApplication());state=RoadController.get(car);state.useSeoulSample();
        car.getLifecycleOwner().getRegistry().setCurrentState(androidx.lifecycle.Lifecycle.State.CREATED);
        root=new RoadCarScreen(car);car.getCarService(ScreenManager.class).push(root);
    }
    @After public void cleanup(){if(screen!=null)screen.onDestroy(screen);root.onDestroy(root);}
    private PlaceSearchClient.Place place(){return new PlaceSearchClient.Place("photon-W-1","검사 장소","서울특별시 종로구 사직로 161","관광지",37.58,126.98);}
    private void create(){
        screen=new PlaceCarSearchScreen(car,state,root.getMarker(),q->{lookups.incrementAndGet();return Arrays.asList(place());},Runnable::run,()->routes.incrementAndGet());
        car.getCarService(ScreenManager.class).push(screen);
    }
    private void submit(String query){((SearchTemplate)screen.onGetTemplate()).getSearchCallbackDelegate().sendSearchSubmitted(query,new OnDoneCallback(){});Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private void type(String query){((SearchTemplate)screen.onGetTemplate()).getSearchCallbackDelegate().sendSearchTextChanged(query,new OnDoneCallback(){});Shadows.shadowOf(Looper.getMainLooper()).idle();}
    @Test public void typingDoesNotSearchOrRequestGps(){
        create();type("경복궁");assertEquals(0,lookups.get());assertEquals(0,routes.get());
        assertFalse(state.gpsEnabled);assertFalse(state.loading);assertTrue(((SearchTemplate)screen.onGetTemplate()).getItemList().getItems().isEmpty());
    }
    @Test public void explicitSearchShowsNamedAddressResultsWithParkedSelection(){
        create();submit("경복궁");SearchTemplate model=(SearchTemplate)screen.onGetTemplate();assertEquals(1,lookups.get());
        assertFalse(model.isShowKeyboardByDefault());
        Row result=(Row)model.getItemList().getItems().get(0);assertEquals("검사 장소",result.getTitle().toCharSequence().toString());
        assertTrue(result.getOnClickDelegate().isParkedOnly());assertEquals(0,routes.get());assertFalse(state.gpsEnabled);
    }
    @Test public void choosingResultPreparesRouteAndRetainsNameWithoutStartingGps(){
        create();submit("경복궁");Row result=(Row)((SearchTemplate)screen.onGetTemplate()).getItemList().getItems().get(0);
        result.getOnClickDelegate().sendClick(new OnDoneCallback(){});Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1,routes.get());assertEquals("검사 장소",state.destinationName);assertEquals("서울특별시 종로구 사직로 161",state.destinationAddress);
        assertEquals(37.58,state.destination.lat,.000001);assertFalse(state.gpsEnabled);assertFalse(state.guiding);
    }
    @Test public void oldSearchCannotPublishAfterInputChanges(){
        List<Runnable> jobs=new ArrayList<>();screen=new PlaceCarSearchScreen(car,state,root.getMarker(),q->Arrays.asList(place()),jobs::add,()->routes.incrementAndGet());
        submit("경복궁");type("서울역");jobs.get(0).run();Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(((SearchTemplate)screen.onGetTemplate()).getItemList().getItems().isEmpty());assertEquals(0,routes.get());assertFalse(state.gpsEnabled);
    }
    @Test public void destroyedScreenIgnoresLateResponse(){
        List<Runnable> jobs=new ArrayList<>();screen=new PlaceCarSearchScreen(car,state,root.getMarker(),q->Arrays.asList(place()),jobs::add,()->routes.incrementAndGet());
        submit("경복궁");screen.onDestroy(screen);jobs.get(0).run();Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(((SearchTemplate)screen.onGetTemplate()).getItemList().getItems().isEmpty());assertEquals(0,routes.get());assertFalse(state.gpsEnabled);
    }
    @Test public void invalidInputDoesNotCallProvider(){
        create();submit(" ");submit("a");submit(String.join("",Collections.nCopies(121,"가")));
        assertEquals(0,lookups.get());assertTrue(((SearchTemplate)screen.onGetTemplate()).getItemList().getItems().isEmpty());assertFalse(state.loading);
    }
}
