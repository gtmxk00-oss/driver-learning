package kr.routememory.pleos;

import ai.pleos.playground.navi.constants.CautionPointType;
import ai.pleos.playground.navi.constants.NaviErrorCode;
import ai.pleos.playground.navi.constants.NavigationStatus;
import ai.pleos.playground.navi.data.CautionPoint;
import ai.pleos.playground.navi.data.DestinationArrivedInfo;
import ai.pleos.playground.navi.data.TBTInfo;
import ai.pleos.playground.navi.helper.NaviHelper;
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener;
import ai.pleos.playground.vehicle.Vehicle;
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue;
import ai.pleos.playground.vehicle.constant.control.VehicleGear;
import ai.pleos.playground.vehicle.listener.GearSelectionListener;
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.List;
import kr.routememory.core.DriveCoach.Signal;
import kr.routememory.core.DriveCoach.Turn;
import kr.routememory.core.DriveCoach.TurnType;
import kr.routememory.core.GuidanceSession;
import kr.routememory.core.GuidanceSession.Outcome;

/**
 * Connects the Pleos Connect SDKs to the companion's rules. Vehicle SDK gives the turn signal and the gear;
 * NaviHelper gives the next turn, reroutes, caution points and the navigation state. Everything the rules decide
 * comes back to the host on the main thread. Speed is not read here: the rules only need distance, signal and gear.
 * The Vehicle SDK routes an event by the listener's type, not by the property: one object registered for both the
 * turn signal and the gear receives the turn signal as a gear (seen on the official emulator: LEFT arrived as
 * GEAR_REVERSE). So each property gets its own listener object.
 */
public final class PleosBridge implements NaviHelperEventListener {
    private final TurnSignalStateListener signalListener=new TurnSignalStateListener() {
        @Override public void onTurnSignalStateUpdated(TurnSignalValue value){PleosBridge.this.onTurnSignalStateUpdated(value);}
        @Override public void onFailed(Exception e){PleosBridge.this.onFailed(e);}
    };
    private final GearSelectionListener gearListener=new GearSelectionListener() {
        @Override public void onSelected(VehicleGear value){PleosBridge.this.onSelected(value);}
        @Override public void onFailed(Exception e){PleosBridge.this.onFailed(e);}
    };
    public interface Host {
        void onLink(String vehicleState, String naviState);
        void onVehicle(Signal signal, String gear);
        void onNavigation(String turnLabel, int meters, String status);
        void onOutcome(Outcome outcome, String source);
    }
    private final Context context; private final GuidanceSession session; private final Host host;
    private final Handler main=new Handler(Looper.getMainLooper());
    private Vehicle vehicle; private NaviHelper navi;
    private String vehicleState="연결 전",naviState="연결 전",gear="-",naviStatus="-";

    public PleosBridge(Context context,GuidanceSession session,Host host){this.context=context.getApplicationContext();this.session=session;this.host=host;}
    public GuidanceSession session(){return session;}
    public boolean connected(){return vehicle!=null||navi!=null;}

    /** Each SDK is tried on its own, so a missing service explains itself instead of taking the other down. */
    public void connect() {
        if(vehicle==null) {
            try {
                Vehicle v=new Vehicle(context);v.initialize();
                v.getTurnSignal().registerTurnSignalState(signalListener);
                v.getGear().registerGearSelection(gearListener);
                vehicle=v;vehicleState="연결됨";
            } catch(Throwable e){vehicle=null;vehicleState="연결 실패: "+brief(e);}
        }
        if(navi==null) {
            try {
                NaviHelper n=new NaviHelper(context);n.initialize();n.addListener(this);
                navi=n;naviState="연결됨";
                n.getRouteStateInfo();n.getTBTInfo();
            } catch(Throwable e){navi=null;naviState="연결 실패: "+brief(e);}
        }
        host.onLink(vehicleState,naviState);
    }
    public void release() {
        try{if(vehicle!=null){vehicle.getTurnSignal().unregisterTurnSignalState(signalListener);vehicle.getGear().unregisterGearSelection(gearListener);vehicle.release();}}catch(Throwable ignored){}
        try{if(navi!=null){navi.removeListener(this);navi.release();}}catch(Throwable ignored){}
        vehicle=null;navi=null;vehicleState="연결 해제";naviState="연결 해제";
        host.onLink(vehicleState,naviState);
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
    private static final String TAG="PleosBridge";
    public void onFailed(Exception e){post(()->{vehicleState="오류: "+brief(e);host.onLink(vehicleState,naviState);});}

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
    /** The place id must survive across trips, so it is built from the turn type and the navigation's own description. */
    static String placeId(TurnType type,String description){return type.name().toLowerCase()+"|"+(description==null?"":description.trim());}
    @Override public void onTBTInfo(List<TBTInfo> list) {
        TBTInfo first=list==null||list.isEmpty()?null:list.get(0);
        TurnType type=first==null?null:map(first.getType());
        post(()->{
            if(type==null){host.onNavigation(first==null?"다음 회전 없음":"판단 대상 아님: "+first.getType().name(),-1,naviStatus);host.onOutcome(session.onTurn(null,-1,now()),"tbt");return;}
            int meters=(int)Math.round(first.getDistance());
            host.onNavigation(type.label,meters,naviStatus);
            host.onOutcome(session.onTurn(new Turn(placeId(type,first.getDescription()),type),meters,now()),"tbt");
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
    @Override public void onRouteCancelled(){post(()->host.onOutcome(session.onNavigationStatus(false,now()),"cancel"));}
    @Override public void onDestinationArrived(DestinationArrivedInfo info){post(()->host.onOutcome(session.onNavigationStatus(false,now()),"arrive"));}
    @Override public void onError(NaviErrorCode code){post(()->{naviState="오류: "+code.name();host.onLink(vehicleState,naviState);});}
}
