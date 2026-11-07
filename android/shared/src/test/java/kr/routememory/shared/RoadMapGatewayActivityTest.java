package kr.routememory.shared;

import android.app.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Synthetic gateway status only. No real endpoint, image or location request. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=29)
public final class RoadMapGatewayActivityTest {
    private RoadController state;private ActivityController<RoadActivity> activity;private RoadActivity app;
    @Before public void setup(){state=RoadController.get(RuntimeEnvironment.getApplication());state.useSeoulSample();state.disconnectHighResolution();state.setMapStyle(RoadMapStyle.ROAD);activity=Robolectric.buildActivity(RoadActivity.class).create().start().resume();app=activity.get();}
    @After public void cleanup(){activity.pause().stop().destroy();state.disconnectHighResolution();state.setMapStyle(RoadMapStyle.ROAD);state.stopAll();}
    private View find(View view,String label,boolean description){
        if(description&&label.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;
        if(!description&&view instanceof TextView&&!(view instanceof EditText)&&label.contentEquals(((TextView)view).getText()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),label,description);if(found!=null)return found;}return null;
    }
    private AlertDialog open(){app.mapConnection();return org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();}
    private EditText address(AlertDialog dialog){return (EditText)find(dialog.getWindow().getDecorView(),"고화질 지도 서버 주소",true);}
    private Button connect(AlertDialog dialog){return (Button)find(dialog.getWindow().getDecorView(),"연결하고 사용",false);}
    private RoadMapGateway result(String origin,boolean configured)throws java.io.IOException{return RoadMapGateway.parse(origin,"{\"provider\":\"vworld\",\"configured\":"+configured+",\"maxNativeZoom\":18}",true);}
    private void await(Callable<Boolean> condition)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!condition.call()&&System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5);}assertTrue(condition.call());}
    @Test public void openingTypingAndUsbShortcutSendNoRequestOrGps(){
        AtomicInteger calls=new AtomicInteger();app.gatewayLookup=origin->{calls.incrementAndGet();return result(origin,false);};AlertDialog dialog=open();address(dialog).setText("https://maps.example.org");
        View usb=find(dialog.getWindow().getDecorView(),"USB 개발 서버 주소 입력",false);assertNotNull(usb);usb.performClick();assertEquals("http://127.0.0.1:4173",address(dialog).getText().toString());
        assertEquals(0,calls.get());assertFalse(state.gpsEnabled);assertFalse(state.loading);assertFalse(state.highResolutionReady());
    }
    @Test public void missingServerKeyCannotEnableHdAndNoSecretIsEntered()throws Exception{
        AtomicInteger calls=new AtomicInteger();app.gatewayLookup=origin->{calls.incrementAndGet();return result(origin,false);};AlertDialog dialog=open();address(dialog).setText("https://maps.example.org");connect(dialog).performClick();
        await(()->calls.get()==1&&find(dialog.getWindow().getDecorView(),"이 서버에 브이월드 키가 설정되지 않았습니다. 서버의 발급키 설정을 마친 뒤 다시 연결하세요.",false)!=null);
        assertFalse(state.highResolutionReady());assertEquals(RoadMapStyle.ROAD,state.mapStyle);assertFalse(state.gpsEnabled);assertFalse(state.loading);assertTrue(dialog.isShowing());
    }
    @Test public void explicitSuccessfulCheckEnablesFixedHdProviderWithoutGpsOrRoute()throws Exception{
        app.gatewayLookup=origin->result(origin,true);AlertDialog dialog=open();address(dialog).setText("https://maps.example.org");connect(dialog).performClick();
        await(()->state.highResolutionReady());assertEquals("https://maps.example.org",state.highResolutionGateway());assertEquals(RoadMapStyle.SATELLITE_HD,state.mapStyle);assertFalse(state.gpsEnabled);assertFalse(state.guiding);assertFalse(state.loading);assertNull(state.route);assertFalse(dialog.isShowing());
    }
    @Test public void invalidAddressNeverCallsProvider(){
        AtomicInteger calls=new AtomicInteger();app.gatewayLookup=origin->{calls.incrementAndGet();return result(origin,true);};AlertDialog dialog=open();address(dialog).setText("https://maps.example.org/?key=secret");connect(dialog).performClick();assertNotNull(address(dialog).getError());assertEquals(0,calls.get());assertFalse(state.highResolutionReady());
    }
    @Test public void changingAddressRejectsOldStatusResponse()throws Exception{
        CountDownLatch produced=new CountDownLatch(1);app.gatewayLookup=origin->{RoadMapGateway result=result(origin,true);produced.countDown();return result;};AlertDialog dialog=open();address(dialog).setText("https://a.example.org");connect(dialog).performClick();assertTrue(produced.await(2,TimeUnit.SECONDS));address(dialog).setText("https://b.example.org");
        Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(state.highResolutionReady());assertEquals(RoadMapStyle.ROAD,state.mapStyle);
    }
    @Test public void disconnectInvalidatesQueuedStatusBeforeDismissListenerRuns()throws Exception{
        state.setHighResolutionGateway(result("https://old.example.org",true));CountDownLatch produced=new CountDownLatch(1);app.gatewayLookup=origin->{RoadMapGateway result=result(origin,true);produced.countDown();return result;};AlertDialog dialog=open();address(dialog).setText("https://new.example.org");connect(dialog).performClick();assertTrue(produced.await(2,TimeUnit.SECONDS));
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(state.highResolutionReady());assertEquals(RoadMapStyle.ROAD,state.mapStyle);assertFalse(state.gpsEnabled);
    }
}
