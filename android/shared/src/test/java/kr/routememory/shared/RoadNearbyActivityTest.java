package kr.routememory.shared;

import android.app.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import kr.routememory.core.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Manual nearby behavior without network calls, GPS permissions, or map drawing. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=29)
public final class RoadNearbyActivityTest {
    private RoadController state;
    private ActivityController<RoadActivity> activity;
    @Before public void setup(){state=RoadController.get(RuntimeEnvironment.getApplication());state.stopAll();state.useSeoulSample();}
    @After public void cleanup(){if(activity!=null)activity.pause().stop().destroy();state.stopAll();}
    private RoadActivity open(){activity=Robolectric.buildActivity(RoadActivity.class).create().start().resume();return activity.get();}
    private TextView find(View view,String label){if(view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return (TextView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView found=find(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
    private TextView text(AlertDialog dialog,String label){return find(dialog.getWindow().getDecorView(),label);}
    private AlertDialog sheet(RoadActivity app,String kind){app.nearbySearch(kind);return org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();}
    private void await(AlertDialog dialog,String label)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(text(dialog,label)==null&&System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5);}assertNotNull("Missing "+label,text(dialog,label));}
    private NearbySearchClient.NearbyPlace place(){return new NearbySearchClient.NearbyPlace("photon-N-1","테스트 주차장","서울특별시 종로구", "주차장",37.5797,126.978,111, null,null);}
    private NearbySearchClient.Result result(RoadRoute.Point point,String kind,int radius,List<NearbySearchClient.NearbyPlace> places){return new NearbySearchClient.Result(kind,"Photon · OpenStreetMap",point,radius,Instant.now().toString(),places,"공개 지도 · 실시간 미연결");}
    @Test public void openingAndChangingControlsDoesNotSendCoordinatesOrRequestGps(){
        RoadActivity app=open();AtomicInteger calls=new AtomicInteger();app.nearbyClient=new NearbySearchClient(){@Override public Result search(RoadRoute.Point center,String kind,int radius){calls.incrementAndGet();return result(center,kind,radius,List.of(place()));}};
        assertNotNull(find(app.getWindow().getDecorView(),"주유소"));assertNotNull(find(app.getWindow().getDecorView(),"주차장"));
        AlertDialog dialog=sheet(app,"parking");text(dialog,"현재 위치 주변").performClick();text(dialog,"1km").performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0,calls.get());assertFalse(state.gpsEnabled);assertNull(state.fix);assertFalse(state.guiding);assertFalse(state.loading);
    }
    @Test public void currentCenterWithoutFreshGpsShowsMessageWithoutNetworkOrPermission(){
        RoadActivity app=open();AtomicInteger calls=new AtomicInteger();app.nearbyClient=new NearbySearchClient(){@Override public Result search(RoadRoute.Point center,String kind,int radius){calls.incrementAndGet();return result(center,kind,radius,List.of());}};
        AlertDialog dialog=sheet(app,"parking");text(dialog,"현재 위치 주변").performClick();text(dialog,"주변 주차장 조회").performClick();
        assertNotNull(text(dialog,"최신 GPS 위치가 없습니다. GPS를 연결하고 위치 수신 후 다시 조회해 주세요"));assertEquals(0,calls.get());assertFalse(state.gpsEnabled);assertTrue(dialog.isShowing());
    }
    @Test public void oldConnectButtonCannotTurnOffGpsEnabledElsewhere(){
        RoadActivity app=open();AlertDialog dialog=sheet(app,"parking");assertNotNull(text(dialog,"GPS 연결"));state.gpsEnabled=true;text(dialog,"GPS 연결").performClick();assertTrue(state.gpsEnabled);assertFalse(state.guiding);
    }
    @Test public void explicitDestinationLookupAndSelectionRequestsNamedRouteWithoutGps()throws Exception{
        RoadActivity app=open();AtomicInteger calls=new AtomicInteger(),routes=new AtomicInteger();app.requestRoute=routes::incrementAndGet;
        app.nearbyClient=new NearbySearchClient(){@Override public Result search(RoadRoute.Point center,String kind,int radius){assertEquals(state.destination.lat,center.lat,.000001);assertEquals("parking",kind);assertEquals(1000,radius);calls.incrementAndGet();return result(center,kind,radius,List.of(place()));}};
        AlertDialog dialog=sheet(app,"parking");text(dialog,"1km").performClick();text(dialog,"주변 주차장 조회").performClick();await(dialog,"테스트 주차장");assertNotNull(text(dialog,"잔여 면수 미제공"));assertNotNull(text(dialog,"서울특별시 종로구 · 직선 111m"));
        ((View)text(dialog,"테스트 주차장").getParent()).performClick();assertEquals(1,calls.get());assertEquals(1,routes.get());assertEquals("테스트 주차장",state.destinationName);assertEquals("서울특별시 종로구",state.destinationAddress);assertFalse(dialog.isShowing());assertFalse(state.gpsEnabled);assertFalse(state.guiding);
    }
    @Test public void existingFreshGpsIsSentOnlyAfterExplicitCurrentCenterLookup()throws Exception{
        RoadActivity app=open();state.gpsEnabled=true;state.fix=new RoadTracker.Fix(37.57,126.98,10,System.currentTimeMillis());AtomicInteger calls=new AtomicInteger();
        app.nearbyClient=new NearbySearchClient(){@Override public Result search(RoadRoute.Point center,String kind,int radius){assertEquals(37.57,center.lat,.000001);assertEquals(126.98,center.lon,.000001);calls.incrementAndGet();return result(center,kind,radius,List.of(place()));}};
        AlertDialog dialog=sheet(app,"parking");text(dialog,"현재 위치 주변").performClick();assertEquals(0,calls.get());text(dialog,"주변 주차장 조회").performClick();await(dialog,"테스트 주차장");assertEquals(1,calls.get());assertFalse(state.guiding);
    }
    @Test public void noKeyFuelResultsShowConnectionPendingWithoutSyntheticPrices()throws Exception{
        RoadActivity app=open();app.nearbyClient=new NearbySearchClient(){@Override public Result search(RoadRoute.Point center,String kind,int radius){return result(center,kind,radius,List.of(new NearbyPlace("photon-N-2","테스트 주유소","서울특별시 종로구","주유소",37.5797,126.978,111,null,null)));}};
        AlertDialog dialog=sheet(app,"fuel");text(dialog,"주변 주유소 조회").performClick();await(dialog,"테스트 주유소");assertNotNull(text(dialog,"유가 연동 대기"));assertFalse(state.gpsEnabled);
    }
    @Test public void reportedZeroAndStaleParkingRemainDistinctFromUnknown()throws Exception{
        RoadActivity app=open();app.nearbyClient=new NearbySearchClient(){@Override public Result search(RoadRoute.Point center,String kind,int radius){return result(center,kind,radius,List.of(
            new NearbyPlace("1","보고된 만차","서울","주차장",37.5797,126.978,111,null,new ParkingInfo("reported",100,0,"서울시",Instant.now().toString(),"")),
            new NearbyPlace("2","이전 잔여정보","서울","주차장",37.5798,126.978,112,null,new ParkingInfo("reported",50,10,"서울시",Instant.now().minusSeconds(3600).toString(),""))));}};
        AlertDialog dialog=sheet(app,"parking");text(dialog,"주변 주차장 조회").performClick();await(dialog,"보고된 만차");assertNotNull(text(dialog,"보고 시점 · 만차 · 전체 100면"));assertNotNull(text(dialog,"이전 정보 · 잔여 10면 · 전체 50면"));
    }
    @Test public void nearbyErrorAndEmptyResultsPreserveDestination()throws Exception{
        RoadActivity app=open();app.nearbyClient=new NearbySearchClient(){@Override public Result search(RoadRoute.Point center,String kind,int radius)throws java.io.IOException{throw new java.io.IOException("주변 서버 오류");}};
        AlertDialog dialog=sheet(app,"parking");text(dialog,"주변 주차장 조회").performClick();await(dialog,"주변 서버 오류");dialog.dismiss();
        app.nearbyClient=new NearbySearchClient(){@Override public Result search(RoadRoute.Point center,String kind,int radius){return result(center,kind,radius,List.of());}};dialog=sheet(app,"parking");text(dialog,"주변 주차장 조회").performClick();await(dialog,"반경 안에 등록된 주차장이 없습니다. 반경이나 기준 위치를 바꿔 조회해 주세요");assertEquals("경복궁 · 서울 샘플",state.destinationName);assertFalse(state.gpsEnabled);
    }
    @Test public void dismissedNearbyIgnoresLateResponse()throws Exception{
        RoadActivity app=open();CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1),finished=new CountDownLatch(1);app.nearbyClient=new NearbySearchClient(){@Override public Result search(RoadRoute.Point center,String kind,int radius){started.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException ignored){}finally{finished.countDown();}return result(center,kind,radius,List.of(place()));}};
        try{AlertDialog dialog=sheet(app,"parking");text(dialog,"주변 주차장 조회").performClick();assertTrue(started.await(2,TimeUnit.SECONDS));dialog.dismiss();release.countDown();assertTrue(finished.await(2,TimeUnit.SECONDS));Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(text(dialog,"테스트 주차장"));assertEquals("경복궁 · 서울 샘플",state.destinationName);}finally{release.countDown();}
    }
    @Test public void changingRadiusInvalidatesLateNearbyResponse()throws Exception{
        RoadActivity app=open();CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1),finished=new CountDownLatch(1);app.nearbyClient=new NearbySearchClient(){@Override public Result search(RoadRoute.Point center,String kind,int radius){started.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException ignored){}finally{finished.countDown();}return result(center,kind,radius,List.of(place()));}};
        try{AlertDialog dialog=sheet(app,"parking");text(dialog,"주변 주차장 조회").performClick();assertTrue(started.await(2,TimeUnit.SECONDS));text(dialog,"1km").performClick();release.countDown();assertTrue(finished.await(2,TimeUnit.SECONDS));Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(text(dialog,"테스트 주차장"));assertNotNull(text(dialog,"주변 주차장 조회"));assertFalse(state.gpsEnabled);}finally{release.countDown();}
    }
}
