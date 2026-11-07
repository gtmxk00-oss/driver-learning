package kr.routememory.core;

import java.util.*;

/** Deterministic, inspectable personalization. No camera, CAN data, GPS or ML claim. */
public final class Engine {
    public enum Kind { EXIT, SEQUENCE, MERGE, SIMPLE }
    public static final class Segment {
        public final String id, name, cue;
        public final Kind kind;
        public final int meters;
        public Segment(String id, String name, Kind kind, int meters, String cue) {
            this.id=id; this.name=name; this.kind=kind; this.meters=meters; this.cue=cue;
        }
    }
    public static final class Route {
        public final String id, name;
        public final int minutes;
        public final List<Segment> segments;
        public Route(String id, String name, int minutes, Segment... segments) {
            this.id=id; this.name=name; this.minutes=minutes;
            this.segments=Collections.unmodifiableList(Arrays.asList(segments));
        }
        public int meters() { int n=0; for (Segment s: segments) n+=s.meters; return n; }
        public int complexCount() { int n=0; for (Segment s: segments) if(s.kind!=Kind.SIMPLE)n++; return n; }
    }
    public static final class Profile {
        public final String id, name;
        public final Map<String,Integer> confirmedMisses = new HashMap<>();
        public final Set<String> avoid = new HashSet<>();
        public final Map<String,String> notes = new HashMap<>();
        public int completedTrips;
        public Profile(String id, String name) { this.id=id; this.name=name; }
        public int misses(String segment) { return Math.max(0,confirmedMisses.getOrDefault(segment,0)); }
    }
    public static final Route FAST = new Route("fast","빠른 길",18,
        new Segment("exit-a","가상 한빛 분기점",Kind.EXIT,1200,"이번 분기점은 오른쪽 도로입니다"),
        new Segment("fork-b","가상 두빛 갈림길",Kind.SEQUENCE,900,"첫 번째는 직진, 다음 갈림길에서 오른쪽입니다"),
        new Segment("merge-c","가상 새빛 합류",Kind.MERGE,1000,"합류 구간입니다. 주변 교통을 확인하세요"));
    public static final Route EASY = new Route("easy","편안한 길",21,
        new Segment("simple-a","가상 중앙대로",Kind.SIMPLE,1600,"다음 교차로까지 직진입니다"),
        new Segment("simple-b","가상 공원 입구",Kind.SIMPLE,1800,"다음 교차로에서 오른쪽입니다"));
    public static final Route RECOVERY = new Route("recovery","시연 대체 경로",8,
        new Segment("recovery-a","가상 우회 연결로",Kind.SIMPLE,700,"다음 교차로까지 직진입니다"),
        new Segment("recovery-b","가상 공원 연결로",Kind.SIMPLE,900,"다음 교차로에서 오른쪽입니다"));
    public static List<Route> routes() { return Arrays.asList(FAST,EASY); }

    public int leadMeters(Profile p, Segment s) {
        int base=s.kind==Kind.SEQUENCE?450:300;
        return Math.min(900,base+Math.min(4,p.misses(s.id))*150);
    }
    public String guidance(Profile p, Segment s) {
        // A sequence is always explained; personal history changes timing, not road facts.
        return s.cue+".";
    }
    public int burden(Profile p, Route r) {
        int result=0;
        for(Segment s:r.segments) {
            result += s.kind==Kind.SIMPLE?0:2;
            result += Math.min(4,p.misses(s.id))*2;
            if(p.avoid.contains(s.id)) result+=12;
        }
        return result;
    }
    public Route recommend(Profile p) {
        // Explicit demo trade-off: a burden point has a cost of 0.4 virtual minutes.
        return FAST.minutes+0.4*burden(p,FAST) <= EASY.minutes+0.4*burden(p,EASY)?FAST:EASY;
    }

    public static final class Trip {
        public enum Status { RUNNING, REROUTING, FINISHED, CANCELLED }
        public final Profile driver;
        public final Route route;
        public Route activeRoute;
        public final boolean injectMiss;
        public Status status=Status.RUNNING;
        public int index=0, remaining, tickCount=0;
        public String message="시연을 시작합니다", speech="";
        public boolean announced=false;
        private int recoveryTicks=0;
        private final Set<String> pending = new LinkedHashSet<>();
        private boolean reviewed=false;
        public Trip(Profile p, Route r, boolean inject) {
            driver=p; route=r; activeRoute=r; injectMiss=inject; remaining=r.segments.get(0).meters;
        }
        public Segment segment() { return activeRoute.segments.get(Math.min(index,activeRoute.segments.size()-1)); }
        public Set<String> pending() { return Collections.unmodifiableSet(pending); }
        public int metersLeft() {
            if(status==Status.FINISHED)return 0;
            int n=remaining; for(int j=index+1;j<activeRoute.segments.size();j++)n+=activeRoute.segments.get(j).meters;
            return n;
        }
        /** One tick is 100 virtual meters; never interpreted as physical vehicle speed. */
        public void tick(Engine engine) {
            speech="";
            if(status==Status.FINISHED||status==Status.CANCELLED)return;
            tickCount++;
            if(status==Status.REROUTING) {
                if(--recoveryTicks<=0) {
                    status=Status.RUNNING; activeRoute=RECOVERY; index=0;
                    remaining=segment().meters; announced=false; message="시연 대체 경로로 연결했습니다";
                }
                return;
            }
            remaining=Math.max(0,remaining-100);
            if(!announced && remaining<=engine.leadMeters(driver,segment())) {
                announced=true; message=engine.guidance(driver,segment()); speech=message;
            }
            if(remaining==0) {
                if(injectMiss && index==0 && activeRoute==FAST) {
                    pending.add(segment().id);
                    status=Status.REROUTING; recoveryTicks=3;
                    message="다음 경로로 안내하겠습니다."; speech=message;
                } else advance();
            }
        }
        private void advance() {
            index++;
            if(index>=activeRoute.segments.size()) {
                index=activeRoute.segments.size()-1; remaining=0;
                status=Status.FINISHED; driver.completedTrips++;
                message="도착했습니다. 정차 후 주행을 돌아보세요."; speech=message;
            } else {
                remaining=segment().meters; announced=false;
                message="다음 구간: "+segment().name;
            }
        }
        public void cancel() { if(status==Status.RUNNING||status==Status.REROUTING) { status=Status.CANCELLED; speech=""; message="시연을 종료했습니다."; } }
        /** Applied once, only after completed driving; intentional detours never train mistakes. */
        public boolean review(boolean wasMistake) {
            if(status!=Status.FINISHED||reviewed)return false;
            reviewed=true;
            if(wasMistake)for(String id:pending)driver.confirmedMisses.put(id,Math.min(100,driver.misses(id)+1));
            return true;
        }
    }
}
