package kr.routememory.pleos;

import ai.pleos.playground.navi.constants.CautionPointType;
import ai.pleos.playground.navi.constants.NaviErrorCode;
import ai.pleos.playground.navi.constants.NavigationStatus;
import ai.pleos.playground.navi.data.CautionPoint;
import ai.pleos.playground.navi.data.CurrentLocationInfo;
import ai.pleos.playground.navi.data.DestinationArrivedInfo;
import ai.pleos.playground.navi.data.DestinationInfo;
import ai.pleos.playground.navi.data.RouteStartInfo;
import ai.pleos.playground.navi.data.TBTInfo;
import ai.pleos.playground.navi.data.TripInfo;
import ai.pleos.playground.navi.helper.NaviHelper;
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener;
import ai.pleos.playground.vehicle.Vehicle;
import ai.pleos.playground.vehicle.api.model.area.SeatArea;
import ai.pleos.playground.vehicle.api.model.area.WindowArea;
import ai.pleos.playground.vehicle.constant.area.SeatValue;
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue;
import ai.pleos.playground.vehicle.constant.area.WindowValue;
import ai.pleos.playground.vehicle.constant.control.LightState;
import ai.pleos.playground.vehicle.constant.control.VehicleGear;
import ai.pleos.playground.vehicle.constant.control.WiperState;
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener;
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener;
import ai.pleos.playground.vehicle.listener.GearSelectionListener;
import ai.pleos.playground.vehicle.listener.LightStateListener;
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener;
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener;
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener;
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener;
import ai.pleos.playground.vehicle.listener.WiperStateListener;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import kr.routememory.core.DriveCoach;
import kr.routememory.core.DriveCoach.DriverType;
import kr.routememory.core.DriveCoach.Signal;
import kr.routememory.core.DriveCoach.Turn;
import kr.routememory.core.DriveCoach.TurnType;
import kr.routememory.core.GuidanceSession;
import kr.routememory.core.GuidanceSession.Outcome;

/**
 * Connects the Pleos Connect SDKs to the companion's rules. Vehicle SDK gives the turn signal, the gear, the front
 * passenger seat, the brake pedal, the steering wheel angle, the front wiper, the low beam, the driver's seat belt and
 * the trip distance; NaviHelper gives the next turns, reroutes, caution points, the destination, the navigation state,
 * the road's speed limit, the trip's remaining distance and time, arrival, and a position of its own; the Android Car
 * API gives the speed where it may, FusedLocation the position (and the speed where the Car API may not) and the
 * proximity alerts for remembered places. Everything the rules decide comes back to the host on the main thread.
 * The Vehicle SDK routes an event by the listener's type, not by the property: one object registered for both the
 * turn signal and the gear receives the turn signal as a gear (seen on the official emulator: LEFT arrived as
 * GEAR_REVERSE). So each property gets its own listener object.
 */
public final class PleosBridge implements NaviHelperEventListener {
    private static final String TAG="PleosBridge";
    /** A remembered place is announced when the car comes this close without a route. */
    static final float PROXIMITY_METERS=300f;
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
    private final BrakePedalStatusListener brakeListener=new BrakePedalStatusListener() {
        @Override public void onBrakePedalStatusUpdated(Boolean pressed){PleosBridge.this.onBrake(pressed!=null&&pressed);}
        @Override public void onFailed(Exception e){android.util.Log.d(TAG,"brake "+e);}
    };
    private final SteeringWheelAngleListener steerListener=new SteeringWheelAngleListener() {
        @Override public void onSteeringWheelAngleUpdated(Float degrees){PleosBridge.this.onSteering(degrees==null?0f:degrees);}
        @Override public void onFailed(Exception e){android.util.Log.d(TAG,"steering "+e);}
    };
    private final WiperStateListener wiperListener=new WiperStateListener() {
        @Override public void onWiperStateUpdated(WiperState state){PleosBridge.this.onWiper(state==WiperState.ON);}
        @Override public void onFailed(Exception e){android.util.Log.d(TAG,"wiper "+e);}
    };
    private final LightStateListener lowBeamListener=new LightStateListener() {
        @Override public void onLightStateUpdated(LightState state){PleosBridge.this.onLowBeam(state==LightState.ON);}
        @Override public void onFailed(Exception e){android.util.Log.d(TAG,"low beam "+e);}
    };
    private final SeatBeltBuckledStateListener beltListener=new SeatBeltBuckledStateListener() {
        @Override public void onSeatBeltBuckledStateUpdated(Boolean buckled){PleosBridge.this.onSeatBelt(buckled!=null&&buckled);}
        @Override public void onFailed(Exception e){android.util.Log.d(TAG,"seat belt "+e);}
    };
    private final DistanceDrivenListener tripListener=new DistanceDrivenListener() {
        @Override public void onDistanceDrivenUpdated(Float km){PleosBridge.this.onTripDistance(km==null?-1:km);}
        // The official emulator registers the trip distance but then reports it unsupported; the screen says so.
        @Override public void onFailed(Exception e){android.util.Log.d(TAG,"trip distance "+e);post(()->{if(extras.remove("주행거리"))host.onLink(vehicleState,naviState);});}
    };
    private final SeatArea frontPassenger=new SeatArea(SeatValue.ROW_1_RIGHT),driverSeat=new SeatArea(SeatValue.ROW_1_LEFT);
    private final WindowArea windshield=new WindowArea(WindowValue.FRONT_WINDSHIELD);
    public interface Host {
        void onLink(String vehicleState, String naviState);
        void onSources(String speedSource, String locationState);
        void onVehicle(Signal signal, String gear);
        void onSpeed(int kph);
        void onNavigation(String turnLabel, int meters, String status);
        void onDestination(String name);
        /** The car's other signals in one line: brake, steering, wiper, low beam, seat belt, trip distance. */
        void onCar(String line);
        /** The road and the trip from the navigation in one line: speed limit, remaining distance and time, arrival. */
        void onRoad(String line);
        void onOutcome(Outcome outcome, String source);
    }
    private final Context context; private final GuidanceSession session; private final Host host;
    private final Handler main=new Handler(Looper.getMainLooper());
    private Vehicle vehicle; private NaviHelper navi;
    private CarSpeedSource carSpeed; private final PleosLocationSource location=new PleosLocationSource();
    private boolean carSpeedLive,seatLive; private final List<String> extras=new ArrayList<>();
    private String vehicleState="연결 전",naviState="연결 전",gear="-",naviStatus="-";
    private double lastLat,lastLon; private long lastAt; private int derived1=-1,derived2=-1;
    private boolean brake,wiper,lowBeam,arrived; private Boolean belt; private float steer; private double tripKm=-1;
    private int limitKph=-1; private String trip="";

    public PleosBridge(Context context,GuidanceSession session,Host host){this.context=context.getApplicationContext();this.session=session;this.host=host;}
    public GuidanceSession session(){return session;}
    public boolean connected(){return vehicle!=null||navi!=null;}
    public boolean seatLive(){return seatLive;}
    /** Which of the extra Vehicle SDK signals the car reports, for the screen. */
    public String extrasLive(){return String.join("·",extras);}

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
                // The other signals, each on its own: a car that lacks one keeps the rest.
                extras.clear();
                tryRegister("브레이크",()->v.getBrake().registerBrakePedalStatus(brakeListener));
                tryRegister("핸들",()->v.getSteeringWheel().registerSteeringWheelAngle(steerListener));
                tryRegister("와이퍼",()->v.getWiper().registerWiperState(windshield,wiperListener));
                tryRegister("전조등",()->v.getLight().registerLowBeamLightState(lowBeamListener));
                tryRegister("벨트",()->v.getSeat().registerSeatBeltBuckledState(driverSeat,beltListener));
                tryRegister("주행거리",()->v.getOdometer().registerTripCurrentDrivingDistance(tripListener));
            } catch(Throwable e){vehicle=null;vehicleState="연결 실패: "+brief(e);}
        }
        if(navi==null) {
            try {
                NaviHelper n=new NaviHelper(context);n.initialize();n.addListener(this);
                navi=n;naviState="연결됨";
                n.getRouteStateInfo();n.getTBTInfo();n.getDestinationInfo();n.getCurrentLocationInfo();
            } catch(Throwable e){navi=null;naviState="연결 실패: "+brief(e);}
        }
        host.onLink(vehicleState,naviState);
        String speedState;
        try{if(carSpeed==null)carSpeed=new CarSpeedSource();carSpeedLive=carSpeed.start(context,kph->post(()->onSpeedKph(kph)));speedState=carSpeed.state();}catch(RuntimeException|LinkageError e){carSpeedLive=false;speedState="Car API 없음";}
        location.start(context,(lat,lon,at)->post(()->onLocation(lat,lon,at)));
        watchPlaces();
        host.onCar(carLine());host.onRoad(roadLine());
    }
    private void tryRegister(String name,Runnable register){try{register.run();extras.add(name);}catch(Throwable e){android.util.Log.d(TAG,name+" not available: "+brief(e));}}
    /** Asks the location service to watch every remembered place that has a position; called again whenever the memory changes. */
    public void watchPlaces() {
        location.watch(DriveCoach.placeCoords(session.profile()),PROXIMITY_METERS,placeId->post(()->host.onOutcome(session.onProximity(placeId,now()),"proximity")));
        host.onSources(carSpeedLive?"Car API":"위치로 계산 ("+(carSpeed==null?"Car API 없음":carSpeed.state())+")",location.state()+(location.watching()>0?" · 근접 알림 "+location.watching()+"곳":""));
    }
    public void release() {
        try{if(vehicle!=null){vehicle.getTurnSignal().unregisterTurnSignalState(signalListener);vehicle.getGear().unregisterGearSelection(gearListener);if(seatLive)vehicle.getSeat().unregisterSeatOccupancy(frontPassenger,seatListener);}}catch(Throwable ignored){}
        if(vehicle!=null) {
            try{vehicle.getBrake().unregisterBrakePedalStatus(brakeListener);}catch(Throwable ignored){}
            try{vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steerListener);}catch(Throwable ignored){}
            try{vehicle.getWiper().unregisterWiperState(windshield,wiperListener);}catch(Throwable ignored){}
            try{vehicle.getLight().unregisterLowBeamLightState(lowBeamListener);}catch(Throwable ignored){}
            try{vehicle.getSeat().unregisterSeatBeltBuckledState(driverSeat,beltListener);}catch(Throwable ignored){}
            try{vehicle.getOdometer().unregisterTripCurrentDrivingDistance(tripListener);}catch(Throwable ignored){}
            try{vehicle.release();}catch(Throwable ignored){}
        }
        try{if(navi!=null){navi.removeListener(this);navi.release();}}catch(Throwable ignored){}
        try{if(carSpeed!=null)carSpeed.stop();}catch(RuntimeException|LinkageError ignored){}location.stop();carSpeedLive=false;seatLive=false;extras.clear();
        vehicle=null;navi=null;vehicleState="연결 해제";naviState="연결 해제";
        host.onLink(vehicleState,naviState);host.onSources("-","-");
    }
    private static String brief(Throwable e){String m=e.getMessage();return e.getClass().getSimpleName()+(m==null||m.isEmpty()?"":" "+m.substring(0,Math.min(60,m.length())));}
    private void post(Runnable r){if(Looper.myLooper()==Looper.getMainLooper())r.run();else main.post(r);}
    private static long now(){return System.currentTimeMillis();}
    private String carLine() {
        return "브레이크 "+(brake?"밟음":"뗌")+" · 핸들 "+Math.round(steer)+"° · 와이퍼 "+(wiper?"켜짐":"꺼짐")+" · 전조등 "+(lowBeam?"켜짐":"꺼짐")
            +" · 벨트 "+(belt==null?"모름":belt?"착용":"해제")+" · 주행 "+(tripKm<0?"모름":String.format(Locale.ROOT,"%.1f km",tripKm));
    }
    private String roadLine(){return "제한속도 "+(limitKph<0?"모름":limitKph+" km/h")+(trip.isEmpty()?"":" · "+trip)+(arrived?" · 도착함":"");}

    // Vehicle SDK
    public void onTurnSignalStateUpdated(TurnSignalValue value) {
        android.util.Log.d(TAG,"turn signal "+value);
        Signal s=value==TurnSignalValue.LEFT?Signal.LEFT:value==TurnSignalValue.RIGHT?Signal.RIGHT:Signal.OFF;
        post(()->{host.onVehicle(s,gear);host.onOutcome(session.onSignal(s,now()),"signal");});
    }
    public void onSelected(VehicleGear value) {
        android.util.Log.d(TAG,"gear "+value);
        boolean parked=value==VehicleGear.GEAR_PARK;
        post(()->{gear=value.name().replace("GEAR_","");if(!parked)arrived=false;host.onVehicle(session.signal(),gear);host.onOutcome(session.onGear(parked,now()),"gear");host.onRoad(roadLine());});
    }
    /** The front passenger seat decides "passenger" for a taxi; other drivers set it themselves. */
    public void onSeatOccupancyUpdated(boolean occupied) {
        android.util.Log.d(TAG,"front passenger seat "+occupied);
        post(()->{if(session.profile().driverType==DriverType.TAXI){session.setPassenger(occupied);host.onOutcome(session.onGear(session.parked(),now()),"seat");}});
    }
    public void onBrake(boolean pressed){post(()->{brake=pressed;host.onCar(carLine());host.onOutcome(session.onBrake(pressed,now()),"brake");});}
    public void onSteering(float degrees){post(()->{steer=degrees;host.onCar(carLine());host.onOutcome(session.onSteering(degrees,now()),"steering");});}
    public void onWiper(boolean on){post(()->{wiper=on;host.onCar(carLine());host.onOutcome(session.onWiper(on,now()),"wiper");});}
    public void onLowBeam(boolean on){post(()->{lowBeam=on;host.onCar(carLine());host.onOutcome(session.onLowBeam(on,now()),"low-beam");});}
    public void onSeatBelt(boolean buckled){post(()->{belt=buckled;host.onCar(carLine());host.onOutcome(session.onSeatBelt(buckled,now()),"seat-belt");});}
    public void onTripDistance(double km){post(()->{tripKm=km;host.onCar(carLine());host.onOutcome(session.onTripDistance(km),"trip-distance");});}
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
        post(()->{arrived=false;if(name!=null&&!name.trim().isEmpty()){host.onDestination(name.trim());host.onOutcome(session.onDestination(name,now()),"route");}host.onRoad(roadLine());});
        if(navi!=null)try{navi.getDestinationInfo();}catch(Throwable ignored){}
    }
    @Override public void onDestinationInfo(DestinationInfo info) {
        String name=info==null?null:info.getName();
        post(()->{if(name!=null&&!name.trim().isEmpty()){host.onDestination(name.trim());host.onOutcome(session.onDestination(name,now()),"destination");}});
    }
    @Override public void onDestinationChanged(){if(navi!=null)try{navi.getDestinationInfo();}catch(Throwable ignored){}}
    @Override public void onRouteCancelled(){post(()->host.onOutcome(session.onNavigationStatus(false,now()),"cancel"));}
    /** Arrived: the route is over, and the question about a miss may come at this stop, before the gear goes to P. */
    @Override public void onDestinationArrived(DestinationArrivedInfo info) {
        post(()->{arrived=true;host.onOutcome(session.onNavigationStatus(false,now()),"arrive");host.onOutcome(session.onArrival(now()),"arrival");host.onRoad(roadLine());});
    }
    /** The road's limit: on a fast road the sentence is cut to its core. */
    @Override public void onCurrentRoadSpeedLimit(int kph){post(()->{limitKph=kph>0?kph:-1;host.onRoad(roadLine());host.onOutcome(session.onSpeedLimit(limitKph,now()),"speed-limit");});}
    /** The navigation's own position stands in when FusedLocation is not available. */
    @Override public void onCurrentLocationInfo(CurrentLocationInfo info) {
        if(info==null||location.live())return;
        post(()->onLocation(info.getLatitude(),info.getLongitude(),now()));
    }
    /** Remaining distance and time, as the navigation gives them, for the screen. */
    @Override public void onTripInfo(TripInfo info) {
        if(info==null)return;
        post(()->{trip="남은 거리 "+info.getDistance()+" · 남은 시간 "+info.getEta();host.onRoad(roadLine());});
    }
    @Override public void onError(NaviErrorCode code){post(()->{naviState="오류: "+code.name();host.onLink(vehicleState,naviState);});}
}
