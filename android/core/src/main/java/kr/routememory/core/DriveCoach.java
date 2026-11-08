package kr.routememory.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Decides when the companion app speaks. Same rules as RouteMemory-PC src/coach.js; change both together. */
public final class DriveCoach {
    private static final int WEAK_AFTER=2,LOWER_AFTER=3,STEP_METERS=150,MAX_STEPS=4,BRIEF_MAX=3;
    /**
     * The decision distance grows with speed: the DRIVE II simulator work put the last turn message about 137 m ahead
     * at 64 km/h and further out for every km/h more, and the AR-HUD study on multi-branch roads found 1.5 km best.
     * The distance is scaled around 60 km/h, between half and double, and a road never driven before adds 30%.
     */
    public static final int SPEED_REF_KPH=60; static final double SPEED_MIN=0.5,SPEED_MAX=2.0,NEW_ROAD_FACTOR=1.3;
    /** A road cell driven on this many separate trips is familiar; weak types stay quiet there, remembered spots do not. */
    public static final int FAMILIAR_AFTER=3;
    /** Lane changes are signalled only about two thirds of the time (ensemble LSTM study); a driver who rarely signals
     *  gets no "signal off" sentence for weak types, because the signal says nothing about their preparation. */
    public static final int SIGNAL_SAMPLES=5; static final double SIGNAL_RATE_MIN=0.3;
    /** Under load (fast, or another turn right behind) the sentence is cut to its core (AdaptiveVoice, CHI 2024). */
    public static final int BRIEF_SPEED_KPH=80,BRIEF_GAP_METERS=500;
    public static final String LOWER_QUESTION="같은 유형을 세 번 잘 지나셨어요. 이 유형 알림을 줄일까요?";
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
    /** How well this driver knows the road under the car. UNKNOWN when no position has been seen. */
    public enum Familiarity {UNKNOWN,NEW,KNOWN,FAMILIAR}
    public enum Action {NONE,WAIT,SILENT,SPEAK}
    public static final class Turn {
        public final String id; public final TurnType type;
        /** What the navigation adds to the turn, said inside the sentence: "3시 방향 출구", "두 번째 출구", a road name. */
        public final String detail;
        public Turn(String id,TurnType type){this(id,type,null);}
        public Turn(String id,TurnType type,String detail){this.id=id;this.type=type;this.detail=detail==null||detail.trim().isEmpty()?null:detail.trim();}
    }
    /** Everything the car knows at the moment of a decision. Fields the car cannot give stay at their defaults. */
    public static final class Context {
        public int meters; public Signal signal=Signal.OFF; public boolean passenger;
        public int speedKph=-1; public Familiarity familiarity=Familiarity.UNKNOWN; public boolean brief;
        public Context(int meters,Signal signal,boolean passenger){this.meters=meters;this.signal=signal;this.passenger=passenger;}
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
        /** Turns passed, and how many of them the driver signalled for: the driver's own signalling rate. */
        public int signalTurns,signalOnTurns;
        /** Remembered places grouped by the destination they were learned on, and a spoken label per place. */
        final Map<String,Set<String>> destPlaces=new HashMap<>(); final Map<String,String> placeLabels=new HashMap<>();
        /** Road cells (about 100 m) and on how many trips each was driven. */
        final Map<String,Integer> roadCells=new HashMap<>();
        public Profile(String id,String name,DriverType driverType){this.id=id;this.name=name;this.driverType=driverType;}
        public int misses(TurnType type){return typeMisses.getOrDefault(type,0);}
        public int placeCount(){return places.size();}
        public int pendingCount(){return pending.size();}
        public int roadCellCount(){return roadCells.size();}
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
    /** The decision distance for the speed the car is doing and the road it is on; -1 speed means unknown. */
    public static int decisionMeters(Profile p,Turn turn,int speedKph,Familiarity familiarity) {
        double factor=speedKph<0?1:Math.max(SPEED_MIN,Math.min(SPEED_MAX,speedKph/(double)SPEED_REF_KPH));
        if(familiarity==Familiarity.NEW)factor*=NEW_ROAD_FACTOR;
        int meters=(int)Math.round(decisionMeters(p,turn)*factor);
        return Math.max(100,Math.min(p.driverType.maxMeters*2,meters));
    }
    /** The driver's own signalling rate over passed turns, or -1 until there are enough. */
    public static double signalRate(Profile p){return p.signalTurns<SIGNAL_SAMPLES?-1:p.signalOnTurns/(double)p.signalTurns;}
    public static boolean rarelySignals(Profile p){double rate=signalRate(p);return rate>=0&&rate<SIGNAL_RATE_MIN;}
    static String speechFor(Profile p,Turn turn,boolean passenger,boolean brief) {
        String detail=turn.detail;
        if(brief)return detail!=null?turn.type.label+", "+detail+".":turn.type.label+" 준비.";
        String with=detail!=null?detail+"입니다. ":"";
        if(p.driverType.neutralOnly||passenger)return turn.type.label+" 준비하세요."+(detail!=null?" "+detail+"입니다.":"");
        if(remembers(p,turn.id))return "지난번 놓친 "+turn.type.label+"예요. "+with+"미리 준비하세요.";
        return turn.type.subject+" 곧 나옵니다. "+with+"미리 준비하세요.";
    }
    public static Decision decide(Profile p,Turn turn,int meters,Signal signal,boolean passenger){return decide(p,turn,new Context(meters,signal,passenger));}
    public static Decision decide(Profile p,Turn turn,Context c) {
        int limit=decisionMeters(p,turn,c.speedKph,c.familiarity);
        boolean remembered=remembers(p,turn.id);
        if(!isWeak(p,turn.type)&&!remembered)return new Decision(Action.NONE,"not-weak","",limit);
        // A road driven many times is known; only a spot actually missed there is worth a word.
        if(!remembered&&c.familiarity==Familiarity.FAMILIAR)return new Decision(Action.NONE,"familiar-road","",limit);
        if(c.meters>limit)return new Decision(Action.WAIT,"too-far","",limit);
        if(turn.type.side!=null&&c.signal==turn.type.side)return new Decision(Action.SILENT,"prepared","",limit);
        if(!remembered&&turn.type.side!=null&&rarelySignals(p))return new Decision(Action.SILENT,"rarely-signals","",limit);
        return new Decision(Action.SPEAK,c.signal==Signal.OFF?"signal-off":"needs-attention",speechFor(p,turn,c.passenger,c.brief),limit);
    }
    /** Works without an active route, for drivers such as route buses. */
    public static Decision decidePoint(Profile p,String placeId) {
        if(!remembers(p,placeId))return new Decision(Action.NONE,"unknown-place","",0);
        return new Decision(Action.SPEAK,"memory-point","헷갈렸던 지점입니다. 미리 확인하세요.",0);
    }
    public static void addMemo(Profile p,String placeId){p.places.add(placeId);}
    public static void labelPlace(Profile p,String placeId,String label){if(label!=null&&!label.trim().isEmpty())p.placeLabels.put(placeId,label.trim().replace('\t',' ').replace('\n',' '));}
    public static String placeLabel(Profile p,String placeId) {
        String label=p.placeLabels.get(placeId);
        if(label!=null)return label;
        int bar=placeId.indexOf('|');
        String head=bar<0?placeId:placeId.substring(0,bar),tail=bar<0?"":placeId.substring(bar+1).trim();
        for(TurnType t:TurnType.values())if(t.name().equalsIgnoreCase(head))return tail.isEmpty()?t.label:tail+" "+t.label;
        return tail.isEmpty()?placeId:tail;
    }
    /** A place is tied to the destination it was learned on, so the next trip there can start with it. */
    public static void rememberFor(Profile p,String destination,String placeId) {
        if(destination==null||destination.trim().isEmpty())return;
        p.destPlaces.computeIfAbsent(destination.trim(),k->new LinkedHashSet<>()).add(placeId);
    }
    public static List<Place> placesFor(Profile p,String destination) {
        List<Place> out=new ArrayList<>();
        Set<String> ids=destination==null?null:p.destPlaces.get(destination.trim());
        if(ids!=null)for(String id:ids)if(remembers(p,id))out.add(new Place(id,placeLabel(p,id)));
        return out;
    }
    public static void noteMiss(Profile p,Turn turn) {
        for(Turn item:p.pending)if(item.id.equals(turn.id))return;
        p.pending.add(turn);
    }
    /** A question about a past mistake is never asked in front of a passenger or while moving. */
    public static boolean canReview(Profile p,boolean parked,boolean passenger){return !p.pending.isEmpty()&&parked&&!passenger;}
    public static Review review(Profile p,boolean confirmed){return review(p,confirmed,null);}
    public static Review review(Profile p,boolean confirmed,String destination) {
        Turn item=p.pending.poll();
        if(item==null)return null;
        boolean wasWeak=isWeak(p,item.type);
        if(confirmed) {
            p.typeMisses.put(item.type,p.misses(item.type)+1);
            p.passStreak.put(item.type,0);
            addMemo(p,item.id);
            if(item.detail!=null)labelPlace(p,item.id,item.detail+" "+item.type.label);
            rememberFor(p,destination,item.id);
        }
        return new Review(item.type,confirmed,!wasWeak&&isWeak(p,item.type));
    }
    /** Returns true when the driver may be offered to lower this weak type. */
    public static boolean notePass(Profile p,Turn turn,int signalOnMeters) {
        int streak=p.passStreak.getOrDefault(turn.type,0)+1;
        p.passStreak.put(turn.type,streak);
        if(turn.type.side!=null){p.signalTurns++;if(signalOnMeters>0)p.signalOnTurns++;}
        if(signalOnMeters>0) {
            p.habitMeters=Math.round((p.habitMeters*(float)p.habitSamples+signalOnMeters)/(p.habitSamples+1));
            p.habitSamples++;
        }
        return isWeak(p,turn.type)&&streak>=LOWER_AFTER;
    }
    public static void lowerType(Profile p,TurnType type){p.chosenWeak.remove(type);p.typeMisses.remove(type);p.passStreak.remove(type);}

    // Road familiarity: the world in cells of about 100 m, each counted once per trip.
    public static String cellKey(double lat,double lon) {
        double step=0.0009;
        long la=(long)Math.floor(lat/step),lo=(long)Math.floor(lon*Math.cos(Math.toRadians(lat))/step);
        return la+","+lo;
    }
    public static void noteRoad(Profile p,String cell){p.roadCells.put(cell,p.roadCells.getOrDefault(cell,0)+1);}
    public static Familiarity familiarity(Profile p,String cell) {
        int n=p.roadCells.getOrDefault(cell,0);
        return n==0?Familiarity.NEW:n<FAMILIAR_AFTER?Familiarity.KNOWN:Familiarity.FAMILIAR;
    }
    /** One sentence when the car is parked after a trip; nothing when there was nothing to watch. */
    public static String summarySpeech(int watched,int passed,int missCandidates,int signalOff) {
        if(watched==0&&signalOff==0)return "";
        StringBuilder b=new StringBuilder();
        if(watched>0)b.append("오늘 지켜본 회전 ").append(watched).append("곳 중 잘 지난 곳 ").append(passed).append(", 놓침 후보 ").append(missCandidates).append("입니다.");
        if(signalOff>0)b.append(watched>0?" ":"").append("방향지시등 없이 지난 회전이 ").append(signalOff).append("번 있었어요.");
        return b.toString();
    }

    /** What a profile learned, one fact per line, so it can be kept on the device. */
    public static String snapshot(Profile p) {
        StringBuilder b=new StringBuilder();
        for(Map.Entry<TurnType,Integer> e:p.typeMisses.entrySet())b.append("miss ").append(e.getKey().name()).append(' ').append(e.getValue()).append('\n');
        for(Map.Entry<TurnType,Integer> e:p.passStreak.entrySet())b.append("streak ").append(e.getKey().name()).append(' ').append(e.getValue()).append('\n');
        for(TurnType t:p.chosenWeak)b.append("weak ").append(t.name()).append('\n');
        for(String id:p.places)b.append("place ").append(id.replace('\n',' ')).append('\n');
        b.append("habit ").append(p.habitMeters).append(' ').append(p.habitSamples).append('\n');
        b.append("signal ").append(p.signalTurns).append(' ').append(p.signalOnTurns).append('\n');
        for(Map.Entry<String,Set<String>> e:p.destPlaces.entrySet())for(String id:e.getValue())b.append("dest ").append(e.getKey().replace('\t',' ')).append('\t').append(id.replace('\n',' ')).append('\n');
        for(Map.Entry<String,String> e:p.placeLabels.entrySet())b.append("label ").append(e.getKey().replace('\t',' ')).append('\t').append(e.getValue()).append('\n');
        for(Map.Entry<String,Integer> e:p.roadCells.entrySet())b.append("cell ").append(e.getKey()).append(' ').append(e.getValue()).append('\n');
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
                else if(line.startsWith("signal ")){String[] f=line.split(" ",3);p.signalTurns=Math.max(0,Integer.parseInt(f[1]));p.signalOnTurns=Math.max(0,Math.min(p.signalTurns,Integer.parseInt(f[2])));}
                else if(line.startsWith("dest ")){String[] f=line.substring(5).split("\t",2);if(f.length==2&&!f[0].isEmpty()&&!f[1].isEmpty())rememberFor(p,f[0],f[1]);}
                else if(line.startsWith("label ")){String[] f=line.substring(6).split("\t",2);if(f.length==2&&!f[0].isEmpty())labelPlace(p,f[0],f[1]);}
                else if(line.startsWith("cell ")){String[] f=line.split(" ",3);int n=Integer.parseInt(f[2]);if(n>0&&f[1].matches("-?\\d+,-?\\d+"))p.roadCells.put(f[1],n);}
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
    /** The briefing for a destination the navigation just received, from what was learned on earlier trips there. */
    public static Briefing briefingFor(Profile p,String destination,boolean parked,boolean passenger){return briefing(p,placesFor(p,destination),parked,passenger);}
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
        p.destPlaces.clear();p.placeLabels.clear();p.roadCells.clear();
        p.habitMeters=0;p.habitSamples=0;p.signalTurns=0;p.signalOnTurns=0;
    }
}
