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

/** Host keyboard and parked result selection. Typing never sends a search request. */
final class PlaceCarSearchScreen extends Screen implements DefaultLifecycleObserver {
    interface Lookup { List<PlaceSearchClient.Place> search(String query) throws IOException; }
    private final RoadController state;
    private final String returnMarker;
    private final Lookup lookup;
    private final Executor worker;
    private final Runnable prepareRoute;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final boolean ownsWorker;
    private String query="",message="주소나 장소 이름을 입력한 뒤 검색을 눌러주세요. 검색어는 Photon에 전송됩니다.";
    private List<PlaceSearchClient.Place> results=Collections.emptyList();
    private boolean loading,closed;
    private int generation;
    private Future<?> pending;

    PlaceCarSearchScreen(CarContext context,RoadController state,String returnMarker){
        this(context,state,returnMarker,new PlaceSearchClient()::search,Executors.newSingleThreadExecutor(),state::search,true);
    }
    PlaceCarSearchScreen(CarContext context,RoadController state,String returnMarker,Lookup lookup,Executor worker,Runnable prepareRoute){
        this(context,state,returnMarker,lookup,worker,prepareRoute,false);
    }
    private PlaceCarSearchScreen(CarContext context,RoadController state,String returnMarker,Lookup lookup,Executor worker,Runnable prepareRoute,boolean ownsWorker){
        super(context);this.state=state;this.returnMarker=returnMarker;this.lookup=lookup;this.worker=worker;this.prepareRoute=prepareRoute;this.ownsWorker=ownsWorker;getLifecycle().addObserver(this);
    }
    private void cancel(){generation++;if(pending!=null)pending.cancel(true);pending=null;loading=false;}
    @Override public void onStop(@NonNull LifecycleOwner owner){boolean wasLoading=loading;cancel();if(wasLoading)message="검색을 중지했습니다. 검색 버튼을 눌러 다시 찾아주세요";}
    @Override public void onDestroy(@NonNull LifecycleOwner owner){closed=true;cancel();if(ownsWorker)((ExecutorService)worker).shutdownNow();}
    private void changed(String text){
        if(closed)return;cancel();query=text==null?"":text;results=Collections.emptyList();
        message=query.length()>120?"검색어를 120자 이내로 입력하세요":"입력 후 검색을 눌러주세요 · 검색어를 Photon에 전송합니다";invalidate();
    }
    private void search(String text){
        if(closed)return;changed(text);String value=query.trim().replaceAll("\\s+"," ");
        if(value.length()<2||value.length()>120||value.matches("(?s).*[\\p{Cc}].*")){
            message="주소나 장소 이름을 2~120자로 입력하세요";invalidate();return;
        }
        query=value;loading=true;message="장소를 검색하고 있습니다";final int ticket=generation;invalidate();
        Runnable request=()->{
            List<PlaceSearchClient.Place> answer=null;
            try{answer=lookup.search(value);}catch(IOException|RuntimeException ignored){}
            final List<PlaceSearchClient.Place> found=answer;
            main.post(()->{
                if(closed||generation!=ticket||!query.equals(value))return;
                loading=false;results=found==null?Collections.emptyList():new ArrayList<>(found.subList(0,Math.min(8,found.size())));
                message=found==null?"검색하지 못했습니다. 인터넷 연결을 확인하고 다시 검색하세요":results.isEmpty()?"검색 결과가 없습니다. 지역명이나 도로명을 함께 입력해 보세요":"이름과 주소를 확인하고 목적지를 선택하세요";invalidate();
            });
        };
        if(worker instanceof ExecutorService)pending=((ExecutorService)worker).submit(request);else worker.execute(request);
    }
    private void select(PlaceSearchClient.Place place){
        if(closed)return;
        if(state.guiding){CarToast.makeText(getCarContext(),"길 안내를 종료한 뒤 목적지를 바꿔 주세요",CarToast.LENGTH_LONG).show();return;}
        cancel();state.setDestination(place.point(),place.name,place.address);prepareRoute.run();
        getScreenManager().popTo(returnMarker);
    }
    @NonNull @Override public Template onGetTemplate(){
        ItemList.Builder items=new ItemList.Builder();
        for(PlaceSearchClient.Place place:results.subList(0,Math.min(5,results.size())))items.addItem(new Row.Builder()
            .setTitle(place.name).addText(place.address.isEmpty()?place.category:place.address)
            .setOnClickListener(ParkedOnlyOnClickListener.create(()->select(place))).build());
        if(results.isEmpty())items.setNoItemsMessage(message);
        SearchTemplate.Builder builder=new SearchTemplate.Builder(new SearchTemplate.SearchCallback(){
            @Override public void onSearchTextChanged(@NonNull String text){changed(text);}
            @Override public void onSearchSubmitted(@NonNull String text){search(text);}
        }).setHeaderAction(Action.BACK).setSearchHint("주소나 장소 이름 · 정차 중 검색")
            .setInitialSearchText(query.substring(0,Math.min(120,query.length()))).setShowKeyboardByDefault(results.isEmpty()&&!loading);
        return loading?builder.setLoading(true).build():builder.setItemList(items.build()).build();
    }
}
