package kr.routememory.core;

import kr.routememory.core.DriveCoach.DriverType;
import kr.routememory.core.DriveCoach.Familiarity;
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
    /** One clean pass of a signalled turn: the next turn arriving closes it. */
    static void cleanPass(GuidanceSession s,String id,long at){s.onTurn(exitLeft(id),200,at);s.onSignal(Signal.LEFT,at+1);s.onTurn(exitLeft(id),50,at+2);s.onSignal(Signal.OFF,at+3);}

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
        for(int i=0;i<3;i++)cleanPass(s,"p"+i,i*10000);
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

        // The decision distance follows the speed: half at 30 km/h, double at 120, 30% more on a road never driven.
        Profile g=new Profile("g","G",DriverType.GENERAL);Turn e=exitLeft("e");
        check(DriveCoach.decisionMeters(g,e,-1,Familiarity.UNKNOWN)==300,"unknown speed keeps the base distance");
        check(DriveCoach.decisionMeters(g,e,120,Familiarity.UNKNOWN)==600,"120 km/h doubles it: "+DriveCoach.decisionMeters(g,e,120,Familiarity.UNKNOWN));
        check(DriveCoach.decisionMeters(g,e,30,Familiarity.UNKNOWN)==150,"30 km/h halves it");
        check(DriveCoach.decisionMeters(g,e,10,Familiarity.UNKNOWN)==150,"never below half");
        check(DriveCoach.decisionMeters(g,e,-1,Familiarity.NEW)==390,"a new road adds 30%");
        check(DriveCoach.decisionMeters(g,e,-1,Familiarity.FAMILIAR)==300,"a familiar road changes nothing");
        Profile t=new Profile("t","T",DriverType.TRUCK);
        check(DriveCoach.decisionMeters(t,e,120,Familiarity.NEW)==1560,"a truck at 120 on a new road: 600*2*1.3");
        s=fresh();
        DriveCoach.chooseWeak(s.profile(),TurnType.EXIT_LEFT);
        s.onGear(false,0);
        check(s.onTurn(exitLeft("fast"),500,0).event==Event.NONE,"500 m is too far at an unknown speed");
        check(s.onSpeed(120,100).event==Event.SPEAK,"at 120 km/h the same 500 m is inside the distance");

        // The navigation's detail goes into the sentence; under load the sentence is cut to its core.
        s=fresh();
        DriveCoach.chooseWeak(s.profile(),TurnType.ROUNDABOUT);
        s.onGear(false,0);
        Outcome detailed=s.onTurn(new Turn("r1",TurnType.ROUNDABOUT,"3시 방향 출구"),200,0);
        check(detailed.speech.equals("회전교차로가 곧 나옵니다. 3시 방향 출구입니다. 미리 준비하세요."),"detail inside the sentence: "+detailed.speech);
        s.onSpeed(90,500);
        Outcome brief=s.onTurn(new Turn("r2",TurnType.ROUNDABOUT,"3시 방향 출구"),200,1000);
        check(brief.speech.equals("회전교차로, 3시 방향 출구."),"brief at speed: "+brief.speech);
        s.onSpeed(50,1500);
        s.onTurn(new Turn("r3",TurnType.ROUNDABOUT),200,400,2000);
        Outcome tight=s.onTurn(new Turn("r3",TurnType.ROUNDABOUT),190,400,2100);
        check(tight.speech.equals("회전교차로 준비."),"brief when another turn is right behind: "+tight.speech);

        // On a familiar road a weak type stays quiet, a remembered spot does not; a new road is judged earlier.
        s=fresh();
        DriveCoach.chooseWeak(s.profile(),TurnType.EXIT_LEFT);DriveCoach.addMemo(s.profile(),"known-miss");
        s.onGear(false,0);s.setFamiliarity(Familiarity.FAMILIAR,0);
        Outcome quiet=s.onTurn(exitLeft("f1"),200,0);
        check(quiet.event==Event.NONE&&quiet.decision.reason.equals("familiar-road"),"weak type on a familiar road is not spoken: "+quiet.decision.reason);
        check(s.onTurn(exitLeft("known-miss"),200,1000).event==Event.SPEAK,"a remembered spot speaks even on a familiar road");
        s.setFamiliarity(Familiarity.NEW,2000);
        s.onTurn(exitLeft("f2"),360,2000);
        check(s.onTurn(exitLeft("f2"),350,2100).event==Event.SPEAK,"350 m is inside 390 on a new road");
        // Positions count a road cell once per trip; three trips make it familiar.
        s=fresh();
        for(int trip=0;trip<3;trip++){s.onGear(false,trip*100);s.onLocation(37.5665,126.9780,trip*100+1);s.onLocation(37.5700,126.9780,trip*100+2);s.onGear(true,trip*100+3);}
        s.onGear(false,1000);s.onLocation(37.5665,126.9780,1001);
        check(s.familiarity()==Familiarity.FAMILIAR,"three trips over a cell make it familiar: "+s.familiarity());
        s.onLocation(37.6000,126.9780,1002);
        check(s.familiarity()==Familiarity.NEW,"a cell never driven is new");
        check(s.profile().roadCellCount()==3,"cells are counted, not positions: "+s.profile().roadCellCount());

        // A driver who rarely signals gets no "signal off" sentence for a weak type, but still for a remembered spot.
        s=fresh();
        DriveCoach.chooseWeak(s.profile(),TurnType.EXIT_LEFT);DriveCoach.addMemo(s.profile(),"spot");
        for(int i=0;i<6;i++)DriveCoach.notePass(s.profile(),exitLeft("n"+i),i==0?300:0);
        check(DriveCoach.rarelySignals(s.profile()),"1 of 6 signalled is rare");
        s.onGear(false,0);
        Outcome rare=s.onTurn(exitLeft("w1"),200,0);
        check(rare.event==Event.SILENT&&rare.decision.reason.equals("rarely-signals"),"no nagging a driver who never signals: "+rare.decision.reason);
        Outcome spot1=s.onTurn(exitLeft("spot"),210,1000),spot2=s.onTurn(exitLeft("spot"),200,1100);
        check(spot1.event==Event.SPEAK||spot2.event==Event.SPEAK,"a remembered spot is still said");

        // Parking after a trip: the miss question first, then the summary of the trip.
        s=fresh();
        DriveCoach.chooseWeak(s.profile(),TurnType.EXIT_LEFT);
        s.onGear(false,0);
        cleanPass(s,"q0",0);cleanPass(s,"q1",10000);
        s.onTurn(exitLeft("q2"),100,20000);
        check(s.onReroute(20500).event==Event.MISS_CANDIDATE,"third one missed");
        Outcome first=s.onGear(true,30000);
        check(first.event==Event.QUESTION,"the question comes first when parked: "+first.event);
        s.answer(true);
        Outcome second=s.afterAnswer();
        check(second.event==Event.SUMMARY&&second.speech.equals("오늘 지켜본 회전 3곳 중 잘 지난 곳 2, 놓침 후보 1입니다."),"then the summary: "+second.speech);
        check(s.afterAnswer().event==Event.NONE,"nothing more");
        check(DriveCoach.remembers(s.profile(),"q2"),"the confirmed miss is remembered");
        // Unsignalled turns are counted for the summary even when nothing was watched.
        s=fresh();
        s.onGear(false,0);
        s.onTurn(exitLeft("u0"),100,0);s.onTurn(exitLeft("u1"),900,1000);s.onTurn(exitLeft("u2"),900,2000);
        Outcome only=s.onGear(true,3000);
        check(only.event==Event.SUMMARY&&only.speech.equals("방향지시등 없이 지난 회전이 2번 있었어요."),"unsignalled turns are reported: "+only.speech);
        // With a passenger nothing is summarised, and the bus wording stays neutral.
        s=fresh();s.setPassenger(true);s.onGear(false,0);s.onTurn(exitLeft("v0"),100,0);s.onTurn(exitLeft("v1"),900,1000);
        check(s.onGear(true,2000).event==Event.NONE,"no summary in front of a passenger");

        // Three clean passes of a weak type: parked, the offer to lower it comes before the summary; yes lowers it.
        s=fresh();
        DriveCoach.chooseWeak(s.profile(),TurnType.EXIT_LEFT);
        s.onGear(false,0);
        for(int i=0;i<3;i++)cleanPass(s,"l"+i,i*10000);
        s.onTurn(exitLeft("l3"),900,40000);
        Outcome offer=s.onGear(true,50000);
        check(offer.event==Event.LOWER_QUESTION&&offer.speech.equals(DriveCoach.LOWER_QUESTION),"the lowering offer when parked: "+offer.event);
        check(s.lowerAsked()&&s.pendingQuestion()!=null,"it is a pending question of its own kind");
        s.answerLower(true);
        check(!DriveCoach.isWeak(s.profile(),TurnType.EXIT_LEFT),"yes lowers the type");
        check(s.afterAnswer().event==Event.SUMMARY,"the summary follows");

        // The destination ties remembered spots to it, so the next trip there starts with a briefing.
        Profile keep=new Profile("car","차량",DriverType.GENERAL);
        s=new GuidanceSession(keep);
        check(s.onDestination("서울병원",0).event==Event.NONE,"nothing to brief yet");
        s.onGear(false,0);
        s.onTurn(new Turn("exit_left|강변북로 진출",TurnType.EXIT_LEFT,"강변북로 진출"),100,0);
        s.onReroute(500);
        check(s.onGear(true,1000).event==Event.QUESTION,"asked when parked");
        s.answer(true);
        GuidanceSession again=new GuidanceSession(keep);
        Outcome briefing=again.onDestination("서울병원",2000);
        check(briefing.event==Event.BRIEFING&&briefing.speech.equals("이번 길에 기억해 둔 곳이 1곳 있습니다. 강변북로 진출 왼쪽 출구입니다."),"briefing from the destination: "+briefing.speech);
        again.onGear(false,2500);
        check(again.onDestination("서울병원",3000).event==Event.NONE,"no briefing while driving");
        check(again.onDestination("다른 곳",3500).event==Event.NONE,"nothing learned for another destination");

        // A memo by voice or button remembers the current turn with the driver's own words.
        s=fresh();
        s.onDestination("물류센터",0);s.onGear(false,0);
        s.onTurn(exitLeft("m1"),400,0);
        Outcome memo=s.memo("여기 합류가 어려워",100);
        check(memo.event==Event.MEMO&&memo.speech.equals("기억해 둘게요. 여기 합류가 어려워"),"memo acknowledged: "+memo.speech);
        check(DriveCoach.remembers(s.profile(),"m1")&&DriveCoach.placeLabel(s.profile(),"m1").equals("여기 합류가 어려워"),"the turn is remembered with the words");
        check(!DriveCoach.comfortOffer(s.profile(),"물류센터",true,false).isEmpty(),"a merge memo opens the comfort offer for that destination");
        check(s.memo("   ",200).event==Event.NONE,"empty words are not a memo");

        // Everything new survives the snapshot too.
        Profile full=new Profile("f","F",DriverType.GENERAL);
        DriveCoach.notePass(full,exitLeft("s1"),0);DriveCoach.notePass(full,exitLeft("s2"),250);
        DriveCoach.addMemo(full,"exit_left|x");DriveCoach.labelPlace(full,"exit_left|x","x 왼쪽 출구");DriveCoach.rememberFor(full,"회사","exit_left|x");
        DriveCoach.noteRoad(full,DriveCoach.cellKey(37.5,127.0));DriveCoach.noteRoad(full,DriveCoach.cellKey(37.5,127.0));
        Profile copy=new Profile("f","F",DriverType.GENERAL);
        DriveCoach.restore(copy,DriveCoach.snapshot(full)+"cell bad 3\ndest \tnope\nlabel x\n");
        check(copy.signalTurns==2&&copy.signalOnTurns==1,"signal counts restored");
        check(DriveCoach.placesFor(copy,"회사").size()==1&&DriveCoach.placesFor(copy,"회사").get(0).label.equals("x 왼쪽 출구"),"destination places restored");
        check(DriveCoach.familiarity(copy,DriveCoach.cellKey(37.5,127.0))==Familiarity.KNOWN&&copy.roadCellCount()==1,"road cells restored, garbage skipped");
        System.out.println("GuidanceSession checks passed: "+checks);
    }
}
