package kr.routememory.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Decides when the companion app speaks. Same rules as RouteMemory-PC src/coach.js; change both together. */
public final class DriveCoach {
    private static final int WEAK_AFTER=2,LOWER_AFTER=3,STEP_METERS=150,MAX_STEPS=4,BRIEF_MAX=3;
    private DriveCoach() {}

    public enum DriverType {
        GENERAL("일반",300,900,false),TAXI("택시",300,900,false),TRUCK("화물",600,1200,false),BUS("버스",450,900,true);
        public final String label; public final int baseMeters,maxMeters; public final boolean neutralOnly;
        DriverType(String label,int baseMeters,int maxMeters,boolean neutralOnly){this.label=label;this.baseMeters=baseMeters;this.maxMeters=maxMeters;this.neutralOnly=neutralOnly;}
    }
    public enum Signal {OFF,LEFT,RIGHT}
    public enum TurnType {
        EXIT_LEFT("왼쪽 출구","왼쪽 출구가",Signal.LEFT),EXIT_RIGHT("오른쪽 출구","오른쪽 출구가",Signal.RIGHT),
        KEEP_LEFT("왼쪽 분기","왼쪽 분기가",Signal.LEFT),KEEP_RIGHT("오른쪽 분기","오른쪽 분기가",Signal.RIGHT),
        TURN_LEFT("좌회전","좌회전이",Signal.LEFT),TURN_RIGHT("우회전","우회전이",Signal.RIGHT),
        ROUNDABOUT("회전교차로","회전교차로가",null),NARROW_ROAD("좁은 길","좁은 길이",null);
        public final String label,subject; public final Signal side;
        TurnType(String label,String subject,Signal side){this.label=label;this.subject=subject;this.side=side;}
    }
    public enum Action {NONE,WAIT,SILENT,SPEAK}
    public static final class Turn {
        public final String id; public final TurnType type;
        public Turn(String id,TurnType type){this.id=id;this.type=type;}
    }
    public static final class Decision {
        public final Action action; public final String reason,speech; public final int limit;
        Decision(Action action,String reason,String speech,int limit){this.action=action;this.reason=reason;this.speech=speech;this.limit=limit;}
    }
    public static final class Review {
        public final TurnType type; public final boolean confirmed,becameWeak;
        Review(TurnType type,boolean confirmed,boolean becameWeak){this.type=type;this.confirmed=confirmed;this.becameWeak=becameWeak;}
    }
    public static final class Place {
        public final String id,label;
        public Place(String id,String label){this.id=id;this.label=label;}
    }
    public static final class Briefing {
        public final List<Place> points; public final String speech;
        Briefing(List<Place> points,String speech){this.points=points;this.speech=speech;}
    }
    public static final class Profile {
        public final String id,name; public final DriverType driverType;
        final Map<TurnType,Integer> typeMisses=new EnumMap<>(TurnType.class),passStreak=new EnumMap<>(TurnType.class);
        final Set<TurnType> chosenWeak=EnumSet.noneOf(TurnType.class);
        final Set<String> places=new HashSet<>();
        final ArrayDeque<Turn> pending=new ArrayDeque<>();
        final Set<String> comfortAsks=new HashSet<>(),comfortRoutes=new HashSet<>();
        public int habitMeters,habitSamples;
        public Profile(String id,String name,DriverType driverType){this.id=id;this.name=name;this.driverType=driverType;}
        public int misses(TurnType type){return typeMisses.getOrDefault(type,0);}
        public int placeCount(){return places.size();}
        public int pendingCount(){return pending.size();}
    }
    public static void chooseWeak(Profile p,TurnType type){p.chosenWeak.add(type);}
    public static boolean isWeak(Profile p,TurnType type){return p.chosenWeak.contains(type)||p.misses(type)>=WEAK_AFTER;}
    public static boolean remembers(Profile p,String placeId){return p.places.contains(placeId);}
    public static List<String> weakLabels(Profile p) {
        List<String> labels=new ArrayList<>();
        for(TurnType type:TurnType.values())if(isWeak(p,type))labels.add(type.label);
        return labels;
    }
    public static int decisionMeters(Profile p,Turn turn) {
        int start=Math.max(p.driverType.baseMeters,p.habitMeters);
        return Math.min(p.driverType.maxMeters,start+Math.min(MAX_STEPS,p.misses(turn.type))*STEP_METERS);
    }
    private static String speechFor(Profile p,Turn turn,boolean passenger) {
        if(p.driverType.neutralOnly||passenger)return turn.type.label+" 준비하세요.";
        if(remembers(p,turn.id))return "지난번 놓친 "+turn.type.label+"예요. 미리 준비하세요.";
        return turn.type.subject+" 곧 나옵니다. 미리 준비하세요.";
    }
    public static Decision decide(Profile p,Turn turn,int meters,Signal signal,boolean passenger) {
        int limit=decisionMeters(p,turn);
        if(!isWeak(p,turn.type)&&!remembers(p,turn.id))return new Decision(Action.NONE,"not-weak","",limit);
        if(meters>limit)return new Decision(Action.WAIT,"too-far","",limit);
        if(turn.type.side!=null&&signal==turn.type.side)return new Decision(Action.SILENT,"prepared","",limit);
        return new Decision(Action.SPEAK,signal==Signal.OFF?"signal-off":"needs-attention",speechFor(p,turn,passenger),limit);
    }
    /** Works without an active route, for drivers such as route buses. */
    public static Decision decidePoint(Profile p,String placeId) {
        if(!remembers(p,placeId))return new Decision(Action.NONE,"unknown-place","",0);
        return new Decision(Action.SPEAK,"memory-point","헷갈렸던 지점입니다. 미리 확인하세요.",0);
    }
    public static void addMemo(Profile p,String placeId){p.places.add(placeId);}
    public static void noteMiss(Profile p,Turn turn) {
        for(Turn item:p.pending)if(item.id.equals(turn.id))return;
        p.pending.add(turn);
    }
    /** A question about a past mistake is never asked in front of a passenger or while moving. */
    public static boolean canReview(Profile p,boolean parked,boolean passenger){return !p.pending.isEmpty()&&parked&&!passenger;}
    public static Review review(Profile p,boolean confirmed) {
        Turn item=p.pending.poll();
        if(item==null)return null;
        boolean wasWeak=isWeak(p,item.type);
        if(confirmed) {
            p.typeMisses.put(item.type,p.misses(item.type)+1);
            p.passStreak.put(item.type,0);
            addMemo(p,item.id);
        }
        return new Review(item.type,confirmed,!wasWeak&&isWeak(p,item.type));
    }
    /** Returns true when the driver may be offered to lower this weak type. */
    public static boolean notePass(Profile p,Turn turn,int signalOnMeters) {
        int streak=p.passStreak.getOrDefault(turn.type,0)+1;
        p.passStreak.put(turn.type,streak);
        if(signalOnMeters>0) {
            p.habitMeters=Math.round((p.habitMeters*(float)p.habitSamples+signalOnMeters)/(p.habitSamples+1));
            p.habitSamples++;
        }
        return isWeak(p,turn.type)&&streak>=LOWER_AFTER;
    }
    public static void lowerType(Profile p,TurnType type){p.chosenWeak.remove(type);p.typeMisses.remove(type);p.passStreak.remove(type);}
    /** What a profile learned, one fact per line, so it can be kept on the device. */
    public static String snapshot(Profile p) {
        StringBuilder b=new StringBuilder();
        for(Map.Entry<TurnType,Integer> e:p.typeMisses.entrySet())b.append("miss ").append(e.getKey().name()).append(' ').append(e.getValue()).append('\n');
        for(Map.Entry<TurnType,Integer> e:p.passStreak.entrySet())b.append("streak ").append(e.getKey().name()).append(' ').append(e.getValue()).append('\n');
        for(TurnType t:p.chosenWeak)b.append("weak ").append(t.name()).append('\n');
        for(String id:p.places)b.append("place ").append(id.replace('\n',' ')).append('\n');
        b.append("habit ").append(p.habitMeters).append(' ').append(p.habitSamples).append('\n');
        return b.toString();
    }
    /** Reads a snapshot back; lines it does not understand are skipped rather than trusted. */
    public static void restore(Profile p,String text) {
        if(text==null)return;
        for(String line:text.split("\n")) {
            try {
                if(line.startsWith("miss ")){String[] f=line.split(" ",3);p.typeMisses.put(TurnType.valueOf(f[1]),Math.max(0,Integer.parseInt(f[2])));}
                else if(line.startsWith("streak ")){String[] f=line.split(" ",3);p.passStreak.put(TurnType.valueOf(f[1]),Math.max(0,Integer.parseInt(f[2])));}
                else if(line.startsWith("weak "))p.chosenWeak.add(TurnType.valueOf(line.substring(5)));
                else if(line.startsWith("place ")&&line.length()>6)p.places.add(line.substring(6));
                else if(line.startsWith("habit ")){String[] f=line.split(" ",3);p.habitMeters=Math.max(0,Integer.parseInt(f[1]));p.habitSamples=Math.max(0,Integer.parseInt(f[2]));}
            } catch(RuntimeException ignored){}
        }
    }
    /** Read before departure, and only for remembered points on this route. */
    public static Briefing briefing(Profile p,List<Place> route,boolean parked,boolean passenger) {
        List<Place> points=new ArrayList<>();
        if(!parked||passenger)return new Briefing(points,"");
        for(Place place:route)if(remembers(p,place.id)&&points.size()<BRIEF_MAX)points.add(place);
        if(points.isEmpty())return new Briefing(points,"");
        List<String> labels=new ArrayList<>();
        for(Place place:points)labels.add(place.label);
        return new Briefing(points,"이번 길에 기억해 둔 곳이 "+points.size()+"곳 있습니다. "+String.join(", ",labels)+"입니다.");
    }
    public static void noteHardMerge(Profile p,String destination){p.comfortAsks.add(destination);}
    public static boolean usesComfortRoute(Profile p,String destination){return p.comfortRoutes.contains(destination);}
    public static String comfortOffer(Profile p,String destination,boolean parked,boolean passenger) {
        if(!parked||passenger||!p.comfortAsks.contains(destination)||usesComfortRoute(p,destination))return "";
        return "지난번 어려웠던 합류를 피하는 편한 길로 바꿀까요?";
    }
    public static void acceptComfort(Profile p,String destination){p.comfortRoutes.add(destination);}
    /** Erases what this profile learned. Other profiles keep theirs. */
    public static void clearMemory(Profile p) {
        p.typeMisses.clear();p.passStreak.clear();p.chosenWeak.clear();p.places.clear();p.pending.clear();p.comfortAsks.clear();p.comfortRoutes.clear();
        p.habitMeters=0;p.habitSamples=0;
    }
}
