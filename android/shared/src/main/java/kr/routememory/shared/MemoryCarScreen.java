package kr.routememory.shared;

import android.graphics.*;
import android.view.Surface;
import androidx.annotation.NonNull;
import androidx.car.app.*;
import androidx.car.app.model.*;
import androidx.car.app.navigation.*;
import androidx.car.app.navigation.model.*;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.lifecycle.*;
import java.util.TimeZone;
import kr.routememory.core.Engine;

/** Host renders all controls; the app only draws its virtual route on the map surface. */
final class MemoryCarScreen extends Screen implements DefaultLifecycleObserver {
    private final DemoController state;
    private final NavigationManager nav;
    private final RoutePainter painter=new RoutePainter();
    private Surface surface;
    private Rect visible=new Rect();
    private boolean navActive,hostAttached;
    private final Runnable observer=this::refresh;
    MemoryCarScreen(CarContext context) {
        super(context); state=DemoController.get(context);
        nav=context.getCarService(NavigationManager.class);
        getLifecycle().addObserver(this); state.addListener(observer);
    }
    private void bindHost() {
        if(hostAttached)return;hostAttached=true;
        CarContext context=getCarContext();
        nav.setNavigationManagerCallback(ContextCompat.getMainExecutor(context),new NavigationManagerCallback(){
            @Override public void onStopNavigation() { state.stop(); }
            @Override public void onAutoDriveEnabled() {
                CarToast.makeText(context,"가상 경로 자동 주행이 준비되었습니다",CarToast.LENGTH_SHORT).show();
            }
        });
        context.getCarService(AppManager.class).setSurfaceCallback(new SurfaceCallback(){
            @Override public void onSurfaceAvailable(@NonNull SurfaceContainer sc) { surface=sc.getSurface(); draw(); }
            @Override public void onSurfaceDestroyed(@NonNull SurfaceContainer sc) { surface=null; }
            @Override public void onVisibleAreaChanged(@NonNull Rect area) { visible=new Rect(area); draw(); }
        });
    }
    @Override public void onStart(@NonNull LifecycleOwner owner) { bindHost(); refresh(); }
    @Override public void onStop(@NonNull LifecycleOwner owner) { unbindHost(); }
    private void unbindHost() {
        if(!hostAttached)return;hostAttached=false;
        if(navActive) { nav.navigationEnded(); navActive=false; }
        nav.clearNavigationManagerCallback();
        getCarContext().getCarService(AppManager.class).setSurfaceCallback(null);surface=null;
    }
    @Override public void onDestroy(@NonNull LifecycleOwner owner) {
        state.removeListener(observer); state.stop();
        unbindHost();
    }
    private void refresh() {
        if(!hostAttached)return;
        if(state.running()&&!navActive) { nav.navigationStarted(); navActive=true; }
        if(!state.running()&&navActive) { nav.navigationEnded(); navActive=false; }
        if(state.running()) {
            if(state.trip.status==Engine.Trip.Status.REROUTING)
                nav.updateTrip(new Trip.Builder().setLoading(true).build());
            else nav.updateTrip(new Trip.Builder().setCurrentRoad(state.trip.segment().name)
                .addStep(step(state.trip.segment()),estimate(state.trip.remaining)).build());
        }
        draw(); invalidate();
    }
    private void draw() {
        if(surface==null||!surface.isValid())return;
        Canvas c=null;
        try {
            c=surface.lockCanvas(null);
            Rect area=visible.isEmpty()?new Rect(0,0,c.getWidth(),c.getHeight()):visible;
            painter.draw(c,area,state);
        } catch(IllegalArgumentException|IllegalStateException ignored) {
            // Surface replacement can race with host callbacks; redraw on next frame.
        } finally { if(c!=null)surface.unlockCanvasAndPost(c); }
    }
    private Step step(Engine.Segment segment) {
        int type=(segment.kind==Engine.Kind.EXIT||segment.id.equals("simple-b"))
            ?Maneuver.TYPE_TURN_NORMAL_RIGHT:Maneuver.TYPE_STRAIGHT;
        String cue=state.trip!=null&&state.trip.announced?state.trip.message:"시연 · "+segment.name;
        return new Step.Builder(cue).setManeuver(new Maneuver.Builder(type).build()).build();
    }
    private TravelEstimate estimate(int meters) {
        long seconds=Math.max(1,meters/10);
        return new TravelEstimate.Builder(Distance.create(Math.max(0,meters),Distance.UNIT_METERS),
            DateTimeWithZone.create(System.currentTimeMillis()+seconds*1000,TimeZone.getDefault()))
            .setRemainingTimeSeconds(seconds).build();
    }
    @NonNull @Override public Template onGetTemplate() {
        if(state.running()) {
            NavigationTemplate.Builder b=new NavigationTemplate.Builder()
                .setActionStrip(new ActionStrip.Builder().addAction(new Action.Builder().setTitle("시연 종료")
                    .setOnClickListener(state::stop).build()).build())
                .setDestinationTravelEstimate(estimate(state.trip.metersLeft()));
            if(state.trip.status==Engine.Trip.Status.REROUTING)
                b.setNavigationInfo(new RoutingInfo.Builder().setLoading(true).build());
            else b.setNavigationInfo(new RoutingInfo.Builder().setCurrentStep(step(state.trip.segment()),
                Distance.create(state.trip.remaining,Distance.UNIT_METERS)).build());
            return b.build();
        }
        if(state.needsReview())return reviewTemplate();
        Pane.Builder p=new Pane.Builder()
            .addRow(new Row.Builder().setTitle("가상 경로 주행 시연")
                .addText("실제 길 안내 아님 · 약 45초 자동 진행").build())
            .addRow(new Row.Builder().setTitle(state.profile.name+" · 출구 안내 "+state.lead()+"m 전")
                .addText("확인한 출구 놓침 "+state.profile.misses("exit-a")+"회 · 처음에는 300m 전").build())
            .addRow(new Row.Builder().setTitle("선택: "+state.route.name)
                .addText(state.routeSummary(state.route)).build())
            .addAction(parkedAction("주행 시연",()->getScreenManager().push(new BriefScreen(getCarContext(),false))))
            .addAction(parkedAction("실수 학습 시연",()->getScreenManager().push(new BriefScreen(getCarContext(),true))));
        return new PaneTemplate.Builder(p.build()).setTitle("길기억 · 정차 중 준비")
            .setHeaderAction(Action.BACK)
            .setActionStrip(new ActionStrip.Builder()
                .addAction(parkedAction("실제 지도·GPS",()->getScreenManager().popToRoot()))
                .addAction(new Action.Builder().setIcon(new CarIcon.Builder(IconCompat.createWithResource(getCarContext(),android.R.drawable.ic_menu_manage)).build())
                    .setOnClickListener(ParkedOnlyOnClickListener.create(()->getScreenManager().push(new SettingsScreen(getCarContext())))).build()).build())
            .build();
    }
    private Template reviewTemplate() {
        Pane p=new Pane.Builder()
            .addRow(new Row.Builder().setTitle("시연이 끝났습니다")
                .addText("정차 후 확인하세요. 이탈을 실수로 단정하지 않습니다.").build())
            .addRow(new Row.Builder().setTitle("가상 한빛 분기점의 경로 이탈")
                .addText("출구를 놓친 경우에만 다음 안내를 150m 앞당깁니다.").build())
            .addAction(parkedAction("출구를 놓쳤어요",()->state.review(true)))
            .addAction(parkedAction("의도한 변경이에요",()->state.review(false))).build();
        return new PaneTemplate.Builder(p).setTitle("길기억 · 주행 돌아보기").setHeaderAction(Action.BACK).build();
    }
    private static Action parkedAction(String title,Runnable run) {
        return new Action.Builder().setTitle(title).setOnClickListener(ParkedOnlyOnClickListener.create(run::run)).build();
    }

    static final class BriefScreen extends Screen {
        private final DemoController s;
        private final boolean miss;
        BriefScreen(CarContext c,boolean miss) {super(c);s=DemoController.get(c);this.miss=miss;}
        @NonNull @Override public Template onGetTemplate() {
            Engine.Route r=miss?Engine.FAST:s.route;
            Pane.Builder brief=new Pane.Builder();
            for(Engine.Segment seg:r.segments)brief.addRow(new Row.Builder().setTitle(seg.name)
                .addText(seg.cue+" · "+s.engine.leadMeters(s.profile,seg)+"m 전").build());
            brief.addAction(parkedAction("시연 시작",()->{RoadController.get(getCarContext()).stopAll();s.start(miss);getScreenManager().popToRoot();}));
            return new PaneTemplate.Builder(brief.build()).setTitle("가상 구간 미리 보기 · 정차 중")
                .setHeaderAction(Action.BACK).build();
        }
    }

    private static final class SettingsScreen extends Screen {
        private final DemoController s;
        SettingsScreen(CarContext c) { super(c); s=DemoController.get(c); }
        private Row row(String title,String text,Runnable run) {
            return new Row.Builder().setTitle(title).addText(text)
                .setOnClickListener(ParkedOnlyOnClickListener.create(run::run)).build();
        }
        @NonNull @Override public Template onGetTemplate() {
            ItemList list=new ItemList.Builder()
                .addItem(row("실제 지도·GPS","지도 화면으로 돌아가기",()->getScreenManager().popToRoot()))
                .addItem(row("운전자 변경",s.profile.name,()->{ s.switchDriver(); invalidate(); }))
                .addItem(row("빠른 길 · 18분",s.routeSummary(Engine.FAST),()->{s.chooseRoute(Engine.FAST);getScreenManager().pop();}))
                .addItem(row("편안한 길 · 21분",s.routeSummary(Engine.EASY),()->{s.chooseRoute(Engine.EASY);getScreenManager().pop();}))
                .addItem(row("합류 구간 메모",s.profile.avoid.contains("merge-c")?"회피 요청됨 · 누르면 해제":"여기 합류하기 어려워 · 누르면 저장",()->{
                    boolean avoid=!s.profile.avoid.contains("merge-c"); s.setMergeMemo(avoid?"여기 합류하기 어려워":"",avoid);invalidate();
                }))
                .addItem(row("나의 기록",s.profile.completedTrips+"회 시연 · 추천: "+s.engine.recommend(s.profile).name,
                    ()->getScreenManager().push(new HistoryScreen(getCarContext())))).build();
            return new ListTemplate.Builder().setSingleList(list).setTitle("정차 중 설정")
                .setHeaderAction(Action.BACK).build();
        }
    }
    private static final class HistoryScreen extends Screen {
        private final DemoController s;
        HistoryScreen(CarContext c) { super(c);s=DemoController.get(c); }
        @NonNull @Override public Template onGetTemplate() {
            String memo=s.profile.notes.getOrDefault("merge-c","아직 메모가 없습니다");
            return new MessageTemplate.Builder(s.profile.name+"\n출구 놓침 "+s.profile.misses("exit-a")+"회\n다음 안내 "+s.lead()+"m 전\n합류 메모: "+memo)
                .setTitle("개인별 학습 기록").setHeaderAction(Action.BACK)
                .addAction(parkedAction("기록 삭제",()->getScreenManager().push(new ResetScreen(getCarContext())))).build();
        }
    }
    private static final class ResetScreen extends Screen {
        ResetScreen(CarContext c) { super(c); }
        @NonNull @Override public Template onGetTemplate() {
            return new MessageTemplate.Builder("현재 운전자의 시연 기록과 메모를 삭제할까요?")
                .setTitle("기록 삭제").setHeaderAction(Action.BACK)
                .addAction(parkedAction("삭제",()->{DemoController.get(getCarContext()).clearCurrentDriver();getScreenManager().popToRoot();}))
                .addAction(new Action.Builder().setTitle("취소").setOnClickListener(()->getScreenManager().pop()).build()).build();
        }
    }
}

