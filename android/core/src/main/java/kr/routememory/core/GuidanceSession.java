package kr.routememory.core;

import kr.routememory.core.DriveCoach.Action;
import kr.routememory.core.DriveCoach.Decision;
import kr.routememory.core.DriveCoach.Profile;
import kr.routememory.core.DriveCoach.Signal;
import kr.routememory.core.DriveCoach.Turn;
import kr.routememory.core.DriveCoach.TurnType;

/**
 * Turns real navigation and vehicle events into the companion's decisions. Same rules as RouteMemory-PC
 * src/coach-guidance.js; change both together. The SDK bridge maps vendor types onto these calls, so this class
 * stays testable without a car: next turn with remaining metres, turn signal, gear, reroute, caution points.
 */
public final class GuidanceSession {
    /** A reroute this soon after a turn was passed with no matching signal counts as a missed turn. */
    public static final long MISS_WINDOW_MS=90000;
    /** A reroute while still this close to the current turn also counts. */
    public static final int MISS_NEAR_METERS=150;
    public enum Event {NONE,SPEAK,SILENT,MISS_CANDIDATE,PASSED,QUESTION}
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
    private String question;

    public GuidanceSession(Profile profile){this.profile=profile;}
    public Profile profile(){return profile;}
    public Turn currentTurn(){return current;}
    public int meters(){return meters;}
    public Signal signal(){return signal;}
    public boolean parked(){return parked;}
    public boolean navigating(){return navigating;}
    public String pendingQuestion(){return question;}
    public void setPassenger(boolean value){passenger=value;}

    public void setProfile(Profile next){profile=next;current=null;meters=-1;announced=false;signalOnMeters=0;lastPassed=null;question=null;}

    /** The next turn from the navigation, with the distance still to go. */
    public Outcome onTurn(Turn turn,int remainingMeters,long now) {
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
    public Outcome onGear(boolean parkedNow,long now){parked=parkedNow;return parked?reviewIfPossible():none();}
    public Outcome onNavigationStatus(boolean routeDrive,long now){navigating=routeDrive;return routeDrive?none():clearTurn(now);}
    /** The navigation computed a new route on its own: the driver may have left the planned one. */
    public Outcome onReroute(long now) {
        Turn suspect=null;
        if(lastPassed!=null&&!lastPassedJudged&&now-lastPassed.at<=MISS_WINDOW_MS&&lastPassed.signalOnMeters==0)suspect=lastPassed.turn;
        else if(current!=null&&meters>=0&&meters<=MISS_NEAR_METERS&&signalOnMeters==0)suspect=current;
        if(suspect==null)return none();
        DriveCoach.noteMiss(profile,suspect);
        lastPassedJudged=true;current=null;meters=-1;announced=false;signalOnMeters=0;
        Outcome asked=reviewIfPossible();
        return asked.event==Event.QUESTION?asked:new Outcome(Event.MISS_CANDIDATE,"",null);
    }
    /** The driver answered the parked question. */
    public DriveCoach.Review answer(boolean confirmed){question=null;return DriveCoach.review(profile,confirmed);}

    private Outcome judge(long now) {
        Decision d=DriveCoach.decide(profile,current,meters,signal,passenger);
        // A driver who signalled once for this turn has prepared, even if the signal went off again.
        if(d.action==Action.SILENT||signalOnMeters>0&&d.action==Action.SPEAK)return new Outcome(Event.SILENT,"",d);
        if(d.action==Action.SPEAK&&!announced){announced=true;return new Outcome(Event.SPEAK,d.speech,d);}
        return new Outcome(Event.NONE,"",d);
    }
    private Outcome passTurn(long now) {
        Passed previous=lastPassedJudged?null:lastPassed;
        lastPassed=new Passed(current,now,signalOnMeters);lastPassedJudged=false;
        if(previous==null)return none();
        // The turn before this one was neither rerouted nor missed, so it counts as a pass.
        boolean watched=DriveCoach.isWeak(profile,previous.turn.type)||DriveCoach.remembers(profile,previous.turn.id);
        DriveCoach.notePass(profile,previous.turn,previous.signalOnMeters);
        return watched?new Outcome(Event.PASSED,"",null):none();
    }
    private Outcome clearTurn(long now){Outcome passed=current==null?none():passTurn(now);current=null;meters=-1;announced=false;signalOnMeters=0;return passed;}
    private Outcome reviewIfPossible() {
        if(!DriveCoach.canReview(profile,parked,passenger))return none();
        question="아까 그 출구, 놓치셨나요?";
        return new Outcome(Event.QUESTION,question,null);
    }
    private static boolean matches(Signal s,TurnType type){return type.side!=null&&s==type.side;}
    private static Outcome none(){return new Outcome(Event.NONE,"",null);}
}
