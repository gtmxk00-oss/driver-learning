package kr.routememory.shared;

import android.Manifest;
import android.app.*;
import android.content.Intent;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.Editable;
import android.text.TextWatcher;
import android.graphics.text.LineBreaker;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;
import kr.routememory.core.*;

/** A map-first native screen. Phone and car use the same actual-road session. */
public final class RoadActivity extends Activity {
    private static final int BLUE=0xff246bfd,GREEN=0xff064c3e,INK=0xff142a3b,MUTED=0xff526373,LINE=0xffdce4ea,SOFT=0xfff3f6f8,TURN_MUTED=0xffc9e3da,WHITE=Color.WHITE;
    private static final String FONT="sans-serif",FONT_MEDIUM="sans-serif-medium";
    private RoadController state;
    private RoadMapView map;
    private TextView destinationLabel,originLabel,gpsLabel,notice,time,distance,arrival,turnDistance,turnCue,turnMeta;
    private Button primary,settings,stepsButton,mapStyleButton;
    private LinearLayout turnCard;
    private NavIcon turnIcon;
    private RoadRoute shownRoute;
    private boolean wasGuiding;
    private boolean destroyed;
    private int placeGeneration;
    private Future<?> placeTask;
    private AlertDialog placeDialog;
    private final ExecutorService placeWorker=Executors.newSingleThreadExecutor();
    private int nearbyGeneration;
    private Future<?> nearbyTask;
    private AlertDialog nearbyDialog;
    private final ExecutorService nearbyWorker=Executors.newSingleThreadExecutor();
    interface GatewayLookup { RoadMapGateway check(String origin)throws java.io.IOException; }
    GatewayLookup gatewayLookup=value->RoadMapGateway.check(value,BuildConfig.DEBUG);
    private final ExecutorService gatewayWorker=Executors.newSingleThreadExecutor();
    private Future<?> gatewayTask;
    private int gatewayGeneration;
    private AlertDialog gatewayDialog;
    private final Handler ui=new Handler(Looper.getMainLooper());
    PlaceSearchClient placeClient=new PlaceSearchClient();
    NearbySearchClient nearbyClient=new NearbySearchClient();
    Runnable requestRoute;
    private final Runnable observer=this::refresh;
    @Override public void onCreate(Bundle saved){
        setTheme(android.R.style.Theme_Material_Light_NoActionBar);super.onCreate(saved);
        state=RoadController.get(this);requestRoute=state::search;getWindow().setStatusBarColor(WHITE);getWindow().setNavigationBarColor(WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);build();refresh();
    }
    @Override protected void onStart(){super.onStart();state.addListener(observer);refresh();}
    @Override protected void onStop(){state.removeListener(observer);super.onStop();}
    @Override protected void onDestroy(){destroyed=true;cancelPlaceSearch();cancelNearbySearch();cancelGateway();if(placeDialog!=null)placeDialog.dismiss();if(nearbyDialog!=null)nearbyDialog.dismiss();if(gatewayDialog!=null)gatewayDialog.dismiss();placeWorker.shutdownNow();nearbyWorker.shutdownNow();gatewayWorker.shutdownNow();if(map!=null)map.close();super.onDestroy();}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setTypeface(Typeface.create(FONT,Typeface.NORMAL));t.setLineSpacing(dp(2),1);return t;}
    private TextView bold(String value,int size,int color){TextView t=text(value,size,color);t.setTypeface(Typeface.create(FONT_MEDIUM,Typeface.NORMAL));return t;}
    private GradientDrawable background(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private StateListDrawable controlBackground(int color,boolean filled){
        StateListDrawable states=new StateListDrawable();GradientDrawable focus=background(color,10);focus.setStroke(dp(2),BLUE);states.addState(new int[]{android.R.attr.state_focused},focus);
        GradientDrawable pressed=background(filled?GREEN:SOFT,10);pressed.setStroke(dp(1),filled?GREEN:BLUE);states.addState(new int[]{android.R.attr.state_pressed},pressed);
        GradientDrawable normal=background(color,10);if(!filled)normal.setStroke(dp(1),LINE);states.addState(new int[]{},normal);return states;
    }
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private void gap(LinearLayout parent,int n){parent.addView(new View(this),new LinearLayout.LayoutParams(1,dp(n)));}
    private LinearLayout.LayoutParams rowWeight(){return new LinearLayout.LayoutParams(0,-2,1);}
    private Button button(String value,int color,boolean filled,Runnable action){
        Button b=new Button(this);b.setText(value);b.setAllCaps(false);b.setTextSize(15);b.setTypeface(Typeface.create(FONT_MEDIUM,Typeface.NORMAL));
        b.setTextColor(filled?WHITE:color);b.setBackground(controlBackground(filled?color:WHITE,filled));b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(dp(10),0,dp(10),0);b.setOnClickListener(v->action.run());return b;
    }
    private View iconButton(String kind,String label,Runnable action){
        FrameLayout box=new FrameLayout(this);box.setBackground(controlBackground(WHITE,false));box.setElevation(dp(2));box.setContentDescription(label);box.setClickable(true);box.setFocusable(true);
        box.addView(new NavIcon(kind,INK),new FrameLayout.LayoutParams(dp(24),dp(24),Gravity.CENTER));box.setOnClickListener(v->action.run());return box;
    }
    /** Hallmark app redesign: map workbench, retained route/search/GPS handlers, independent brand. */
    private void build(){
        boolean wide=getResources().getConfiguration().screenWidthDp>getResources().getConfiguration().screenHeightDp;
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(SOFT);setContentView(root);
        root.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;});
        map=new RoadMapView(this);map.setContentDescription("실제 도로 지도. 이동·확대 또는 탭하여 목적지 지정");root.addView(map,new FrameLayout.LayoutParams(-1,-1));
        map.setOnDestinationSelected(point->{if(state.guiding){Toast.makeText(this,"안내를 종료한 뒤 목적지를 바꿔 주세요",Toast.LENGTH_SHORT).show();return;}state.setDestination(point);});

        LinearLayout top=column();FrameLayout.LayoutParams topLp=new FrameLayout.LayoutParams(wide?dp(326):-1,-2,Gravity.TOP|Gravity.START);topLp.setMargins(dp(16),dp(16),dp(16),0);root.addView(top,topLp);
        if(!wide)top.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->map.setTopOcclusion(b+dp(12)));
        LinearLayout search=new LinearLayout(this);search.setGravity(Gravity.CENTER_VERTICAL);search.setPadding(dp(10),dp(10),dp(6),dp(10));search.setBackground(background(WHITE,16));search.setElevation(dp(2));top.addView(search,new LinearLayout.LayoutParams(-1,-2));
        View searchIcon=iconButton("search","주소·장소명으로 목적지 검색",this::placeSearch);searchIcon.setElevation(0);search.addView(searchIcon,new LinearLayout.LayoutParams(dp(44),dp(44)));
        LinearLayout labels=column();LinearLayout.LayoutParams labelsLp=new LinearLayout.LayoutParams(0,-2,1);labelsLp.setMargins(dp(5),0,dp(4),0);search.addView(labels,labelsLp);
        originLabel=text("길기억 · 경로 준비",11,MUTED);labels.addView(originLabel);destinationLabel=bold("목적지를 선택하세요",17,INK);destinationLabel.setSingleLine(true);destinationLabel.setEllipsize(TextUtils.TruncateAt.END);labels.addView(destinationLabel);labels.setContentDescription("주소·장소명으로 목적지 검색");labels.setOnClickListener(v->placeSearch());
        View more=iconButton("more","더보기 · 나의 운전",this::menu);more.setElevation(0);search.addView(more,new LinearLayout.LayoutParams(dp(44),dp(44)));
        gap(top,8);LinearLayout shortcuts=new LinearLayout(this);shortcuts.setGravity(Gravity.CENTER_VERTICAL);top.addView(shortcuts,new LinearLayout.LayoutParams(-1,dp(44)));
        Button fuel=button("주유소",INK,false,()->nearbySearch("fuel")),parking=button("주차장",INK,false,()->nearbySearch("parking"));fuel.setTextSize(13);parking.setTextSize(13);fuel.setContentDescription("주변 주유소 찾기");parking.setContentDescription("주변 주차장 찾기");
        shortcuts.addView(fuel,new LinearLayout.LayoutParams(dp(82),-1));LinearLayout.LayoutParams parkingLp=new LinearLayout.LayoutParams(dp(82),-1);parkingLp.leftMargin=dp(6);shortcuts.addView(parking,parkingLp);
        mapStyleButton=button("일반지도",INK,false,this::mapTypes);mapStyleButton.setTextSize(12);LinearLayout.LayoutParams styleLp=new LinearLayout.LayoutParams(0,-1,1);styleLp.leftMargin=dp(6);shortcuts.addView(mapStyleButton,styleLp);
        gap(top,8);turnCard=new LinearLayout(this);turnCard.setGravity(Gravity.CENTER_VERTICAL);turnCard.setPadding(dp(16),dp(12),dp(16),dp(12));turnCard.setBackground(background(GREEN,16));turnCard.setElevation(dp(2));top.addView(turnCard,new LinearLayout.LayoutParams(-1,-2));
        turnIcon=new NavIcon("right",WHITE);turnCard.addView(turnIcon,new LinearLayout.LayoutParams(dp(42),dp(56)));LinearLayout turnTexts=column();LinearLayout.LayoutParams turnLp=new LinearLayout.LayoutParams(0,-2,1);turnLp.leftMargin=dp(12);turnCard.addView(turnTexts,turnLp);
        turnMeta=text("경로 미리 보기",11,TURN_MUTED);turnTexts.addView(turnMeta);turnDistance=bold("",34,WHITE);turnTexts.addView(turnDistance);turnCue=text("",14,WHITE);turnCue.setMaxLines(2);turnCue.setBreakStrategy(LineBreaker.BREAK_STRATEGY_BALANCED);turnTexts.addView(turnCue);

        LinearLayout controls=column();FrameLayout.LayoutParams controlsLp=new FrameLayout.LayoutParams(dp(44),-2,(wide?Gravity.TOP:Gravity.BOTTOM)|Gravity.END);controlsLp.setMargins(0,dp(wide?16:0),dp(16),dp(wide?0:248));root.addView(controls,controlsLp);
        controls.addView(iconButton("location","현재 위치 따라가기",()->{if(state.fix!=null)map.followLocation();else toggleGps();}),new LinearLayout.LayoutParams(dp(44),dp(44)));gap(controls,8);
        controls.addView(iconButton("fit","전체 경로 보기",()->map.fitRoute()),new LinearLayout.LayoutParams(dp(44),dp(44)));gap(controls,8);
        LinearLayout zoom=column();zoom.setBackground(background(WHITE,10));zoom.setElevation(dp(2));controls.addView(zoom);View plus=iconButton("plus","지도 확대",()->map.zoomIn());plus.setElevation(0);zoom.addView(plus,new LinearLayout.LayoutParams(dp(44),dp(44)));
        View line=new View(this);line.setBackgroundColor(LINE);LinearLayout.LayoutParams lineLp=new LinearLayout.LayoutParams(-1,dp(1));lineLp.setMargins(dp(10),0,dp(10),0);zoom.addView(line,lineLp);View minus=iconButton("minus","지도 축소",()->map.zoomOut());minus.setElevation(0);zoom.addView(minus,new LinearLayout.LayoutParams(dp(44),dp(44)));

        LinearLayout sheet=column();sheet.setPadding(dp(18),dp(16),dp(18),dp(8));sheet.setBackground(background(WHITE,16));sheet.setElevation(dp(3));FrameLayout.LayoutParams sheetLp=new FrameLayout.LayoutParams(wide?dp(340):-1,-2,Gravity.BOTTOM|Gravity.END);if(wide)sheetLp.setMargins(0,0,dp(16),dp(16));root.addView(sheet,sheetLp);
        sheet.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)map.getLayoutParams();int inset=wide?0:b-t;if(p.bottomMargin!=inset){p.bottomMargin=inset;map.setLayoutParams(p);}});
        LinearLayout summary=new LinearLayout(this);summary.setGravity(Gravity.CENTER_VERTICAL);sheet.addView(summary);LinearLayout metric=column();summary.addView(metric,rowWeight());time=bold("목적지를 정해볼까요?",23,INK);metric.addView(time);distance=text("주소나 장소 이름으로 찾아보세요",12,MUTED);metric.addView(distance);arrival=bold("",12,BLUE);summary.addView(arrival);
        gap(sheet,7);notice=text("",11,MUTED);notice.setMaxLines(1);notice.setEllipsize(TextUtils.TruncateAt.END);sheet.addView(notice);gap(sheet,12);LinearLayout actions=new LinearLayout(this);sheet.addView(actions,new LinearLayout.LayoutParams(-1,dp(48)));
        settings=button("경로 설정",INK,false,this::routeSettings);LinearLayout.LayoutParams settingsLp=new LinearLayout.LayoutParams(dp(94),-1);settingsLp.rightMargin=dp(8);actions.addView(settings,settingsLp);primary=button("경로 탐색",BLUE,true,this::primaryAction);actions.addView(primary,new LinearLayout.LayoutParams(0,-1,1));gap(sheet,4);
        LinearLayout footer=new LinearLayout(this);footer.setGravity(Gravity.CENTER_VERTICAL);sheet.addView(footer);
        gpsLabel=bold("GPS 연결",11,MUTED);gpsLabel.setGravity(Gravity.CENTER_VERTICAL);gpsLabel.setContentDescription("GPS 상태 · 눌러서 연결 또는 종료");gpsLabel.setOnClickListener(v->toggleGps());footer.addView(gpsLabel,new LinearLayout.LayoutParams(0,dp(44),1));
        TextView privacy=text("정보",11,MUTED);privacy.setGravity(Gravity.CENTER);privacy.setContentDescription("지도·위치 사용 안내");privacy.setOnClickListener(v->privacy());footer.addView(privacy,new LinearLayout.LayoutParams(dp(44),dp(44)));stepsButton=button("안내 목록",BLUE,false,this::showSteps);stepsButton.setTextSize(11);stepsButton.setBackgroundColor(Color.TRANSPARENT);footer.addView(stepsButton,new LinearLayout.LayoutParams(dp(74),dp(44)));
    }
    void mapTypes(){
        String[] labels={"일반지도", "위성지도 / 2025년 10m 시연 영상",state.highResolutionReady()?"고화질 항공지도 / 서버 키 설정됨":"고화질 항공지도 / 연결 필요"};
        int selected=state.mapStyle==RoadMapStyle.ROAD?0:state.mapStyle==RoadMapStyle.SATELLITE?1:2;
        new AlertDialog.Builder(this).setTitle("지도 종류").setSingleChoiceItems(labels,selected,(dialog,which)->{
            if(which==2&&!state.highResolutionReady()){dialog.dismiss();mapConnection();return;}
            state.setMapStyle(which==0?RoadMapStyle.ROAD:which==1?RoadMapStyle.SATELLITE:RoadMapStyle.SATELLITE_HD);dialog.dismiss();
        }).setNeutralButton("고화질 연결",(d,w)->mapConnection()).setNegativeButton("닫기",null).show();
    }
    private void cancelGateway(){gatewayGeneration++;if(gatewayTask!=null)gatewayTask.cancel(true);gatewayTask=null;}
    /** A status check sends no GPS coordinates. Only an explicit successful connection enables HD tiles. */
    void mapConnection(){
        if(gatewayDialog!=null&&gatewayDialog.isShowing())return;
        LinearLayout content=column();content.setPadding(dp(22),dp(8),dp(22),dp(10));
        content.addView(text("선명한 브이월드 항공영상은 발급받은 키를 설정한 지도 서버와 연결합니다. 키는 앱에 넣지 않습니다.",13,INK));gap(content,14);
        EditText origin=new EditText(this);origin.setTextSize(15);origin.setSingleLine(true);origin.setHint("https://지도서버주소");origin.setContentDescription("고화질 지도 서버 주소");origin.setText(state.highResolutionGateway());origin.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);origin.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(240)});content.addView(origin,new LinearLayout.LayoutParams(-1,dp(52)));
        gap(content,10);Button connect=button("연결하고 사용",BLUE,true,()->{});content.addView(connect,new LinearLayout.LayoutParams(-1,dp(48)));gap(content,10);
        TextView status=text(state.highResolutionReady()?"서버 키 설정을 확인했습니다. 실제 인증과 화질은 영상 수신 후 확인할 수 있습니다.":"고화질 지도 미연결. 현재 위성지도는 10m 원본 영상입니다.",12,MUTED);status.setContentDescription("고화질 지도 연결 상태");content.addView(status);gap(content,12);
        if(BuildConfig.DEBUG){Button usb=button("USB 개발 서버 주소 입력",INK,false,()->origin.setText("http://127.0.0.1:4173"));usb.setTextSize(12);content.addView(usb,new LinearLayout.LayoutParams(-1,dp(44)));gap(content,8);content.addView(text("PC 서버 실행과 USB 포트 연결 설정이 필요합니다. 주소를 입력하는 것만으로 조회하지 않습니다.",11,MUTED));}
        gap(content,8);content.addView(text("연결 확인에는 GPS를 보내지 않습니다. 연결 후에는 화면에 표시한 지도 타일 좌표를 선택한 서버로 보냅니다. 설정은 앱 메모리에만 보관합니다.",11,MUTED));
        ScrollView scroll=new ScrollView(this);scroll.addView(content);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("고화질 지도 연결").setView(scroll).setNegativeButton("닫기",(d,w)->cancelGateway()).setNeutralButton("연결 해제",(d,w)->{cancelGateway();state.disconnectHighResolution();}).create();gatewayDialog=dialog;
        dialog.setOnDismissListener(d->{if(gatewayDialog==dialog){cancelGateway();gatewayDialog=null;}});
        origin.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){cancelGateway();connect.setEnabled(true);connect.setText("연결하고 사용");status.setText("주소를 확인한 뒤 연결 버튼을 눌러 주세요");}public void afterTextChanged(Editable value){}});
        connect.setOnClickListener(v->{
            final String address;try{address=RoadMapGateway.normalizeOrigin(origin.getText().toString().trim(),BuildConfig.DEBUG);origin.setError(null);}catch(java.io.IOException error){origin.setError(error.getMessage());return;}
            cancelGateway();final int ticket=gatewayGeneration;connect.setEnabled(false);connect.setText("연결 확인 중…");status.setText("지도 서버의 키 설정을 확인합니다. GPS는 전송하지 않습니다.");
            gatewayTask=gatewayWorker.submit(()->{
                RoadMapGateway result=null;String error=null;try{result=gatewayLookup.check(address);}catch(java.io.IOException|RuntimeException problem){error="지도 서버 주소·실행 상태·HTTPS 연결을 확인한 뒤 다시 시도하세요";}
                final RoadMapGateway answer=result;final String problem=error;
                ui.post(()->{
                    if(destroyed||ticket!=gatewayGeneration||gatewayDialog!=dialog||!dialog.isShowing())return;
                    connect.setEnabled(true);connect.setText("연결하고 사용");
                    if(problem!=null){status.setText(problem);return;}
                    if(answer==null||!answer.configured){status.setText("이 서버에 브이월드 키가 설정되지 않았습니다. 서버의 발급키 설정을 마친 뒤 다시 연결하세요.");return;}
                    state.setHighResolutionGateway(answer);state.setMapStyle(RoadMapStyle.SATELLITE_HD);status.setText("서버 키 설정됨. 실제 인증·영상은 타일 수신 시 확인합니다.");dialog.dismiss();
                });
            });
        });
        dialog.show();dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(state.highResolutionReady());
    }
    private void primaryAction(){if(state.guiding){state.stopAll();return;}if(state.route==null){state.search();return;}if(!state.gpsEnabled){toggleGps();return;}state.startGuidance();if(state.guiding)map.followLocation();}
    private String coordinates(RoadRoute.Point point){return String.format(Locale.US,"%.6f, %.6f",point.lat,point.lon);}
    private String destinationTitle(){if(!state.destinationName.isEmpty())return state.destinationName;return String.format(Locale.US,"%.4f, %.4f",state.destination.lat,state.destination.lon);}
    private void cancelPlaceSearch(){placeGeneration++;if(placeTask!=null)placeTask.cancel(true);placeTask=null;}
    /** Typing stays local. Only Search submits a query, and a result tap prepares a road route. */
    void placeSearch(){
        if(state.guiding){routeSettings();return;}
        if(placeDialog!=null&&placeDialog.isShowing())return;
        LinearLayout content=column();content.setPadding(dp(22),dp(8),dp(22),dp(10));
        EditText query=new EditText(this);query.setSingleLine(true);query.setTextSize(16);query.setTextColor(INK);query.setHint("예: 경복궁, 서울역, 사직로 161");query.setContentDescription("주소·장소명 검색어");query.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(120)});query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);query.setInputType(android.text.InputType.TYPE_CLASS_TEXT);content.addView(query,new LinearLayout.LayoutParams(-1,dp(52)));
        gap(content,10);Button submit=button("검색",BLUE,true,()->{});content.addView(submit,new LinearLayout.LayoutParams(-1,dp(44)));gap(content,12);
        TextView message=text("검색 버튼을 누르면 검색어를 Photon에 전송합니다.",12,MUTED);content.addView(message);gap(content,8);
        LinearLayout results=column();ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.addView(results);
        int resultHeight=Math.max(120,Math.min(280,getResources().getConfiguration().screenHeightDp/3));content.addView(scroll,new LinearLayout.LayoutParams(-1,dp(resultHeight)));
        gap(content,8);content.addView(text("GPS가 꺼져 있으면 설정한 출발지에서 경로를 미리 봅니다.\n장소 검색: Photon · © OpenStreetMap contributors",10,MUTED));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("어디로 갈까요?").setView(content).setNegativeButton("닫기",null).setNeutralButton("지도·좌표로 지정",(d,w)->routeSettings()).create();
        placeDialog=dialog;
        dialog.setOnDismissListener(d->{if(placeDialog==dialog){cancelPlaceSearch();placeDialog=null;}});
        query.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count){cancelPlaceSearch();submit.setEnabled(true);submit.setText("검색");results.removeAllViews();message.setText("검색 버튼을 누르면 검색어를 Photon에 전송합니다.");}
            @Override public void afterTextChanged(Editable value){}
        });
        Runnable search=()->{
            final String term;
            try{term=PlaceSearchClient.normalizeQuery(query.getText().toString());query.setError(null);}catch(java.io.IOException e){query.setError(e.getMessage());return;}
            InputMethodManager keyboard=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.hideSoftInputFromWindow(query.getWindowToken(),0);query.clearFocus();
            cancelPlaceSearch();final int ticket=placeGeneration;submit.setEnabled(false);submit.setText("검색 중…");results.removeAllViews();message.setText("주소·장소를 찾고 있습니다…");
            placeTask=placeWorker.submit(()->{
                List<PlaceSearchClient.Place> found=null;String problem=null;
                try{found=placeClient.search(term);}catch(java.io.IOException e){problem=e.getMessage();}catch(RuntimeException e){problem="장소를 검색하지 못했습니다. 다시 시도해 주세요";}
                final List<PlaceSearchClient.Place> answer=found;final String error=problem;
                ui.post(()->{
                    if(destroyed||ticket!=placeGeneration||placeDialog!=dialog||!dialog.isShowing())return;
                    submit.setEnabled(true);submit.setText("검색");results.removeAllViews();
                    if(error!=null){message.setText(error);return;}
                    if(answer==null||answer.isEmpty()){message.setText("검색 결과가 없습니다. 지역명과 함께 입력해 주세요");return;}
                    message.setText("이름과 주소를 확인하고 목적지를 선택하세요");
                    for(PlaceSearchClient.Place place:answer){
                        LinearLayout row=column();row.setPadding(dp(13),dp(11),dp(13),dp(11));row.setBackground(background(0xfff3f6fb,12));
                        row.addView(bold(place.name,16,INK));if(!place.address.isEmpty()){gap(row,4);row.addView(text(place.address,12,MUTED));}gap(row,5);row.addView(text(place.category+" · 이곳으로 경로 찾기",11,BLUE));
                        row.setContentDescription(place.name+" · "+place.address+" · 이곳으로 경로 찾기");row.setClickable(true);row.setFocusable(true);
                        row.setOnClickListener(v->{if(destroyed||!dialog.isShowing()||state.guiding)return;dialog.dismiss();state.setDestination(place.point(),place.name,place.address);map.fitRoute();requestRoute.run();});
                        results.addView(row,new LinearLayout.LayoutParams(-1,-2));gap(results,8);
                    }
                });
            });
        };
        submit.setOnClickListener(v->search.run());query.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_SEARCH||(event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER&&event.getAction()==KeyEvent.ACTION_UP)){if(submit.isEnabled())search.run();return true;}return false;});
        dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    private void cancelNearbySearch(){nearbyGeneration++;if(nearbyTask!=null)nearbyTask.cancel(true);nearbyTask=null;}
    /** Only the lookup button transmits a chosen center. Opening this sheet never requests GPS. */
    void nearbySearch(String kind){
        if(state.guiding){Toast.makeText(this,"안내를 종료한 뒤 주변 목적지를 선택해 주세요",Toast.LENGTH_SHORT).show();return;}
        if(nearbyDialog!=null&&nearbyDialog.isShowing())return;
        final boolean fuel="fuel".equals(kind);String type=fuel?"주유소":"주차장";
        LinearLayout content=column();content.setPadding(dp(22),dp(4),dp(22),dp(8));
        RadioGroup centers=new RadioGroup(this);RadioButton destination=new RadioButton(this),current=new RadioButton(this);destination.setId(View.generateViewId());current.setId(View.generateViewId());destination.setText("목적지 주변 · "+destinationTitle());current.setText("현재 위치 주변");destination.setTextSize(13);current.setTextSize(13);destination.setTextColor(INK);current.setTextColor(INK);destination.setContentDescription("주변 검색 기준 · 목적지");current.setContentDescription("주변 검색 기준 · 현재 위치");centers.addView(destination);centers.addView(current);centers.check(destination.getId());content.addView(centers);
        TextView gpsHint=text(state.freshFix()?"현재 위치를 선택하면 수신된 최신 GPS로 조회합니다.":"현재 위치 검색은 GPS 연결 후 최신 위치가 필요합니다.",10,MUTED);content.addView(gpsHint);
        if(!state.gpsEnabled){gap(content,5);Button gps=button("GPS 연결",BLUE,false,()->{if(nearbyDialog!=null)nearbyDialog.dismiss();if(!state.gpsEnabled)toggleGps();});gps.setTextSize(12);content.addView(gps,new LinearLayout.LayoutParams(-1,dp(36)));}
        RadioGroup radii=new RadioGroup(this);radii.setOrientation(LinearLayout.HORIZONTAL);RadioButton one=new RadioButton(this),three=new RadioButton(this);one.setId(View.generateViewId());three.setId(View.generateViewId());one.setText("1km");three.setText("3km");one.setTextSize(13);three.setTextSize(13);one.setContentDescription("주변 검색 반경 1km");three.setContentDescription("주변 검색 반경 3km");radii.addView(one);radii.addView(three);radii.check(three.getId());content.addView(radii);
        Button submit=button("주변 "+type+" 조회",BLUE,true,()->{});content.addView(submit,new LinearLayout.LayoutParams(-1,dp(44)));gap(content,10);
        TextView message=text("조회 버튼을 누르면 선택한 기준 좌표를 Photon에 전송합니다.",11,MUTED);content.addView(message);gap(content,6);
        LinearLayout results=column();ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.addView(results);content.addView(scroll,new LinearLayout.LayoutParams(-1,dp(210)));
        gap(content,6);content.addView(text(fuel?"유가 연동 대기 · 표시 거리는 직선 거리입니다.":"잔여 면수 미제공 · 도로 교통량으로 빈자리를 판단하지 않습니다.",10,MUTED));
        ScrollView container=new ScrollView(this);container.addView(content);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("주변 "+type).setView(container).setNegativeButton("닫기",null).setNeutralButton("실시간 정보 안내",(d,w)->nearbyInfo()).create();nearbyDialog=dialog;
        dialog.setOnDismissListener(d->{if(nearbyDialog==dialog){cancelNearbySearch();nearbyDialog=null;}});
        Runnable reset=()->{cancelNearbySearch();submit.setEnabled(true);submit.setText("주변 "+type+" 조회");results.removeAllViews();message.setText("기준 위치·반경을 확인한 뒤 조회해 주세요. 좌표는 조회 버튼을 누를 때만 전송합니다.");};
        centers.setOnCheckedChangeListener((g,id)->reset.run());radii.setOnCheckedChangeListener((g,id)->reset.run());
        submit.setOnClickListener(v->{
            final RoadRoute.Point center;
            if(centers.getCheckedRadioButtonId()==current.getId()){
                if(!state.gpsEnabled||!state.freshFix()){message.setText("최신 GPS 위치가 없습니다. GPS를 연결하고 위치 수신 후 다시 조회해 주세요");return;}
                center=new RoadRoute.Point(state.fix.lat,state.fix.lon);
            }else center=new RoadRoute.Point(state.destination.lat,state.destination.lon);
            final int radius=radii.getCheckedRadioButtonId()==one.getId()?1000:3000;
            cancelNearbySearch();final int ticket=nearbyGeneration;submit.setEnabled(false);submit.setText("조회 중…");results.removeAllViews();message.setText("가까운 "+type+"를 찾고 있습니다…");
            nearbyTask=nearbyWorker.submit(()->{
                NearbySearchClient.Result found=null;String problem=null;
                try{found=nearbyClient.search(center,kind,radius);}catch(java.io.IOException e){problem=e.getMessage();}catch(RuntimeException e){problem="주변 장소를 조회하지 못했습니다. 다시 시도해 주세요";}
                final NearbySearchClient.Result answer=found;final String error=problem;
                ui.post(()->{
                    if(destroyed||ticket!=nearbyGeneration||nearbyDialog!=dialog||!dialog.isShowing())return;
                    submit.setEnabled(true);submit.setText("주변 "+type+" 조회");results.removeAllViews();
                    if(error!=null){message.setText(error);return;}
                    if(answer==null||answer.places.isEmpty()){message.setText("반경 안에 등록된 "+type+(fuel?"가":"이")+" 없습니다. 반경이나 기준 위치를 바꿔 조회해 주세요");return;}
                    message.setText(answer.provider+" · 직선 거리순 · "+answer.places.size()+"곳\n"+answer.notice);
                    for(NearbySearchClient.NearbyPlace place:answer.places){
                        LinearLayout row=column();row.setPadding(dp(12),dp(10),dp(12),dp(10));row.setBackground(background(0xfff3f6fb,12));row.addView(bold(place.name,15,INK));gap(row,3);
                        row.addView(text((place.address.isEmpty()?"상세 주소 미등록":place.address)+" · 직선 "+meters(place.distanceMeters),11,MUTED));gap(row,5);
                        row.addView(bold(fuel?fuelStatus(place.fuelPrice):parkingStatus(place.parking),12,BLUE));
                        String metadata=fuel?liveMetadata(place.fuelPrice.source,place.fuelPrice.updatedAt,place.fuelPrice.priceWonPerLiter!=null):liveMetadata(place.parking.source,place.parking.updatedAt,place.parking.available!=null||place.parking.capacity!=null);
                        if(!metadata.isEmpty()){gap(row,3);row.addView(text(metadata,10,MUTED));}
                        gap(row,5);row.addView(text("이곳으로 경로 찾기",11,BLUE));row.setContentDescription(place.name+" · "+place.address+" · 직선 "+meters(place.distanceMeters)+" · 이곳으로 경로 찾기");row.setClickable(true);row.setFocusable(true);
                        row.setOnClickListener(selected->{if(destroyed||!dialog.isShowing()||state.guiding)return;dialog.dismiss();state.setDestination(place.point(),place.name,place.address);map.fitRoute();requestRoute.run();});results.addView(row,new LinearLayout.LayoutParams(-1,-2));gap(results,8);
                    }
                });
            });
        });dialog.show();
    }
    private String fuelStatus(NearbySearchClient.PriceInfo info){if(info.priceWonPerLiter==null)return "유가 연동 대기";return (info.status.equals("stale")?"이전 보고 가격 · ":"보고 가격 · ")+(info.fuelType.isEmpty()?"유종 미제공":info.fuelType)+" "+String.format(Locale.KOREA,"%,.0f원/L",info.priceWonPerLiter);}
    private String parkingStatus(NearbySearchClient.ParkingInfo info){String capacity=info.capacity==null?"":" · 전체 "+info.capacity+"면";if(info.available==null)return "잔여 면수 미제공"+capacity;return (info.status.equals("stale")?"이전 정보 · ":"보고 시점 · ")+(info.available==0?"만차":"잔여 "+info.available+"면")+capacity;}
    private String liveMetadata(String source,String updatedAt,boolean hasValue){return hasValue?source+" · "+updatedAt+"\n도착 시 달라질 수 있습니다":"";}
    private void nearbyInfo(){new AlertDialog.Builder(this).setTitle("실시간 유가·주차 정보").setMessage("현재는 Photon·OpenStreetMap에 등록된 주변 주유소·주차장을 찾습니다. 거리는 직선 거리이며 실제 도로 경로·영업 여부와 다를 수 있습니다.\n\n실시간 유가는 오피넷, 주차 잔여 면수는 서울시 등 제공 기관의 별도 연동이 필요합니다. 현재 앱에는 API 키가 없어서 가격·빈자리 수를 표시하지 않습니다. 도로 교통량만으로 주차 가능 여부를 계산할 수 없습니다.\n\n추후 값이 연결되면 출처·갱신 시각을 함께 표시하며 오래된 정보와 도착 시 변동을 구분합니다. 제공 지역·주차장에 따라 정보가 없을 수 있습니다.\n\n주변 조회 버튼을 누를 때만 선택한 기준 좌표를 Photon으로 보내며 검색 결과는 앱 메모리에만 잠시 보관합니다.").setPositiveButton("확인",null).show();}
    private EditText coordinateInput(LinearLayout parent,String label,RoadRoute.Point value){
        parent.addView(text(label,12,MUTED));EditText e=new EditText(this);e.setSingleLine(true);e.setTextColor(INK);e.setTextSize(16);e.setContentDescription(label);e.setText(coordinates(value));e.setInputType(android.text.InputType.TYPE_CLASS_TEXT);e.setPadding(dp(10),dp(9),dp(10),dp(9));e.setBackground(background(0xfff1f5fa,12));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48));p.topMargin=dp(6);parent.addView(e,p);gap(parent,15);return e;
    }
    private RoadRoute.Point parse(EditText input){String[] pair=input.getText().toString().trim().split(",",-1);if(pair.length!=2)throw new IllegalArgumentException();double lat=Double.parseDouble(pair[0].trim()),lon=Double.parseDouble(pair[1].trim());if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>85.05112878||Math.abs(lon)>180)throw new IllegalArgumentException();return new RoadRoute.Point(lat,lon);}
    private void routeSettings(){
        if(state.guiding){new AlertDialog.Builder(this).setTitle("길 안내 중입니다").setItems(new String[]{"현재 위치에서 다시 탐색","GPS · 길 안내 모두 종료","취소"},(d,w)->{if(w==0)reroute();else if(w==1)state.stopAll();}).show();return;}
        LinearLayout content=column();content.setPadding(dp(22),dp(10),dp(22),dp(8));content.addView(text("지도 탭 또는 숫자 좌표로 목적지를 지정하세요.",12,MUTED));gap(content,17);
        EditText from=coordinateInput(content,"출발지 · 위도, 경도",state.origin);from.setEnabled(!state.gpsEnabled);if(state.gpsEnabled){content.addView(text("출발지는 최신 GPS 위치를 사용합니다.",11,BLUE));gap(content,10);}
        EditText to=coordinateInput(content,"목적지 · 위도, 경도",state.destination);Button sample=button("서울 시청 → 경복궁 샘플",BLUE,false,()->{});content.addView(sample,new LinearLayout.LayoutParams(-1,dp(44)));gap(content,12);content.addView(text("탐색을 누르면 출발·도착 좌표가 OSRM 공개 서버로 전송됩니다. 실시간 교통은 미반영입니다.",11,MUTED));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("경로 설정").setView(content).setNegativeButton("닫기",null).setPositiveButton("경로 탐색",null).create();sample.setOnClickListener(v->{state.useSeoulSample();map.fitRoute();dialog.dismiss();});
        dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{RoadRoute.Point origin=parse(from),destination=parse(to);if(!state.gpsEnabled&&!coordinates(origin).equals(coordinates(state.origin)))state.setOrigin(origin);if(!coordinates(destination).equals(coordinates(state.destination)))state.setDestination(destination);state.search();dialog.dismiss();}catch(RuntimeException e){to.setError("위도, 경도를 확인하세요");}});
    }
    private void reroute(){if(!state.gpsEnabled||!state.freshFix()){Toast.makeText(this,"최신 GPS 위치를 먼저 받아 주세요",Toast.LENGTH_SHORT).show();return;}state.search();}
    private void menu(){
        String[] items={"나의 운전 · 개인화 시연",state.gpsEnabled?"GPS · 길 안내 모두 종료":"GPS 위치 켜기","현재 위치에서 다시 탐색","지도·위치 사용 안내"};
        new AlertDialog.Builder(this).setTitle("길기억").setItems(items,(d,w)->{if(w==0){if(getPackageName().equals("kr.routememory.mobile"))startActivity(new Intent().setClassName(getPackageName(),"kr.routememory.mobile.MainActivity").putExtra("showLearning",true));else Toast.makeText(this,"개인화 시연은 앱의 차량 홈에서 사용할 수 있습니다",Toast.LENGTH_SHORT).show();}else if(w==1)toggleGps();else if(w==2)reroute();else privacy();}).show();
    }
    private void showSteps(){if(state.route==null)return;LinearLayout list=column();list.setPadding(dp(22),dp(8),dp(22),dp(16));int n=0;for(RoadRoute.Step step:state.route.steps){TextView row=text((++n)+". "+step.cue+"\n"+meters(step.distanceMeters),14,INK);row.setPadding(0,dp(11),0,dp(11));list.addView(row);}ScrollView scroll=new ScrollView(this);scroll.addView(list);new AlertDialog.Builder(this).setTitle("회전 안내 목록").setView(scroll).setPositiveButton("닫기",null).show();}
    private void privacy(){new AlertDialog.Builder(this).setTitle("지도·위치 사용 안내").setMessage("주소·장소 검색 버튼을 누르면 검색어를 Photon 공개 서버로 전송합니다. 입력 중에는 검색하지 않으며 GPS 위치를 주소·장소명 검색에 보내지 않습니다. 주변 주유소·주차장은 조회 버튼을 누를 때 선택한 기준 좌표를 Photon에 전송합니다. 검색어·결과는 앱 메모리에만 잠시 보관합니다.\n\n일반지도는 OpenStreetMap, 위성지도는 EOxCloudless에서 받으며 화면에 표시한 지역의 타일을 요청합니다. 위성영상은 2025년 Sentinel 자료를 합성한 원본 10m 영상으로 실시간이 아닙니다. 크게 확대하면 화질이 거칠어지며 현재 차량·빈 주차면을 판단할 수 없습니다.\n\n경로 탐색·재탐색을 누를 때 출발·도착 좌표를 OSRM 공개 서버로 보냅니다. 외부 제공자는 요청을 기록할 수 있습니다.\n\nGPS는 버튼과 위치 권한 허용 이후에만 수신합니다. 화면을 나가도 GPS 전경 서비스는 계속 동작하며 앱의 GPS 종료 또는 허용한 알림에서 종료할 수 있습니다. 실제 위치 이력·도로 실수 기록을 저장하지 않지만 지도 타일은 캐시합니다.\n\n실시간 교통·실제 도로 개인화는 미연결입니다. 장소 검색 범위와 정확도는 공개 지도 데이터에 따라 달라질 수 있습니다.\n\n일반지도: © OpenStreetMap contributors\nopenstreetmap.org/copyright\n위성영상: EOxCloudless https://cloudless.eox.at by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2025)\nCC BY-NC-SA 4.0 · 비상업용 공모전 시제품\nhttps://creativecommons.org/licenses/by-nc-sa/4.0/\n검색: Photon · 경로: OSRM").setPositiveButton("확인",null).show();}
    private void toggleGps(){
        if(state.gpsEnabled){state.stopAll();return;}
        new AlertDialog.Builder(this).setTitle("GPS 위치를 사용할까요?").setMessage("기기의 현재 위치를 받아 출발지와 길 안내를 갱신합니다. 경로 탐색을 누르면 출발지·목적지 좌표가 OSRM 공개 서버로 전송됩니다. 누적 위치 이력은 저장하지 않습니다. 정밀한 위치를 허용해 주세요.").setNegativeButton("취소",null).setPositiveButton("GPS 켜기",(d,w)->{
            ArrayList<String> permissions=new ArrayList<>();if(!state.hasPermission()){permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);}if(android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)permissions.add(Manifest.permission.POST_NOTIFICATIONS);if(permissions.isEmpty())state.startGps();else requestPermissions(permissions.toArray(new String[0]),701);
        }).show();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==701){if(state.hasPermission())state.startGps();else{state.notice="위치 권한이 없어 GPS를 켜지 않았습니다. 지도 미리 보기는 사용할 수 있습니다";state.changed();}}}
    private String meters(double value){return value>=1000?String.format(Locale.KOREA,"%.1fkm",value/1000):Math.round(Math.max(0,value))+"m";}
    private String turnKind(RoadRoute.Step step){if(step==null)return "straight";if(step.type.equals("arrive"))return "arrive";if(step.type.contains("roundabout")||step.type.contains("rotary"))return "roundabout";if(step.modifier.contains("left"))return "left";if(step.modifier.contains("right"))return "right";return "straight";}
    private void refresh(){
        if(map==null)return;map.setMapStyle(state.mapStyle);mapStyleButton.setText(state.mapStyle==RoadMapStyle.SATELLITE_HD?"고화질 지도":state.mapStyle.label);mapStyleButton.setContentDescription("지도 유형: "+state.mapStyle.label+". 지도 종류 선택");mapStyleButton.setTextColor(state.mapStyle!=RoadMapStyle.ROAD?BLUE:INK);destinationLabel.setText(destinationTitle());originLabel.setText(state.gpsEnabled?"길기억 · 현재 위치에서":"길기억 · 경로 준비");gpsLabel.setText(state.gpsEnabled?(state.fix==null?"GPS 연결 중":"GPS 오차 "+Math.round(state.fix.accuracy)+"m"):"GPS 연결");gpsLabel.setTextColor(state.gpsEnabled?GREEN:MUTED);notice.setText(state.notice);stepsButton.setEnabled(state.route!=null);stepsButton.setAlpha(state.route!=null?1:.4f);settings.setText(state.guiding?"주행 설정":"경로 설정");
        if(state.guiding&&state.tracker!=null){
            time.setText(Math.max(1,Math.round(state.tracker.remainingSeconds/60))+"분");time.setTextSize(32);time.setTextColor(BLUE);distance.setText("남은 거리 "+meters(state.tracker.remainingMeters));arrival.setText(new java.text.SimpleDateFormat("HH:mm",Locale.KOREA).format(new java.util.Date(System.currentTimeMillis()+Math.round(state.tracker.remainingSeconds)*1000))+" 도착");
            turnCard.setVisibility(View.VISIBLE);turnMeta.setText("다음 안내 · GPS 주행 중");turnDistance.setText(meters(state.tracker.nextDistanceMeters));turnCue.setText(state.tracker.nextCue);turnIcon.setKind(turnKind(state.tracker.nextStep()));if(state.tracker.status==RoadTracker.Status.STALE||state.tracker.status==RoadTracker.Status.POOR_ACCURACY)turnMeta.setText("GPS 수신 대기 · 안내 보류");if(state.tracker.status==RoadTracker.Status.OFF_ROUTE){turnDistance.setText("경로 이탈");turnDistance.setTextSize(25);turnCue.setText("정확도를 확인한 뒤 다시 탐색해 주세요");}else turnDistance.setTextSize(31);primary.setText("안내 종료");primary.setEnabled(true);
        }else if(state.route!=null){
            time.setText(Math.max(1,Math.round(state.route.durationSeconds/60))+"분");time.setTextSize(32);time.setTextColor(BLUE);distance.setText(meters(state.route.distanceMeters)+" · 실시간 교통 미반영");arrival.setText("경로 미리 보기");RoadRoute.Step next=state.route.steps.get(Math.min(1,state.route.steps.size()-1));turnCard.setVisibility(View.VISIBLE);turnMeta.setText("경로 미리 보기 · GPS 안내 전");turnDistance.setText(meters(state.route.steps.get(0).distanceMeters));turnDistance.setTextSize(31);turnCue.setText(next.cue);turnIcon.setKind(turnKind(next));if(!state.gpsEnabled){primary.setText("GPS 연결 후 안내");primary.setEnabled(true);}else{primary.setText(state.freshFix()?"안내 시작":"GPS 수신 대기");primary.setEnabled(state.freshFix());}
        }else{time.setText(state.loading?"경로를 찾고 있어요":"목적지를 정해볼까요?");time.setTextSize(23);time.setTextColor(INK);distance.setText("지도 탭 · 좌표로 목적지 선택");arrival.setText("");turnCard.setVisibility(View.GONE);primary.setText(state.loading?"탐색 중…":"경로 탐색");primary.setEnabled(!state.loading);}
        primary.setAlpha(primary.isEnabled()?1:.48f);map.setData(state.route,state.fix,state.origin,state.destination);if(shownRoute!=state.route){shownRoute=state.route;if(shownRoute!=null)map.fitRoute();}if(state.guiding&&!wasGuiding)map.followLocation();wasGuiding=state.guiding;
    }
    /** Code-native icons, shared visual language with the PC version. */
    private final class NavIcon extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final Path path=new Path();private String kind;private final int color;
        NavIcon(String kind,int color){super(RoadActivity.this);this.kind=kind;this.color=color;setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);}
        void setKind(String value){if(!kind.equals(value)){kind=value;invalidate();}}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);int save=canvas.save();float unit=Math.min(getWidth(),getHeight())/24f;canvas.translate((getWidth()-24*unit)/2,(getHeight()-24*unit)/2);canvas.scale(unit,unit);paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);path.reset();
            switch(kind){
                case "search":canvas.drawCircle(10.5f,10.5f,6.5f,paint);canvas.drawLine(15.5f,15.5f,21,21,paint);break;
                case "more":paint.setStyle(Paint.Style.FILL);canvas.drawCircle(12,5,1.4f,paint);canvas.drawCircle(12,12,1.4f,paint);canvas.drawCircle(12,19,1.4f,paint);break;
                case "location":canvas.drawCircle(12,12,6.5f,paint);canvas.drawCircle(12,12,2,paint);canvas.drawLine(12,1.5f,12,5,paint);canvas.drawLine(12,19,12,22.5f,paint);canvas.drawLine(1.5f,12,5,12,paint);canvas.drawLine(19,12,22.5f,12,paint);break;
                case "fit":path.moveTo(3,8);path.lineTo(3,3);path.lineTo(8,3);path.moveTo(16,3);path.lineTo(21,3);path.lineTo(21,8);path.moveTo(21,16);path.lineTo(21,21);path.lineTo(16,21);path.moveTo(8,21);path.lineTo(3,21);path.lineTo(3,16);canvas.drawPath(path,paint);break;
                case "plus":canvas.drawLine(5,12,19,12,paint);canvas.drawLine(12,5,12,19,paint);break;
                case "minus":canvas.drawLine(5,12,19,12,paint);break;
                case "left":path.moveTo(16,21);path.lineTo(16,11);path.quadTo(16,7,12,7);path.lineTo(4,7);path.moveTo(8,3);path.lineTo(4,7);path.lineTo(8,11);paint.setStrokeWidth(2.6f);canvas.drawPath(path,paint);break;
                case "right":case "route":path.moveTo(8,21);path.lineTo(8,11);path.quadTo(8,7,12,7);path.lineTo(20,7);path.moveTo(16,3);path.lineTo(20,7);path.lineTo(16,11);paint.setStrokeWidth(2.6f);canvas.drawPath(path,paint);break;
                case "roundabout":canvas.drawArc(5,5,19,19,25,295,false,paint);path.moveTo(20,5);path.lineTo(20,11);path.lineTo(14,11);canvas.drawPath(path,paint);break;
                case "arrive":canvas.drawLine(6,21,6,3,paint);path.moveTo(6,3);path.lineTo(20,3);path.lineTo(17,8);path.lineTo(20,13);path.lineTo(6,13);canvas.drawPath(path,paint);break;
                default:path.moveTo(12,21);path.lineTo(12,3);path.moveTo(6,9);path.lineTo(12,3);path.lineTo(18,9);paint.setStrokeWidth(2.6f);canvas.drawPath(path,paint);break;
            }canvas.restoreToCount(save);
        }
    }
}
