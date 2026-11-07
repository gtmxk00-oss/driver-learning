package kr.routememory.mobile;

import android.app.*;
import android.os.Bundle;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.speech.RecognizerIntent;
import android.view.*;
import android.widget.*;
import java.util.ArrayList;
import kr.routememory.core.Engine;
import kr.routememory.shared.*;

/** Phone companion for Android Auto: prepare, simulate, review; car uses native templates. */
public final class MainActivity extends Activity {
    private static final int BG=Color.rgb(244,247,251),CARD=Color.WHITE,INK=Color.rgb(24,37,54),
        MUTED=Color.rgb(115,128,147),ACCENT=Color.rgb(36,107,253);
    private DemoController state;
    private LinearLayout page;
    private final Runnable observer=this::render;
    private boolean listening;
    @Override public void onCreate(Bundle saved) {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar);super.onCreate(saved);state=DemoController.get(this);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        handle(getIntent());
    }
    @Override protected void onNewIntent(Intent intent) {super.onNewIntent(intent);setIntent(intent);handle(intent);}
    private void handle(Intent intent) {
        if("androidx.car.app.action.NAVIGATE".equals(intent.getAction())) {
            kr.routememory.core.RoadRoute.Point point=RoadIntents.destination(intent);
            if(point!=null)RoadController.get(this).setDestination(point);
            else Toast.makeText(this,"앱의 검색창에서 주소나 장소를 검색해 주세요",Toast.LENGTH_LONG).show();
            openMap();
        }
        else if(!intent.getBooleanExtra("showLearning",false)&&!state.running()&&!state.needsReview())openMap();
    }
    private void openMap(){startActivity(new Intent(this,RoadActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));finish();}
    @Override protected void onStart(){super.onStart();if(!isFinishing()){state.addListener(observer);render();}}
    @Override protected void onStop(){state.removeListener(observer);super.onStop();}
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private TextView text(String value,int size,int color) {
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);
        t.setLineSpacing(dp(3),1);return t;
    }
    private GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private void space(LinearLayout parent,int height){View v=new View(this);parent.addView(v,new LinearLayout.LayoutParams(1,dp(height)));}
    private LinearLayout box(){LinearLayout b=new LinearLayout(this);b.setOrientation(LinearLayout.VERTICAL);b.setPadding(dp(20),dp(20),dp(20),dp(20));b.setBackground(bg(CARD,20));page.addView(b,new LinearLayout.LayoutParams(-1,-2));space(page,14);return b;}
    private void button(LinearLayout parent,String title,boolean primary,Runnable action){
        Button b=new Button(this);b.setText(title);b.setTextSize(16);b.setAllCaps(false);
        b.setTextColor(primary?Color.WHITE:INK);b.setBackground(bg(primary?ACCENT:Color.rgb(235,241,248),14));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(56));p.topMargin=dp(10);parent.addView(b,p);b.setOnClickListener(v->action.run());
    }
    private void render() {
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(24),dp(26),dp(24),dp(30));
        scroll.addView(page);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{
            v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;
        });
        TextView brand=text("↱  길기억",26,ACCENT);brand.setTypeface(null,Typeface.BOLD);page.addView(brand);
        space(page,6);page.addView(text("나의 운전 · 개인화 시연",12,MUTED));space(page,24);
        if(state.running()){driving();return;}
        if(state.needsReview()){review();return;}
        page.addView(text("나를 기억하는\n다음 주행",29,INK));space(page,10);
        page.addView(text("실제 지도와 GPS 길 안내를 휴대전화와 Android Auto에서 사용하세요. 개인별 학습은 아래 가상 주행으로 체험할 수 있습니다.",14,MUTED));
        button(page,"지도로 돌아가기",true,this::openMap);
        button(page,"동반앱 시연",false,()->startActivity(new Intent(this,kr.routememory.coach.CoachActivity.class)));space(page,24);
        if(!state.notice.isEmpty()){page.addView(text(state.notice,14,ACCENT));space(page,16);}
        LinearLayout personal=box();personal.addView(text(state.profile.name+"의 맞춤 안내",14,MUTED));
        TextView metric=text(state.lead()+"m 전에 안내",32,ACCENT);metric.setTypeface(null,Typeface.BOLD);personal.addView(metric);
        personal.addView(text("확인한 출구 놓침 "+state.profile.misses("exit-a")+"회 · 기본 300m\n개인 기록에 따라 최대 900m까지 앞당깁니다.",14,MUTED));
        for(Engine.Route r:Engine.routes()){
            LinearLayout choice=box();String selected=state.route==r?"● ":"○ ";
            choice.addView(text(selected+r.name+"   "+r.minutes+"분",20,state.route==r?ACCENT:INK));
            choice.addView(text(state.routeSummary(r),14,MUTED));
            if(state.engine.recommend(state.profile)==r)choice.addView(text("현재 운전자에게 추천",12,ACCENT));
            choice.setContentDescription(r.name+" 선택");choice.setOnClickListener(v->state.chooseRoute(r));
        }
        LinearLayout brief=box();brief.addView(text("출발 전, 이 구간만 미리 보세요",18,INK));
        for(Engine.Segment seg:state.route.segments){space(brief,10);brief.addView(text(seg.name+"\n"+seg.cue+" · "+state.engine.leadMeters(state.profile,seg)+"m 전",14,MUTED));}
        button(page,"선택한 길로 주행 시연",true,()->state.start(false));
        button(page,"실수 → 학습 → 다음 안내 시연",false,()->state.start(true));
        space(page,12);page.addView(text("실수 시연은 첫 출구 이탈을 자동으로 발생시킵니다.\n주행이 끝난 후 확인해야 학습됩니다. 약 45초 소요.",12,MUTED));space(page,24);
        LinearLayout tools=box();tools.addView(text("정차 중 준비",18,INK));
        button(tools,"운전자 변경 · "+state.profile.name,false,state::switchDriver);
        button(tools,"합류 구간 메모",false,()->memoDialog(""));
        button(tools,"말로 메모 남기기",false,this::voiceMemo);
        space(tools,8);tools.addView(text("음성 인식은 설치된 인식 서비스가 처리하며, 서비스 설정에 따라 서버를 사용할 수 있습니다.",12,MUTED));
        String note=state.profile.notes.getOrDefault("merge-c","메모 없음");space(tools,12);tools.addView(text("새빛 합류: "+note,14,MUTED));
        if(state.profile.avoid.contains("merge-c"))tools.addView(text("다음 경로 비교에 회피 선호 반영 중",12,ACCENT));
        button(tools,"현재 운전자 기록 삭제",false,()->new AlertDialog.Builder(this)
            .setTitle("학습 기록을 삭제할까요?").setMessage("현재 운전자의 시연 기록과 메모가 삭제됩니다.")
            .setNegativeButton("취소",null).setPositiveButton("삭제",(d,w)->state.clearCurrentDriver()).show());
        page.addView(text("학습 기록은 이 기기에만 저장됩니다. 규칙 기반 개인화이며, 가상 경로의 시간·부담 점수는 시연용 수치입니다. 실제 도로 기록 학습과 차량 실기기 검증은 후속 단계입니다.",12,MUTED));
    }
    private void driving() {
        Engine.Trip trip=state.trip;
        page.addView(text("가상 주행 중",14,MUTED));space(page,10);
        page.addView(text(trip.status==Engine.Trip.Status.REROUTING?"다음 경로로\n안내하겠습니다":trip.remaining+"m",36,ACCENT));
        space(page,10);page.addView(text(trip.message,23,INK));space(page,18);
        View map=new View(this){final RoutePainter painter=new RoutePainter();final Rect bounds=new Rect();@Override protected void onDraw(Canvas c){bounds.set(0,0,getWidth(),getHeight());painter.draw(c,bounds,state);}};
        page.addView(map,new LinearLayout.LayoutParams(-1,dp(280)));space(page,14);
        page.addView(text(trip.segment().name+" · "+state.profile.name,14,MUTED));
        page.addView(text("가상 이동 100m / 1.2초 · 실제 차속과 무관",12,MUTED));
        button(page,state.isPaused()?"시연 계속":"시연 일시정지",false,()->state.pause(!state.isPaused()));
        button(page,"시연 종료",false,state::stop);
    }
    private void review() {
        page.addView(text("주행을 돌아볼까요?",29,INK));space(page,18);
        LinearLayout card=box();card.addView(text("가상 한빛 분기점에서 경로 이탈",20,INK));space(card,10);
        card.addView(text("출구를 놓친 경우에만 실수로 학습합니다. 다음 주행에서는 지금보다 150m 먼저 안내합니다.",16,MUTED));
        button(page,"출구를 놓쳤어요 · 학습",true,()->state.review(true));
        button(page,"의도한 경로 변경 · 학습 안 함",false,()->state.review(false));
    }
    private void memoDialog(String initial) {
        if(state.running()||state.needsReview())return;
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(24),dp(8),dp(24),dp(8));
        EditText input=new EditText(this);input.setHint("예: 여기 합류하기 어려워");input.setText(initial.isEmpty()?state.profile.notes.getOrDefault("merge-c",""):initial);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)});content.addView(input);
        CheckBox avoid=new CheckBox(this);avoid.setText("다음 경로 비교에서 이 합류 구간 회피 선호");avoid.setChecked(state.profile.avoid.contains("merge-c"));content.addView(avoid);
        new AlertDialog.Builder(this).setTitle("가상 새빛 합류 메모").setView(content)
            .setNegativeButton("취소",null).setPositiveButton("저장",(d,w)->state.setMergeMemo(input.getText().toString(),avoid.isChecked())).show();
    }
    private void voiceMemo() {
        if(state.running()||listening)return;
        Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ko-KR");
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT,"새빛 합류 구간 메모를 말해주세요");
        try{listening=true;startActivityForResult(intent,71);}
        catch(ActivityNotFoundException e){listening=false;Toast.makeText(this,"음성 인식 앱이 없어 글로 메모합니다",Toast.LENGTH_LONG).show();memoDialog("");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);listening=false;
        if(request==71&&result==RESULT_OK&&data!=null){ArrayList<String> words=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);if(words!=null&&!words.isEmpty())memoDialog(words.get(0));}
    }
}
