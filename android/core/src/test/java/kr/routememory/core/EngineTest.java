package kr.routememory.core;

public final class EngineTest {
    static int checks;
    static void check(boolean condition,String why) { checks++; if(!condition)throw new AssertionError(why); }
    public static void main(String[] args) {
        Engine e=new Engine(); Engine.Profile a=new Engine.Profile("a","A"), b=new Engine.Profile("b","B");
        check(e.leadMeters(a,Engine.FAST.segments.get(0))==300,"new driver timing");
        check(e.recommend(a)==Engine.FAST,"fresh driver fastest route");
        Engine.Trip t=new Engine.Trip(a,Engine.FAST,true);
        check(!t.review(true),"no training while moving");
        int alerts=0; boolean recovered=false;
        for(int n=0;n<100 && t.status!=Engine.Trip.Status.FINISHED;n++) {
            t.tick(e); if(t.speech.equals(Engine.FAST.segments.get(0).cue+"."))alerts++;
            if(t.status==Engine.Trip.Status.REROUTING)recovered=true;
        }
        check(t.status==Engine.Trip.Status.FINISHED,"bounded arrival");
        check(recovered,"calm reroute"); check(alerts==1,"no repeated cue spam");
        check(t.activeRoute==Engine.RECOVERY,"deviation selects a different virtual route");
        check(a.misses("exit-a")==0,"deviation is unconfirmed");
        check(t.review(true),"confirmed review accepted");
        check(!t.review(true)&&a.misses("exit-a")==1,"review is idempotent");
        check(e.leadMeters(a,Engine.FAST.segments.get(0))==450,"next drive earlier");
        check(e.leadMeters(b,Engine.FAST.segments.get(0))==300,"profile isolation");
        Engine.Trip intentional=new Engine.Trip(a,Engine.FAST,true);
        while(intentional.status!=Engine.Trip.Status.FINISHED)intentional.tick(e);
        intentional.review(false); check(a.misses("exit-a")==1,"intentional detour ignored");
        a.confirmedMisses.put("exit-a",99); check(e.leadMeters(a,Engine.FAST.segments.get(0))==900,"timing capped");
        int before=e.burden(b,Engine.FAST); b.avoid.add("merge-c");
        check(e.burden(b,Engine.FAST)>before,"memo changes route cost");
        check(e.recommend(b)==Engine.EASY,"avoided merge changes recommendation");
        Engine.Trip cancelled=new Engine.Trip(b,Engine.FAST,true); cancelled.cancel(); cancelled.tick(e);
        check(cancelled.status==Engine.Trip.Status.CANCELLED&&!cancelled.review(true),"cancel is terminal");
        Engine.Trip easy=new Engine.Trip(b,Engine.EASY,true);
        while(easy.status!=Engine.Trip.Status.FINISHED)easy.tick(e);
        check(easy.pending().isEmpty(),"injected exit never contaminates easy route");
        System.out.println("PASS: "+checks+" behavioral checks");
    }
}
