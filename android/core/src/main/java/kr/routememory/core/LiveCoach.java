package kr.routememory.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import kr.routememory.core.DriveCoach.Action;
import kr.routememory.core.DriveCoach.DriverType;
import kr.routememory.core.DriveCoach.Profile;

/**
 * Live mode on real position only. Same rules as RouteMemory-PC src/coach-live.js; change both together.
 * There is no turn signal and no navigation route yet, so it remembers points the driver marks and reminds them on the next approach.
 */
public final class LiveCoach {
    public static final double TRUSTED_ACCURACY=100,MERGE_METERS=30,MOVING_SPEED=2;
    public static final long FRESH_MS=15000,PARKED_AFTER_MS=8000;
    private static final double EARTH_RADIUS=6371008.8,PARKED_SPEED=0.8,RELEASE_FACTOR=1.5;
    private LiveCoach() {}

    /** speed is NaN when the receiver did not report one. */
    public static final class Fix {
        public final double lat,lon,accuracy,speed; public final long timestamp;
        public Fix(double lat,double lon,double accuracy,double speed,long timestamp){this.lat=lat;this.lon=lon;this.accuracy=accuracy;this.speed=speed;this.timestamp=timestamp;}
    }
    public static final class Point {
        public final String id,label; public final double lat,lon; public final long at;
        public Point(String id,double lat,double lon,String label,long at){this.id=id;this.lat=lat;this.lon=lon;this.label=label;this.at=at;}
    }
    public static final class Nearest { public final Point point; public final double meters; Nearest(Point point,double meters){this.point=point;this.meters=meters;} }
    public static final class Decision {
        public final Action action; public final String reason,speech; public final int limit; public final Point point;
        Decision(Action action,String reason,String speech,int limit,Point point){this.action=action;this.reason=reason;this.speech=speech;this.limit=limit;this.point=point;}
    }
    public static final class Status {
        public final long ageMs; public final boolean fresh,moving,parked,trusted;
        Status(long ageMs,boolean fresh,boolean moving,boolean parked,boolean trusted){this.ageMs=ageMs;this.fresh=fresh;this.moving=moving;this.parked=parked;this.trusted=trusted;}
    }
    public static final class Observation {
        public final boolean accepted; public final String reason; public final Fix fix; public final double speed; public final Nearest nearest; public final Decision decision; public final Status status;
        Observation(String reason){accepted=false;this.reason=reason;fix=null;speed=Double.NaN;nearest=null;decision=null;status=null;}
        Observation(Fix fix,double speed,Nearest nearest,Decision decision,Status status){accepted=true;reason="";this.fix=fix;this.speed=speed;this.nearest=nearest;this.decision=decision;this.status=status;}
    }
    public static final class Remembered {
        public final boolean ok,merged; public final String reason; public final Point point;
        Remembered(boolean ok,boolean merged,String reason,Point point){this.ok=ok;this.merged=merged;this.reason=reason;this.point=point;}
    }
    public static final class Spoken { public final String text; public final long at; public final Point point; Spoken(String text,long at,Point point){this.text=text;this.at=at;this.point=point;} }
    public static final class Session {
        Profile profile; final List<Point> points=new ArrayList<>(); Fix last; double speed=Double.NaN; long stillSince=-1; final Map<String,Long> announced=new HashMap<>(); Spoken spoken;
        public Session(Profile profile){this.profile=profile;}
        public Profile profile(){return profile;}
        public List<Point> points(){return Collections.unmodifiableList(points);}
        public Fix last(){return last;}
        public double speed(){return speed;}
        public Spoken spoken(){return spoken;}
        public boolean announced(String id){return announced.containsKey(id);}
    }

    public static double distanceMeters(double lat1,double lon1,double lat2,double lon2) {
        double dLat=Math.toRadians(lat2-lat1),dLon=Math.toRadians(lon2-lon1);
        double s=Math.pow(Math.sin(dLat/2),2)+Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*Math.pow(Math.sin(dLon/2),2);
        return 2*EARTH_RADIUS*Math.asin(Math.min(1,Math.sqrt(s)));
    }
    /** Takes what a receiver reports; anything malformed is dropped rather than guessed. */
    public static boolean validFix(Fix f) {
        return f!=null&&!Double.isNaN(f.lat)&&!Double.isNaN(f.lon)&&Math.abs(f.lat)<=90&&Math.abs(f.lon)<=180&&!Double.isNaN(f.accuracy)&&f.accuracy>=0;
    }
    private static boolean trusted(Fix f){return f.accuracy<=TRUSTED_ACCURACY;}

    public static Session createSession(Profile profile,List<Point> points) {
        Session s=new Session(profile);
        for(Point p:points)addPoint(s,p);
        return s;
    }
    public static Point addPoint(Session s,Point p) {
        if(Double.isNaN(p.lat)||Double.isNaN(p.lon))return null;
        s.points.add(p);DriveCoach.addMemo(s.profile,p.id);return p;
    }
    public static int limitMeters(Session s){return s.profile.driverType.baseMeters;}
    /** A profile's type is fixed, so the session takes a new profile and registers its points again. */
    public static void setDriverType(Session s,DriverType type) {
        s.profile=new Profile(s.profile.id,s.profile.name,type);
        for(Point p:s.points)DriveCoach.addMemo(s.profile,p.id);
    }
    public static Nearest nearest(Session s,Fix fix) {
        Nearest best=null;
        for(Point p:s.points){double meters=distanceMeters(fix.lat,fix.lon,p.lat,p.lon);if(best==null||meters<best.meters)best=new Nearest(p,meters);}
        return best;
    }
    /** Speed comes from the receiver when it reports one, otherwise from the last two trusted fixes. */
    private static double speedOf(Session s,Fix fix) {
        if(!Double.isNaN(fix.speed)&&fix.speed>=0)return fix.speed;
        Fix last=s.last;
        if(last==null||!trusted(last)||!trusted(fix))return Double.NaN;
        double seconds=(fix.timestamp-last.timestamp)/1000.0;
        return seconds>=1?distanceMeters(last.lat,last.lon,fix.lat,fix.lon)/seconds:Double.NaN;
    }
    public static Status status(Session s,long now) {
        Fix last=s.last;
        if(last==null)return new Status(-1,false,false,false,false);
        long ageMs=now-last.timestamp;boolean fresh=ageMs>=-2000&&ageMs<=FRESH_MS;
        boolean moving=fresh&&!Double.isNaN(s.speed)&&s.speed>=MOVING_SPEED;
        boolean parked=fresh&&s.stillSince>=0&&now-s.stillSince>=PARKED_AFTER_MS;
        return new Status(ageMs,fresh,moving,parked,trusted(last));
    }
    public static Observation observe(Session s,Fix fix) {
        if(!validFix(fix))return new Observation("invalid");
        if(s.last!=null&&fix.timestamp<=s.last.timestamp)return new Observation("stale");
        double speed=speedOf(s,fix);long now=fix.timestamp;
        boolean known=!Double.isNaN(speed);
        if(known&&speed>=MOVING_SPEED)s.stillSince=-1;
        else if(known&&speed<PARKED_SPEED&&s.stillSince<0)s.stillSince=now;
        s.last=new Fix(fix.lat,fix.lon,fix.accuracy,Double.NaN,now);s.speed=speed;
        int limit=limitMeters(s);Nearest near=nearest(s,s.last);boolean moving=known&&speed>=MOVING_SPEED;
        // A point said once stays quiet until the car has clearly left it.
        for(Iterator<String> it=s.announced.keySet().iterator();it.hasNext();) {
            String id=it.next();Point point=null;
            for(Point p:s.points)if(p.id.equals(id))point=p;
            if(point==null||distanceMeters(s.last.lat,s.last.lon,point.lat,point.lon)>limit*RELEASE_FACTOR)it.remove();
        }
        Decision decision;
        if(!trusted(s.last))decision=new Decision(Action.WAIT,"poor-accuracy","",limit,null);
        else if(near==null)decision=new Decision(Action.NONE,"no-points","",limit,null);
        else if(near.meters>limit)decision=new Decision(Action.WAIT,"too-far","",limit,null);
        else if(s.announced.containsKey(near.point.id))decision=new Decision(Action.WAIT,"already-said","",limit,null);
        else if(!moving)decision=new Decision(Action.WAIT,"not-moving","",limit,null);
        else {
            decision=new Decision(Action.SPEAK,"memory-point",DriveCoach.decidePoint(s.profile,near.point.id).speech,limit,near.point);
            s.announced.put(near.point.id,now);
            s.spoken=new Spoken(decision.speech,now,near.point);
        }
        return new Observation(s.last,speed,near,decision,status(s,now));
    }
    /** Marks where the car is now. A fresh, trusted fix is required; a point within 30 m is reused. */
    public static Remembered remember(Session s,long now) {
        Fix last=s.last;
        if(last==null)return new Remembered(false,false,"no-fix",null);
        if(!trusted(last))return new Remembered(false,false,"poor-accuracy",null);
        if(now-last.timestamp>FRESH_MS)return new Remembered(false,false,"stale",null);
        Nearest near=nearest(s,last);
        if(near!=null&&near.meters<=MERGE_METERS)return new Remembered(true,true,"",near.point);
        Point point=addPoint(s,new Point("live-"+now,last.lat,last.lon,"지점 "+(s.points.size()+1),now));
        return new Remembered(true,false,"",point);
    }
    public static void forget(Session s) {
        for(Point p:s.points)s.profile.places.remove(p.id);
        s.points.clear();s.announced.clear();s.spoken=null;
    }
}
