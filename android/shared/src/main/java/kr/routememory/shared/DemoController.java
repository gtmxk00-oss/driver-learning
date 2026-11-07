package kr.routememory.shared;

import android.content.Context;
import android.os.*;
import java.util.*;
import kr.routememory.core.Engine;

/** Shared state across phone and car, driven only by a virtual route clock. */
public final class DemoController {
    private static DemoController instance;
    public static synchronized DemoController get(Context c) {
        if(instance==null)instance=new DemoController(c.getApplicationContext());
        return instance;
    }
    public final Engine engine=new Engine();
    public Engine.Profile profile;
    public Engine.Route route=Engine.FAST;
    public Engine.Trip trip;
    public String notice="";
    private final ProfileStore store;
    private final GuidanceSpeaker speaker;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Set<Runnable> listeners=new LinkedHashSet<>();
    private boolean paused;
    private final Runnable clock=new Runnable(){ public void run(){
        if(!running()||paused)return;
        trip.tick(engine);
        if(!trip.speech.isEmpty())speaker.speak(trip.speech);
        if(trip.status==Engine.Trip.Status.FINISHED)store.save(profile);
        changed();
        if(running())handler.postDelayed(this,1200);
    }};
    private final Context context;
    private DemoController(Context c) { context=c;store=new ProfileStore(c); profile=store.load(store.active()); speaker=new GuidanceSpeaker(c); }
    public boolean running() { return trip!=null&&(trip.status==Engine.Trip.Status.RUNNING||trip.status==Engine.Trip.Status.REROUTING); }
    public boolean needsReview() { return trip!=null&&trip.status==Engine.Trip.Status.FINISHED&&!trip.pending().isEmpty(); }
    public void addListener(Runnable r) { listeners.add(r); }
    public void removeListener(Runnable r) { listeners.remove(r); }
    private void changed() { for(Runnable r:new ArrayList<>(listeners))r.run(); }
    public boolean isPaused() { return paused; }
    public void start(boolean simulateMiss) {
        if(running()||needsReview())return;
        if(RoadController.get(context).gpsEnabled){notice="실제 길 안내 화면에서 GPS를 종료한 뒤 시연하세요";changed();return;}
        if(simulateMiss)route=Engine.FAST;
        trip=new Engine.Trip(profile,route,simulateMiss); paused=false; notice="";
        changed(); handler.removeCallbacks(clock); handler.postDelayed(clock,1200);
    }
    public void pause(boolean value) {
        paused=value; handler.removeCallbacks(clock); speaker.stop();
        if(running()&&!paused)handler.postDelayed(clock,1200);
        changed();
    }
    public void stop() { handler.removeCallbacks(clock); speaker.stop(); if(trip!=null)trip.cancel(); changed(); }
    public void review(boolean mistake) {
        if(trip!=null&&trip.review(mistake)) {
            store.save(profile); trip=null; notice=mistake?"확인한 실수를 다음 주행에 반영했습니다":"의도한 경로 변경은 실수로 학습하지 않았습니다"; changed();
        }
    }
    public void chooseRoute(Engine.Route r) { if(!running()&&!needsReview()){ route=r; changed(); } }
    public void switchDriver() {
        if(running()||needsReview())return;
        profile=store.load(profile.id.equals("me")?"guest":"me"); store.setActive(profile.id); trip=null; changed();
    }
    public void setMergeMemo(String memo,boolean avoid) {
        if(running()||needsReview())return;
        String clean=memo.trim(); if(clean.length()>200)clean=clean.substring(0,200);
        profile.notes.put("merge-c",clean);
        if(avoid)profile.avoid.add("merge-c"); else profile.avoid.remove("merge-c");
        store.save(profile); changed();
    }
    public void clearCurrentDriver() {
        if(running()||needsReview())return;
        profile=new Engine.Profile(profile.id,profile.name); store.save(profile); trip=null; changed();
    }
    public int lead() { return engine.leadMeters(profile,Engine.FAST.segments.get(0)); }
    public String routeSummary(Engine.Route r) {
        return r.minutes+"분 · 복잡한 구간 "+r.complexCount()+"곳 · 개인 부담 "+engine.burden(profile,r);
    }
}
