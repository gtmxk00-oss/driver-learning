package kr.routememory.pleos;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;
import kr.routememory.coach.CoachActivity;
import kr.routememory.core.DriveCoach;
import kr.routememory.core.DriveCoach.DriverType;
import kr.routememory.core.DriveCoach.Profile;
import kr.routememory.core.DriveCoach.Signal;
import kr.routememory.core.GuidanceSession;
import kr.routememory.core.GuidanceSession.Event;
import kr.routememory.core.GuidanceSession.Outcome;

/**
 * The companion on the Pleos Connect SDKs: turn signal and gear from the Vehicle SDK, next turn and reroutes from
 * NaviHelper. Needs the Pleos Connect emulator or a Pleos car; on anything else it reports which service is missing.
 */
public class GuidanceActivity extends Activity implements PleosBridge.Host {
    static final String BADGE="Pleos SDK 연동 · 방향지시등·기어·내비 이벤트 실제 · 공식 에뮬레이터 또는 실차 필요";
    private static final int SIM_BG=0xFF14151A,SIM_PANEL=0xFF1D1F25,SIM_INK=0xFFF1EEE8,SIM_INK2=0xFFB6B3AC;
    private static final long SPEAK_MS=4400;
    private CoachActivity.Palette p;
    private GuidanceSession session; private PleosBridge bridge;
    private TextToSpeech voice; private boolean voiceReady;
    private String spoken=""; private long spokenAt; private String vehicleState="연결 전",naviState="연결 전",turnLabel="다음 회전 없음",naviStatus="-",gear="-";
    private int meters=-1; private Signal signal=Signal.OFF;
    private TextView headline,distance,metrics,tag,chips,statusLabel,status,facts;
    private LinearLayout statusBox,typeBar,answers; private Button connect;

    @Override protected void onCreate(Bundle saved) {
        boolean night=(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        String mode=getSharedPreferences("coach",MODE_PRIVATE).getString("mode","auto");
        boolean dark=mode.equals("dark")||mode.equals("auto")&&night;
        p=new CoachActivity.Palette(dark);
        setTheme(dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);super.onCreate(saved);
        session=new GuidanceSession(load());
        bridge=new PleosBridge(this,session,this);
        build();render();
        voice=new TextToSpeech(getApplicationContext(),code->{voiceReady=code==TextToSpeech.SUCCESS&&voice!=null&&voice.setLanguage(Locale.KOREAN)>=TextToSpeech.LANG_AVAILABLE;});
    }
    @Override protected void onDestroy(){bridge.release();if(voice!=null)voice.shutdown();super.onDestroy();}
    PleosBridge bridge(){return bridge;}

    // What the driver confirmed stays on this device, per driver type.
    private Profile load() {
        SharedPreferences prefs=getSharedPreferences("coach-guidance",MODE_PRIVATE);
        DriverType type=DriverType.GENERAL;
        try{type=DriverType.valueOf(prefs.getString("driver","GENERAL"));}catch(IllegalArgumentException ignored){}
        Profile profile=new Profile("car","차량",type);
        DriveCoach.restore(profile,prefs.getString("memory-"+type.name(),""));
        return profile;
    }
    private void save() {
        Profile profile=session.profile();
        getSharedPreferences("coach-guidance",MODE_PRIVATE).edit().putString("driver",profile.driverType.name()).putString("memory-"+profile.driverType.name(),DriveCoach.snapshot(profile)).apply();
    }

    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private TextView text(int size,int color,boolean bold){TextView t=new TextView(this);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(2),1);if(bold)t.setTypeface(null,Typeface.BOLD);return t;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private Button button(String title,boolean primary,Runnable action) {
        Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextColor(primary?p.accentInk:p.ink);
        b.setBackground(round(primary?p.accent:p.surface2,10));b.setMinHeight(dp(48));b.setOnClickListener(v->action.run());return b;
    }
    private void build() {
        boolean wide=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(p.canvas);
        TextView badge=text(13,p.ink,true);badge.setText(BADGE);badge.setPadding(dp(16),dp(10),dp(16),dp(6));root.addView(badge);
        LinearLayout stage=new LinearLayout(this);stage.setOrientation(wide?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);
        root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout navi=new LinearLayout(this);navi.setOrientation(LinearLayout.VERTICAL);navi.setBackgroundColor(SIM_BG);
        LinearLayout head=new LinearLayout(this);head.setBackgroundColor(SIM_PANEL);head.setPadding(dp(18),dp(14),dp(18),dp(14));head.setGravity(Gravity.CENTER_VERTICAL);
        headline=text(24,SIM_INK,true);distance=text(24,SIM_INK,true);
        head.addView(headline,new LinearLayout.LayoutParams(0,-2,1));head.addView(distance);navi.addView(head);
        metrics=text(15,SIM_INK2,false);metrics.setPadding(dp(18),dp(14),dp(18),dp(14));metrics.setLineSpacing(dp(6),1);navi.addView(metrics);
        stage.addView(navi,wide?new LinearLayout.LayoutParams(0,-1,6):new LinearLayout.LayoutParams(-1,0,5));

        LinearLayout app=new LinearLayout(this);app.setOrientation(LinearLayout.VERTICAL);app.setBackgroundColor(p.surface);app.setPadding(dp(16),dp(12),dp(16),dp(12));
        TextView brand=text(18,p.accentText,true);brand.setText("길기억");app.addView(brand);
        tag=text(15,p.ink,true);app.addView(tag);chips=text(13,p.ink2,false);app.addView(chips);
        statusBox=new LinearLayout(this);statusBox.setOrientation(LinearLayout.VERTICAL);statusBox.setGravity(Gravity.CENTER_VERTICAL);statusBox.setPadding(dp(14),dp(10),dp(14),dp(10));
        statusLabel=text(12,p.ink2,true);status=text(18,p.ink,true);statusBox.addView(statusLabel);statusBox.addView(status);
        answers=new LinearLayout(this);answers.setPadding(0,dp(8),0,0);
        answers.addView(button("네, 놓쳤어요",true,()->answer(true)),new LinearLayout.LayoutParams(0,-2,1));
        Button no=button("아니요",false,()->answer(false));LinearLayout.LayoutParams noParams=new LinearLayout.LayoutParams(0,-2,1);noParams.leftMargin=dp(8);answers.addView(no,noParams);
        statusBox.addView(answers);
        LinearLayout.LayoutParams boxParams=new LinearLayout.LayoutParams(-1,0,1);boxParams.topMargin=dp(8);boxParams.bottomMargin=dp(8);app.addView(statusBox,boxParams);
        facts=text(13,p.ink2,false);app.addView(facts);
        stage.addView(app,wide?new LinearLayout.LayoutParams(0,-1,4):new LinearLayout.LayoutParams(-1,0,4));

        TextView note=text(14,p.ink,false);note.setPadding(dp(16),dp(8),dp(16),dp(4));
        note.setText("연결을 누르면 Vehicle SDK와 NaviHelper SDK에 붙습니다. 내비가 안내하는 다음 회전과 방향지시등으로 판단하고, 재탐색 뒤 정차하면 놓쳤는지 묻습니다.");root.addView(note);
        LinearLayout controls=new LinearLayout(this);controls.setPadding(dp(16),dp(4),dp(16),dp(4));
        connect=button("연결",true,()->{if(bridge.connected())bridge.release();else bridge.connect();render();});
        controls.addView(connect,new LinearLayout.LayoutParams(0,-2,1));
        Button clear=button("기억 지우기",false,()->{DriveCoach.clearMemory(session.profile());save();render();});
        LinearLayout.LayoutParams second=new LinearLayout.LayoutParams(0,-2,1);second.leftMargin=dp(8);controls.addView(clear,second);
        Button back=button("가상 시연",false,this::finish);
        LinearLayout.LayoutParams third=new LinearLayout.LayoutParams(0,-2,1);third.leftMargin=dp(8);controls.addView(back,third);
        root.addView(controls);
        typeBar=new LinearLayout(this);typeBar.setPadding(dp(16),dp(4),dp(16),dp(10));
        for(DriverType type:DriverType.values()) {
            Button b=button(type.label,false,()->{save();Profile next=new Profile("car","차량",type);DriveCoach.restore(next,getSharedPreferences("coach-guidance",MODE_PRIVATE).getString("memory-"+type.name(),""));session.setProfile(next);save();render();});
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-2,1);params.rightMargin=dp(6);typeBar.addView(b,params);
        }
        root.addView(typeBar);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        setContentView(root);
    }
    private void answer(boolean confirmed){session.answer(confirmed);spoken="";save();render();}

    // Host: everything arrives on the main thread.
    @Override public void onLink(String v,String n){vehicleState=v;naviState=n;render();}
    @Override public void onVehicle(Signal s,String g){signal=s;gear=g;render();}
    @Override public void onNavigation(String label,int m,String st){turnLabel=label;meters=m;naviStatus=st;render();}
    @Override public void onOutcome(Outcome outcome,String source) {
        if(outcome.event==Event.SPEAK||outcome.event==Event.QUESTION){spoken=outcome.speech;spokenAt=System.currentTimeMillis();if(voiceReady)voice.speak(outcome.speech,TextToSpeech.QUEUE_ADD,null,"guidance");}
        if(outcome.event==Event.MISS_CANDIDATE||outcome.event==Event.PASSED||outcome.event==Event.QUESTION)save();
        render();
    }
    private static String keepWords(String value) {
        StringBuilder out=new StringBuilder();
        for(int i=0;i<value.length();i++){char c=value.charAt(i);out.append(c);if(i+1<value.length()&&c!=' '&&value.charAt(i+1)!=' ')out.append('⁠');}
        return out.toString();
    }
    private void statusView(int background,int label,int body,String labelText,String bodyText) {
        statusBox.setBackground(round(background,12));statusLabel.setTextColor(label);status.setTextColor(body);statusLabel.setText(labelText);status.setText(keepWords(bodyText));
    }
    private void render() {
        long now=System.currentTimeMillis();Profile profile=session.profile();DriverType type=profile.driverType;
        headline.setText(turnLabel);distance.setText(meters>=0?meters+"m":"");
        metrics.setText("방향지시등: "+(signal==Signal.LEFT?"왼쪽":signal==Signal.RIGHT?"오른쪽":"꺼짐")+"\n기어: "+gear+"\n내비 상태: "+naviStatus+"\nVehicle SDK: "+vehicleState+"\nNaviHelper SDK: "+naviState);
        tag.setText("차량 연동 · "+type.label);
        chips.setText(type.label+" 유형 · "+(bridge.connected()?"연결됨":"연결 안 됨"));
        facts.setText("약한 유형: "+(DriveCoach.weakLabels(profile).isEmpty()?"없음":String.join(", ",DriveCoach.weakLabels(profile)))+"\n기억 지점: "+profile.placeCount()+"곳 · 확인 대기: "+profile.pendingCount()+"건");
        connect.setText(bridge.connected()?"연결 해제":"연결");
        for(int i=0;i<typeBar.getChildCount();i++)typeBar.getChildAt(i).setBackground(round(DriveCoach.DriverType.values()[i]==type?p.accentSoft:p.surface2,10));
        answers.setVisibility(session.pendingQuestion()!=null?android.view.View.VISIBLE:android.view.View.GONE);
        if(session.pendingQuestion()!=null){statusView(p.accent,p.accentInk,p.accentInk,"정차 중 질문",session.pendingQuestion());return;}
        if(!spoken.isEmpty()&&now-spokenAt<SPEAK_MS){statusView(p.accent,p.accentInk,p.accentInk,"말하는 중",spoken);return;}
        if(!bridge.connected()){statusView(p.surface2,p.ink2,p.ink,"연결 안 됨","연결을 누르면 Pleos SDK에 붙습니다");return;}
        if(session.currentTurn()==null){statusView(p.surface2,p.ink2,p.ink,"지켜보는 중","내비가 다음 회전을 알려 주면 판단합니다");return;}
        if(DriveCoach.isWeak(profile,session.currentTurn().type)||DriveCoach.remembers(profile,session.currentTurn().id)){statusView(p.surface2,p.ink2,p.ink,"지켜보는 중",session.currentTurn().type.label+" · 판단 거리 "+DriveCoach.decisionMeters(profile,session.currentTurn())+"m");return;}
        statusView(p.surface2,p.ink2,p.ink,"지켜보는 중","이 회전은 아직 기록이 없습니다");
    }
}
