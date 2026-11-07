package kr.routememory.shared;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.location.*;
import android.os.*;
import kr.routememory.core.RoadTracker;

/** Explicit, non-sticky location foreground service. No automatic startup or background permission. */
public final class LocationNavigationService extends Service implements LocationListener {
    private static final int ID=42;
    private LocationManager locations;
    private RoadController state;
    private int generation;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable watchdog=new Runnable(){public void run(){if(state!=null)state.checkFreshness();main.postDelayed(this,3000);}};
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onCreate(){
        super.onCreate();state=RoadController.get(this);locations=(LocationManager)getSystemService(LOCATION_SERVICE);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&"kr.routememory.STOP_GPS".equals(intent.getAction())){state.stopAll();stopSelf();return START_NOT_STICKY;}
        if(!state.gpsEnabled||!state.hasPermission()){stopSelf();return START_NOT_STICKY;}
        generation=intent==null?-1:intent.getIntExtra("gpsGeneration",-1);
        if(generation!=state.gpsGeneration){stopSelf();return START_NOT_STICKY;}
        NotificationManager notifications=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        notifications.createNotificationChannel(new NotificationChannel("road_navigation","GPS 길 안내",NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop=PendingIntent.getService(this,42,new Intent(this,LocationNavigationService.class).setAction("kr.routememory.STOP_GPS"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=new Notification.Builder(this,"road_navigation").setSmallIcon(R.drawable.ic_route)
            .setContentTitle("길기억 · GPS 사용 중").setContentText("현재 위치 수신 중 · 누적 위치 기록 저장 안 함")
            .setOngoing(true).setCategory(Notification.CATEGORY_NAVIGATION)
            .addAction(new Notification.Action.Builder(null,"GPS 종료",stop).build());
        Intent launch=getPackageManager().getLaunchIntentForPackage(getPackageName());
        if(launch!=null)builder.setContentIntent(PendingIntent.getActivity(this,0,launch,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
        try{
            startForeground(ID,builder.build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            locations.removeUpdates(this);
            boolean any=false;
            if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED&&locations.getAllProviders().contains(LocationManager.GPS_PROVIDER)&&locations.isProviderEnabled(LocationManager.GPS_PROVIDER)){
                locations.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,this,Looper.getMainLooper());any=true;
            }
            if(locations.getAllProviders().contains(LocationManager.NETWORK_PROVIDER)&&locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER)){
                locations.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,1500,0,this,Looper.getMainLooper());any=true;
            }
            if(!any){state.serviceEnded(generation,"기기 위치 서비스를 켠 뒤 GPS를 다시 시작하세요");stopSelf();}
            else{main.removeCallbacks(watchdog);main.postDelayed(watchdog,3000);}
        }catch(RuntimeException e){state.serviceEnded(generation,"위치를 수신할 수 없습니다. 위치 권한과 기기 위치 설정을 확인하세요");stopSelf();}
        return START_NOT_STICKY;
    }
    @Override public void onLocationChanged(Location location){
        if(generation==state.gpsGeneration&&location.hasAccuracy())state.onFix(new RoadTracker.Fix(location.getLatitude(),location.getLongitude(),location.getAccuracy(),location.getTime()));
    }
    @Override public void onProviderDisabled(String provider){if(state.gpsEnabled){state.notice="위치 서비스 상태가 바뀌었습니다. GPS 수신을 확인하세요";state.changed();}}
    @Override public void onProviderEnabled(String provider){}
    @Override public void onStatusChanged(String provider,int status,Bundle extras){}
    @Override public void onDestroy(){
        main.removeCallbacks(watchdog);try{if(locations!=null)locations.removeUpdates(this);}catch(SecurityException ignored){}
        stopForeground(STOP_FOREGROUND_REMOVE);if(state!=null)state.serviceEnded(generation,"GPS 수신을 종료했습니다");super.onDestroy();
    }
    @Override public void onTaskRemoved(Intent rootIntent){state.stopAll();stopSelf();}
}
