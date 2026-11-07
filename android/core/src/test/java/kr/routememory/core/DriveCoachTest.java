package kr.routememory.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import kr.routememory.core.DriveCoach.Action;
import kr.routememory.core.DriveCoach.DriverType;
import kr.routememory.core.DriveCoach.Profile;
import kr.routememory.core.DriveCoach.Signal;
import kr.routememory.core.DriveCoach.Turn;
import kr.routememory.core.DriveCoach.TurnType;

/** Same expectations as RouteMemory-PC test/coach.test.cjs. */
public final class DriveCoachTest {
    static int checks;
    static void check(boolean condition,String why){checks++;if(!condition)throw new AssertionError(why);}
    static Turn left(String id){return new Turn(id,TurnType.EXIT_LEFT);}
    static Profile profile(DriverType type){return new Profile("a","A",type);}
    static DriveCoach.Review confirm(Profile p,Turn t){DriveCoach.noteMiss(p,t);return DriveCoach.review(p,true);}
    static CoachDemo.State at(String id,double offset) {
        for(int i=0;i<CoachDemo.scenes().size();i++)
            if(CoachDemo.scenes().get(i).id.equals(id))return CoachDemo.stateAt(CoachDemo.startOf(i)+offset);
        throw new AssertionError(id);
    }
    public static void main(String[] args) {
        Profile p=profile(DriverType.GENERAL);
        check(!confirm(p,left("e1")).becameWeak&&!DriveCoach.isWeak(p,TurnType.EXIT_LEFT),"one miss is not a weak type");
        check(DriveCoach.remembers(p,"e1"),"one miss remembers the place");
        check(confirm(p,left("e2")).becameWeak,"two misses make a weak type");
        DriveCoach.Decision firstSeen=DriveCoach.decide(p,left("new"),500,Signal.OFF,false);
        check(firstSeen.action==Action.SPEAK&&firstSeen.speech.equals("왼쪽 출구가 곧 나옵니다. 미리 준비하세요."),"weak type applies to a new road");
        check(DriveCoach.decide(profile(DriverType.GENERAL),left("e"),100,Signal.OFF,false).action==Action.NONE,"no history stays quiet");

        Profile unconfirmed=profile(DriverType.GENERAL);
        DriveCoach.noteMiss(unconfirmed,left("e"));
        check(!DriveCoach.review(unconfirmed,false).confirmed&&unconfirmed.misses(TurnType.EXIT_LEFT)==0&&!DriveCoach.remembers(unconfirmed,"e"),"unconfirmed miss teaches nothing");

        Profile weak=profile(DriverType.GENERAL);DriveCoach.chooseWeak(weak,TurnType.EXIT_LEFT);
        check(DriveCoach.decide(weak,left("e"),200,Signal.LEFT,false).action==Action.SILENT,"matching signal is silent");
        check(DriveCoach.decide(weak,left("e"),200,Signal.OFF,false).action==Action.SPEAK,"no signal speaks");
        check(DriveCoach.decide(weak,left("e"),200,Signal.RIGHT,false).reason.equals("needs-attention"),"wrong signal speaks");
        check(DriveCoach.decide(weak,left("e"),301,Signal.OFF,false).action==Action.WAIT,"too far waits");
        check(DriveCoach.decide(weak,left("e"),300,Signal.OFF,false).action==Action.SPEAK,"decision distance is inclusive");

        Profile truck=profile(DriverType.TRUCK);DriveCoach.chooseWeak(truck,TurnType.NARROW_ROAD);
        DriveCoach.Decision narrow=DriveCoach.decide(truck,new Turn("n",TurnType.NARROW_ROAD),300,Signal.LEFT,false);
        check(narrow.action==Action.SPEAK&&narrow.speech.equals("좁은 길이 곧 나옵니다. 미리 준비하세요."),"sideless turn cannot be silenced");

        int[] expected={300,300,600,450};DriverType[] types=DriverType.values();
        for(int i=0;i<types.length;i++)check(DriveCoach.decisionMeters(profile(types[i]),left("e"))==expected[i],"base distance "+types[i]);
        Profile many=profile(DriverType.GENERAL),manyTruck=profile(DriverType.TRUCK);
        for(int i=0;i<6;i++){confirm(many,left("g"+i));confirm(manyTruck,left("t"+i));}
        check(DriveCoach.decisionMeters(many,left("e"))==900&&DriveCoach.decisionMeters(manyTruck,left("e"))==1200,"distance limits");

        Profile late=profile(DriverType.GENERAL);DriveCoach.notePass(late,left("e"),100);
        check(DriveCoach.decisionMeters(late,left("e"))==300,"late signaller keeps the minimum");
        Profile early=profile(DriverType.GENERAL);DriveCoach.notePass(early,left("e"),500);DriveCoach.notePass(early,left("e"),700);
        check(early.habitMeters==600&&DriveCoach.decisionMeters(early,left("e"))==600,"habit moves the decision");

        Profile taxi=profile(DriverType.TAXI);confirm(taxi,left("e"));
        check(DriveCoach.decide(taxi,left("e"),200,Signal.OFF,false).speech.equals("지난번 놓친 왼쪽 출구예요. 미리 준비하세요."),"alone hears history");
        check(DriveCoach.decide(taxi,left("e"),200,Signal.OFF,true).speech.equals("왼쪽 출구 준비하세요."),"passenger hides history");
        Profile bus=profile(DriverType.BUS);confirm(bus,left("e"));
        check(DriveCoach.decide(bus,left("e"),200,Signal.OFF,false).speech.equals("왼쪽 출구 준비하세요."),"bus is always neutral");

        Profile asking=profile(DriverType.TAXI);DriveCoach.noteMiss(asking,left("e"));
        check(!DriveCoach.canReview(asking,false,false)&&!DriveCoach.canReview(asking,true,true)&&DriveCoach.canReview(asking,true,false),"question waits");
        DriveCoach.noteMiss(asking,left("e"));check(asking.pendingCount()==1,"one question per place");

        Profile memo=profile(DriverType.BUS);
        check(DriveCoach.decidePoint(memo,"p").action==Action.NONE,"unknown point");
        DriveCoach.addMemo(memo,"p");
        check(DriveCoach.decidePoint(memo,"p").speech.equals("헷갈렸던 지점입니다. 미리 확인하세요."),"memo reminds without a route");

        Profile lower=profile(DriverType.GENERAL);DriveCoach.chooseWeak(lower,TurnType.EXIT_LEFT);
        check(!DriveCoach.notePass(lower,left("e"),0)&&!DriveCoach.notePass(lower,left("e"),0)&&DriveCoach.notePass(lower,left("e"),0),"three passes offer lowering");
        check(DriveCoach.isWeak(lower,TurnType.EXIT_LEFT),"offer alone lowers nothing");
        DriveCoach.lowerType(lower,TurnType.EXIT_LEFT);check(!DriveCoach.isWeak(lower,TurnType.EXIT_LEFT),"approval lowers");
        Profile reset=profile(DriverType.GENERAL);DriveCoach.chooseWeak(reset,TurnType.EXIT_LEFT);
        DriveCoach.notePass(reset,left("e"),0);DriveCoach.notePass(reset,left("e"),0);confirm(reset,left("o"));
        check(!DriveCoach.notePass(reset,left("e"),0),"a miss resets the streak");

        Profile parent=profile(DriverType.GENERAL),child=profile(DriverType.GENERAL);
        confirm(parent,left("e1"));confirm(parent,left("e2"));
        check(DriveCoach.decide(parent,left("e3"),100,Signal.OFF,false).action==Action.SPEAK&&DriveCoach.decide(child,left("e3"),100,Signal.OFF,false).action==Action.NONE,"profiles stay separate");

        List<String> alerts=new ArrayList<>();
        for(CoachDemo.Event event:CoachDemo.events())if(event.alert)alerts.add(event.text);
        check(alerts.equals(Arrays.asList("아까 그 출구, 놓치셨나요?","아까 그 출구, 놓치셨나요?","아까 그 출구, 놓치셨나요?","왼쪽 출구가 곧 나옵니다. 미리 준비하세요.",
            "왼쪽 출구 알림을 줄여도 될까요?","이번 길에 기억해 둔 곳이 3곳 있습니다. 강변 합류, 다리 앞 분기, 시장 앞 출구입니다.","지난번 어려웠던 합류를 피하는 편한 길로 바꿀까요?",
            "왼쪽 출구 준비하세요.","아까 그 출구, 놓치셨나요?","오른쪽 출구가 곧 나옵니다. 미리 준비하세요.","좁은 길이 곧 나옵니다. 미리 준비하세요.",
            "헷갈렸던 지점입니다. 미리 확인하세요.")),"demo speech matches PC: "+alerts);
        check(at("silent",4).action==Action.SILENT,"signal scene is silent");
        check(at("miss-1",14).weak.isEmpty()&&at("miss-2",11.5).weak.equals(Arrays.asList("왼쪽 출구")),"second miss shows the weak type");
        check(at("taxi",6.5).phase==CoachDemo.Phase.QUESTION_WAITING&&at("taxi",8.2).phase==CoachDemo.Phase.QUESTION&&!at("taxi",8.2).passenger,"taxi question after the passenger left");
        check(at("truck",1).limit==600,"truck limit");
        check(CoachDemo.stateAt(1e9).scene.id.equals("outro"),"clamped to the end");

        Profile briefed=profile(DriverType.GENERAL);
        List<DriveCoach.Place> route=new ArrayList<>();
        for(String id:Arrays.asList("r1","r2","r3","r4","r5"))route.add(new DriveCoach.Place(id,id));
        for(String id:Arrays.asList("r1","r2","r3","r4","elsewhere"))DriveCoach.addMemo(briefed,id);
        check(DriveCoach.briefing(briefed,route,true,false).points.size()==3,"briefing stops at three points");
        check(DriveCoach.briefing(briefed,route,true,false).speech.contains("r1, r2, r3입니다"),"briefing reads the route order");
        check(DriveCoach.briefing(briefed,route,false,false).speech.isEmpty(),"no briefing while moving");
        check(DriveCoach.briefing(briefed,route,true,true).speech.isEmpty(),"no briefing in front of a passenger");
        check(DriveCoach.briefing(profile(DriverType.GENERAL),route,true,false).speech.isEmpty(),"nothing to brief without memories");

        Profile comfort=profile(DriverType.GENERAL);
        check(DriveCoach.comfortOffer(comfort,"hospital",true,false).isEmpty(),"no offer without a memo");
        DriveCoach.noteHardMerge(comfort,"hospital");
        check(DriveCoach.comfortOffer(comfort,"other",true,false).isEmpty(),"offer is per destination");
        check(DriveCoach.comfortOffer(comfort,"hospital",false,false).isEmpty(),"no offer while moving");
        check(DriveCoach.comfortOffer(comfort,"hospital",true,true).isEmpty(),"no offer in front of a passenger");
        check(!DriveCoach.comfortOffer(comfort,"hospital",true,false).isEmpty(),"offer when parked and alone");
        check(!DriveCoach.usesComfortRoute(comfort,"hospital"),"route waits for approval");
        DriveCoach.acceptComfort(comfort,"hospital");
        check(DriveCoach.usesComfortRoute(comfort,"hospital"),"approval changes the route option");
        check(DriveCoach.comfortOffer(comfort,"hospital",true,false).isEmpty(),"offer is not repeated");

        Profile erased=profile(DriverType.GENERAL),kept=profile(DriverType.GENERAL);
        for(Profile each:Arrays.asList(erased,kept)){confirm(each,left("e"));DriveCoach.chooseWeak(each,TurnType.ROUNDABOUT);DriveCoach.noteHardMerge(each,"x");}
        DriveCoach.clearMemory(erased);
        check(!DriveCoach.remembers(erased,"e")&&!DriveCoach.isWeak(erased,TurnType.ROUNDABOUT),"clearing forgets the profile");
        check(DriveCoach.comfortOffer(erased,"x",true,false).isEmpty(),"clearing forgets comfort memos");
        check(DriveCoach.remembers(kept,"e")&&DriveCoach.isWeak(kept,TurnType.ROUNDABOUT),"other profiles keep their memory");

        check(at("setup",1).weak.isEmpty()&&at("setup",3).weak.contains("회전교차로"),"a chosen weak type shows at once");
        check(at("decline",14).phase==CoachDemo.Phase.DECLINED&&at("decline",14).places==at("decline",0.1).places,"answering no records nothing");
        check(at("lower",3).weak.contains("왼쪽 출구")&&at("lower",7).weak.isEmpty(),"lowering waits for approval");
        check(at("briefing",3).step.speak&&at("briefing",3).step.text.contains("3곳"),"briefing speaks three points");
        check(at("comfort-offer",3).step.text.contains("편한 길"),"memo leads to the comfort offer");
        check(at("family",4.5).action==Action.NONE&&at("family",4.5).spoken.isEmpty(),"the child hears nothing of the parent's memory");
        check(at("erase",1).step.text.contains("5곳")&&at("erase",3).places==0,"erase forgets the parent");
        check(at("taxi",0.1).places==1,"erase leaves other profiles");
        System.out.println("PASS: "+checks+" drive coach checks");
    }
}
