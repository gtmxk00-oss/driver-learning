package kr.routememory.pleos;

import android.car.Car;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.content.Context;

/**
 * Tells the screen when the car wants it simplified. While driving, Android Automotive asks distraction-optimized apps
 * to keep to the essentials (CarUxRestrictions); the car-link screen then shows only its status line and its question.
 * Everything android.car lives here so a device without the library never loads it.
 */
final class UxRestrictionsWatch {
    interface Listener { void onRestricted(boolean restricted); }
    private Car car; private CarUxRestrictionsManager manager; private CarUxRestrictionsManager.OnUxRestrictionsChangedListener listener;
    private String state="없음";

    String state(){return state;}
    boolean start(Context context,Listener target) {
        try {
            car=Car.createCar(context.getApplicationContext());
            if(car==null){state="Car API 없음";return false;}
            manager=(CarUxRestrictionsManager)car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE);
            if(manager==null){state="Car API 없음";return false;}
            listener=restrictions->target.onRestricted(restrictions!=null&&restrictions.isRequiresDistractionOptimization());
            manager.registerListener(listener);
            CarUxRestrictions now=manager.getCurrentCarUxRestrictions();
            target.onRestricted(now!=null&&now.isRequiresDistractionOptimization());
            state="Car API";
            return true;
        } catch(RuntimeException|LinkageError e){state="없음: "+e.getClass().getSimpleName();stop();return false;}
    }
    void stop() {
        try{if(manager!=null)manager.unregisterListener();}catch(RuntimeException|LinkageError ignored){}
        try{if(car!=null)car.disconnect();}catch(RuntimeException|LinkageError ignored){}
        car=null;manager=null;listener=null;
    }
}
