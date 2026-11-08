package kr.routememory.pleos;

import ai.pleos.playground.location.fused.FusedLocation;
import ai.pleos.playground.location.fused.listener.FusedLocationListener;
import ai.pleos.playground.location.fused.listener.ProximityAlertListener;
import ai.pleos.playground.location.fused.model.LocationData;
import ai.pleos.playground.location.fused.model.ProximityAlertEvent;
import ai.pleos.playground.location.fused.model.ProximityAlertRequest;
import ai.pleos.playground.location.fused.model.ProximityTransition;
import android.content.Context;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The car's position from the Pleos FusedLocation SDK, about once a second, and its proximity alerts: one per
 * remembered place that has a position, so the car itself says when a remembered spot comes within reach even while
 * no route is active. The rules count road cells with the position (familiarity) and, when the Car API gives no
 * speed, the bridge derives the speed from consecutive positions.
 */
final class PleosLocationSource {
    interface Listener { void onLocation(double lat,double lon,long atMillis); }
    interface Near { void onNear(String placeId); }
    /** The service is asked for at most this many alerts; the most recently remembered places come first. */
    static final int MAX_ALERTS=20;
    private FusedLocation fused; private FusedLocationListener callback;
    private final List<String> alertIds=new ArrayList<>();
    private String state="연결 전"; private int watching;

    String state(){return state;}
    boolean live(){return fused!=null;}
    /** How many remembered places the service is watching for us. */
    int watching(){return watching;}
    boolean start(Context context,Listener target) {
        try {
            FusedLocation f=new FusedLocation(context.getApplicationContext());
            if(!f.isSupported()){state="FusedLocation 없음";return false;}
            f.initialize();
            callback=new FusedLocationListener() {
                @Override public void onChanged(LocationData d){if(d!=null)target.onLocation(d.getLatitude(),d.getLongitude(),d.getTimestampMillis()>0?d.getTimestampMillis():System.currentTimeMillis());}
                @Override public void onError(Exception e){state="오류: "+e.getClass().getSimpleName();}
            };
            f.registerFusedLocationCallback(callback);
            f.getLastKnownLocation(callback);
            fused=f;state="연결됨";
            return true;
        } catch(RuntimeException|LinkageError e){state="연결 실패: "+e.getClass().getSimpleName();stop();return false;}
    }
    /** Watches these places (id to [lat, lon]); the service fires once the car enters the radius. Replaces the previous set. */
    void watch(Map<String,double[]> places,float radiusMeters,Near target) {
        clearWatch();
        if(fused==null||places==null)return;
        for(Map.Entry<String,double[]> e:places.entrySet()) {
            if(watching>=MAX_ALERTS)break;
            final String placeId=e.getKey(); final double[] at=e.getValue();
            try {
                ProximityAlertRequest request=new ProximityAlertRequest(at[0],at[1],radiusMeters,Collections.singleton(ProximityTransition.ENTER));
                fused.addProximityAlert(request,new ProximityAlertListener() {
                    @Override public void onRegistered(String alertId){synchronized(alertIds){alertIds.add(alertId);}}
                    @Override public void onTransition(ProximityAlertEvent event){if(event!=null&&event.getTransition()==ProximityTransition.ENTER)target.onNear(placeId);}
                    @Override public void onError(Exception error){state="근접 알림 오류: "+error.getClass().getSimpleName();}
                });
                watching++;
            } catch(RuntimeException|LinkageError ex){state="근접 알림 없음: "+ex.getClass().getSimpleName();break;}
        }
    }
    void clearWatch() {
        synchronized(alertIds){for(String id:alertIds)try{if(fused!=null)fused.removeProximityAlert(id);}catch(RuntimeException|LinkageError ignored){}alertIds.clear();}
        watching=0;
    }
    void stop() {
        clearWatch();
        try{if(fused!=null&&callback!=null)fused.unregisterFusedLocationCallback(callback);}catch(RuntimeException|LinkageError ignored){}
        try{if(fused!=null)fused.release();}catch(RuntimeException|LinkageError ignored){}
        fused=null;callback=null;
    }
    /** Ground distance in metres between two positions (haversine). */
    static double meters(double lat1,double lon1,double lat2,double lon2) {
        double r=6371000,dLat=Math.toRadians(lat2-lat1),dLon=Math.toRadians(lon2-lon1);
        double a=Math.sin(dLat/2)*Math.sin(dLat/2)+Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*Math.sin(dLon/2)*Math.sin(dLon/2);
        return 2*r*Math.atan2(Math.sqrt(a),Math.sqrt(1-a));
    }
}
