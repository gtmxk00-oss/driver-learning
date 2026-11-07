package kr.routememory.shared;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;
import kr.routememory.core.*;

/** Main-thread actual-road session, shared by phone and car. Coordinates are memory-only. */
public final class RoadController {
    private static RoadController instance;
    public static synchronized RoadController get(Context context) {
        if(instance==null)instance=new RoadController(context.getApplicationContext());
        return instance;
    }
    public RoadRoute route;
    public RoadTracker tracker;
    public RoadTracker.Fix fix;
    public RoadRoute.Point origin=new RoadRoute.Point(37.5665,126.9780);
    public RoadRoute.Point destination=new RoadRoute.Point(37.5796,126.9770);
    public String destinationName="경복궁 · 서울 샘플",destinationAddress="";
    public boolean gpsEnabled,guiding,loading;
    public RoadMapStyle mapStyle=RoadMapStyle.ROAD;
    private RoadMapGateway highResolutionGateway;
    public String notice="실제 지도 · 실시간 교통 미연결 · GPS 꺼짐";
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private final Set<Runnable> listeners=new LinkedHashSet<>();
    private final GuidanceSpeaker speaker;
    private int requestId,announcedStep=-1;
    private Future<?> pendingRoute;
    private long lastSearchAt;
    private boolean awaitingFirstFix;
    int gpsGeneration;
    private String spokenStatus="";
    private RoadController(Context context){this.context=context;speaker=new GuidanceSpeaker(context);}
    public void addListener(Runnable r){listeners.add(r);}
    public void removeListener(Runnable r){listeners.remove(r);}
    void changed(){for(Runnable r:new ArrayList<>(listeners))r.run();}
    /** Basemap only: retain the selected route, camera, GPS and guidance state. */
    public void setMapStyle(RoadMapStyle style){
        if(style==null||style==mapStyle)return;
        if(style==RoadMapStyle.SATELLITE_HD&&!highResolutionReady())return;
        mapStyle=style;changed();
    }
    public boolean highResolutionReady(){return highResolutionGateway!=null&&highResolutionGateway.configured;}
    public String highResolutionGateway(){return highResolutionGateway==null?"":highResolutionGateway.origin;}
    public void setHighResolutionGateway(RoadMapGateway gateway){
        highResolutionGateway=gateway!=null&&gateway.configured?gateway:null;
        RoadTileStore.get(context).setHighResolutionGateway(highResolutionGateway);
        if(!highResolutionReady()&&mapStyle==RoadMapStyle.SATELLITE_HD)mapStyle=RoadMapStyle.ROAD;
        changed();
    }
    public void disconnectHighResolution(){setHighResolutionGateway(null);}
    public boolean hasPermission(){
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
            ||context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    }
    private static boolean valid(RoadRoute.Point p){return p!=null&&Double.isFinite(p.lat)&&Double.isFinite(p.lon)&&Math.abs(p.lat)<=85.05112878&&Math.abs(p.lon)<=180;}
    private void clearRoute(){requestId++;if(pendingRoute!=null)pendingRoute.cancel(true);loading=false;stopGuidanceInternal();route=null;tracker=null;}
    public void setOrigin(RoadRoute.Point value){
        if(!valid(value)){notice="올바른 출발 좌표를 입력하세요";changed();return;}
        clearRoute();origin=value;notice="출발지를 변경했습니다. 경로를 탐색하세요";changed();
    }
    public void setDestination(RoadRoute.Point value){
        setDestination(value,"지도에서 선택한 목적지","");
    }
    public void setDestination(RoadRoute.Point value,String name,String address){
        if(!valid(value)){notice="올바른 목적지 좌표를 입력하세요";changed();return;}
        clearRoute();destination=value;destinationName=label(name,160);destinationAddress=label(address,320);notice="목적지를 변경했습니다. 경로를 탐색하세요";changed();
    }
    private static String label(String value,int max){if(value==null)return "";String clean=value.replaceAll("\\p{Cc}"," ").trim().replaceAll("\\s+"," ");return clean.substring(0,Math.min(max,clean.length()));}
    public void useSeoulSample(){
        stopAll();origin=new RoadRoute.Point(37.5665,126.9780);destination=new RoadRoute.Point(37.5796,126.9770);
        destinationName="경복궁 · 서울 샘플";destinationAddress="";
        clearRoute();notice="서울 시청 → 경복궁 샘플 · 실제 GPS 위치와 무관";changed();
    }
    public boolean freshFix(){return fix!=null&&(!guiding||tracker==null||(tracker.accepted!=null&&tracker.accepted.timestamp==fix.timestamp))&&Double.isFinite(fix.accuracy)&&fix.accuracy>0&&fix.accuracy<=100
        &&System.currentTimeMillis()-fix.timestamp>=0&&System.currentTimeMillis()-fix.timestamp<=15000;}
    /** User-triggered only. This is the only operation that sends selected coordinates to OSRM. */
    public void search(){
        if(loading)return;
        if(gpsEnabled){
            if(!freshFix()){notice="정확한 최신 GPS 위치를 받은 뒤 탐색하세요";changed();return;}
            origin=new RoadRoute.Point(fix.lat,fix.lon);
        }
        if(!valid(origin)||!valid(destination)){notice="출발지와 목적지 좌표를 확인하세요";changed();return;}
        if(RoadTracker.distance(origin,destination)<30){notice="목적지를 출발지에서 30m 이상 떨어진 곳으로 선택하세요";changed();return;}
        long now=SystemClock.elapsedRealtime();
        if(lastSearchAt>0&&now-lastSearchAt<1500){notice="잠시 후 다시 탐색하세요";changed();return;}
        lastSearchAt=now;clearRoute();loading=true;notice="실제 도로 경로 탐색 중 · 실시간 교통 미반영";
        final int ticket=requestId;
        final RoadRoute.Point from=origin,to=destination;
        changed();pendingRoute=network.submit(()->{
            RoadRoute result=null;String error=null;
            try{result=new OsrmClient().route(from,to);}catch(Exception e){error="경로를 가져오지 못했습니다. 인터넷 연결과 도로 위 좌표를 확인하고 다시 시도하세요";}
            final RoadRoute answer=result;final String problem=error;
            main.post(()->{
                if(ticket!=requestId)return;
                loading=false;route=answer;
                notice=answer!=null?"실제 도로 경로 준비 · 예상 시간에 실시간 교통은 반영되지 않습니다":problem;
                changed();
            });
        });
    }
    public void startGps(){
        if(gpsEnabled)return;
        if(DemoController.get(context).running()){notice="가상 주행을 종료한 뒤 GPS를 켜세요";changed();return;}
        if(!hasPermission()){notice="GPS 버튼에서 위치 권한을 허용하세요";changed();return;}
        gpsGeneration++;gpsEnabled=true;awaitingFirstFix=true;fix=null;notice="GPS 수신 대기 중 · 위치가 확보되면 출발지를 갱신합니다";changed();
        try{context.startForegroundService(new Intent(context,LocationNavigationService.class).putExtra("gpsGeneration",gpsGeneration));}
        catch(RuntimeException e){gpsEnabled=false;awaitingFirstFix=false;notice="GPS를 시작할 수 없습니다. 휴대전화 앱을 화면에 열고 다시 켜세요";changed();}
    }
    void onFix(RoadTracker.Fix value){
        long now=System.currentTimeMillis();
        if(!gpsEnabled||value==null||!Double.isFinite(value.lat)||!Double.isFinite(value.lon)||Math.abs(value.lat)>85.05112878||Math.abs(value.lon)>180||!Double.isFinite(value.accuracy)||value.accuracy<=0||value.timestamp>now+1000||now-value.timestamp>15000)return;
        if(value.accuracy>100){notice="GPS 오차가 큽니다. 정밀한 위치를 기다리세요";changed();return;}
        if(fix!=null){
            if(value.timestamp<=fix.timestamp)return;
            double seconds=(value.timestamp-fix.timestamp)/1000.0;
            if(RoadTracker.distance(new RoadRoute.Point(fix.lat,fix.lon),new RoadRoute.Point(value.lat,value.lon))>Math.max(150,seconds*65+fix.accuracy+value.accuracy)){
                notice="GPS 위치가 크게 튀어 이번 위치를 사용하지 않았습니다";changed();return;
            }
        }
        fix=value;
        if(awaitingFirstFix&&value.accuracy<=100){
            awaitingFirstFix=false;clearRoute();origin=new RoadRoute.Point(value.lat,value.lon);
            notice="현재 위치로 출발지를 설정했습니다. 목적지를 선택하고 경로를 탐색하세요";
        }
        if(guiding&&tracker!=null){tracker.update(value,now);updateGuidance();}
        else if(value.accuracy>100)notice="GPS 오차가 큽니다. 정밀한 위치 수신을 기다리세요";
        changed();
    }
    void checkFreshness(){
        if(!gpsEnabled)return;
        if(!hasPermission()){stopAll();notice="위치 권한이 해제되어 GPS를 종료했습니다";changed();return;}
        if(guiding&&tracker!=null&&fix!=null){tracker.checkFreshness(System.currentTimeMillis());updateGuidance();changed();}
        else if(fix!=null&&System.currentTimeMillis()-fix.timestamp>15000){notice="최신 GPS 위치를 기다리고 있습니다";changed();}
    }
    public void startGuidance(){
        if(route==null||loading){notice="먼저 실제 도로 경로를 탐색하세요";changed();return;}
        if(!gpsEnabled||!freshFix()){notice="GPS를 켜고 오차 100m 이하의 최신 위치를 기다리세요";changed();return;}
        double separation=RoadTracker.distance(new RoadRoute.Point(fix.lat,fix.lon),route.geometry.get(0));
        if(!Double.isFinite(separation)||separation>Math.max(150,fix.accuracy*2)){
            notice="현재 위치와 출발지가 다릅니다. 현재 위치에서 경로를 다시 탐색하세요";changed();return;
        }
        tracker=new RoadTracker(route);guiding=true;announcedStep=-1;spokenStatus="";
        tracker.update(fix,System.currentTimeMillis());notice="GPS 길 안내를 시작했습니다 · 실시간 교통 미반영";speaker.speak("길 안내를 시작합니다");changed();
    }
    private void updateGuidance(){
        if(tracker.status==RoadTracker.Status.ARRIVED){
            stopGuidanceInternal();notice="목적지에 도착했습니다";speaker.speak("목적지에 도착했습니다");
            gpsEnabled=false;fix=null;context.stopService(new Intent(context,LocationNavigationService.class));return;
        }
        String status=tracker.status.name();
        if(tracker.status==RoadTracker.Status.OFF_ROUTE){
            notice="경로 이탈 감지 · 현재 위치에서 다시 탐색 버튼을 누르세요";
            if(!status.equals(spokenStatus))speaker.speak("경로를 벗어났습니다. 새 경로를 탐색해 주세요");
        }else if(tracker.status==RoadTracker.Status.STALE||tracker.status==RoadTracker.Status.POOR_ACCURACY){
            notice="GPS 수신이 불안정합니다. 위치가 확인되면 안내를 계속합니다";
        }else if(tracker.status==RoadTracker.Status.TRACKING){
            notice="GPS 길 안내 중 · 실시간 교통 미반영";
            if(tracker.nextStepIndex!=announcedStep&&tracker.nextDistanceMeters<=300){
                announcedStep=tracker.nextStepIndex;speaker.speak(Math.max(10,Math.round(tracker.nextDistanceMeters/10)*10)+"미터 앞, "+tracker.nextCue);
            }
        }
        spokenStatus=status;
    }
    private void stopGuidanceInternal(){guiding=false;speaker.stop();if(tracker!=null)tracker.stop();}
    public void stopGuidance(){stopGuidanceInternal();notice=gpsEnabled?"길 안내 종료 · GPS 위치는 계속 수신 중":"길 안내와 GPS가 종료되었습니다";changed();}
    public void stopAll(){
        requestId++;gpsGeneration++;if(pendingRoute!=null)pendingRoute.cancel(true);loading=false;stopGuidanceInternal();gpsEnabled=false;awaitingFirstFix=false;fix=null;
        context.stopService(new Intent(context,LocationNavigationService.class));notice="GPS와 길 안내를 종료했습니다";changed();
    }
    void serviceEnded(String message){
        if(!gpsEnabled)return;
        gpsEnabled=false;awaitingFirstFix=false;fix=null;stopGuidanceInternal();notice=message;changed();
    }
    void serviceEnded(int generation,String message){if(generation==gpsGeneration)serviceEnded(message);}
}
