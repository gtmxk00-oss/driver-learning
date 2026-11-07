package kr.routememory.core;

import java.util.ArrayList;
import java.util.Collections;
import kr.routememory.core.DriveCoach.Action;
import kr.routememory.core.DriveCoach.DriverType;
import kr.routememory.core.DriveCoach.Profile;
import kr.routememory.core.LiveCoach.Fix;
import kr.routememory.core.LiveCoach.Observation;
import kr.routememory.core.LiveCoach.Remembered;
import kr.routememory.core.LiveCoach.Session;

/** Same expectations as RouteMemory-PC test/coach-live.test.cjs. */
public final class LiveCoachTest {
    static int checks;
    static void check(boolean condition,String why){checks++;if(!condition)throw new AssertionError(why);}
    // One degree of latitude is about 111.2 km; moving north by d degrees is a clean distance.
    static double north(double meters){return 37.5+meters/111195;}
    static Fix fix(long at,double meters){return new Fix(north(meters),127,8,Double.NaN,at);}
    static Fix fix(long at,double meters,double speed){return new Fix(north(meters),127,8,speed,at);}
    static Fix fix(long at,double meters,double speed,double accuracy){return new Fix(north(meters),127,accuracy,speed,at);}
    static Session session(DriverType type){return LiveCoach.createSession(new Profile("live","실제 위치",type),Collections.emptyList());}

    public static void main(String[] args) {
        check(Math.abs(LiveCoach.distanceMeters(0,0,1,0)-111195)<2,"distance follows the great circle");
        check(LiveCoach.distanceMeters(37.5,127,37.5,127)==0,"zero distance");

        Session s=session(DriverType.GENERAL);
        check(!LiveCoach.observe(s,null).accepted,"null fix dropped");
        check(!LiveCoach.observe(s,new Fix(91,0,5,Double.NaN,1)).accepted,"latitude out of range dropped");
        check(!LiveCoach.observe(s,new Fix(0,0,-1,Double.NaN,1)).accepted,"negative accuracy dropped");
        check(LiveCoach.observe(s,fix(5000,0)).accepted,"valid fix accepted");
        check(LiveCoach.observe(s,fix(5000,10)).reason.equals("stale"),"out-of-order fix dropped");
        check(s.last().timestamp==5000,"stale fix did not replace the last one");

        s=session(DriverType.GENERAL);
        LiveCoach.observe(s,fix(0,0));
        Observation seen=LiveCoach.observe(s,fix(10000,111.195));
        check(Math.abs(seen.speed-11.1195)<0.01,"speed derived from two fixes");
        check(seen.status.moving,"derived speed counts as moving");
        check(LiveCoach.observe(s,fix(20000,200,0.5)).speed==0.5,"reported speed wins");

        s=session(DriverType.GENERAL);
        LiveCoach.observe(s,fix(0,0,0));
        check(!LiveCoach.status(s,5000).parked,"not parked after five seconds");
        check(LiveCoach.status(s,8000).parked,"parked after eight seconds");
        check(!LiveCoach.status(s,30000).fresh&&!LiveCoach.status(s,30000).parked,"a stale fix is neither fresh nor parked");
        LiveCoach.observe(s,fix(31000,50,5));
        check(LiveCoach.status(s,31000).moving&&!LiveCoach.status(s,40000).parked,"moving clears the still timer");

        s=session(DriverType.GENERAL);
        check(LiveCoach.remember(s,0).reason.equals("no-fix"),"remember needs a fix");
        LiveCoach.observe(s,fix(1000,0,Double.NaN,150));
        check(LiveCoach.remember(s,1000).reason.equals("poor-accuracy"),"remember needs a trusted fix");
        LiveCoach.observe(s,fix(2000,0));
        check(LiveCoach.remember(s,2000+LiveCoach.FRESH_MS+1).reason.equals("stale"),"remember needs a fresh fix");
        Remembered first=LiveCoach.remember(s,2000);
        check(first.ok&&!first.merged&&first.point.label.equals("지점 1"),"first point saved");
        LiveCoach.observe(s,fix(3000,20));
        check(LiveCoach.remember(s,3000).merged,"a point within 30 m is reused");
        LiveCoach.observe(s,fix(4000,100));
        check(LiveCoach.remember(s,4000).point.label.equals("지점 2")&&s.points().size()==2,"second point saved");
        check(DriveCoach.remembers(s.profile(),s.points().get(0).id),"profile remembers the point");

        s=session(DriverType.GENERAL);
        LiveCoach.observe(s,fix(0,0));LiveCoach.remember(s,0);
        check(LiveCoach.observe(s,fix(1000,1000,10)).decision.reason.equals("too-far"),"far away waits");
        check(LiveCoach.observe(s,fix(2000,250,10,200)).decision.reason.equals("poor-accuracy"),"poor accuracy waits");
        check(LiveCoach.observe(s,fix(3000,250,0)).decision.reason.equals("not-moving"),"standing still waits");
        Observation said=LiveCoach.observe(s,fix(4000,250,10));
        check(said.decision.action==Action.SPEAK&&said.decision.speech.equals("헷갈렸던 지점입니다. 미리 확인하세요."),"approach speaks");
        check(LiveCoach.observe(s,fix(5000,100,10)).decision.reason.equals("already-said"),"said once per approach");
        LiveCoach.observe(s,fix(6000,400,10));
        check(LiveCoach.observe(s,fix(7000,200,10)).decision.reason.equals("already-said"),"still quiet inside the release ring");
        LiveCoach.observe(s,fix(8000,500,10));
        check(LiveCoach.observe(s,fix(9000,200,10)).decision.action==Action.SPEAK,"speaks again after leaving");

        s=session(DriverType.TRUCK);
        check(LiveCoach.limitMeters(s)==600,"truck reminder distance");
        LiveCoach.observe(s,fix(0,0));LiveCoach.remember(s,0);
        check(LiveCoach.observe(s,fix(1000,550,10)).decision.action==Action.SPEAK,"truck speaks earlier");
        LiveCoach.setDriverType(s,DriverType.GENERAL);
        check(LiveCoach.limitMeters(s)==300&&DriveCoach.remembers(s.profile(),s.points().get(0).id),"type change keeps the points");

        s=session(DriverType.GENERAL);
        LiveCoach.observe(s,fix(0,0));
        Remembered saved=LiveCoach.remember(s,0);
        LiveCoach.forget(s);
        check(s.points().isEmpty()&&!DriveCoach.remembers(s.profile(),saved.point.id),"forget clears points and memory");
        check(LiveCoach.observe(s,fix(1000,10,10)).decision.reason.equals("no-points"),"nothing to say after forget");

        s=session(DriveCoach.DriverType.GENERAL);
        LiveCoach.observe(s,fix(0,0));LiveCoach.remember(s,0);
        Session copy=LiveCoach.createSession(new Profile("live","실제 위치",DriverType.GENERAL),new ArrayList<>(s.points()));
        check(copy.points().size()==1&&DriveCoach.remembers(copy.profile(),s.points().get(0).id),"saved points come back");

        System.out.println("LiveCoach checks passed: "+checks);
    }
}
