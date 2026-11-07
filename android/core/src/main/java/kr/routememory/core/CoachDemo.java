package kr.routememory.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.routememory.core.DriveCoach.Action;
import kr.routememory.core.DriveCoach.Decision;
import kr.routememory.core.DriveCoach.DriverType;
import kr.routememory.core.DriveCoach.Place;
import kr.routememory.core.DriveCoach.Profile;
import kr.routememory.core.DriveCoach.Signal;
import kr.routememory.core.DriveCoach.Turn;
import kr.routememory.core.DriveCoach.TurnType;

/** Virtual scenes for the companion demo, matching RouteMemory-PC src/coach-demo.js. All signals are simulated. */
public final class CoachDemo {
    private static final double QUESTION_SECONDS=3.2,RESULT_SECONDS=1,REROUTE_SECONDS=1.2,PASSENGER_LEAVES_SECONDS=1.6,SPEAK_SECONDS=4.4;
    private static final String ASK="아까 그 출구, 놓치셨나요?";
    private static final List<Place> ROUTE_TO_HOSPITAL=Collections.unmodifiableList(Arrays.asList(new Place("h-1","강변 합류"),new Place("h-2","다리 앞 분기"),new Place("h-3","시장 앞 출구"),new Place("h-4","병원 앞 회전")));
    public enum Outcome {NONE,PASS,MISS,DECLINE}
    public enum Phase {CARD,DRIVING,PASSED,REROUTING,QUESTION_WAITING,QUESTION,LEARNED,DECLINED,MEMO,MEMO_SAVED,PARKED}

    public static final class Scene {
        public String id,tag,title,lead,narration,question,memo,memoText,point,who,comfort;
        public DriverType driver; public Turn turn; public Outcome outcome=Outcome.NONE;
        public int from,signalAt,pointMeters; public double driveSeconds,seconds,narrateAt;
        public boolean passenger,noRoute,card,parked; public Signal signalSide=Signal.OFF;
    }
    /** One moment of a parked scene. */
    public static final class Step {
        public final double at; public final String label,text; public final boolean speak,applies;
        Step(double at,String label,String text,boolean speak,boolean applies){this.at=at;this.label=label;this.text=text;this.speak=speak;this.applies=applies;}
    }
    public static final class Event {
        public final double at; public final boolean alert; public final String text;
        Event(double at,boolean alert,String text){this.at=at;this.alert=alert;this.text=text;}
    }
    public static final class State {
        public Scene scene; public int index,meters=-1,limit,places; public double seconds;
        public Phase phase=Phase.DRIVING; public Signal signal=Signal.OFF; public boolean passenger,speaking;
        public Action action=Action.NONE; public String spoken=""; public List<String> weak=new ArrayList<>(); public Step step;
    }
    private static Scene card(String id,String title,String lead,double seconds,String narration) {
        Scene s=new Scene();s.id=id;s.card=true;s.title=title;s.lead=lead;s.seconds=seconds;s.narration=narration;return s;
    }
    private static Scene drive(String id,String tag,DriverType driver,String turnId,TurnType type,int from,double driveSeconds,Outcome outcome,double seconds,String narration) {
        Scene s=new Scene();s.id=id;s.tag=tag;s.driver=driver;s.turn=new Turn(turnId,type);s.from=from;s.driveSeconds=driveSeconds;
        s.outcome=outcome;s.seconds=seconds;s.narration=narration;
        if(outcome==Outcome.MISS||outcome==Outcome.DECLINE)s.question=ASK;
        return s;
    }
    private static Scene parked(String id,String tag,String who,double narrateAt,double seconds,String narration) {
        Scene s=new Scene();s.id=id;s.tag=tag;s.driver=DriverType.GENERAL;s.who=who;s.parked=true;s.noRoute=true;s.narrateAt=narrateAt;s.seconds=seconds;s.narration=narration;return s;
    }
    private static final List<Scene> SCENES=build();
    private static List<Scene> build() {
        List<Scene> list=new ArrayList<>();
        list.add(card("intro","길기억","놓친 길을 기억해, 필요할 때만 말합니다",13,"길기억은 운전자가 놓친 곳과 약한 회전 유형을 기억해, 필요할 때만 말하는 차량용 앱입니다. 판단 기능은 가상 신호로 시연합니다."));
        list.add(parked("setup","시작 · 프로필","starter",5,19,"시작할 때 운전자와 차량 프로필을 고릅니다. 어려운 유형을 직접 고르면 기록이 쌓이기 전부터 적용됩니다. 오른쪽 창은 내비게이션 옆에 두는 작은 기억 카드입니다."));
        list.add(drive("miss-1","일반 · 평소 주행",DriverType.GENERAL,"exit-a",TurnType.EXIT_LEFT,900,5,Outcome.MISS,20,"왼쪽 출구를 놓치면 내비게이션이 재탐색합니다. 길기억은 주행 중에 끼어들지 않고, 정차한 뒤 운전자에게 확인합니다."));
        list.add(drive("decline","일반 · 일부러 바꾼 길",DriverType.GENERAL,"exit-x",TurnType.EXIT_LEFT,900,4,Outcome.DECLINE,16,"일부러 길을 바꿨다면 아니요라고 답하면 됩니다. 아무것도 기록하지 않습니다."));
        list.add(drive("miss-2","일반 · 다른 길",DriverType.GENERAL,"exit-b",TurnType.EXIT_LEFT,900,4,Outcome.MISS,17,"다른 길에서도 왼쪽 출구를 놓쳤다고 확인하면, 왼쪽 출구가 이 운전자의 약한 유형이 됩니다."));
        list.add(drive("speak","일반 · 첫 출장길",DriverType.GENERAL,"exit-c",TurnType.EXIT_LEFT,1000,6,Outcome.PASS,15,"처음 가는 길이어도 약한 유형이 다가오는데 방향지시등이 꺼져 있으면, 한 문장으로 알립니다."));
        Scene silent=drive("silent","일반 · 첫 출장길",DriverType.GENERAL,"exit-d",TurnType.EXIT_LEFT,1000,6,Outcome.PASS,15,"운전자가 먼저 방향지시등을 켜면 준비한 것으로 보고, 아무 말도 하지 않습니다.");
        silent.signalAt=800;silent.signalSide=Signal.LEFT;list.add(silent);
        Scene lower=parked("lower","일반 · 알림 줄이기",null,8,19,"같은 유형을 연속 세 번 무사히 지나면 정차 중에 알림을 줄여도 될지 묻습니다. 운전자가 승인해야 약한 유형에서 내립니다.");
        lower.turn=new Turn("exit-e",TurnType.EXIT_LEFT);list.add(lower);
        list.add(parked("briefing","가족 · 출발 전 브리핑","parent",12,21,"정차 상태에서 목적지가 정해지면, 그 길에서 기억해 둔 지점을 최대 세 곳까지 미리 읽어 줍니다."));
        Scene merge=new Scene();merge.id="comfort-memo";merge.tag="가족 · 합류 메모";merge.driver=DriverType.GENERAL;merge.who="parent";merge.turn=new Turn("merge-1",TurnType.KEEP_RIGHT);merge.memo="merge-1";merge.memoText="여기 합류가 어려워";merge.comfort="hospital";merge.seconds=10;
        merge.narration="합류가 어려웠던 곳에서 음성으로 메모를 남깁니다.";list.add(merge);
        list.add(parked("comfort-offer","가족 · 편한 길 제안","parent",9,20,"다음에 같은 목적지로 출발할 때 편한 길로 바꿀지 묻습니다. 운전자가 승인해야 내비게이션의 경로 옵션을 바꿉니다."));
        Scene child=drive("family","가족 · 자녀 프로필",DriverType.GENERAL,"merge-1",TurnType.KEEP_RIGHT,800,5,Outcome.NONE,13,"같은 차를 자녀가 운전하면, 부모의 프로필에 쌓인 기억은 울리지 않습니다.");
        child.who="child";list.add(child);
        list.add(parked("erase","가족 · 기억 삭제","parent",5,15,"기억은 프로필별로 지울 수 있습니다. 기록은 운전자의 것이며, 직업 운전자의 기록도 회사에 제공하지 않습니다."));
        Scene taxi=drive("taxi","택시 · 승객 탑승",DriverType.TAXI,"exit-t",TurnType.EXIT_LEFT,700,5,Outcome.MISS,20,"택시 유형은 승객이 타면 지난 실수를 말하지 않습니다. 확인 질문도 승객이 내린 뒤로 미룹니다.");
        taxi.passenger=true;list.add(taxi);
        list.add(drive("truck","화물 · 처음 가는 물류센터",DriverType.TRUCK,"exit-r",TurnType.EXIT_RIGHT,1000,6,Outcome.PASS,16,"화물 유형은 큰 차체를 고려해 더 일찍 판단합니다. 기본 판단 거리가 일반의 두 배인 600미터입니다."));
        list.add(drive("narrow","화물 · 주의 구간",DriverType.TRUCK,"narrow-1",TurnType.NARROW_ROAD,900,5,Outcome.PASS,13,"내비게이션의 주의 지점 정보와 결합해, 좁은 길도 미리 알립니다."));
        Scene memo=new Scene();memo.id="bus-memo";memo.tag="버스 · 새 노선";memo.driver=DriverType.BUS;memo.memo="bus-point";memo.memoText="여기 헷갈려";memo.noRoute=true;memo.passenger=true;memo.seconds=9;
        memo.narration="새 노선에 배치된 기사가 헷갈린 곳에서 음성으로 메모를 남깁니다.";list.add(memo);
        Scene remind=new Scene();remind.id="bus-remind";remind.tag="버스 · 다음 운행";remind.driver=DriverType.BUS;remind.point="bus-point";remind.from=400;remind.driveSeconds=5;remind.pointMeters=300;remind.noRoute=true;remind.passenger=true;remind.seconds=14;
        remind.narration="내비게이션 경로가 없어도, 다음 운행 때 그 지점에 다가가면 위치를 기준으로 알립니다.";list.add(remind);
        list.add(card("outro","사람, 차량, 업무에 맞춰 말합니다","판단 기능은 가상 시연이며, Pleos Connect SDK 연동은 2차 심사 전까지 진행합니다",14,"길기억은 사람과 차량, 업무에 맞춰 말합니다. 판단 기능은 아직 가상 신호로 시연하며, Pleos Connect SDK 연동은 2차 심사 전까지 진행합니다."));
        return Collections.unmodifiableList(list);
    }
    public static List<Scene> scenes(){return SCENES;}
    public static double total(){double sum=0;for(Scene s:SCENES)sum+=s.seconds;return sum;}
    public static double startOf(int index){double sum=0;for(int i=0;i<index;i++)sum+=SCENES.get(i).seconds;return sum;}

    private static Map<String,Profile> seed() {
        Map<String,Profile> profiles=new HashMap<>();
        for(DriverType type:DriverType.values())profiles.put(type.name(),new Profile(type.name(),type.label,type));
        for(String who:Arrays.asList("starter","parent","child"))profiles.put(who,new Profile(who,who,DriverType.GENERAL));
        for(Place place:ROUTE_TO_HOSPITAL)DriveCoach.addMemo(profiles.get("parent"),place.id);
        Profile taxi=profiles.get(DriverType.TAXI.name()),truck=profiles.get(DriverType.TRUCK.name());
        DriveCoach.chooseWeak(taxi,TurnType.EXIT_LEFT);
        DriveCoach.addMemo(taxi,"exit-t");
        DriveCoach.chooseWeak(truck,TurnType.EXIT_RIGHT);
        DriveCoach.chooseWeak(truck,TurnType.NARROW_ROAD);
        return profiles;
    }
    private static Profile profileOf(Map<String,Profile> profiles,Scene s) {
        if(s.driver==null)return null;
        return profiles.get(s.who!=null?s.who:s.driver.name());
    }
    /** Parked moments run the real rules; the returned steps only say what happened and when. */
    private static List<Step> runParked(Scene s,Profile p) {
        switch(s.id) {
            case "setup":
                DriveCoach.chooseWeak(p,TurnType.ROUNDABOUT);
                return Arrays.asList(new Step(0,"시작","운전자와 차량을 고릅니다: 일반 · 승용차",false,false),new Step(2.6,"시작","어려운 유형을 직접 고릅니다: 회전교차로",false,true));
            case "lower":
                if(!DriveCoach.notePass(p,s.turn,0))throw new IllegalStateException("Lowering must follow three passes");
                DriveCoach.lowerType(p,s.turn.type);
                return Arrays.asList(new Step(0,"기록","왼쪽 출구를 연속 3회 무사히 통과했습니다",false,false),new Step(2.6,"정차 중 질문","왼쪽 출구 알림을 줄여도 될까요?",true,false),new Step(6.4,"기록","운전자가 승인해 약한 유형에서 내렸습니다",false,true));
            case "briefing":
                return Arrays.asList(new Step(0,"출발 전","목적지 병원 · 정차 중",false,false),new Step(2,"출발 전 브리핑",DriveCoach.briefing(p,ROUTE_TO_HOSPITAL,true,false).speech,true,false));
            case "comfort-offer":
                String ask=DriveCoach.comfortOffer(p,"hospital",true,false);
                DriveCoach.acceptComfort(p,"hospital");
                return Arrays.asList(new Step(0,"출발 전","목적지 병원 · 정차 중",false,false),new Step(1.8,"정차 중 질문",ask,true,false),new Step(7.2,"기록","운전자가 승인해 내비게이션 경로 옵션을 바꿉니다",false,true));
            case "erase":
                int count=p.placeCount();
                DriveCoach.clearMemory(p);
                return Arrays.asList(new Step(0,"프로필 관리","부모 프로필의 기억 지점 "+count+"곳",false,false),new Step(2.6,"프로필 관리","이 프로필의 기억만 지웠습니다",false,true));
            default:throw new IllegalStateException("Unknown parked scene");
        }
    }
    private static int metersAt(Scene s,double seconds){return (int)Math.round(s.from*Math.max(0,1-seconds/s.driveSeconds));}
    private static Signal signalAt(Scene s,int meters){return s.signalAt>0&&meters<=s.signalAt?s.signalSide:Signal.OFF;}
    private static Decision decision(Scene s,Profile p,int meters) {
        if(s.point!=null)return meters<=s.pointMeters?DriveCoach.decidePoint(p,s.point):new Decision(Action.WAIT,"too-far","",s.pointMeters);
        return DriveCoach.decide(p,s.turn,meters,signalAt(s,meters),s.passenger);
    }
    private static double alertSeconds(Scene s,Profile p) {
        if(s.from==0)return -1;
        for(int step=0;step*0.05<=s.driveSeconds;step++)
            if(decision(s,p,metersAt(s,step*0.05)).action==Action.SPEAK)return Math.round(step*5)/100.0;
        return -1;
    }
    private static double askSeconds(Scene s){return s.driveSeconds+REROUTE_SECONDS+(s.passenger?PASSENGER_LEAVES_SECONDS:0);}
    private static double narrationSeconds(Scene s) {
        if(s.card)return 0.6;
        if(s.parked)return s.narrateAt;
        if(s.memo!=null)return 2.4;
        return s.question!=null?askSeconds(s)+QUESTION_SECONDS:s.driveSeconds+RESULT_SECONDS;
    }
    private static void finish(Scene s,Profile p) {
        if(s.parked){runParked(s,p);return;}
        if(s.memo!=null)DriveCoach.addMemo(p,s.memo);
        if(s.comfort!=null)DriveCoach.noteHardMerge(p,s.comfort);
        if(s.outcome==Outcome.PASS)DriveCoach.notePass(p,s.turn,s.signalAt);
        if(s.outcome==Outcome.MISS||s.outcome==Outcome.DECLINE){DriveCoach.noteMiss(p,s.turn);DriveCoach.review(p,s.outcome==Outcome.MISS);}
    }
    private static void learned(State state,Profile p){state.weak=DriveCoach.weakLabels(p);state.places=p.placeCount();}

    public static State stateAt(double seconds) {
        Map<String,Profile> profiles=seed();
        double time=Math.min(Math.max(seconds,0),total()-0.001),start=0;
        for(int i=0;i<SCENES.size();i++) {
            Scene s=SCENES.get(i);
            Profile p=profileOf(profiles,s);
            if(time>=start+s.seconds){if(p!=null)finish(s,p);start+=s.seconds;continue;}
            State state=new State();state.scene=s;state.index=i;state.seconds=time-start;state.passenger=s.passenger;
            double u=state.seconds;
            if(s.card){state.phase=Phase.CARD;return state;}
            if(s.parked) {
                learned(state,p);
                state.phase=Phase.PARKED;
                boolean applied=false;
                for(Step step:runParked(s,p))if(u>=step.at){state.step=step;applied|=step.applies;}
                if(applied)learned(state,p);
                return state;
            }
            if(s.memo!=null) {
                boolean saved=u>=1.6;
                if(saved)DriveCoach.addMemo(p,s.memo);
                state.phase=saved?Phase.MEMO_SAVED:Phase.MEMO;learned(state,p);return state;
            }
            double alertAt=alertSeconds(s,p);
            state.meters=metersAt(s,Math.min(u,s.driveSeconds));
            state.signal=signalAt(s,state.meters);
            state.limit=s.turn!=null?DriveCoach.decisionMeters(p,s.turn):s.pointMeters;
            state.action=decision(s,p,state.meters).action;
            state.spoken=alertAt<0?"":decision(s,p,metersAt(s,alertAt)).speech;
            state.speaking=alertAt>=0&&u>=alertAt&&u<alertAt+SPEAK_SECONDS;
            learned(state,p);
            if(u>=s.driveSeconds) {
                if(s.question==null){state.phase=Phase.PASSED;return state;}
                double ask=askSeconds(s);
                state.passenger=s.passenger&&u<s.driveSeconds+REROUTE_SECONDS+PASSENGER_LEAVES_SECONDS*0.6;
                if(u<s.driveSeconds+REROUTE_SECONDS)state.phase=Phase.REROUTING;
                else if(u<ask)state.phase=Phase.QUESTION_WAITING;
                else if(u<ask+QUESTION_SECONDS)state.phase=Phase.QUESTION;
                else {
                    finish(s,p);state.phase=s.outcome==Outcome.MISS?Phase.LEARNED:Phase.DECLINED;learned(state,p);
                    state.limit=DriveCoach.decisionMeters(p,s.turn);
                }
            }
            return state;
        }
        throw new IllegalStateException("Timeline ended without a scene");
    }
    /** Timed speech in time order. */
    public static List<Event> events() {
        Map<String,Profile> profiles=seed();
        List<Event> list=new ArrayList<>();
        double start=0;
        for(Scene s:SCENES) {
            Profile p=profileOf(profiles,s);
            if(s.parked) {
                for(Step step:runParked(s,p))if(step.speak)list.add(new Event(start+step.at,true,step.text));
                list.add(new Event(start+narrationSeconds(s),false,s.narration));
                start+=s.seconds;
                continue;
            }
            if(p!=null&&s.memo==null) {
                double alertAt=alertSeconds(s,p);
                if(alertAt>=0)list.add(new Event(start+alertAt,true,decision(s,p,metersAt(s,alertAt)).speech));
            }
            if(s.question!=null)list.add(new Event(start+askSeconds(s),true,s.question));
            list.add(new Event(start+narrationSeconds(s),false,s.narration));
            if(p!=null)finish(s,p);
            start+=s.seconds;
        }
        list.sort(Comparator.comparingDouble(e->e.at));
        return list;
    }
}
