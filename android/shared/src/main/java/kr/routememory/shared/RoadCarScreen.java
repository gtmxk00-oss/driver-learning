package kr.routememory.shared;

import android.Manifest;
import android.content.pm.PackageManager;
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
import java.util.*;
import kr.routememory.core.*;

/** Actual map surface and GPS guidance using the same native session as the phone. */
public final class RoadCarScreen extends Screen implements DefaultLifecycleObserver {
    private final RoadController state;
    private final RoadMapPainter painter;
    private final NavigationManager nav;
    private final Runnable observer=this::refresh;
    private Surface surface;
    private Rect visible=new Rect();
    private boolean active,navActive,destroyed;
    private final CarIcon[] mapIcons=new CarIcon[4];
    public RoadCarScreen(CarContext context){
        super(context);state=RoadController.get(context);nav=context.getCarService(NavigationManager.class);
        setMarker("actual-road-"+System.identityHashCode(this));
        painter=new RoadMapPainter(context,this::draw);getLifecycle().addObserver(this);
    }
    @Override public void onStart(@NonNull LifecycleOwner owner){
        if(active||destroyed)return;active=true;state.addListener(observer);
        nav.setNavigationManagerCallback(ContextCompat.getMainExecutor(getCarContext()),new NavigationManagerCallback(){
            @Override public void onStopNavigation(){state.stopAll();}
        });
        getCarContext().getCarService(AppManager.class).setSurfaceCallback(new SurfaceCallback(){
            @Override public void onSurfaceAvailable(@NonNull SurfaceContainer container){surface=container.getSurface();draw();}
            @Override public void onSurfaceDestroyed(@NonNull SurfaceContainer container){surface=null;}
            @Override public void onVisibleAreaChanged(@NonNull Rect area){visible=new Rect(area);draw();}
        });refresh();
    }
    @Override public void onStop(@NonNull LifecycleOwner owner){
        // A parked options child does not end an ongoing navigation session.
        if(!state.guiding)detach();
    }
    @Override public void onDestroy(@NonNull LifecycleOwner owner){
        if(destroyed)return;
        destroyed=true;state.stopAll();detach();painter.close();
    }
    private void detach(){
        if(!active)return;state.removeListener(observer);active=false;
        if(navActive){nav.navigationEnded();navActive=false;}
        nav.clearNavigationManagerCallback();getCarContext().getCarService(AppManager.class).setSurfaceCallback(null);surface=null;
    }
    private void refresh(){
        if(!active)return;
        painter.setMapStyle(state.mapStyle);
        if(state.guiding&&!navActive){nav.navigationStarted();navActive=true;}
        if(!state.guiding&&navActive){nav.navigationEnded();navActive=false;}
        if(state.guiding&&state.route!=null&&state.tracker!=null){
            RoadRoute.Step next=state.tracker.nextStep();
            double stepSeconds=state.tracker.remainingSeconds*state.tracker.nextDistanceMeters/Math.max(1,state.tracker.remainingMeters);
            nav.updateTrip(new Trip.Builder().setCurrentRoad(next==null?"실제 도로":next.name)
                .addStep(step(),estimate(state.tracker.nextDistanceMeters,Math.max(1,stepSeconds))).build());
        }
        draw();invalidate();
    }
    private void draw(){
        if(!active||surface==null||!surface.isValid())return;Canvas c=null;
        try{
            c=surface.lockCanvas(null);painter.draw(c,visible.isEmpty()?new Rect(0,0,c.getWidth(),c.getHeight()):visible,
                state.route,state.fix,state.origin,state.destination);
        }catch(IllegalArgumentException|IllegalStateException ignored){
            // Host can replace a surface between the availability callback and this frame.
        }finally{if(c!=null)try{surface.unlockCanvasAndPost(c);}catch(IllegalArgumentException|IllegalStateException ignored){}}
    }
    private Step step(){
        boolean tracking=state.guiding&&state.tracker!=null;
        RoadRoute.Step actual=tracking?state.tracker.nextStep():state.route.steps.get(Math.min(1,state.route.steps.size()-1));
        String cue=tracking?state.tracker.nextCue:(actual==null?"실제 경로 미리 보기":actual.cue);
        int type=Maneuver.TYPE_STRAIGHT;
        if(actual!=null){
            if(actual.modifier.contains("left"))type=Maneuver.TYPE_TURN_NORMAL_LEFT;
            else if(actual.modifier.contains("right"))type=Maneuver.TYPE_TURN_NORMAL_RIGHT;
        }
        if(state.guiding&&state.tracker.status==RoadTracker.Status.OFF_ROUTE)cue="경로 이탈 · 설정에서 다시 탐색";
        else if(state.guiding&&(state.tracker.status==RoadTracker.Status.STALE||state.tracker.status==RoadTracker.Status.POOR_ACCURACY))cue="GPS 위치 수신 대기";
        return new Step.Builder(cue==null||cue.isEmpty()?"실제 도로 경로":cue).setManeuver(new Maneuver.Builder(type).build()).build();
    }
    private TravelEstimate estimate(double meters,double seconds){
        long remaining=Math.max(0,(long)Math.ceil(seconds));
        return new TravelEstimate.Builder(Distance.create(Math.max(0,meters),Distance.UNIT_METERS),
            DateTimeWithZone.create(System.currentTimeMillis()+remaining*1000,TimeZone.getDefault()))
            .setRemainingTimeSeconds(remaining).build();
    }
    private static Action parked(String title,Runnable task){return new Action.Builder().setTitle(title).setOnClickListener(ParkedOnlyOnClickListener.create(task::run)).build();}
    private void changeMapStyle(){
        RoadMapStyle next=state.mapStyle==RoadMapStyle.ROAD?RoadMapStyle.SATELLITE:
            state.mapStyle==RoadMapStyle.SATELLITE&&state.highResolutionReady()?RoadMapStyle.SATELLITE_HD:RoadMapStyle.ROAD;
        state.setMapStyle(next);
    }
    private void requestGps(){
        if(state.gpsEnabled){state.stopAll();return;}
        getScreenManager().push(new GpsDisclosureScreen(getCarContext()));
    }
    private void confirmGps(){
        List<String> permissions=new ArrayList<>();
        if(!state.hasPermission()){
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if(android.os.Build.VERSION.SDK_INT>=33&&getCarContext().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        if(permissions.isEmpty()){state.startGps();return;}
        getCarContext().requestPermissions(permissions,
            ContextCompat.getMainExecutor(getCarContext()),(approved,rejected)->{
                if(destroyed)return;
                if(state.hasPermission())state.startGps();
                else CarToast.makeText(getCarContext(),"GPS 사용을 위해 위치 권한이 필요합니다",CarToast.LENGTH_LONG).show();
                invalidate();
            });
    }
    private CarIcon resourceIcon(int resource){return new CarIcon.Builder(IconCompat.createWithResource(getCarContext(),resource)).build();}
    private Action settingsAction(){
        return new Action.Builder().setIcon(resourceIcon(android.R.drawable.ic_menu_manage))
            .setOnClickListener(ParkedOnlyOnClickListener.create(()->getScreenManager().push(new OptionsScreen(getCarContext())))).build();
    }
    private void searchPlaces(){
        if(state.guiding){CarToast.makeText(getCarContext(),"길 안내를 종료한 뒤 목적지를 검색하세요",CarToast.LENGTH_LONG).show();return;}
        getScreenManager().push(new PlaceCarSearchScreen(getCarContext(),state,getMarker()));
    }
    /** Original monochrome symbols; the vehicle host draws each button's chrome. */
    private CarIcon mapIcon(int kind){
        if(mapIcons[kind]!=null)return mapIcons[kind];
        Bitmap bitmap=Bitmap.createBitmap(48,48,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);
        Paint pen=new Paint(Paint.ANTI_ALIAS_FLAG);pen.setColor(Color.BLACK);pen.setStyle(Paint.Style.STROKE);pen.setStrokeWidth(3);pen.setStrokeCap(Paint.Cap.ROUND);
        if(kind==0||kind==1){
            canvas.drawCircle(21,21,13,pen);canvas.drawLine(31,31,41,41,pen);canvas.drawLine(14,21,28,21,pen);
            if(kind==0)canvas.drawLine(21,14,21,28,pen);
        }else if(kind==2){
            canvas.drawLine(8,18,8,8,pen);canvas.drawLine(8,8,18,8,pen);canvas.drawLine(30,8,40,8,pen);canvas.drawLine(40,8,40,18,pen);
            canvas.drawLine(8,30,8,40,pen);canvas.drawLine(8,40,18,40,pen);canvas.drawLine(30,40,40,40,pen);canvas.drawLine(40,40,40,30,pen);
        }else{
            canvas.drawCircle(24,24,11,pen);canvas.drawLine(24,5,24,14,pen);canvas.drawLine(24,34,24,43,pen);
            canvas.drawLine(5,24,14,24,pen);canvas.drawLine(34,24,43,24,pen);pen.setStyle(Paint.Style.FILL);canvas.drawCircle(24,24,3,pen);
        }
        mapIcons[kind]=new CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build();return mapIcons[kind];
    }
    private Action mapAction(int kind,Runnable task){return new Action.Builder().setIcon(mapIcon(kind)).setOnClickListener(task::run).build();}
    private ActionStrip mapActions(){return new ActionStrip.Builder()
        .addAction(mapAction(0,painter::zoomIn)).addAction(mapAction(1,painter::zoomOut))
        .addAction(mapAction(2,painter::fitRoute)).addAction(mapAction(3,painter::followLocation)).build();}
    @NonNull @Override public Template onGetTemplate(){
        NavigationTemplate.Builder template=new NavigationTemplate.Builder()
            .setBackgroundColor(CarColor.createCustom(0xff064c3e,0xff053b31)).setMapActionStrip(mapActions());
        Action primary;
        if(state.route!=null){
            double meters=state.tracker!=null&&state.guiding?state.tracker.remainingMeters:state.route.distanceMeters;
            double seconds=state.tracker!=null&&state.guiding?state.tracker.remainingSeconds:state.route.durationSeconds;
            double next=state.tracker!=null&&state.guiding?state.tracker.nextDistanceMeters:state.route.steps.get(0).distanceMeters;
            primary=state.guiding?new Action.Builder().setTitle("GPS·안내 종료").setOnClickListener(state::stopAll).build():parked("GPS 길 안내 시작",()->{
                state.startGuidance();if(!state.guiding)CarToast.makeText(getCarContext(),state.notice,CarToast.LENGTH_LONG).show();
            });
            template.setNavigationInfo(new RoutingInfo.Builder().setCurrentStep(step(),Distance.create(Math.max(0,next),Distance.UNIT_METERS)).build())
                .setDestinationTravelEstimate(estimate(meters,seconds));
        }else{
            primary=parked(state.loading?"경로 탐색 중":"목적지 검색",this::searchPlaces);
            if(state.loading)template.setNavigationInfo(new RoutingInfo.Builder().setLoading(true).build());
        }
        ActionStrip.Builder controls=new ActionStrip.Builder().addAction(primary).addAction(settingsAction());
        if(!state.guiding){
            Action.Builder gps=new Action.Builder().setIcon(resourceIcon(android.R.drawable.ic_menu_mylocation));
            if(state.gpsEnabled)gps.setOnClickListener(state::stopAll);
            else gps.setOnClickListener(ParkedOnlyOnClickListener.create(this::requestGps));
            controls.addAction(gps.build());
        }
        return template.setActionStrip(controls.build()).build();
    }
    final class OptionsScreen extends Screen {
        OptionsScreen(CarContext context){super(context);}
        private Row option(String title,String description,Runnable task){return new Row.Builder().setTitle(title).addText(description).setOnClickListener(ParkedOnlyOnClickListener.create(()->{task.run();invalidate();})).build();}
        @NonNull @Override public Template onGetTemplate(){
            ItemList list=new ItemList.Builder()
                .addItem(option("지도 종류: "+state.mapStyle.label,
                    "눌러 변경 · 고화질 항공영상은 지도 서버 연결 후 선택",RoadCarScreen.this::changeMapStyle))
                .addItem(option("고화질 항공영상",
                    state.highResolutionReady()?"서버 키 설정 확인됨. 영상 인증·화질은 첫 조회에서 확인합니다.":"지도 서버를 연결해 준비합니다. 현재는 키가 필요합니다.",
                    ()->getScreenManager().push(new MapGatewayCarScreen(getCarContext(),state,RoadCarScreen.this.getMarker()))))
                .addItem(option("주소·장소명으로 목적지 검색","검색어는 Photon에 전송합니다 · 결과를 선택해 경로 탐색",RoadCarScreen.this::searchPlaces))
                .addItem(option("주변 주유소·주차장","목적지 또는 이미 켜진 GPS 주변 조회 · 유가·잔여 면수 미연결",()->getScreenManager().push(new NearbyCarScreen(getCarContext(),state,RoadCarScreen.this.getMarker()))))
                .addItem(option(state.gpsEnabled?"GPS·안내 종료":"GPS 위치 켜기","정차 중 위치 권한 요청 · 정밀 위치 권장",RoadCarScreen.this::requestGps))
                .addItem(new Row.Builder().setTitle(state.destinationName.isEmpty()?"선택한 목적지":state.destinationName)
                    .addText(state.destinationAddress.isEmpty()?"목적지를 검색하거나 지도·좌표로 지정하세요":state.destinationAddress).build())
                .addItem(option("현재 설정으로 경로 탐색","출발·도착 좌표를 OSRM에 전송 · 실시간 교통 미반영",()->{state.search();getScreenManager().pop();}))
                .addItem(option("서울 시청 → 경복궁 샘플","실제 도로 미리 보기 · 현재 GPS 위치와 무관",()->{state.useSeoulSample();getScreenManager().pop();}))
                .addItem(option("목적지 좌표 입력",
                    String.format(Locale.US,"현재 %.5f, %.5f · 정차 중 위도, 경도 형식으로 입력",state.destination.lat,state.destination.lon),
                    ()->getScreenManager().push(new CoordinateInputScreen(getCarContext()))))
                .addItem(option("개인별 학습 시연","실제 GPS를 종료하고 가상 주행·학습 화면으로 이동",()->{
                    state.stopAll();getScreenManager().pop();getScreenManager().push(new MemoryCarScreen(getCarContext()));
                }))
                .addItem(new Row.Builder().setTitle("지도·GPS 데이터 사용")
                    .addText("검색어는 Photon, 일반지도는 OSM, 위성지도는 EOX, 경로 좌표는 OSRM으로 전송합니다. 위치 이력은 저장하지 않습니다.").build())
                .addItem(new Row.Builder().setTitle("실시간 교통 미연결")
                    .addText("예상 시간에는 현재 정체가 반영되지 않습니다. GPS가 꺼져 있으면 서울 샘플을 미리 봅니다.").build())
                .addItem(new Row.Builder().setTitle("실제 도로 학습은 준비 중")
                    .addText("가상 시연의 실수 기록은 실제 도로에 적용하지 않습니다.").build()).build();
            return new ListTemplate.Builder().setSingleList(list).setTitle("실제 지도·GPS 설정 · 정차 중").setHeaderAction(Action.BACK).build();
        }
    }
    final class GpsDisclosureScreen extends Screen {
        GpsDisclosureScreen(CarContext context){super(context);}
        @NonNull @Override public Template onGetTemplate(){
            Pane pane=new Pane.Builder().addRow(new Row.Builder().setTitle("현재 위치로 길 안내")
                .addText("위치 권한을 허용하면 기기 GPS를 수신합니다. 탐색을 누를 때만 출발·목적지 좌표를 OSRM에 전송합니다.").build())
                .addRow(new Row.Builder().setTitle("위치 이력은 저장하지 않습니다")
                    .addText("정밀 위치를 권장합니다. GPS는 종료 버튼으로 끌 수 있으며 실시간 교통은 연결하지 않았습니다.").build())
                .addAction(parked("GPS 켜기",()->{getScreenManager().pop();confirmGps();}))
                .addAction(parked("취소",()->getScreenManager().pop())).build();
            return new PaneTemplate.Builder(pane).setTitle("GPS 위치 사용 · 정차 중").setHeaderAction(Action.BACK).build();
        }
    }
    static RoadRoute.Point parseCoordinateInput(String input){
        if(input==null||input.length()>80)return null;
        String[] pair=input.trim().split(",",-1);if(pair.length!=2)return null;
        String number="[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)";
        String latitude=pair[0].trim(),longitude=pair[1].trim();
        if(!latitude.matches(number)||!longitude.matches(number))return null;
        try{
            double lat=Double.parseDouble(latitude),lon=Double.parseDouble(longitude);
            if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>85.05112878||Math.abs(lon)>180)return null;
            return new RoadRoute.Point(lat,lon);
        }catch(NumberFormatException ignored){return null;}
    }
    /** The host owns the keyboard. Input parsing is local; only parked selection changes the route. */
    final class CoordinateInputScreen extends Screen {
        private String input="";
        private boolean tooLong;
        CoordinateInputScreen(CarContext context){super(context);}
        private void inputChanged(String value){tooLong=value!=null&&value.length()>80;input=value==null?"":value.substring(0,Math.min(80,value.length()));invalidate();}
        @NonNull @Override public Template onGetTemplate(){
            RoadRoute.Point point=tooLong?null:parseCoordinateInput(input);
            ItemList.Builder results=new ItemList.Builder();
            if(point!=null)results.addItem(new Row.Builder().setTitle("이 좌표를 목적지로 선택")
                .addText(String.format(Locale.US,"위도 %.6f, 경도 %.6f",point.lat,point.lon))
                .setOnClickListener(ParkedOnlyOnClickListener.create(()->{
                    state.setDestination(point);
                    getScreenManager().popTo(RoadCarScreen.this.getMarker());
                })).build());
            else results.setNoItemsMessage("예: 37.5796, 126.9770 · 주소·장소명은 목적지 검색을 이용하세요");
            return new SearchTemplate.Builder(new SearchTemplate.SearchCallback(){
                @Override public void onSearchTextChanged(@NonNull String text){inputChanged(text);}
                @Override public void onSearchSubmitted(@NonNull String text){inputChanged(text);}
            }).setHeaderAction(Action.BACK).setSearchHint("목적지 위도, 경도 · 정차 중 입력")
                .setInitialSearchText(input).setShowKeyboardByDefault(true).setItemList(results.build()).build();
        }
    }
}
