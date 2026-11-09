package kr.routememory.pleos;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.nio.file.Files;
import java.util.Locale;
import kr.routememory.coach.CoachActivity;
import kr.routememory.core.DriveCoach;
import kr.routememory.core.DriveCoach.DriverType;
import kr.routememory.core.DriveCoach.Familiarity;
import kr.routememory.core.DriveCoach.Profile;
import kr.routememory.core.DriveCoach.Signal;
import kr.routememory.core.GuidanceSession;
import kr.routememory.core.GuidanceSession.Event;
import kr.routememory.core.GuidanceSession.Outcome;

/**
 * The companion on the Pleos Connect SDKs: turn signal, gear and the passenger seat from the Vehicle SDK, next turns,
 * reroutes and the destination from NaviHelper, the speed from the Car API or from positions, the position from
 * FusedLocation, and a spoken memo or answer through SpeechToText. Needs the Pleos Connect emulator or a Pleos car;
 * on anything else it reports which service is missing. While the car asks for a simplified screen it shows only its
 * status line and its question.
 */
public class GuidanceActivity extends Activity implements PleosBridge.Host,PleosVoice.Host {
    static final String BADGE="Pleos SDK 5종 연동 · 차량 신호 9종·내비 이벤트·위치·근접 알림·음성 인식·합성 실제 · 공식 에뮬레이터 또는 실차 필요";
    // Set in onCreate from the day or night mode: in day mode the turn card is white with black ink, like the demo screen and the built-in panels.
    private int SIM_BG,SIM_PANEL,SIM_INK,SIM_INK2,SIM_LANE,SIM_ROUTE=0xFFFF7A26,METRIC,STROKE,PLAIN;
    private static final long SPEAK_MS=4400,LONG_MS=9000;
    private static final int REQUEST_SPEED=41;
    private static final String CAR_SPEED_PERMISSION="android.car.permission.CAR_SPEED";
    private CoachActivity.Palette p;
    private GuidanceSession session; private PleosBridge bridge; private PleosVoice voice;
    private UxRestrictionsWatch restrictions;
    private PleosSpeaker speaker; private String speakerState="준비 전",carLine="모름",roadLine="모름";
    private String spoken="",spokenLabel=""; private long spokenAt,spokenFor;
    private String vehicleState="연결 전",naviState="연결 전",speedSource="-",locationState="-",voiceState="준비 전",turnLabel="다음 회전 없음",naviStatus="-",gear="-",destination="";
    private int meters=-1,speedKph=-1; private Signal signal=Signal.OFF; private boolean restricted,heardIsAnswer;
    private TextView headline,distance,metrics,tag,chips,statusLabel,status,facts,note,laneText; private LinearLayout lanes;
    private LinearLayout statusBox,typeBar,answers,controls,memoRow,rows; private Button connect,yes,no,memoButton,voiceButton;

    @Override protected void onCreate(Bundle saved) {
        boolean night=(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        String mode=getSharedPreferences("coach",MODE_PRIVATE).getString("mode","auto");
        boolean dark=mode.equals("dark")||mode.equals("auto")&&night;
        p=CoachActivity.Palette.pleos(dark);
        SIM_BG=dark?0xFF0F0F10:0xFFF4F4F4;SIM_PANEL=dark?0xFF1F1F22:0xFFFFFFFF;SIM_INK=dark?0xFFF5F5F5:0xFF111111;SIM_INK2=dark?0xFFB5B5B8:0xFF666666;SIM_LANE=dark?0xFF55555A:0xFFB0B0B5;
        METRIC=dark?0xFFB0B0B3:0xFF444444;STROKE=dark?0xFF3A3A3E:0xFFCFCFCF;PLAIN=dark?0xFF1A1A1C:0xFFFFFFFF;
        setTheme(dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);super.onCreate(saved);
        session=new GuidanceSession(load());
        bridge=new PleosBridge(this,session,this);
        voice=new PleosVoice(this,this);
        speaker=new PleosSpeaker(this,state->{speakerState=state;render();});
        build();render();
        try{restrictions=new UxRestrictionsWatch();restrictions.start(this,value->{restricted=value;render();});}catch(RuntimeException|LinkageError e){restrictions=null;}
    }
    @Override protected void onDestroy(){bridge.release();voice.release();if(restrictions!=null)try{restrictions.stop();}catch(RuntimeException|LinkageError ignored){}if(speaker!=null)speaker.release();super.onDestroy();}
    PleosBridge bridge(){return bridge;}
    GuidanceSession session(){return session;}

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
        // The location service watches the remembered places; a changed memory means a changed watch list.
        if(bridge!=null&&bridge.connected())bridge.watchPlaces();
    }

    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private TextView text(int size,int color,boolean bold){TextView t=new TextView(this);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(0,1.4f);if(bold)t.setTypeface(null,Typeface.BOLD);return t;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private Button button(String title,boolean primary,Runnable action) {
        Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextColor(primary?p.accentInk:p.ink);
        GradientDrawable shape=round(primary?p.accent:PLAIN,10);if(!primary)shape.setStroke(dp(1),STROKE);b.setBackground(shape);b.setMinHeight(dp(72));b.setTextSize(22);b.setOnClickListener(v->action.run());return b;
    }
    private LinearLayout.LayoutParams grow(int leftMargin){LinearLayout.LayoutParams l=new LinearLayout.LayoutParams(0,-2,1);l.leftMargin=leftMargin;return l;}
    private void build() {
        boolean wide=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(p.canvas);
        // The Pleos home screen draws its own close button over the top-left corner of a task; the two top rows keep clear of it.
        int closeInset=dp(72);
        TextView badge=text(18,p.ink,true);badge.setText(BADGE);badge.setPadding(closeInset,dp(10),dp(16),dp(6));root.addView(badge);
        LinearLayout stage=new LinearLayout(this);stage.setOrientation(wide?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);
        root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));

        // Bottom-aligned: while driving, the Pleos emulator lays its camera preview over the top-left of the task.
        LinearLayout navi=new LinearLayout(this);navi.setOrientation(LinearLayout.VERTICAL);navi.setBackgroundColor(SIM_BG);navi.setGravity(Gravity.BOTTOM);
        LinearLayout head=new LinearLayout(this);head.setBackgroundColor(SIM_PANEL);head.setPadding(closeInset,dp(14),dp(18),dp(14));head.setGravity(Gravity.CENTER_VERTICAL);
        headline=text(34,SIM_INK,true);distance=text(34,SIM_INK,true);
        head.addView(headline,new LinearLayout.LayoutParams(0,-2,1));head.addView(distance);navi.addView(head);
        // The lane strip under the turn: four lanes, the one to be in lit, and a word on when to move.
        LinearLayout laneRow=new LinearLayout(this);laneRow.setBackgroundColor(SIM_PANEL);laneRow.setPadding(closeInset,0,dp(18),dp(12));laneRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView laneLabel=text(18,SIM_INK2,true);laneLabel.setText("추천 차선");laneLabel.setPadding(0,0,dp(10),0);laneRow.addView(laneLabel);
        lanes=new LinearLayout(this);
        for(int i=0;i<4;i++){View v=new View(this);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(40),dp(14));lp.rightMargin=dp(5);v.setBackground(round(SIM_LANE,3));lanes.addView(v,lp);}
        laneRow.addView(lanes);
        laneText=text(22,SIM_INK,true);laneText.setPadding(dp(10),0,0,0);laneRow.addView(laneText,new LinearLayout.LayoutParams(0,-2,1));
        navi.addView(laneRow);
        // The facts are shown as rows (fillRows); the one text block stays only as their source.
        metrics=text(15,METRIC,false);metrics.setVisibility(View.GONE);navi.addView(metrics);
        rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);rows.setPadding(dp(24),dp(6),dp(24),dp(10));navi.addView(rows);
        stage.addView(navi,wide?new LinearLayout.LayoutParams(0,-1,6):new LinearLayout.LayoutParams(-1,0,5));

        LinearLayout app=new LinearLayout(this);app.setOrientation(LinearLayout.VERTICAL);app.setBackgroundColor(p.surface);app.setPadding(dp(16),dp(12),dp(16),dp(12));
        TextView brand=text(24,p.accentText,true);brand.setText("운전자학습");app.addView(brand);
        tag=text(22,p.ink,true);app.addView(tag);chips=text(20,p.ink2,false);app.addView(chips);
        statusBox=new LinearLayout(this);statusBox.setOrientation(LinearLayout.VERTICAL);statusBox.setGravity(Gravity.CENTER_VERTICAL);statusBox.setPadding(dp(14),dp(10),dp(14),dp(10));
        statusLabel=text(20,p.ink2,true);status=text(32,p.ink,true);statusBox.addView(statusLabel);statusBox.addView(status);
        answers=new LinearLayout(this);answers.setPadding(0,dp(8),0,0);
        yes=button("네, 놓쳤어요",true,()->answer(true));answers.addView(yes,new LinearLayout.LayoutParams(0,-2,1));
        no=button("아니요",false,()->answer(false));answers.addView(no,grow(dp(8)));
        statusBox.addView(answers);
        LinearLayout.LayoutParams boxParams=new LinearLayout.LayoutParams(-1,0,1);boxParams.topMargin=dp(8);boxParams.bottomMargin=dp(8);app.addView(statusBox,boxParams);
        facts=text(22,p.ink2,false);app.addView(facts);
        stage.addView(app,wide?new LinearLayout.LayoutParams(0,-1,4):new LinearLayout.LayoutParams(-1,0,4));

        note=text(22,p.ink,false);note.setPadding(dp(16),dp(8),dp(16),dp(4));
        note.setText("연결을 누르면 Vehicle·NaviHelper·FusedLocation·SpeechToText·TextToSpeech SDK와 Car API에 붙습니다. 내비가 알려 주는 다음 회전, 방향지시등, 속도, 길 익숙도로 판단하고, 재탐색 뒤 정차하면 놓쳤는지 묻고 오늘 주행을 한 문장으로 정리합니다.");root.addView(note);
        controls=new LinearLayout(this);controls.setPadding(dp(16),dp(4),dp(16),dp(4));
        connect=button("연결",true,()->{if(bridge.connected()){bridge.release();voice.release();}else{askSpeedPermission();bridge.connect();voice.connect();speaker.connect();}render();});
        controls.addView(connect,new LinearLayout.LayoutParams(0,-2,1));
        controls.addView(button("기억 지우기",false,()->{DriveCoach.clearMemory(session.profile());save();render();}),grow(dp(8)));
        controls.addView(button("가상 시연",false,this::finish),grow(dp(8)));
        root.addView(controls);
        memoRow=new LinearLayout(this);memoRow.setPadding(dp(16),0,dp(16),dp(4));
        memoButton=button("여기 헷갈려",false,()->memo("여기 헷갈려"));memoRow.addView(memoButton,new LinearLayout.LayoutParams(0,-2,1));
        voiceButton=button("말로 하기",false,this::listen);memoRow.addView(voiceButton,grow(dp(8)));
        root.addView(memoRow);
        typeBar=new LinearLayout(this);typeBar.setPadding(dp(16),dp(4),dp(16),dp(10));
        for(DriverType type:DriverType.values()) {
            Button b=button(type.label,false,()->{save();Profile next=new Profile("car","차량",type);DriveCoach.restore(next,getSharedPreferences("coach-guidance",MODE_PRIVATE).getString("memory-"+type.name(),""));session.setProfile(next);save();render();});
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-2,1);params.rightMargin=dp(6);typeBar.addView(b,params);
        }
        root.addView(typeBar);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        setContentView(root);
    }
    /** The Car API speed is a dangerous permission on Android Automotive; the car may grant it, the emulator image has no such permission. */
    private void askSpeedPermission() {
        try{if(checkSelfPermission(CAR_SPEED_PERMISSION)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{CAR_SPEED_PERMISSION},REQUEST_SPEED);}catch(RuntimeException ignored){}
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(code,permissions,results);
        if(code==REQUEST_SPEED&&bridge.connected()){bridge.release();bridge.connect();render();}
    }
    private void answer(boolean confirmed) {
        if(session.lowerAsked())session.answerLower(confirmed);else session.answer(confirmed);
        spoken="";save();render();
        // Whatever waited behind the question (another question, the summary) comes a moment later.
        statusBox.postDelayed(()->onOutcome(session.afterAnswer(),"after"),1200);
    }
    private void memo(String words){Outcome o=session.memo(words,System.currentTimeMillis());onOutcome(o,"memo");}
    /** One phrase through the car's microphone: yes/no answers a pending question, anything else is a memo. */
    private void listen() {
        if(voice.listening()){voice.cancel();render();return;}
        if(!voice.ready()){voiceState=voice.state().startsWith("연결")||voice.state().startsWith("오류")?voice.state():"연결을 먼저 누르세요";render();return;}
        heardIsAnswer=session.pendingQuestion()!=null;
        byte[] recording=null;
        // On the emulator the microphone may hear nothing; a recording placed in the app's files folder stands in.
        try{File test=new File(getExternalFilesDir(null),"stt-test.pcm");if(test.isFile())recording=Files.readAllBytes(test.toPath());}catch(Exception ignored){}
        voice.listen(recording);render();
    }
    @Override public void onVoiceState(String state){voiceState=state;render();}
    @Override public void onHeard(String words) {
        if(words.isEmpty()){render();return;}
        if(heardIsAnswer&&session.pendingQuestion()!=null) {
            if(words.matches("^(네|예|응|맞아|놓쳤|줄여|그래).*")){answer(true);return;}
            if(words.matches("^(아니|아뇨|괜찮|그냥).*")){answer(false);return;}
        }
        memo(words);
    }
    private void show(String label,String text,long forMs){spokenLabel=label;spoken=text;spokenAt=System.currentTimeMillis();spokenFor=forMs;}
    private void speak(String text){if(speaker!=null)speaker.speak(text);}

    // Host: everything arrives on the main thread.
    @Override public void onLink(String v,String n){vehicleState=v;naviState=n;render();}
    @Override public void onSources(String speed,String location){speedSource=speed;locationState=location;render();}
    @Override public void onVehicle(Signal s,String g){signal=s;gear=g;render();}
    @Override public void onSpeed(int kph){speedKph=kph;render();}
    @Override public void onNavigation(String label,int m,String st){turnLabel=label;meters=m;naviStatus=st;render();}
    @Override public void onDestination(String name){destination=name==null?"":name;render();}
    @Override public void onCar(String line){carLine=line;render();}
    @Override public void onRoad(String line){roadLine=line;render();}
    @Override public void onOutcome(Outcome outcome,String source) {
        if(outcome==null)return;
        switch(outcome.event) {
            case SPEAK:show("말하는 중",outcome.speech,SPEAK_MS);speak(outcome.speech);break;
            case QUESTION:case LOWER_QUESTION:speak(outcome.speech);break;
            case BRIEFING:show("출발 전 브리핑",outcome.speech,LONG_MS);speak(outcome.speech);break;
            case SUMMARY:show("오늘 주행 요약",outcome.speech,LONG_MS);speak(outcome.speech);break;
            case MEMO:show("메모",outcome.speech,SPEAK_MS);speak(outcome.speech);break;
            default:break;
        }
        if(outcome.event!=Event.NONE&&outcome.event!=Event.SILENT)save();
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
    // The same facts as rows, the way the built-in settings list shows them: the name on the left, the value on the right,
    // a hairline under each.
    private void fillRows(String all) {
        String[] lines=all.split("\n");
        while(rows.getChildCount()<lines.length) {
            LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);
            LinearLayout line=new LinearLayout(this);line.setPadding(0,dp(7),0,dp(7));line.setGravity(Gravity.CENTER_VERTICAL);
            TextView name=text(22,METRIC,false);TextView value=text(22,p.ink,true);value.setGravity(Gravity.END);value.setPadding(dp(24),0,0,0);
            line.addView(name);line.addView(value,new LinearLayout.LayoutParams(0,-2,1));
            View rule=new View(this);rule.setBackgroundColor(STROKE);
            row.addView(line);row.addView(rule,new LinearLayout.LayoutParams(-1,Math.max(1,dp(1))));rows.addView(row);
        }
        for(int i=0;i<rows.getChildCount();i++) {
            View row=rows.getChildAt(i);if(i>=lines.length){row.setVisibility(View.GONE);continue;}
            row.setVisibility(View.VISIBLE);int cut=lines[i].indexOf(": ");LinearLayout line=(LinearLayout)((LinearLayout)row).getChildAt(0);
            ((TextView)line.getChildAt(0)).setText(cut<0?lines[i]:lines[i].substring(0,cut));((TextView)line.getChildAt(1)).setText(cut<0?"":lines[i].substring(cut+2));
        }
    }
    private static String familiarityLabel(Familiarity f){switch(f){case NEW:return "처음 가는 길";case KNOWN:return "몇 번 가 본 길";case FAMILIAR:return "익숙한 길";default:return "모름";}}
    private void render() {
        long now=System.currentTimeMillis();Profile profile=session.profile();DriverType type=profile.driverType;
        headline.setText(turnLabel);distance.setText(meters>=0?meters+"m":"");
        DriveCoach.LaneHint lane=DriveCoach.laneHint(session.currentTurn(),session.meters(),session.currentTurn()==null?0:DriveCoach.decisionMeters(profile,session.currentTurn(),session.speedKph(),session.familiarity()));
        for(int i=0;i<4;i++){boolean on=lane.side==DriveCoach.LaneSide.LEFT&&i==0||lane.side==DriveCoach.LaneSide.RIGHT&&i==3;lanes.getChildAt(i).setBackground(round(on?SIM_ROUTE:SIM_LANE,3));}
        laneText.setText(lane.text);
        metrics.setText("방향지시등: "+(signal==Signal.LEFT?"왼쪽":signal==Signal.RIGHT?"오른쪽":"꺼짐")+"\n기어: "+gear+"\n속도: "+(speedKph>=0?speedKph+" km/h":"모름")+" · "+speedSource
            +"\n내비 상태: "+naviStatus+(destination.isEmpty()?"":" · 목적지 "+destination)+"\n길 익숙도: "+familiarityLabel(session.familiarity())+" · 위치 "+locationState
            +"\n차량 신호: "+carLine+"\n도로: "+roadLine
            +"\nVehicle SDK: "+vehicleState+(bridge.seatLive()?" · 좌석":"")+(bridge.extrasLive().isEmpty()?"":" · "+bridge.extrasLive())+"\nNaviHelper SDK: "+naviState+"\nSpeechToText SDK: "+voiceState+"\nTextToSpeech SDK: "+speakerState);
        fillRows(metrics.getText().toString());
        tag.setText("차량 연동 · "+type.label);
        chips.setText(type.label+" 유형 · "+(bridge.connected()?"연결됨":"연결 안 됨")+(session.passenger()?" · 승객 탑승":"")+(restricted?" · 주행 중 간소화":""));
        double rate=DriveCoach.signalRate(profile);
        facts.setText("약한 유형: "+(DriveCoach.weakLabels(profile).isEmpty()?"없음":String.join(", ",DriveCoach.weakLabels(profile)))+"\n기억 지점: "+profile.placeCount()+"곳 · 확인 대기: "+profile.pendingCount()+"건 · 길 "+profile.roadCellCount()+"칸"
            +"\n판단 거리: "+DriveCoach.decisionMeters(profile,session.currentTurn()==null?new DriveCoach.Turn("-",DriveCoach.TurnType.EXIT_LEFT):session.currentTurn(),session.speedKph(),session.familiarity())+"m · 깜빡이 "+(rate<0?"기록 중":Math.round(rate*100)+"%"));
        connect.setText(bridge.connected()?"연결 해제":"연결");
        voiceButton.setText(voice.listening()?"듣는 중… (누르면 취소)":"말로 하기");
        for(int i=0;i<typeBar.getChildCount();i++)typeBar.getChildAt(i).setBackground(round(DriveCoach.DriverType.values()[i]==type?p.accentSoft:p.surface2,10));
        // While the car asks for a simplified screen, only the status and its question stay.
        int hidden=restricted?View.GONE:View.VISIBLE;
        note.setVisibility(hidden);controls.setVisibility(hidden);typeBar.setVisibility(hidden);facts.setVisibility(hidden);memoRow.setVisibility(restricted&&session.pendingQuestion()==null?View.GONE:View.VISIBLE);
        answers.setVisibility(session.pendingQuestion()!=null?View.VISIBLE:View.GONE);
        yes.setText(session.lowerAsked()?"네, 줄여 주세요":"네, 놓쳤어요");
        if(session.pendingQuestion()!=null){statusView(p.accent,p.accentInk,p.accentInk,session.lowerAsked()?"정차 중 제안":"정차 중 질문",session.pendingQuestion());return;}
        if(!spoken.isEmpty()&&now-spokenAt<spokenFor){statusView(p.accent,p.accentInk,p.accentInk,spokenLabel,spoken);return;}
        if(!bridge.connected()){statusView(p.surface2,p.ink2,p.ink,"연결 안 됨","연결을 누르면 Pleos SDK에 붙습니다");return;}
        if(session.currentTurn()==null){statusView(p.surface2,p.ink2,p.ink,"지켜보는 중","내비가 다음 회전을 알려 주면 판단합니다");return;}
        if(DriveCoach.remembers(profile,session.currentTurn().id)){statusView(p.surface2,p.ink2,p.ink,"지켜보는 중","기억해 둔 곳 · 판단 거리 "+DriveCoach.decisionMeters(profile,session.currentTurn(),session.speedKph(),session.familiarity())+"m");return;}
        if(DriveCoach.isWeak(profile,session.currentTurn().type)){statusView(p.surface2,p.ink2,p.ink,"지켜보는 중",session.currentTurn().type.label+" · 판단 거리 "+DriveCoach.decisionMeters(profile,session.currentTurn(),session.speedKph(),session.familiarity())+"m"+(session.familiarity()==Familiarity.FAMILIAR?" · 익숙한 길이라 말하지 않음":""));return;}
        statusView(p.surface2,p.ink2,p.ink,"지켜보는 중","이 회전은 아직 기록이 없습니다");
    }
}
