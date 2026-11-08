package kr.routememory.pleos;

import android.car.Car;
import android.car.VehiclePropertyIds;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.content.Context;

/**
 * The car's speed from the Android Car API (PERF_VEHICLE_SPEED, m/s). Pleos Vehicle SDK 2.3.2 has no speed property and
 * NaviHelper gives none either, so this is the only direct source. Reading it needs android.car.permission.CAR_SPEED,
 * which the Pleos Connect Emulator v2.1.0 image does not define: there the read fails and the bridge falls back to the
 * speed it derives from positions. Everything android.car lives in this class so a device without the library (a phone,
 * the unit-test JVM) never loads it.
 */
final class CarSpeedSource {
    interface Listener { void onSpeed(int kph); }
    private Car car; private CarPropertyManager properties; private CarPropertyManager.CarPropertyEventCallback callback;
    private String state="연결 전";

    String state(){return state;}

    /** Returns true when the speed is being delivered; the state says why not otherwise. */
    boolean start(Context context,Listener listener) {
        try {
            car=Car.createCar(context.getApplicationContext());
            if(car==null){state="Car API 없음";return false;}
            properties=(CarPropertyManager)car.getCarManager(Car.PROPERTY_SERVICE);
            if(properties==null){state="Car API 없음";return false;}
            callback=new CarPropertyManager.CarPropertyEventCallback() {
                @Override public void onChangeEvent(CarPropertyValue value) {
                    if(value.getPropertyId()!=VehiclePropertyIds.PERF_VEHICLE_SPEED||!(value.getValue() instanceof Float))return;
                    listener.onSpeed(Math.max(0,Math.round(Math.abs((Float)value.getValue())*3.6f)));
                }
                @Override public void onErrorEvent(int propertyId,int zone){}
            };
            // A first read proves the permission; without it the register call would fail just the same.
            Float first=(Float)properties.getProperty(Float.class,VehiclePropertyIds.PERF_VEHICLE_SPEED,0).getValue();
            if(first!=null)listener.onSpeed(Math.max(0,Math.round(Math.abs(first)*3.6f)));
            properties.registerCallback(callback,VehiclePropertyIds.PERF_VEHICLE_SPEED,CarPropertyManager.SENSOR_RATE_NORMAL);
            state="Car API";
            return true;
        } catch(SecurityException e){state="속도 권한 없음";stop();return false;}
        catch(RuntimeException|LinkageError e){state="속도 없음: "+e.getClass().getSimpleName();stop();return false;}
    }
    void stop() {
        try{if(properties!=null&&callback!=null)properties.unregisterCallback(callback);}catch(RuntimeException|LinkageError ignored){}
        try{if(car!=null)car.disconnect();}catch(RuntimeException|LinkageError ignored){}
        car=null;properties=null;callback=null;
    }
}
