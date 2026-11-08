package kr.routememory.pleos;

import ai.pleos.playground.navi.constants.CautionPointType;
import ai.pleos.playground.navi.constants.NaviErrorCode;
import ai.pleos.playground.navi.constants.NavigationStatus;
import ai.pleos.playground.navi.constants.RouteOption;
import ai.pleos.playground.navi.data.CautionPoint;
import ai.pleos.playground.navi.data.DestinationArrivedInfo;
import ai.pleos.playground.navi.data.DestinationInfo;
import ai.pleos.playground.navi.data.RouteStartInfo;
import ai.pleos.playground.navi.data.TBTInfo;
import ai.pleos.playground.navi.helper.NaviHelper;
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener;
import ai.pleos.playground.vehicle.Vehicle;
import ai.pleos.playground.vehicle.api.model.area.SeatArea;
import ai.pleos.playground.vehicle.constant.area.SeatValue;
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue;
import ai.pleos.playground.vehicle.constant.control.VehicleGear;
import ai.pleos.playground.vehicle.listener.GearSelectionListener;
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener;
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.List;
import kr.routememory.core.DriveCoach;
import kr.routememory.core.DriveCoach.DriverType;
import kr.routememory.core.DriveCoach.Signal;
import kr.routememory.core.DriveCoach.Turn;
import kr.routememory.core.DriveCoach.TurnType;
import kr.routememory.core.GuidanceSession;
import kr.routememory.core.GuidanceSession.Outcome;

/**
 * Connects the Pleos Connect SDKs to the companion's rules. Vehicle SDK gives the turn signal, the gear and the front
 * passenger seat; NaviHelper gives the next turns, reroutes, caution points, the destination and the navigation state;
 * the Android Car API gives the speed where it may, FusedLocation the position (and the speed where the Car API may
 * not). Everything the rules decide comes back to the host on the main thread.
 * The Vehicle SDK routes an event by the listener's type, not by the property: one object registered for both the
 * turn signal and the gear receives the turn signal as a gear (seen on the official emulator: LEFT arrived as
 * GEAR_REVERSE). So each property gets its own listener object.
 */
public final class PleosBridge implements NaviHelperEventListener {
    private static final String TAG="PleosBridge";
    private final TurnSignalStateListener signalListener=new TurnSignalStateListener() {
        @Override public void onTurnSignalStateUpdated(TurnSignalValue value){PleosBridge.this.onTurnSignalStateUpdated(value);}
        @Override public void onFailed(Exception e){PleosBridge.this.onFailed(e);}
    };
    private final GearSelectionListener gearListener=new GearSelectionListener() {
        @Override public void onSelected(VehicleGear value){PleosBridge.this.onSelected(value);}
        @Override public void onFailed(Exception e){PleosBridge.this.onFailed(e);}
    };
    private final SeatOccupancyListener seatListener=new SeatOccupancyListener() {
        @Override public void onSeatOccupancyUpdated(Boolean occupied){PleosBridge.this.onSeatOccupancyUpdated(occupied!=null&&occupied);}
        @Override public void onFailed(Exception e){android.util.Log.d(TAG,"seat "+e);}
    };
    private final SeatArea frontPassenger=new SeatArea(SeatValue.ROW_1_RIGHT);
    public interface Host {
        void onLink(String vehicleState, String naviState);
        void onSources(String speedSource, String locationState);
        void onVehicle(Signal signal, String gear);
        void onSpeed(int kph);
        void onNavigation(String turnLabel, int meters, String status);
        void onDestination(String name);
        void onOutcome(Outcome outcome, String source);
    }
    private final Context context; private final GuidanceSession session; private final Host host;
    private final Handler main=new Handler(Looper.getMainLooper());
    private Vehicle vehicle; private NaviHelper navi;
    private CarSpeedSource carSpeed; private final PleosLocationSource location=new PleosLocationSource();
    private boolean carSpeedLive,seatLive;
    private String vehicleState="연결 전",naviState="연결 전",gear="-",naviStatus="-";
    private double lastLat,lastLon; private long lastAt; private int derived1=-1,derived2=-1;

    public PleosBridge(Context context,GuidanceSession session,Host host){this.context=context.getApplicationContext();this.session=session;this.host=host;}
    public GuidanceSession session(){return session;}
    public boolean connected(){return vehicle!=null||navi!=null;}
    public boolean seatLive(){return seatLive;}

    /** Each SDK is tried on its own, so a missing service explains itself instead of taking the other down. */
    public void connect() {
        if(vehicle==null) {
            try {
                Vehicle v=new Vehicle(context);v.initialize();
                v.getTurnSignal().registerTurnSignalState(signalListener);
                v.getGear().registerGearSelection(gearListener);
                vehicle=v;vehicleState="연결됨";
                // The front passenger seat: a taxi's fare. Not every car reports it, so this is tried apart.
                try{v.getSeat().registerSeatOccupancy(frontPassenger,seatListener);seatLive=true;}catch(Throwable e){seatLive=false;android.util.Log.d(TAG,"seat occupancy not available: "+brief(e));}
            } catch(Throwable e){vehicle=null;vehicleState="연결 실패: "+brief(e);}
        }
        if(navi==null) {
            try {
                NaviHelper n=new NaviHelper(context);n.initialize();n.addListener(this);
                navi=n;naviState="연결됨";
                n.getRouteStateInfo();n.getTBTInfo();n.getDestinationInfo();
            } catch(Throwable e){navi=null;naviState="연결 실패: "+brief(e);}
        }
        host.onLink(vehicleState,naviState);
        String speedState;
        try{if(carSpeed==null)carSpeed=new CarSpeedSource();carSpeedLive=carSpeed.start(context,kph->post(()->onSpeedKph(kph)));speedState=carSpeed.state();}catch(RuntimeException|LinkageError e){carSpeed=null;carSpeedLive=false;speedState="Car API 없음";}
        location.start(context,(lat,lon,at)->post(()->onLocation(lat,lon,at)));
        host.onSources(carSpeedLive?"Car API":"위치로 계산 ("+speedState+")",location.state());
    }
    public void release() {
        try{if(vehicle!=null){vehicle.getTurnSignal().unregisterTurnSignalState(signalListener);vehicle.getGear().unregisterGearSelection(gearListener);if(seatLive)vehicle.getSeat().unregisterSeatOccupancy(frontPassenger,seatListener);vehicle.release();}}catch(Throwable ignored){}
        try{if(navi!=null){navi.removeListener(this);navi.release();}}catch(Throwable ignored){}
        try{if(carSpeed!=null)carSpeed.stop();}catch(RuntimeException|LinkageError ignored){}location.stop();carSpeedLive=false;seatLive=false;
        vehicle=null;navi=null;vehicleState="연결 해제";naviState="연결 해제";
        host.onLink(vehicleState,naviState);host.onSources("-","-");
    }
    /** The driver accepted the comfort route: the navigation is asked for its comfortable option. */
    public boolean requestComfortRoute() {
        if(navi==null)return false;
        try{navi.changeRouteOption(RouteOption.COMFORTABLE);return true;}catch(Throwable e){android.util.Log.d(TAG,"route option "+brief(e));return false;}
    }
    private static String brief(Throwable e){String m=e.getMessage();return e.getClass().getSimpleName()+(m==null||m.isEmpty()?"":" "+m.substring(0,Math.min(60,m.length())));}
    private void post(Runnable r){if(Looper.myLooper()==Looper.getMainLooper())r.run();else main.post(r);}
    private static long now(){return System.currentTimeMillis();}

    // Vehicle SDK
    public void onTurnSignalStateUpdated(TurnSignalValue value) {
        android.util.Log.d(TAG,"turn signal "+value);
        Signal s=value==TurnSignalValue.LEFT?Signal.LEFT:value==TurnSignalValue.RIGHT?Signal.RIGHT:Signal.OFF;
        post(()->{host.onVehicle(s,gear);host.onOutcome(session.onSignal(s,now()),"signal");});
    }
    public void onSelected(VehicleGear value) {
        android.util.Log.d(TAG,"gear "+value);
        boolean parked=value==VehicleGear.GEAR_PARK;
        post(()->{gear=value.name().replace("GEAR_","");host.onVehicle(session.signal(),gear);host.onOutcome(session.onGear(parked,now()),"gear");});
    }
    /** The front passenger seat decides "passenger" for a taxi; other drivers set it themselves. */
    public void onSeatOccupancyUpdated(boolean occupied) {
        android.util.Log.d(TAG,"front passenger seat "+occupied);
        post(()->{if(session.profile().driverType==DriverType.TAXI){session.setPassenger(occupied);host.onOutcome(session.onGear(session.parked(),now()),"seat");}});
    }
    public void onFailed(Exception e){post(()->{vehicleState="오류: "+brief(e);host.onLink(vehicleState,naviState);});}

    // Car API / FusedLocation
    public void onSpeedKph(int kph){host.onSpeed(kph);host.onOutcome(session.onSpeed(kph,now()),"speed");}
    public void onLocation(double lat,double lon,long at) {
        if(!carSpeedLive&&lastAt>0) {
            long dt=at-lastAt;
            if(dt>=500&&dt<=5000) {
                int kph=(int)Math.round(PleosLocationSource.meters(lastLat,lastLon,lat,lon)/(dt/1000.0)*3.6);
                // Three positions smooth the jumps of a fused fix; anything over 200 km/h is a jump, not a car.
                if(kph<=200){int avg=derived1<0?kph:derived2<0?(kph+derived1)/2:(kph+derived1+derived2)/3;derived2=derived1;derived1=kph;onSpeedKph(avg);}
            } else if(dt>5000){derived1=-1;derived2=-1;}
        }
        lastLat=lat;lastLon=lon;lastAt=at;
        host.onOutcome(session.onLocation(lat,lon,now()),"location");
    }

    // NaviHelper SDK
    /** Pleos turn types onto the companion's turn types; anything else is not a turn the rules know. */
    static TurnType map(ai.pleos.playground.navi.constants.TurnType type) {
        if(type==null)return null;
        switch(type) {
            case TURN_EXIT_IC_L:return TurnType.EXIT_LEFT;
            case TURN_EXIT_IC_R:return TurnType.EXIT_RIGHT;
            case TURN_KEEP_L:return TurnType.KEEP_LEFT;
            case TURN_KEEP_R:return TurnType.KEEP_RIGHT;
            case TURN_LEFT:case TURN_SHARP_L:return TurnType.TURN_LEFT;
            case TURN_RIGHT:case TURN_SHARP_R:return TurnType.TURN_RIGHT;
            default:return type.name().startsWith("TURN_ENTER_ROUNDABOUT")?TurnType.ROUNDABOUT:null;
        }
    }
    /** What the sentence adds to the turn: the roundabout exit as a clock direction, or the navigation's own words. */
    static String detail(ai.pleos.playground.navi.constants.TurnType type,String description) {
        String name=type==null?"":type.name();
        if(name.startsWith("TURN_ENTER_ROUNDABOUT_")) {
            try{return Integer.parseInt(name.substring("TURN_ENTER_ROUNDABOUT_".length()))+"시 방향 출구";}catch(NumberFormatException ignored){}
        }
        String words=description==null?"":description.trim();
        TurnType mapped=map(type);
        if(words.length()<2||mapped!=null&&words.equals(mapped.label))return null;
        return words.length()>24?words.substring(0,24):words;
    }
    /** The place id must survive across trips, so it is built from the turn type and the navigation's own description. */
    static String placeId(TurnType type,String description){return type.name().toLowerCase()+"|"+(description==null?"":description.trim());}
    @Override public void onTBTInfo(List<TBTInfo> list) {
        TBTInfo first=list==null||list.isEmpty()?null:list.get(0),second=list!=null&&list.size()>1?list.get(1):null;
        TurnType type=first==null?null:map(first.getType());
        post(()->{
            if(type==null){host.onNavigation(first==null?"다음 회전 없음":"판단 대상 아님: "+first.getType().name(),-1,naviStatus);host.onOutcome(session.onTurn(null,-1,now()),"tbt");return;}
            int meters=(int)Math.round(first.getDistance());
            int gap=second==null?-1:(int)Math.round(second.getDistance()-first.getDistance());
            String detail=detail(first.getType(),first.getDescription());
            host.onNavigation(type.label+(detail==null?"":" · "+detail),meters,naviStatus);
            host.onOutcome(session.onTurn(new Turn(placeId(type,first.getDescription()),type,detail),meters,gap>0?gap:-1,now()),"tbt");
        });
    }
    @Override public void onCautionPointInfo(List<CautionPoint> list) {
        if(list==null)return;
        for(CautionPoint point:list) {
            if(point.getType()!=CautionPointType.NARROW_ROAD)continue;
            // A caution point only takes the stage when no turn is being watched.
            post(()->{if(session.currentTurn()==null||session.currentTurn().type==TurnType.NARROW_ROAD)host.onOutcome(session.onCaution("narrow|"+point.getId(),point.getRemainDistance(),now()),"caution");});
            return;
        }
    }
    @Override public void onReRouteEnded(){post(()->host.onOutcome(session.onReroute(now()),"reroute"));}
    @Override public void onNavigationStatus(NavigationStatus status) {
        post(()->{naviStatus=status.name();host.onNavigation(session.currentTurn()==null?"다음 회전 없음":session.currentTurn().type.label,session.meters(),naviStatus);host.onOutcome(session.onNavigationStatus(status==NavigationStatus.ROUTE_DRIVE,now()),"status");});
    }
    @Override public void onRouteStarted(RouteStartInfo info) {
        String name=info==null?null:info.getName();
        post(()->{if(name!=null&&!name.trim().isEmpty()){host.onDestination(name.trim());host.onOutcome(session.onDestination(name,now()),"route");}});
        if(navi!=null)try{navi.getDestinationInfo();}catch(Throwable ignored){}
    }
    @Override public void onDestinationInfo(DestinationInfo info) {
        String name=info==null?null:info.getName();
        post(()->{if(name!=null&&!name.trim().isEmpty()){host.onDestination(name.trim());host.onOutcome(session.onDestination(name,now()),"destination");}});
    }
    @Override public void onDestinationChanged(){if(navi!=null)try{navi.getDestinationInfo();}catch(Throwable ignored){}}
    @Override public void onRouteCancelled(){post(()->host.onOutcome(session.onNavigationStatus(false,now()),"cancel"));}
    @Override public void onDestinationArrived(DestinationArrivedInfo info){post(()->host.onOutcome(session.onNavigationStatus(false,now()),"arrive"));}
    @Override public void onError(NaviErrorCode code){post(()->{naviState="오류: "+code.name();host.onLink(vehicleState,naviState);});}
}
