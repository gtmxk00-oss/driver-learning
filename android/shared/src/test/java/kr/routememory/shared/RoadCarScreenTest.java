package kr.routememory.shared;

import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import androidx.car.app.OnDoneCallback;
import androidx.car.app.Session;
import androidx.car.app.model.*;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.car.app.testing.TestCarContext;
import kr.routememory.core.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.util.Arrays;
import static org.junit.Assert.*;

/** Builds actual host templates with synthetic route data and no map Surface or real location. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=29)
public final class RoadCarScreenTest {
    private RoadCarScreen screen;
    private RoadController state;
    @Before public void setup(){
        state=RoadController.get(RuntimeEnvironment.getApplication());state.useSeoulSample();
        screen=new RoadCarScreen(TestCarContext.createCarContext(RuntimeEnvironment.getApplication()));
    }
    @After public void cleanup(){screen.onDestroy(screen);}
    private RoadRoute road(){
        RoadRoute.Point a=new RoadRoute.Point(37.5665,126.9780),b=new RoadRoute.Point(37.5765,126.9780);
        return new RoadRoute("synthetic-test",1112,120,Arrays.asList(a,b),Arrays.asList(
            new RoadRoute.Step("test","직진","depart","straight",1112,120,a,Arrays.asList(a,b)),
            new RoadRoute.Step("test","도착","arrive","",0,0,b,Arrays.asList(b,b))));
    }
    @Test public void mapFirstHomeRestrictsGpsAndSearchToParkedWithoutFakeNavigation(){
        NavigationTemplate model=(NavigationTemplate)screen.onGetTemplate();
        assertNull(model.getNavigationInfo());assertNull(model.getDestinationTravelEstimate());
        assertEquals(3,model.getActionStrip().getActions().size());
        for(Action a:model.getActionStrip().getActions())assertTrue(a.getOnClickDelegate().isParkedOnly());
        assertNotNull(model.getMapActionStrip());assertEquals(4,model.getMapActionStrip().getActions().size());
        assertFalse(state.gpsEnabled);assertFalse(state.guiding);
    }
    @Test public void roadPreviewBuildsHostNavigationModelWithoutStartingGps(){
        state.route=road();NavigationTemplate model=(NavigationTemplate)screen.onGetTemplate();
        assertNotNull(model.getNavigationInfo());assertNotNull(model.getDestinationTravelEstimate());
        assertEquals(3,model.getActionStrip().getActions().size());
        assertTrue(model.getActionStrip().getActions().get(0).getOnClickDelegate().isParkedOnly());
        assertFalse(state.gpsEnabled);assertFalse(state.guiding);
    }
    @Test public void activeGuidanceProvidesUnrestrictedStopAction(){
        state.route=road();state.tracker=new RoadTracker(state.route);state.guiding=true;
        NavigationTemplate model=(NavigationTemplate)screen.onGetTemplate();
        assertFalse(model.getActionStrip().getActions().get(0).getOnClickDelegate().isParkedOnly());
        assertTrue(model.getActionStrip().getActions().get(1).getOnClickDelegate().isParkedOnly());
        assertEquals(CarColor.createCustom(0xff064c3e,0xff053b31),model.getBackgroundColor());
    }
    @Test public void gpsCanBeStoppedWhileMovingBeforeGuidanceStarts(){
        state.gpsEnabled=true;NavigationTemplate model=(NavigationTemplate)screen.onGetTemplate();
        assertFalse(model.getActionStrip().getActions().get(2).getOnClickDelegate().isParkedOnly());
        assertTrue(model.getActionStrip().getActions().get(0).getOnClickDelegate().isParkedOnly());
    }
    @Test public void gpsDisclosureDoesNotRequestLocationUntilParkedConfirmation(){
        RoadCarScreen.GpsDisclosureScreen disclosure=screen.new GpsDisclosureScreen(
            TestCarContext.createCarContext(RuntimeEnvironment.getApplication()));
        PaneTemplate model=(PaneTemplate)disclosure.onGetTemplate();
        assertEquals(2,model.getPane().getRows().size());assertEquals(2,model.getPane().getActions().size());
        for(Action action:model.getPane().getActions())assertTrue(action.getOnClickDelegate().isParkedOnly());
        assertFalse(state.gpsEnabled);assertFalse(state.loading);
    }
    @Test public void mapCameraControlsAreAvailableWithoutGpsPermission(){
        NavigationTemplate model=(NavigationTemplate)screen.onGetTemplate();
        for(Action action:model.getMapActionStrip().getActions()){
            assertNotNull(action.getIcon());assertFalse(action.getOnClickDelegate().isParkedOnly());
            action.getOnClickDelegate().sendClick(new OnDoneCallback(){});
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(state.gpsEnabled);assertFalse(state.loading);assertNull(state.fix);
    }
    @Test public void parkedOptionsKeepPersonalLearningReachable(){
        RoadCarScreen.OptionsScreen options=screen.new OptionsScreen(TestCarContext.createCarContext(RuntimeEnvironment.getApplication()));
        ListTemplate model=(ListTemplate)options.onGetTemplate();boolean found=false;
        for(Item item:model.getSingleList().getItems())if(item instanceof Row){
            Row row=(Row)item;if(row.getTitle().toCharSequence().toString().equals("개인별 학습 시연")){
                found=true;assertTrue(row.getOnClickDelegate().isParkedOnly());
            }
        }
        assertTrue(found);assertFalse(state.gpsEnabled);
    }
    @Test public void parkedMapStyleSwitchKeepsRouteDestinationAndGpsState(){
        state.disconnectHighResolution();state.setMapStyle(RoadMapStyle.ROAD);state.route=road();RoadRoute saved=state.route;RoadRoute.Point destination=state.destination;
        RoadCarScreen.OptionsScreen options=screen.new OptionsScreen(TestCarContext.createCarContext(RuntimeEnvironment.getApplication()));
        Row toggle=(Row)((ListTemplate)options.onGetTemplate()).getSingleList().getItems().get(0);
        assertEquals("지도 종류: 일반지도",toggle.getTitle().toCharSequence().toString());assertTrue(toggle.getOnClickDelegate().isParkedOnly());
        toggle.getOnClickDelegate().sendClick(new OnDoneCallback(){});Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(RoadMapStyle.SATELLITE,state.mapStyle);assertSame(saved,state.route);assertSame(destination,state.destination);assertFalse(state.gpsEnabled);assertFalse(state.guiding);assertFalse(state.loading);
        Row back=(Row)((ListTemplate)options.onGetTemplate()).getSingleList().getItems().get(0);assertEquals("지도 종류: 위성지도",back.getTitle().toCharSequence().toString());
        back.getOnClickDelegate().sendClick(new OnDoneCallback(){});Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(RoadMapStyle.ROAD,state.mapStyle);assertSame(saved,state.route);
    }
    @Test public void carHighResolutionStateDisclosesMissingGatewayWithoutEnablingGps(){
        state.disconnectHighResolution();RoadCarScreen.OptionsScreen options=screen.new OptionsScreen(TestCarContext.createCarContext(RuntimeEnvironment.getApplication()));
        Row availability=(Row)((ListTemplate)options.onGetTemplate()).getSingleList().getItems().get(1);
        assertEquals("고화질 항공영상",availability.getTitle().toCharSequence().toString());assertFalse(state.highResolutionReady());
        assertTrue(availability.getTexts().get(0).toCharSequence().toString().contains("서버"));assertTrue(availability.getOnClickDelegate().isParkedOnly());
        assertFalse(state.gpsEnabled);assertFalse(state.loading);assertFalse(state.guiding);
    }
    @Test public void serviceNormalLaunchOpensActualMapWithoutStartingGpsOrSearch(){
        Session session=new MemoryCarService().onCreateSession();
        session.setCarContextInternal(TestCarContext.createCarContext(RuntimeEnvironment.getApplication()));
        assertTrue(session.onCreateScreen(new Intent(Intent.ACTION_MAIN)) instanceof RoadCarScreen);
        assertFalse(state.gpsEnabled);assertFalse(state.loading);
    }
    @Test public void screenDestroyStopsSharedGpsAndGuidance(){
        state.route=road();state.tracker=new RoadTracker(state.route);state.guiding=true;state.gpsEnabled=true;
        screen.onDestroy(screen);assertFalse(state.gpsEnabled);assertFalse(state.guiding);assertNull(state.fix);
    }
    @Test public void geoCoordinatesAndLabelAreParsed(){
        RoadRoute.Point a=RoadIntents.destination(new Intent("androidx.car.app.action.NAVIGATE",Uri.parse("geo:37.57,126.98")));
        assertNotNull(a);assertEquals(37.57,a.lat,.000001);assertEquals(126.98,a.lon,.000001);
        RoadRoute.Point b=RoadIntents.destination(new Intent("androidx.car.app.action.NAVIGATE",Uri.parse("geo:0,0?q=37.57,126.98(Example)")));
        assertNotNull(b);assertEquals(a.lat,b.lat,.000001);
    }
    @Test public void addressSearchAndMalformedGeoAreNotRoutedAsCoordinates(){
        assertNull(RoadIntents.destination(new Intent("androidx.car.app.action.NAVIGATE",Uri.parse("geo:0,0?q=Seoul"))));
        assertNull(RoadIntents.destination(new Intent("androidx.car.app.action.NAVIGATE",Uri.parse("geo:95,126.98"))));
        assertNull(RoadIntents.destination(new Intent("androidx.car.app.action.NAVIGATE",Uri.parse("https://example.org/?q=37.5,126.9"))));
        assertNull(RoadIntents.destination(null));
    }
    @Test public void vehicleCoordinateInputAcceptsOnlyPlainValidNumbers(){
        RoadRoute.Point point=RoadCarScreen.parseCoordinateInput(" 37.5796, 126.9770 ");
        assertNotNull(point);assertEquals(37.5796,point.lat,.000001);assertEquals(126.977,point.lon,.000001);
        assertNotNull(RoadCarScreen.parseCoordinateInput("-33.8, +151.2"));
        for(String invalid:Arrays.asList("Seoul","geo:37.5,126.9","37.5,126.9(place)","37,126,1","NaN,127","37,Infinity","86,127","37,181","37e0,127",""))
            assertNull(invalid,RoadCarScreen.parseCoordinateInput(invalid));
    }
    @Test public void vehicleSearchShowsParkedCoordinateSelectionWithoutChangingDestination(){
        RoadCarScreen.CoordinateInputScreen input=screen.new CoordinateInputScreen(
            TestCarContext.createCarContext(RuntimeEnvironment.getApplication()));
        SearchTemplate empty=(SearchTemplate)input.onGetTemplate();
        assertTrue(empty.getItemList().getItems().isEmpty());assertTrue(empty.isShowKeyboardByDefault());
        RoadRoute.Point previous=state.destination;
        empty.getSearchCallbackDelegate().sendSearchTextChanged("37.5, 127.0",new OnDoneCallback(){});
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        SearchTemplate result=(SearchTemplate)input.onGetTemplate();
        assertEquals(1,result.getItemList().getItems().size());
        Row row=(Row)result.getItemList().getItems().get(0);assertTrue(row.getOnClickDelegate().isParkedOnly());
        assertSame(previous,state.destination);assertFalse(state.gpsEnabled);assertFalse(state.loading);
    }
    @Test public void vehicleSearchSubmissionWithAddressProducesNoSelectableCoordinate(){
        RoadCarScreen.CoordinateInputScreen input=screen.new CoordinateInputScreen(
            TestCarContext.createCarContext(RuntimeEnvironment.getApplication()));
        SearchTemplate search=(SearchTemplate)input.onGetTemplate();
        search.getSearchCallbackDelegate().sendSearchSubmitted("경복궁",new OnDoneCallback(){});
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        SearchTemplate result=(SearchTemplate)input.onGetTemplate();
        assertTrue(result.getItemList().getItems().isEmpty());assertFalse(state.loading);assertFalse(state.gpsEnabled);
    }
}
