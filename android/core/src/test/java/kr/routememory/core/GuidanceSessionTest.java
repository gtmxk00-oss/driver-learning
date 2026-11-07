package kr.routememory.core;

import kr.routememory.core.DriveCoach.DriverType;
import kr.routememory.core.DriveCoach.Profile;
import kr.routememory.core.DriveCoach.Signal;
import kr.routememory.core.DriveCoach.Turn;
import kr.routememory.core.DriveCoach.TurnType;
import kr.routememory.core.GuidanceSession.Event;
import kr.routememory.core.GuidanceSession.Outcome;

/** Same expectations as RouteMemory-PC test/coach-guidance.test.cjs. */
public final class GuidanceSessionTest {
    static int checks;
    static void check(boolean condition,String why){checks++;if(!condition)throw new AssertionError(why);}
    static Turn exitLeft(String id){return new Turn(id,TurnType.EXIT_LEFT);}
    static GuidanceSession fresh(){return new GuidanceSession(new Profile("car","차량",DriverType.GENERAL));}

    public static void main(String[] args) {
        // A reroute right after passing an exit with the signal off makes a miss candidate; parking asks the question.
        GuidanceSession s=fresh();
        check(s.onTurn(exitLeft("e1"),900,0).event==Event.NONE,"unknown turn says nothing");
        s.onTurn(exitLeft("e1"),200,5000);
        s.onGear(false,5000);
        check(s.onTurn(exitLeft("next"),1500,8000).event==Event.NONE,"passing e1 without history is quiet");
        Outcome miss=s.onReroute(9000);
        check(miss.event==Event.MISS_CANDIDATE,"reroute soon after the exit is a miss candidate: "+miss.event);
        check(s.pendingQuestion()==null,"no question while driving");
        check(s.onGear(true,20000).event==Event.QUESTION,"parking asks about the miss");
        check(s.answer(true).confirmed,"driver confirms");
        check(DriveCoach.remembers(s.profile(),"e1"),"the exit is remembered");

        // Saying no records nothing.
        s=fresh();
        s.onTurn(exitLeft("e2"),100,0);s.onGear(false,0);
        s.onReroute(1000);
        s.onGear(true,2000);
        s.answer(false);
        check(!DriveCoach.remembers(s.profile(),"e2"),"no means no record");

        // Two confirmed misses make the type weak; a new road then gets one sentence, once, unless the signal is on.
        s=fresh();
        for(String id:new String[]{"a","b"}){s.onGear(false,0);s.onTurn(exitLeft(id),100,0);s.onReroute(1000);s.onGear(true,2000);s.answer(true);}
        check(DriveCoach.isWeak(s.profile(),TurnType.EXIT_LEFT),"weak after two misses");
        s.onGear(false,3000);
        check(s.onTurn(exitLeft("new"),800,3000).event==Event.NONE,"too far to speak");
        Outcome spoke=s.onTurn(exitLeft("new"),400,4000);
        check(spoke.event==Event.SPEAK&&spoke.speech.contains("왼쪽 출구가 곧 나옵니다"),"speaks inside the decision distance");
        check(s.onTurn(exitLeft("new"),300,5000).event==Event.NONE,"said once per turn");
        s.onTurn(exitLeft("quiet"),700,6000);
        s.onSignal(Signal.LEFT,6500);
        check(s.onTurn(exitLeft("quiet"),400,7000).event==Event.SILENT,"matching signal keeps it silent");
        s.onSignal(Signal.OFF,7500);
        check(s.onTurn(exitLeft("quiet"),300,8000).event==Event.SILENT||s.onTurn(exitLeft("quiet"),300,8000).event==Event.NONE,"a signal already seen keeps counting as prepared");

        // A reroute long after the turn is not blamed on it; a reroute near the turn with the signal on is not either.
        s=fresh();
        s.onGear(false,0);
        s.onTurn(exitLeft("x"),100,0);s.onTurn(exitLeft("y"),2000,1000);
        check(s.onReroute(1000+GuidanceSession.MISS_WINDOW_MS+1).event==Event.NONE,"late reroute is not a miss");
        s=fresh();
        s.onGear(false,0);s.onSignal(Signal.LEFT,0);
        s.onTurn(exitLeft("z"),100,0);
        check(s.onReroute(500).event==Event.NONE,"reroute with the signal on is not a miss");

        // Passing a weak-type turn cleanly counts as a pass, and three passes offer to lower it.
        s=fresh();
        DriveCoach.chooseWeak(s.profile(),TurnType.EXIT_LEFT);
        s.onGear(false,0);
        for(int i=0;i<3;i++){s.onTurn(exitLeft("p"+i),200,i*10000);s.onSignal(Signal.LEFT,i*10000+1);s.onTurn(exitLeft("p"+i),50,i*10000+2);s.onSignal(Signal.OFF,i*10000+3);}
        Outcome passed=s.onTurn(exitLeft("p3"),900,40000);
        check(passed.event==Event.PASSED,"a clean pass of a weak type is reported");
        check(s.profile().passStreak.getOrDefault(TurnType.EXIT_LEFT,0)>=2,"passes accumulate");

        // Caution points use the narrow-road rule; the navigation ending clears the turn.
        s=fresh();
        DriveCoach.chooseWeak(s.profile(),TurnType.NARROW_ROAD);
        s.onGear(false,0);
        check(s.onCaution("narrow-1",250,0).event==Event.SPEAK,"a weak caution type speaks");
        check(s.onNavigationStatus(false,1000).event!=Event.SPEAK&&s.currentTurn()==null,"ending the route clears the turn");

        // Passengers change the sentence and hold the question.
        s=fresh();
        s.setPassenger(true);
        DriveCoach.chooseWeak(s.profile(),TurnType.EXIT_LEFT);
        s.onGear(false,0);
        check(s.onTurn(exitLeft("t"),120,0).speech.equals("왼쪽 출구 준비하세요."),"neutral wording with a passenger");
        s.onReroute(500);
        check(s.onGear(true,1000).event==Event.NONE,"no question in front of a passenger");
        s.setPassenger(false);
        check(s.onGear(true,2000).event==Event.QUESTION,"question once the passenger has left");

        // Memory survives a restart through the snapshot, and garbage in the snapshot is ignored.
        Profile kept=new Profile("k","K",DriverType.TAXI);
        DriveCoach.chooseWeak(kept,TurnType.ROUNDABOUT);DriveCoach.noteMiss(kept,exitLeft("exit_left|강변북로 진출"));DriveCoach.review(kept,true);DriveCoach.notePass(kept,exitLeft("p"),420);
        Profile back=new Profile("k","K",DriverType.TAXI);
        DriveCoach.restore(back,DriveCoach.snapshot(kept)+"weak NOPE\nmiss\nplace \n");
        check(DriveCoach.isWeak(back,TurnType.ROUNDABOUT)&&DriveCoach.remembers(back,"exit_left|강변북로 진출")&&back.misses(TurnType.EXIT_LEFT)==1&&back.habitMeters==420&&back.placeCount()==1,"snapshot round trip");
        System.out.println("GuidanceSession checks passed: "+checks);
    }
}
