package kr.routememory.pleos;

import ai.pleos.playground.location.fused.FusedLocation;
import ai.pleos.playground.location.fused.listener.FusedLocationListener;
import ai.pleos.playground.location.fused.model.LocationData;
import android.content.Context;

/**
 * The car's position from the Pleos FusedLocation SDK, about once a second. The rules count road cells with it
 * (familiarity) and, when the Car API gives no speed, the bridge derives the speed from consecutive positions.
 */
final class PleosLocationSource {
    interface Listener { void onLocation(double lat,double lon,long atMillis); }
    private FusedLocation fused; private FusedLocationListener callback;
    private String state="연결 전";

    String state(){return state;}
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
    void stop() {
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
