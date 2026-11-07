package kr.routememory.shared;

import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.car.app.*;
import androidx.car.app.model.*;
import androidx.lifecycle.*;
import java.io.IOException;
import java.util.concurrent.*;

/** Parked AAOS/Android Auto gateway setup. The key stays on the user's map server. */
final class MapGatewayCarScreen extends Screen implements DefaultLifecycleObserver {
    interface Lookup { RoadMapGateway check(String origin) throws IOException; }
    private final RoadController state;
    private final Lookup lookup;
    private final Executor worker;
    private final boolean ownsWorker;
    private final String returnMarker;
    private final Handler main=new Handler(Looper.getMainLooper());
    private String input="",message="키가 없는 HTTPS 서버 주소를 입력하고 검색을 누르세요. 연결 확인만 요청하며 GPS를 시작하지 않습니다.";
    private RoadMapGateway checked;
    private int generation;
    private boolean loading,closed;
    private Future<?> pending;
    MapGatewayCarScreen(CarContext context,RoadController state,String returnMarker){
        this(context,state,returnMarker,value->RoadMapGateway.check(value,BuildConfig.DEBUG),Executors.newSingleThreadExecutor(),true);
    }
    MapGatewayCarScreen(CarContext context,RoadController state,String marker,Lookup lookup,Executor worker){this(context,state,marker,lookup,worker,false);}
    private MapGatewayCarScreen(CarContext context,RoadController state,String marker,Lookup lookup,Executor worker,boolean owns){
        super(context);this.state=state;returnMarker=marker;this.lookup=lookup;this.worker=worker;ownsWorker=owns;getLifecycle().addObserver(this);
    }
    private void cancel(){generation++;if(pending!=null)pending.cancel(true);pending=null;loading=false;checked=null;}
    @Override public void onStop(@NonNull LifecycleOwner owner){cancel();}
    @Override public void onDestroy(@NonNull LifecycleOwner owner){closed=true;cancel();if(ownsWorker)((ExecutorService)worker).shutdownNow();}
    private void changed(String value){
        if(closed)return;cancel();input=value==null?"":value;
        message="서버 주소만 입력하세요. API 키는 앱에 입력하지 않습니다. 입력 중에는 조회하지 않습니다.";invalidate();
    }
    private void check(String value){
        if(closed)return;changed(value);final String origin;
        try{origin=RoadMapGateway.normalizeOrigin(input,BuildConfig.DEBUG);}catch(IOException e){message=e.getMessage();invalidate();return;}
        input=origin;final int ticket=generation;loading=true;invalidate();
        Runnable task=()->{
            RoadMapGateway result=null;String error="지도 서버 연결을 확인하지 못했습니다.";
            try{result=lookup.check(origin);}catch(IOException e){if(e.getMessage()!=null)error=e.getMessage();}catch(RuntimeException ignored){}
            final RoadMapGateway answer=result;final String failure=error;
            main.post(()->{
                if(closed||generation!=ticket)return;loading=false;
                checked=answer!=null&&origin.equals(answer.origin)&&answer.configured?answer:null;
                message=answer==null?failure:checked==null?"서버에 브이월드 키가 없습니다. PC 서버에 키를 설정하고 다시 확인하세요.":"서버 키 설정이 확인되었습니다. 실제 영상 인증·화질은 선택 후 첫 조회에서 확인합니다.";invalidate();
            });
        };
        if(worker instanceof ExecutorService)pending=((ExecutorService)worker).submit(task);else worker.execute(task);
    }
    private void select(){
        if(closed||checked==null)return;RoadMapGateway target=checked;cancel();state.setHighResolutionGateway(target);state.setMapStyle(RoadMapStyle.SATELLITE_HD);getScreenManager().popTo(returnMarker);
    }
    private void disconnect(){cancel();state.disconnectHighResolution();message="고화질 지도 연결을 해제했습니다. GPS·경로는 유지합니다.";invalidate();}
    @NonNull @Override public Template onGetTemplate(){
        ItemList.Builder items=new ItemList.Builder();
        if(checked!=null)items.addItem(new Row.Builder().setTitle("이 서버로 고화질 항공지도 연결")
            .addText(checked.origin).addText(message).setOnClickListener(ParkedOnlyOnClickListener.create(this::select)).build());
        else items.setNoItemsMessage(message);
        SearchTemplate.Builder template=new SearchTemplate.Builder(new SearchTemplate.SearchCallback(){
            @Override public void onSearchTextChanged(@NonNull String text){changed(text);}
            @Override public void onSearchSubmitted(@NonNull String text){check(text);}
        }).setHeaderAction(Action.BACK).setSearchHint("키 없는 지도 서버 주소 · 정차 중")
            .setInitialSearchText(input.substring(0,Math.min(240,input.length()))).setShowKeyboardByDefault(checked==null&&!loading);
        if(state.highResolutionReady())template.setActionStrip(new ActionStrip.Builder().addAction(new Action.Builder().setTitle("연결 해제")
            .setOnClickListener(ParkedOnlyOnClickListener.create(this::disconnect)).build()).build());
        return loading?template.setLoading(true).build():template.setItemList(items.build()).build();
    }
}
