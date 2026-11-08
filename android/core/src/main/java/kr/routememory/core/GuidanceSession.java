package kr.routememory.core;

import java.util.ArrayDeque;
import java.util.HashSet;
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
 * position, destination. What the car cannot give (speed, position, the destination name) simply stays unknown.
 */
public final class GuidanceSession {
    /** A reroute this soon after a turn was passed with no matching signal counts as a missed turn. */
    public static final long MISS_WINDOW_MS=90000;
    /** A reroute while still this close to the current turn also counts. */
    public static final int MISS_NEAR_METERS=150;
    public enum Event {NONE,SPEAK,SILENT,MISS_CANDIDATE,PASSED,QUESTION,LOWER_QUESTION,SUMMARY,BRIEFING,MEMO}
    public static final class Outcome {
        public final Event event; public final String speech; public final Decision decision;
        Outcome(Event event,String speech,Decision decision){this.event=event;this.speech=speech;this.decision=decision;}
    }
    private static final class Passed { final Turn turn; final long at; final int signalOnMeters; Passed(Turn t,long at,int s){turn=t;this.at=at;signalOnMeters=s;} }

    private Profile profile;
    private Turn current; private int meters=-1; private boolean announced;
    private int signalOnMeters=0; // metres at which the matching signal was first seen for the current turn, 0 if never
    private Signal signal=Signal.OFF; private boolean passenger,parked=true,navigating;
    private Passed lastPassed; private boolean lastPassedJudged;
    private String question; private TurnType lowerAsked;
    private int speedKph=-1,nextGapMeters=-1; private Familiarity familiarity=Familiarity.UNKNOWN;
    private String destination,lastCell; private final Set<String> tripCells=new HashSet<>();
    private TurnType pendingLower; private final ArrayDeque<Outcome> queued=new ArrayDeque<>();
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

    public void setProfile(Profile next){profile=next;current=null;meters=-1;announced=false;signalOnMeters=0;lastPassed=null;question=null;lowerAsked=null;pendingLower=null;queued.clear();}

    /** The next turn from the navigation, with the distance still to go. */
    public Outcome onTurn(Turn turn,int remainingMeters,long now){return onTurn(turn,remainingMeters,-1,now);}
    /** As above, with the distance from this turn to the one after it (-1 when the navigation gives only one). */
    public Outcome onTurn(Turn turn,int remainingMeters,int gapToNextMeters,long now) {
        nextGapMeters=gapToNextMeters;
        if(turn==null)return clearTurn(now);
        boolean changed=current==null||!current.id.equals(turn.id)||remainingMeters>meters+MISS_NEAR_METERS;
        if(changed){
            Outcome passed=current==null?null:passTurn(now);
            current=turn;announced=false;signalOnMeters=0;
            if(passed!=null&&passed.event==Event.PASSED){meters=remainingMeters;return passed;}
        }
        meters=remainingMeters;
        if(matches(signal,turn.type)&&signalOnMeters==0)signalOnMeters=Math.max(1,remainingMeters);
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
        String cell=DriveCoach.cellKey(lat,lon);
        if(cell.equals(lastCell))return none();
        lastCell=cell;
        familiarity=DriveCoach.familiarity(profile,cell);
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
        Turn suspect=null;
        if(lastPassed!=null&&!lastPassedJudged&&now-lastPassed.at<=MISS_WINDOW_MS&&lastPassed.signalOnMeters==0)suspect=lastPassed.turn;
        else if(current!=null&&meters>=0&&meters<=MISS_NEAR_METERS&&signalOnMeters==0)suspect=current;
        if(suspect==null)return none();
        DriveCoach.noteMiss(profile,suspect);tripMisses++;
        // Blamed on the current turn, the turn before it was passed after all.
        if(suspect==current)settle();
        lastPassedJudged=true;current=null;meters=-1;announced=false;signalOnMeters=0;
        Outcome asked=reviewIfPossible();
        return asked.event==Event.QUESTION?asked:new Outcome(Event.MISS_CANDIDATE,"",null);
    }
    /** The driver answered the parked question about a miss. */
    public DriveCoach.Review answer(boolean confirmed){question=null;lowerAsked=null;return DriveCoach.review(profile,confirmed,destination);}
    /** The driver answered the offer to lower a weak type. */
    public void answerLower(boolean lower){if(lower&&lowerAsked!=null)DriveCoach.lowerType(profile,lowerAsked);question=null;lowerAsked=null;}
    /** What else was waiting behind the question just answered: the next question, or the trip summary. */
    public Outcome afterAnswer(){Outcome next=queued.poll();if(next!=null&&(next.event==Event.QUESTION||next.event==Event.LOWER_QUESTION))question=next.speech;return next==null?none():next;}
    /** The driver marks this spot as confusing, by voice or by a button; it joins the memory with its own words. */
    public Outcome memo(String text,long now) {
        String words=text==null?"":text.trim();
        String placeId=current!=null?current.id:lastCell!=null?"memo|"+lastCell:null;
        if(placeId==null||words.isEmpty())return none();
        DriveCoach.addMemo(profile,placeId);DriveCoach.labelPlace(profile,placeId,words);
        DriveCoach.rememberFor(profile,destination,placeId);
        if(words.contains("합류")&&destination!=null)DriveCoach.noteHardMerge(profile,destination);
        return new Outcome(Event.MEMO,"기억해 둘게요. "+words,null);
    }

    private boolean brief(){return speedKph>=DriveCoach.BRIEF_SPEED_KPH||nextGapMeters>0&&nextGapMeters<=DriveCoach.BRIEF_GAP_METERS;}
    private Outcome judge(long now) {
        Context c=new Context(meters,signal,passenger);c.speedKph=speedKph;c.familiarity=familiarity;c.brief=brief();
        Decision d=DriveCoach.decide(profile,current,c);
        // A driver who signalled once for this turn has prepared, even if the signal went off again.
        if(d.action==Action.SILENT||signalOnMeters>0&&d.action==Action.SPEAK)return new Outcome(Event.SILENT,"",d);
        if(d.action==Action.SPEAK&&!announced){announced=true;tripSpoken++;return new Outcome(Event.SPEAK,d.speech,d);}
        return new Outcome(Event.NONE,"",d);
    }
    private Outcome passTurn(long now) {
        Outcome settled=settle();
        lastPassed=new Passed(current,now,signalOnMeters);lastPassedJudged=false;
        return settled;
    }
    /** The last turn left behind was neither rerouted nor missed, so it counts as a pass. */
    private Outcome settle() {
        if(lastPassed==null||lastPassedJudged)return none();
        Passed previous=lastPassed;lastPassedJudged=true;
        boolean watched=DriveCoach.isWeak(profile,previous.turn.type)||DriveCoach.remembers(profile,previous.turn.id);
        if(previous.turn.type.side!=null&&previous.signalOnMeters==0)tripSignalOff++;
        if(DriveCoach.notePass(profile,previous.turn,previous.signalOnMeters))pendingLower=previous.turn.type;
        if(!watched)return none();
        tripPassed++;
        return new Outcome(Event.PASSED,"",null);
    }
    private Outcome clearTurn(long now){Outcome passed=current==null?none():passTurn(now);current=null;meters=-1;announced=false;signalOnMeters=0;return passed;}
    private Outcome reviewIfPossible() {
        if(!DriveCoach.canReview(profile,parked,passenger))return none();
        question="아까 그 출구, 놓치셨나요?";lowerAsked=null;
        return new Outcome(Event.QUESTION,question,null);
    }
    private void tripStart(){tripPassed=0;tripMisses=0;tripSignalOff=0;tripSpoken=0;tripCells.clear();lastCell=null;queued.clear();}
    /** Parked after driving: the miss question first, then the offer to lower a type, then the summary, one at a time. */
    private Outcome tripEnd() {
        queued.clear();
        settle();
        Outcome review=reviewIfPossible();
        if(review.event==Event.QUESTION){question=null;queued.add(review);}
        if(pendingLower!=null&&DriveCoach.isWeak(profile,pendingLower)&&!passenger){queued.add(new Outcome(Event.LOWER_QUESTION,DriveCoach.LOWER_QUESTION,null));}
        String summary=passenger||profile.driverType.neutralOnly?"":DriveCoach.summarySpeech(tripPassed+tripMisses,tripPassed,tripMisses,tripSignalOff);
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
