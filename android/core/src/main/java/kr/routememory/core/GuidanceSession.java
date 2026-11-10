package kr.routememory.core;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import kr.routememory.core.DriveCoach.Action;
import kr.routememory.core.DriveCoach.Briefing;
import kr.routememory.core.DriveCoach.Context;
import kr.routememory.core.DriveCoach.Decision;
import kr.routememory.core.DriveCoach.Familiarity;
import kr.routememory.core.DriveCoach.Profile;
import kr.routememory.core.DriveCoach.Signal;
import kr.routememory.core.DriveCoach.Turn;
import kr.routememory.core.DriveCoach.TurnType;

/**
 * Turns real navigation and vehicle events into the companion's decisions. Same rules as RouteMemory-PC
 * src/coach-guidance.js; change both together. The SDK bridge maps vendor types onto these calls, so this class
 * stays testable without a car: next turn with remaining metres, turn signal, gear, reroute, caution points, speed,
 * position, destination, and the extra signals a Pleos car gives (brake, steering angle, wiper, low beam, seat belt,
 * trip distance, the road's speed limit, arrival, a remembered spot coming near). What the car cannot give simply
 * stays unknown.
 */
public final class GuidanceSession {
    /** A reroute this soon after a turn was passed with no matching signal counts as a missed turn. */
    public static final long MISS_WINDOW_MS=90000;
    /** A reroute while still this close to the current turn also counts. */
    public static final int MISS_NEAR_METERS=150;
    /** The wheel turned this far while this close to the turn: the driver took it, whatever the navigation says next. */
    public static final float STEER_TURN_DEG=45f; public static final int STEER_NEAR_METERS=300;
    /** Brake held at walking pace or less counts as stopped. */
    public static final int STOPPED_KPH=3;
    /** Road cells driven while this close to the next turn carry that turn's outcome; the nearest few are kept. */
    public static final int APPROACH_METERS=600,APPROACH_CELLS=8;
    public enum Event {NONE,SPEAK,SILENT,MISS_CANDIDATE,PASSED,QUESTION,LOWER_QUESTION,SUMMARY,BRIEFING,MEMO}
    public static final class Outcome {
        public final Event event; public final String speech; public final Decision decision;
        Outcome(Event event,String speech,Decision decision){this.event=event;this.speech=speech;this.decision=decision;}
    }
    private static final class Passed {
        final Turn turn; final long at; final int signalOnMeters; final boolean turned,hard; final String[] cells;
        Passed(Turn t,long at,int s,boolean turned,boolean hard,String[] cells){turn=t;this.at=at;signalOnMeters=s;this.turned=turned;this.hard=hard;this.cells=cells;}
    }

    private Profile profile;
    private Turn current; private int meters=-1; private boolean announced;
    private int signalOnMeters=0; // metres at which the matching signal was first seen for the current turn, 0 if never
    private boolean turnedNear; // the steering wheel turned near the current turn
    private Signal signal=Signal.OFF; private boolean passenger,parked=true,navigating;
    private Passed lastPassed; private boolean lastPassedJudged;
    private String question; private TurnType lowerAsked;
    private int speedKph=-1,nextGapMeters=-1; private Familiarity familiarity=Familiarity.UNKNOWN;
    private String destination,lastCell; private final Set<String> tripCells=new HashSet<>();
    private TurnType pendingLower; private final ArrayDeque<Outcome> queued=new ArrayDeque<>();
    // The extra car signals; each stays at "unknown" until the car reports it.
    private boolean rain,night,brake,arrived; private float steerDeg; private double tripKm=-1; private int roadLimitKph=-1;
    private double lastLat=Double.NaN,lastLon=Double.NaN; private final Map<String,double[]> pendingCoords=new HashMap<>();
    // The cells driven on the way to the current turn and whether one of them was a hard segment, the cells of a
    // suspected miss until the driver answers, and the counts the other profiles on this device left on the road.
    private final ArrayDeque<String> approach=new ArrayDeque<>(); private boolean hardSeen;
    private final Map<String,String[]> pendingCells=new HashMap<>(); private Map<String,int[]> sharedSegments=new HashMap<>();
    // What happened on this trip, for the one sentence when the car is parked again.
    private int tripPassed,tripMisses,tripSignalOff,tripSpoken;

    public GuidanceSession(Profile profile){this.profile=profile;}
    public Profile profile(){return profile;}
    public Turn currentTurn(){return current;}
    public int meters(){return meters;}
    public Signal signal(){return signal;}
    public boolean parked(){return parked;}
    public boolean navigating(){return navigating;}
    public int speedKph(){return speedKph;}
    public Familiarity familiarity(){return familiarity;}
    public String destination(){return destination;}
    public String pendingQuestion(){return question;}
    /** True while the pending question is the offer to lower a weak type rather than a question about a miss. */
    public boolean lowerAsked(){return lowerAsked!=null;}
    public int tripPassed(){return tripPassed;}
    public int tripMisses(){return tripMisses;}
    public int tripSignalOff(){return tripSignalOff;}
    public int tripSpoken(){return tripSpoken;}
    public void setPassenger(boolean value){passenger=value;}
    public boolean passenger(){return passenger;}
    public boolean rain(){return rain;}
    public boolean night(){return night;}
    public boolean brake(){return brake;}
    public boolean arrived(){return arrived;}
    public float steerDeg(){return steerDeg;}
    public double tripKm(){return tripKm;}
    public int roadLimitKph(){return roadLimitKph;}
    /** The segment counts of the other profiles on this device, added up; used where this driver has none of their own. */
    public void setSharedSegments(Map<String,int[]> shared){sharedSegments=shared==null?new HashMap<>():shared;}
    /** True when the road under the car is a hard segment right now. */
    public boolean hardSegment(){return DriveCoach.hardSegment(profile,sharedSegments,lastCell);}

    public void setProfile(Profile next){profile=next;current=null;meters=-1;announced=false;signalOnMeters=0;turnedNear=false;lastPassed=null;question=null;lowerAsked=null;pendingLower=null;queued.clear();pendingCoords.clear();pendingCells.clear();resetApproach();}

    /** The next turn from the navigation, with the distance still to go. */
    public Outcome onTurn(Turn turn,int remainingMeters,long now){return onTurn(turn,remainingMeters,-1,now);}
    /** As above, with the distance from this turn to the one after it (-1 when the navigation gives only one). */
    public Outcome onTurn(Turn turn,int remainingMeters,int gapToNextMeters,long now) {
        nextGapMeters=gapToNextMeters;
        if(turn==null)return clearTurn(now);
        boolean changed=current==null||!current.id.equals(turn.id)||remainingMeters>meters+MISS_NEAR_METERS;
        if(changed){
            Outcome passed=current==null?null:passTurn(now);
            current=turn;announced=false;signalOnMeters=0;turnedNear=false;resetApproach();
            if(passed!=null&&passed.event==Event.PASSED){meters=remainingMeters;trail();return passed;}
        }
        meters=remainingMeters;
        if(matches(signal,turn.type)&&signalOnMeters==0)signalOnMeters=Math.max(1,remainingMeters);
        trail();
        return judge(now);
    }
    public Outcome onCaution(String id,int remainingMeters,long now){return onTurn(new Turn(id,TurnType.NARROW_ROAD),remainingMeters,now);}
    public Outcome onSignal(Signal next,long now) {
        signal=next;
        if(current!=null&&matches(next,current.type)&&signalOnMeters==0)signalOnMeters=Math.max(1,meters);
        return current==null?none():judge(now);
    }
    /** The car's speed in km/h; a change can bring the turn inside the decision distance. */
    public Outcome onSpeed(int kph,long now){speedKph=kph<0?-1:kph;return current==null?none():judge(now);}
    /** The car's position: counts the road cell once per trip and tells how familiar this road is. */
    public Outcome onLocation(double lat,double lon,long now) {
        lastLat=lat;lastLon=lon;
        String cell=DriveCoach.cellKey(lat,lon);
        if(cell.equals(lastCell))return none();
        lastCell=cell;
        familiarity=DriveCoach.familiarity(profile,cell);
        trail();
        if(!parked&&tripCells.add(cell))DriveCoach.noteRoad(profile,cell);
        return current==null?none():judge(now);
    }
    /** Sets the familiarity directly, for a console or a test that has no positions to give. */
    public Outcome setFamiliarity(Familiarity value,long now){familiarity=value==null?Familiarity.UNKNOWN:value;return current==null?none():judge(now);}
    /** The destination the navigation is heading for: while parked, the briefing of what was learned on earlier trips there. */
    public Outcome onDestination(String name,long now) {
        destination=name==null||name.trim().isEmpty()?null:name.trim();
        if(destination==null)return none();
        Briefing b=DriveCoach.briefingFor(profile,destination,parked,passenger);
        return b.speech.isEmpty()?none():new Outcome(Event.BRIEFING,b.speech,null);
    }
    public Outcome onGear(boolean parkedNow,long now) {
        boolean was=parked;parked=parkedNow;
        if(was&&!parkedNow){tripStart();return none();}
        if(!was&&parkedNow)return tripEnd();
        return parked?reviewIfPossible():none();
    }
    public Outcome onNavigationStatus(boolean routeDrive,long now){navigating=routeDrive;return routeDrive?none():clearTurn(now);}
    /** The navigation computed a new route on its own: the driver may have left the planned one. */
    public Outcome onReroute(long now) {
        Turn suspect=null;String[] cells=null;
        if(lastPassed!=null&&!lastPassedJudged&&now-lastPassed.at<=MISS_WINDOW_MS&&lastPassed.signalOnMeters==0) {
            // The wheel turned at that exit: the driver took it, so the new route is not a miss.
            if(lastPassed.turned)return settle();
            suspect=lastPassed.turn;cells=lastPassed.cells;
        } else if(current!=null&&meters>=0&&meters<=MISS_NEAR_METERS&&signalOnMeters==0) {
            if(turnedNear)return none();
            suspect=current;cells=approach.toArray(new String[0]);
        }
        if(suspect==null)return none();
        DriveCoach.noteMiss(profile,suspect);tripMisses++;
        if(!Double.isNaN(lastLat))pendingCoords.put(suspect.id,new double[]{lastLat,lastLon});
        if(cells!=null&&cells.length>0)pendingCells.put(suspect.id,cells);
        // Blamed on the current turn, the turn before it was passed after all.
        if(suspect==current)settle();
        lastPassedJudged=true;current=null;meters=-1;announced=false;signalOnMeters=0;turnedNear=false;resetApproach();
        Outcome asked=reviewIfPossible();
        return asked.event==Event.QUESTION?asked:new Outcome(Event.MISS_CANDIDATE,"",null);
    }
    /** The driver answered the parked question about a miss. A confirmed miss keeps where it happened, for the proximity alert. */
    public DriveCoach.Review answer(boolean confirmed) {
        question=null;lowerAsked=null;
        DriveCoach.Review r=DriveCoach.review(profile,confirmed,destination);
        if(r!=null) {
            double[] at=pendingCoords.remove(r.id);String[] cells=pendingCells.remove(r.id);
            if(r.confirmed&&at!=null)DriveCoach.placeCoord(profile,r.id,at[0],at[1]);
            // A confirmed miss is written onto the road it was approached on; a deliberate change of route is not.
            if(r.confirmed&&cells!=null)for(String cell:cells)DriveCoach.noteSegment(profile,cell,false,r.type.side!=null);
        }
        return r;
    }
    /** The driver answered the offer to lower a weak type. */
    public void answerLower(boolean lower){if(lower&&lowerAsked!=null)DriveCoach.lowerType(profile,lowerAsked);question=null;lowerAsked=null;}
    /** What else was waiting behind the question just answered: the next question, or the trip summary. */
    public Outcome afterAnswer(){Outcome next=queued.poll();if(next!=null&&(next.event==Event.QUESTION||next.event==Event.LOWER_QUESTION))question=next.speech;return next==null?none():next;}
    /** The driver marks this spot as confusing, by voice or by a button; it joins the memory with its own words and its position. */
    public Outcome memo(String text,long now) {
        String words=text==null?"":text.trim();
        String placeId=current!=null?current.id:lastCell!=null?"memo|"+lastCell:null;
        if(placeId==null||words.isEmpty())return none();
        DriveCoach.addMemo(profile,placeId);DriveCoach.labelPlace(profile,placeId,words);
        DriveCoach.rememberFor(profile,destination,placeId);
        if(!Double.isNaN(lastLat))DriveCoach.placeCoord(profile,placeId,lastLat,lastLon);
        return new Outcome(Event.MEMO,"기억해 둘게요. "+words,null);
    }

    // The extra car signals.
    /** The front wiper: rain means the decision distance grows. */
    public Outcome onWiper(boolean on,long now){rain=on;return current==null?none():judge(now);}
    /** The low beam: night means the decision distance grows a little. */
    public Outcome onLowBeam(boolean on,long now){night=on;return current==null?none():judge(now);}
    /** The steering wheel angle in degrees; a real turn near the watched turn means it was taken. */
    public Outcome onSteering(float degrees,long now) {
        steerDeg=degrees;
        if(current!=null&&Math.abs(degrees)>=STEER_TURN_DEG&&meters>=0&&meters<=STEER_NEAR_METERS)turnedNear=true;
        return none();
    }
    /** The brake pedal; held after arriving, the car is stopped enough to be asked. */
    public Outcome onBrake(boolean pressed,long now){brake=pressed;return arrived&&stopped()?reviewIfPossible():none();}
    /** The navigation says the destination is reached: the question may come at this stop, before the gear goes to P. */
    public Outcome onArrival(long now){arrived=true;return canAsk()?reviewIfPossible():none();}
    /** The driver's seat belt coming off at a standstill ends the trip like parking does. */
    public Outcome onSeatBelt(boolean buckled,long now){return !buckled&&!parked&&speedKph>=0&&speedKph<=STOPPED_KPH?onGear(true,now):none();}
    /** The trip distance from the odometer, in km, for the summary. */
    public Outcome onTripDistance(double km){tripKm=km<0?-1:km;return none();}
    /** The road's speed limit from the navigation: on a fast road the sentence is cut to its core. */
    public Outcome onSpeedLimit(int kph,long now){roadLimitKph=kph<=0?-1:kph;return current==null?none():judge(now);}
    /** A remembered spot came within reach (a proximity alert) while no route is active: say it, as for a bus without a route. */
    public Outcome onProximity(String placeId,long now) {
        if(navigating||parked||passenger)return none();
        Decision d=DriveCoach.decidePoint(profile,placeId);
        if(d.action!=Action.SPEAK)return none();
        tripSpoken++;
        return new Outcome(Event.SPEAK,d.speech,d);
    }

    /** Remembers the cell under the car as part of the way to the current turn. */
    private void trail() {
        if(current==null||lastCell==null||meters<0||meters>APPROACH_METERS||approach.contains(lastCell))return;
        approach.addLast(lastCell);
        if(approach.size()>APPROACH_CELLS)approach.removeFirst();
    }
    private void resetApproach(){approach.clear();hardSeen=false;}
    private boolean stopped(){return brake&&speedKph>=0&&speedKph<=STOPPED_KPH;}
    private boolean canAsk(){return parked||arrived&&stopped();}
    private boolean brief(){return speedKph>=DriveCoach.BRIEF_SPEED_KPH||nextGapMeters>0&&nextGapMeters<=DriveCoach.BRIEF_GAP_METERS||roadLimitKph>=DriveCoach.BRIEF_LIMIT_KPH;}
    private Outcome judge(long now) {
        Context c=new Context(meters,signal,passenger);c.speedKph=speedKph;c.familiarity=familiarity;c.brief=brief();c.rain=rain;c.night=night;c.hardSegment=hardSegment();if(c.hardSegment)hardSeen=true;
        Decision d=DriveCoach.decide(profile,current,c);
        // A driver who signalled once for this turn has prepared, even if the signal went off again.
        if(d.action==Action.SILENT||signalOnMeters>0&&d.action==Action.SPEAK)return new Outcome(Event.SILENT,"",d);
        if(d.action==Action.SPEAK&&!announced){announced=true;tripSpoken++;return new Outcome(Event.SPEAK,d.speech,d);}
        return new Outcome(Event.NONE,"",d);
    }
    private Outcome passTurn(long now) {
        Outcome settled=settle();
        lastPassed=new Passed(current,now,signalOnMeters,turnedNear,hardSeen,approach.toArray(new String[0]));lastPassedJudged=false;
        return settled;
    }
    /** The last turn left behind was neither rerouted nor missed, so it counts as a pass. */
    private Outcome settle() {
        if(lastPassed==null||lastPassedJudged)return none();
        Passed previous=lastPassed;lastPassedJudged=true;
        boolean watched=DriveCoach.isWeak(profile,previous.turn.type)||DriveCoach.remembers(profile,previous.turn.id)||previous.hard;
        if(previous.turn.type.side!=null&&previous.signalOnMeters==0)tripSignalOff++;
        if(DriveCoach.notePass(profile,previous.turn,previous.signalOnMeters))pendingLower=previous.turn.type;
        for(String cell:previous.cells)DriveCoach.noteSegment(profile,cell,true,previous.turn.type.side!=null&&previous.signalOnMeters==0);
        if(!watched)return none();
        tripPassed++;
        return new Outcome(Event.PASSED,"",null);
    }
    private Outcome clearTurn(long now){Outcome passed=current==null?none():passTurn(now);current=null;meters=-1;announced=false;signalOnMeters=0;turnedNear=false;resetApproach();return passed;}
    private Outcome reviewIfPossible() {
        if(!DriveCoach.canReview(profile,canAsk(),passenger))return none();
        question="아까 그 출구, 놓치셨나요?";lowerAsked=null;
        return new Outcome(Event.QUESTION,question,null);
    }
    private void tripStart(){tripPassed=0;tripMisses=0;tripSignalOff=0;tripSpoken=0;tripCells.clear();lastCell=null;queued.clear();arrived=false;tripKm=-1;turnedNear=false;resetApproach();}
    /** Parked after driving: the miss question first, then the offer to lower a type, then the summary, one at a time. */
    private Outcome tripEnd() {
        queued.clear();
        settle();
        Outcome review=reviewIfPossible();
        if(review.event==Event.QUESTION){question=null;queued.add(review);}
        if(pendingLower!=null&&DriveCoach.isWeak(profile,pendingLower)&&!passenger){queued.add(new Outcome(Event.LOWER_QUESTION,DriveCoach.LOWER_QUESTION,null));}
        String summary=passenger||profile.driverType.neutralOnly?"":DriveCoach.summarySpeech(tripPassed+tripMisses,tripPassed,tripMisses,tripSignalOff,tripKm);
        if(!summary.isEmpty())queued.add(new Outcome(Event.SUMMARY,summary,null));
        Outcome first=queued.poll();
        if(first==null)return none();
        if(first.event==Event.QUESTION)question=first.speech;
        if(first.event==Event.LOWER_QUESTION){question=first.speech;lowerAsked=pendingLower;pendingLower=null;}
        return first;
    }
    private static boolean matches(Signal s,TurnType type){return type.side!=null&&s==type.side;}
    private static Outcome none(){return new Outcome(Event.NONE,"",null);}
}
