package kr.routememory.shared;

import android.app.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import kr.routememory.core.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Builds native phone UI without a map draw, network request, or hardware location access. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=29)
public final class RoadActivityTest {
    private RoadController state;
    private ActivityController<RoadActivity> activity;
    @Before public void setup(){state=RoadController.get(RuntimeEnvironment.getApplication());state.stopAll();state.useSeoulSample();}
    @After public void cleanup(){if(activity!=null)activity.pause().stop().destroy();state.stopAll();}
    private RoadActivity open(){activity=Robolectric.buildActivity(RoadActivity.class).create().start().resume();return activity.get();}
    private TextView find(View view,String label){
        if(view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return (TextView)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView result=find(((ViewGroup)view).getChildAt(i),label);if(result!=null)return result;}
        return null;
    }
    private TextView find(Activity app,String label){return find(app.getWindow().getDecorView(),label);}
    @Test public void nativeScreenOpensWithoutStartingGps(){
        RoadActivity app=open();assertNotNull(find(app,"길기억 · 경로 준비"));assertNotNull(find(app,"경로 탐색"));
        assertFalse(state.gpsEnabled);assertFalse(state.loading);assertNull(state.fix);
        assertNotNull(find(app,"GPS 연결"));assertNotNull(find(app,"경로 설정"));
    }
    @Test public void gpsClickFirstShowsDisclosure(){
        RoadActivity app=open();find(app,"GPS 연결").performClick();
        assertNotNull(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
        assertFalse(state.gpsEnabled);assertNull(state.fix);
    }
    @Test public void routePreviewDoesNotEnableGpsGuidance(){
        RoadRoute.Point a=state.origin,b=state.destination;
        state.route=new RoadRoute("synthetic",1500,300,Arrays.asList(a,b),Arrays.asList(
            new RoadRoute.Step("test","직진","depart","straight",1500,300,a,Arrays.asList(a,b)),
            new RoadRoute.Step("test","도착","arrive","",0,0,b,Arrays.asList(b,b))));
        RoadActivity app=open();assertNotNull(find(app,"5분"));assertNotNull(find(app,"1.5km · 실시간 교통 미반영"));
        find(app,"GPS 연결 후 안내").performClick();assertFalse(state.guiding);assertFalse(state.gpsEnabled);
        assertNotNull(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
    }
    @Test public void oldServiceCannotEndNewGpsSession(){
        state.gpsEnabled=true;int old=state.gpsGeneration;state.gpsGeneration++;
        state.serviceEnded(old,"이전 세션 종료");assertTrue(state.gpsEnabled);
        state.serviceEnded(state.gpsGeneration,"현재 세션 종료");assertFalse(state.gpsEnabled);
    }
    private EditText input(View view,String description){
        if(view instanceof EditText&&description.contentEquals(view.getContentDescription()))return (EditText)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){EditText result=input(((ViewGroup)view).getChildAt(i),description);if(result!=null)return result;}
        return null;
    }
    @Test public void routeDialogKeepsTypingAcrossGpsStatusRefresh(){
        RoadActivity app=open();find(app,"경로 설정").performClick();AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        EditText destination=input(dialog.getWindow().getDecorView(),"목적지 · 위도, 경도");assertNotNull(destination);destination.setText("37.6000, 127.0000");
        state.notice="GPS 상태 갱신";state.changed();assertEquals("37.6000, 127.0000",destination.getText().toString());
        assertEquals(37.5796,state.destination.lat,.000001);assertFalse(state.loading);assertFalse(state.gpsEnabled);
    }
    @Test public void invalidCoordinateStaysInDialogWithoutNetworkRequest(){
        RoadActivity app=open();find(app,"경로 설정").performClick();AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        EditText destination=input(dialog.getWindow().getDecorView(),"목적지 · 위도, 경도");destination.setText("경복궁");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertNotNull(destination.getError());assertTrue(dialog.isShowing());assertFalse(state.loading);assertFalse(state.gpsEnabled);
    }
    private AlertDialog searchDialog(RoadActivity app){app.placeSearch();return org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();}
    private TextView resultText(View view,String label){
        if(view instanceof TextView&&!(view instanceof EditText)&&label.contentEquals(((TextView)view).getText()))return (TextView)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView result=resultText(((ViewGroup)view).getChildAt(i),label);if(result!=null)return result;}
        return null;
    }
    private TextView dialogText(AlertDialog dialog,String text){return resultText(dialog.getWindow().getDecorView(),text);}
    private void awaitDialogText(AlertDialog dialog,String label)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(dialogText(dialog,label)==null&&System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5);}
        assertNotNull("Expected dialog text: "+label,dialogText(dialog,label));
    }
    private PlaceSearchClient.Place palace(){return new PlaceSearchClient.Place("photon-W-1","경복궁","서울특별시 종로구 사직로 161","관광지",37.57975,126.97668);}
    @Test public void typingPlaceQueryDoesNotSendSearchOrStartGps(){
        RoadActivity app=open();AtomicInteger calls=new AtomicInteger();app.placeClient=new PlaceSearchClient(){@Override public List<Place> search(String query){calls.incrementAndGet();return List.of(palace());}};
        AlertDialog dialog=searchDialog(app);EditText query=input(dialog.getWindow().getDecorView(),"주소·장소명 검색어");assertNotNull(query);query.setText("경복궁");
        Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(0,calls.get());assertFalse(state.gpsEnabled);assertFalse(state.loading);assertFalse(state.guiding);
    }
    @Test public void manualSearchSelectsNamedDestinationAndRequestsRouteWithoutGps()throws Exception{
        RoadActivity app=open();AtomicInteger calls=new AtomicInteger(),routes=new AtomicInteger();app.requestRoute=routes::incrementAndGet;
        app.placeClient=new PlaceSearchClient(){@Override public List<Place> search(String query){assertEquals("경복궁",query);calls.incrementAndGet();return List.of(palace());}};
        AlertDialog dialog=searchDialog(app);input(dialog.getWindow().getDecorView(),"주소·장소명 검색어").setText("경복궁");dialogText(dialog,"검색").performClick();awaitDialogText(dialog,"경복궁");
        ((View)dialogText(dialog,"경복궁").getParent()).performClick();
        assertEquals(1,calls.get());assertEquals(1,routes.get());assertEquals("경복궁",state.destinationName);assertEquals("서울특별시 종로구 사직로 161",state.destinationAddress);
        assertEquals(37.57975,state.destination.lat,.000001);assertFalse(dialog.isShowing());assertFalse(state.gpsEnabled);assertFalse(state.guiding);assertNotNull(find(app,"경복궁"));
    }
    @Test public void shortQueryShowsValidationWithoutSendingSearch(){
        RoadActivity app=open();AtomicInteger calls=new AtomicInteger();app.placeClient=new PlaceSearchClient(){@Override public List<Place> search(String query){calls.incrementAndGet();return List.of(palace());}};
        AlertDialog dialog=searchDialog(app);EditText query=input(dialog.getWindow().getDecorView(),"주소·장소명 검색어");query.setText("가");dialogText(dialog,"검색").performClick();
        assertNotNull(query.getError());assertEquals(0,calls.get());assertFalse(state.gpsEnabled);
    }
    @Test public void changingSearchQueryRejectsLateResults()throws Exception{
        RoadActivity app=open();CountDownLatch firstStarted=new CountDownLatch(1),releaseFirst=new CountDownLatch(1);
        app.placeClient=new PlaceSearchClient(){@Override public List<Place> search(String query){
            if(query.equals("경복궁")){firstStarted.countDown();boolean released=false;while(!released)try{released=releaseFirst.await(2,TimeUnit.SECONDS);}catch(InterruptedException ignored){}return List.of(palace());}
            return List.of(new Place("photon-N-2","서울역","서울특별시 중구","역",37.5547,126.9707));
        }};
        try{
            AlertDialog dialog=searchDialog(app);EditText query=input(dialog.getWindow().getDecorView(),"주소·장소명 검색어");query.setText("경복궁");dialogText(dialog,"검색").performClick();assertTrue(firstStarted.await(2,TimeUnit.SECONDS));
            query.setText("서울역");dialogText(dialog,"검색").performClick();releaseFirst.countDown();awaitDialogText(dialog,"서울역");
            assertNull(dialogText(dialog,"경복궁"));assertFalse(state.gpsEnabled);assertEquals("경복궁 · 서울 샘플",state.destinationName);
        }finally{releaseFirst.countDown();}
    }
    @Test public void searchFailureIsVisibleWithoutChangingDestination()throws Exception{
        RoadActivity app=open();app.placeClient=new PlaceSearchClient(){@Override public List<Place> search(String query)throws java.io.IOException{throw new java.io.IOException("검색 서버에 연결하지 못했습니다");}};
        AlertDialog dialog=searchDialog(app);input(dialog.getWindow().getDecorView(),"주소·장소명 검색어").setText("서울역");dialogText(dialog,"검색").performClick();awaitDialogText(dialog,"검색 서버에 연결하지 못했습니다");
        assertEquals("경복궁 · 서울 샘플",state.destinationName);assertFalse(state.loading);assertFalse(state.gpsEnabled);
    }
    @Test public void emptySearchResultsNeverInventDestination()throws Exception{
        RoadActivity app=open();app.placeClient=new PlaceSearchClient(){@Override public List<Place> search(String query){return List.of();}};
        AlertDialog dialog=searchDialog(app);input(dialog.getWindow().getDecorView(),"주소·장소명 검색어").setText("없는 장소");dialogText(dialog,"검색").performClick();awaitDialogText(dialog,"검색 결과가 없습니다. 지역명과 함께 입력해 주세요");
        assertEquals("경복궁 · 서울 샘플",state.destinationName);assertFalse(state.guiding);assertFalse(state.gpsEnabled);
    }
    @Test public void dismissedSearchDoesNotReceiveLateResults()throws Exception{
        RoadActivity app=open();CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1),finished=new CountDownLatch(1);
        app.placeClient=new PlaceSearchClient(){@Override public List<Place> search(String query){started.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException ignored){}finally{finished.countDown();}return List.of(palace());}};
        try{
            AlertDialog dialog=searchDialog(app);input(dialog.getWindow().getDecorView(),"주소·장소명 검색어").setText("경복궁");dialogText(dialog,"검색").performClick();assertTrue(started.await(2,TimeUnit.SECONDS));
            dialog.dismiss();release.countDown();assertTrue(finished.await(2,TimeUnit.SECONDS));Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(dialog.isShowing());assertNull(dialogText(dialog,"경복궁"));assertEquals("경복궁 · 서울 샘플",state.destinationName);assertFalse(state.gpsEnabled);
        }finally{release.countDown();}
    }
    @Test public void dismissingOldDialogCannotCancelAReopenedPlaceSearch()throws Exception{
        RoadActivity app=open();AtomicInteger calls=new AtomicInteger();app.placeClient=new PlaceSearchClient(){@Override public List<Place> search(String query){calls.incrementAndGet();return List.of(palace());}};
        AlertDialog previous=searchDialog(app);previous.dismiss();AlertDialog current=searchDialog(app);assertNotSame(previous,current);
        input(current.getWindow().getDecorView(),"주소·장소명 검색어").setText("경복궁");dialogText(current,"검색").performClick();awaitDialogText(current,"경복궁");assertEquals(1,calls.get());assertTrue(current.isShowing());assertFalse(state.gpsEnabled);
    }
    @Test public void mapDestinationClearsOldSearchNameAndSampleRestoresItsLabel(){
        state.setDestination(palace().point(),palace().name,palace().address);state.setDestination(new RoadRoute.Point(37.58,126.98));
        assertEquals("지도에서 선택한 목적지",state.destinationName);assertEquals("",state.destinationAddress);
        state.useSeoulSample();assertEquals("경복궁 · 서울 샘플",state.destinationName);
    }
}
