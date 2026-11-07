package kr.routememory.shared;

import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.car.app.*;
import androidx.car.app.model.*;
import androidx.lifecycle.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import kr.routememory.core.RoadRoute;

/** Explicit parked nearby lookup. Opening this screen never enables GPS or sends coordinates. */
final class NearbyCarScreen extends Screen implements DefaultLifecycleObserver {
    interface Lookup { NearbySearchClient.Result search(RoadRoute.Point center,String kind,int radius) throws IOException; }
    private final RoadController state;
    private final String returnMarker;
    private final Lookup lookup;
    private final Executor worker;
    private final Runnable prepareRoute;
    private final boolean ownsWorker;
    private final Handler main=new Handler(Looper.getMainLooper());
    private String kind="fuel",message="조회할 때 선택한 중심 좌표를 Photon에 전송합니다. 유가·잔여 면수는 미연결입니다.";
    private int radius=1000,generation;
    private boolean current,loading,closed,showResults;
    private Future<?> pending;
    private List<NearbySearchClient.NearbyPlace> results=Collections.emptyList();

    NearbyCarScreen(CarContext context,RoadController state,String marker){
        this(context,state,marker,new NearbySearchClient()::search,Executors.newSingleThreadExecutor(),state::search,true);
    }
    NearbyCarScreen(CarContext context,RoadController state,String marker,Lookup lookup,Executor worker,Runnable route){
        this(context,state,marker,lookup,worker,route,false);
    }
    private NearbyCarScreen(CarContext context,RoadController state,String marker,Lookup lookup,Executor worker,Runnable route,boolean owns){
        super(context);this.state=state;returnMarker=marker;this.lookup=lookup;this.worker=worker;prepareRoute=route;ownsWorker=owns;
        getLifecycle().addObserver(this);
    }
    private void cancel(){generation++;if(pending!=null)pending.cancel(true);pending=null;loading=false;}
    @Override public void onStop(@NonNull LifecycleOwner owner){boolean interrupted=loading;cancel();if(interrupted)message="조회를 중지했습니다. 다시 조회해 주세요.";}
    @Override public void onDestroy(@NonNull LifecycleOwner owner){closed=true;cancel();if(ownsWorker)((ExecutorService)worker).shutdownNow();}
    private Row action(String title,String text,Runnable run){return new Row.Builder().setTitle(title).addText(text)
        .setOnClickListener(ParkedOnlyOnClickListener.create(run::run)).build();}
    private void configure(){cancel();showResults=false;results=Collections.emptyList();invalidate();}
    private void switchCenter(){current=!current;configure();}
    private void search(){
        if(closed)return;cancel();results=Collections.emptyList();showResults=false;
        if(state.guiding){message="길 안내를 종료한 뒤 주변 장소를 검색하세요.";invalidate();return;}
        if(current&&(!state.gpsEnabled||!state.freshFix())){
            message="현재 위치는 기존 GPS가 켜져 있고 새 위치가 수신된 경우에만 조회할 수 있습니다. 지도 설정에서 GPS를 켜거나 목적지 주변을 선택하세요.";invalidate();return;
        }
        RoadRoute.Point center=current?new RoadRoute.Point(state.fix.lat,state.fix.lon):state.destination;
        final RoadRoute.Point chosen=new RoadRoute.Point(center.lat,center.lon);
        final String requestedKind=kind;final int requestedRadius=radius,ticket=generation;
        loading=true;invalidate();
        Runnable job=()->{
            NearbySearchClient.Result answer=null;String error="인터넷 연결을 확인한 뒤 다시 조회하세요.";
            try{answer=lookup.search(chosen,requestedKind,requestedRadius);}catch(IOException e){if(e.getMessage()!=null)error=e.getMessage();}catch(RuntimeException ignored){}
            final NearbySearchClient.Result found=answer;final String failure=error;
            main.post(()->{
                if(closed||generation!=ticket)return;
                loading=false;showResults=true;results=found==null?Collections.emptyList():found.places;
                message=found==null?failure:results.isEmpty()?"반경 안의 등록된 장소가 없습니다. 3km로 넓히거나 다른 중심을 선택하세요.":"가까운 순 · 직선 거리 · OSM/Photon · 유가·잔여 면수 미연결";
                invalidate();
            });
        };
        if(worker instanceof ExecutorService)pending=((ExecutorService)worker).submit(job);else worker.execute(job);
    }
    private void select(NearbySearchClient.NearbyPlace place){
        if(closed||state.guiding)return;cancel();state.setDestination(place.point(),place.name,place.address);prepareRoute.run();getScreenManager().popTo(returnMarker);
    }
    static String detail(NearbySearchClient.NearbyPlace place,String kind){
        String distance=place.distanceMeters<1000?Math.round(place.distanceMeters)+"m":String.format(Locale.KOREA,"%.1fkm",place.distanceMeters/1000);
        String value;
        if("fuel".equals(kind)){
            NearbySearchClient.PriceInfo p=place.fuelPrice;
            value=p.priceWonPerLiter==null?"유가 연동 대기":String.format(Locale.KOREA,"%,.0f원/L 보고 가격",p.priceWonPerLiter)+("stale".equals(p.status)?" · 오래된 정보":"")+" · "+p.source+" · "+p.updatedAt;
        }else{
            NearbySearchClient.ParkingInfo p=place.parking;
            value=p.available==null?"잔여 면수 미제공":("stale".equals(p.status)?"이전 보고 ":"보고 ")+(p.available==0?"만차":p.available+"면 남음")+" · "+p.source+" · "+p.updatedAt;
        }
        return "직선 "+distance+" · "+value;
    }
    @NonNull @Override public Template onGetTemplate(){
        ListTemplate.Builder template=new ListTemplate.Builder().setTitle(("fuel".equals(kind)?"주유소":"주차장")+" 찾기 · 정차 중").setHeaderAction(Action.BACK);
        if(loading)return template.setLoading(true).build();
        ItemList.Builder list=new ItemList.Builder();
        if(showResults){
            for(NearbySearchClient.NearbyPlace place:results.subList(0,Math.min(5,results.size())))list.addItem(new Row.Builder().setTitle(place.name)
                .addText(place.address.isEmpty()?"주소 미제공":place.address).addText(detail(place,kind))
                .setOnClickListener(ParkedOnlyOnClickListener.create(()->select(place))).build());
            if(results.isEmpty())list.setNoItemsMessage(message);
            template.setActionStrip(new ActionStrip.Builder().addAction(new Action.Builder().setTitle("검색 설정")
                .setOnClickListener(ParkedOnlyOnClickListener.create(this::configure)).build()).build());
        }else{
            list.addItem(action("분류: "+("fuel".equals(kind)?"주유소":"주차장"),"눌러 분류 변경",()->{kind="fuel".equals(kind)?"parking":"fuel";configure();}))
                .addItem(action(current?"현재 위치 주변":"목적지 주변",current?"이미 켜진 GPS의 새 위치만 사용":"선택한 목적지 기준 · 기본값은 서울 샘플",this::switchCenter))
                .addItem(action("반경 "+radius/1000+"km","눌러 1km / 3km 변경",()->{radius=radius==1000?3000:1000;configure();}))
                .addItem(action("이 조건으로 조회","선택한 중심 좌표를 Photon에 전송 · 자동 조회 없음",this::search))
                .addItem(new Row.Builder().setTitle("데이터 안내").addText(message).build());
        }
        return template.setSingleList(list.build()).build();
    }
}
