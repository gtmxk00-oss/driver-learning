package kr.routememory.coach;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import kr.routememory.core.CoachDemo;
import kr.routememory.core.DriveCoach;

/** Plays the virtual companion scenes. Vehicle and navigation inputs are simulated until the Pleos SDK is connected. */
public class CoachActivity extends Activity {
    /** The same colour tokens as RouteMemory-PC src/tokens.css, light and dark. */
    public static final class Palette {
        public final int canvas,surface,surface2,ink,ink2,accent,accentInk,accentText,accentSoft,strong,onStrong,onStrong2;
        public Palette(boolean dark) {
            canvas=dark?0xFF111215:0xFFF3F2EE;surface=dark?0xFF18191D:0xFFFBFAF7;surface2=dark?0xFF202126:0xFFEAE8E2;
            ink=dark?0xFFF1EEE8:0xFF17181C;ink2=dark?0xFFC3C0B8:0xFF45474F;
            accent=dark?0xFFFF7A26:0xFFFF6A1A;accentInk=0xFF1B0B02;accentText=dark?0xFFFF9550:0xFFB23C06;accentSoft=dark?0xFF3A2214:0xFFFDE3D2;
            strong=dark?0xFF22232A:0xFF1B1C21;onStrong=0xFFF4F1EB;onStrong2=0xFFB9B5AC;
        }
    }
    // The virtual road is a night map in both modes, as on the PC.
    private static final int SIM_BG=0xFF14151A,SIM_PANEL=0xFF1D1F25,SIM_ROAD=0xFF30333B,SIM_LANE=0xFF5B5F69,SIM_INK=0xFFF1EEE8,SIM_INK2=0xFFB6B3AC,SIM_ROUTE=0xFFFF7A26,SIM_GOOD=0xFF5CCF95;
    private static final String[] MODES={"auto","light","dark"},MODE_NAMES={"화면 자동","화면 밝게","화면 어둡게"};
    private Palette p; private String mode="auto";
    private static final long TICK_MS=50;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final List<CoachDemo.Event> events=CoachDemo.events();
    private final double total=CoachDemo.total();
    private double position; private long lastTick; private boolean playing;
    private TextToSpeech voice; private boolean voiceReady;
    private TextView tag,chips,status,statusLabel,facts,headline,distance,caption;
    private Button play; private LinearLayout statusBox,sceneBar; private RoadView road;
    private final Runnable tick=this::onTick;

    @Override protected void onCreate(Bundle saved) {
        mode=getSharedPreferences("coach",MODE_PRIVATE).getString("mode","auto");
        boolean night=(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        boolean dark=mode.equals("dark")||mode.equals("auto")&&night;
        p=new Palette(dark);
        setTheme(dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);super.onCreate(saved);
        if(saved!=null)position=saved.getDouble("position",0);
        build();render();
        voice=new TextToSpeech(getApplicationContext(),code->{
            voiceReady=code==TextToSpeech.SUCCESS&&voice!=null&&voice.setLanguage(Locale.KOREAN)>=TextToSpeech.LANG_AVAILABLE;
        });
    }
    @Override protected void onStop(){setPlaying(false);super.onStop();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putDouble("position",position);}
    /** Auto follows the car's day and night setting; a choice is kept on this device. */
    private void nextMode() {
        String next=MODES[(Arrays.asList(MODES).indexOf(mode)+1)%MODES.length];
        getSharedPreferences("coach",MODE_PRIVATE).edit().putString("mode",next).apply();
        setPlaying(false);recreate();
    }
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);if(voice!=null)voice.shutdown();super.onDestroy();}
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private TextView text(int size,int color,boolean bold) {
        TextView t=new TextView(this);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(2),1);
        if(bold)t.setTypeface(null,Typeface.BOLD);
        return t;
    }
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private Button button(String title,boolean primary,Runnable action) {
        Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextColor(primary?p.accentInk:p.ink);
        b.setBackground(round(primary?p.accent:p.surface2,10));b.setMinHeight(dp(48));b.setOnClickListener(v->action.run());return b;
    }
    private void build() {
        boolean wide=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(p.canvas);
        TextView badge=text(13,p.ink,true);badge.setText(R_BADGE);badge.setPadding(dp(16),dp(10),dp(16),dp(6));root.addView(badge);
        LinearLayout stage=new LinearLayout(this);stage.setOrientation(wide?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);
        root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout navi=new LinearLayout(this);navi.setOrientation(LinearLayout.VERTICAL);navi.setBackgroundColor(SIM_BG);
        LinearLayout head=new LinearLayout(this);head.setBackgroundColor(SIM_PANEL);head.setPadding(dp(18),dp(14),dp(18),dp(14));head.setGravity(Gravity.CENTER_VERTICAL);
        headline=text(24,SIM_INK,true);distance=text(24,SIM_INK,true);
        head.addView(headline,new LinearLayout.LayoutParams(0,-2,1));head.addView(distance);navi.addView(head);
        road=new RoadView(this);navi.addView(road,new LinearLayout.LayoutParams(-1,0,1));
        stage.addView(navi,wide?new LinearLayout.LayoutParams(0,-1,6):new LinearLayout.LayoutParams(-1,0,5));

        LinearLayout app=new LinearLayout(this);app.setOrientation(LinearLayout.VERTICAL);app.setBackgroundColor(p.surface);app.setPadding(dp(16),dp(12),dp(16),dp(12));
        TextView brand=text(18,p.accentText,true);brand.setText("길기억");app.addView(brand);
        tag=text(15,p.ink,true);app.addView(tag);chips=text(13,p.ink2,false);app.addView(chips);
        statusBox=new LinearLayout(this);statusBox.setOrientation(LinearLayout.VERTICAL);statusBox.setGravity(Gravity.CENTER_VERTICAL);statusBox.setPadding(dp(14),dp(10),dp(14),dp(10));
        statusLabel=text(12,p.ink2,true);status=text(18,p.ink,true);statusBox.addView(statusLabel);statusBox.addView(status);
        LinearLayout.LayoutParams boxParams=new LinearLayout.LayoutParams(-1,0,1);boxParams.topMargin=dp(8);boxParams.bottomMargin=dp(8);app.addView(statusBox,boxParams);
        facts=text(13,p.ink2,false);app.addView(facts);
        stage.addView(app,wide?new LinearLayout.LayoutParams(0,-1,4):new LinearLayout.LayoutParams(-1,0,4));

        caption=text(14,p.ink,false);caption.setPadding(dp(16),dp(8),dp(16),dp(4));root.addView(caption);
        LinearLayout controls=new LinearLayout(this);controls.setPadding(dp(16),dp(4),dp(16),dp(4));
        play=button("재생",true,()->{if(position>=total-0.1)seek(0);setPlaying(!playing);});
        controls.addView(play,new LinearLayout.LayoutParams(0,-2,1));
        Button restart=button("처음부터",false,()->{setPlaying(false);seek(0);});
        LinearLayout.LayoutParams second=new LinearLayout.LayoutParams(0,-2,1);second.leftMargin=dp(8);controls.addView(restart,second);
        Button modeButton=button(MODE_NAMES[Arrays.asList(MODES).indexOf(mode)],false,this::nextMode);
        LinearLayout.LayoutParams third=new LinearLayout.LayoutParams(0,-2,1);third.leftMargin=dp(8);controls.addView(modeButton,third);
        Button liveButton=button("실제 위치",false,()->{setPlaying(false);startActivity(new Intent(this,LiveCoachActivity.class));});
        LinearLayout.LayoutParams fourth=new LinearLayout.LayoutParams(0,-2,1);fourth.leftMargin=dp(8);controls.addView(liveButton,fourth);
        Intent link=vehicleLinkIntent();
        if(link!=null){Button linkButton=button("차량 연동",false,()->{setPlaying(false);startActivity(link);});LinearLayout.LayoutParams fifth=new LinearLayout.LayoutParams(0,-2,1);fifth.leftMargin=dp(8);controls.addView(linkButton,fifth);}
        root.addView(controls);
        HorizontalScrollView scroll=new HorizontalScrollView(this);sceneBar=new LinearLayout(this);sceneBar.setPadding(dp(16),dp(4),dp(16),dp(10));
        List<CoachDemo.Scene> scenes=CoachDemo.scenes();
        for(int i=0;i<scenes.size();i++) {
            int index=i;CoachDemo.Scene s=scenes.get(i);
            Button b=button(s.tag!=null?s.tag:s.title,false,()->{setPlaying(false);seek(CoachDemo.startOf(index)+0.01);});
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.rightMargin=dp(6);sceneBar.addView(b,p);
        }
        scroll.addView(sceneBar);root.addView(scroll);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        setContentView(root);
    }
    /** A build that can link to the car returns the screen to open; the demo alone returns null. */
    protected Intent vehicleLinkIntent(){return null;}
    private static final String R_BADGE="가상 시연 · Pleos SDK 연동 전 · 신호는 시뮬레이션";
    private void setPlaying(boolean value) {
        playing=value;handler.removeCallbacks(tick);
        if(play!=null)play.setText(value?"일시정지":"재생");
        if(value){lastTick=SystemClock.uptimeMillis();handler.postDelayed(tick,TICK_MS);}
    }
    private void onTick() {
        long now=SystemClock.uptimeMillis();
        double next=position+(now-lastTick)/1000.0;lastTick=now;
        for(CoachDemo.Event event:events)if(event.alert&&event.at>position&&event.at<=next&&voiceReady)voice.speak(event.text,TextToSpeech.QUEUE_ADD,null,"coach");
        if(next>=total-0.05){setPlaying(false);seek(total-0.05);return;}
        seek(next);handler.postDelayed(tick,TICK_MS);
    }
    void seek(double seconds){position=seconds;render();}
    // Korean breaks anywhere by default; a word joiner keeps each word on one line.
    private static String keepWords(String value) {
        StringBuilder out=new StringBuilder();
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);out.append(c);
            if(i+1<value.length()&&c!=' '&&value.charAt(i+1)!=' ')out.append('⁠');
        }
        return out.toString();
    }
    private void statusView(int background,int label,int body,String labelText,String bodyText) {
        statusBox.setBackground(round(background,12));statusLabel.setTextColor(label);status.setTextColor(body);statusLabel.setText(labelText);status.setText(keepWords(bodyText));
    }
    private void render() {
        CoachDemo.State s=CoachDemo.stateAt(position);
        caption.setText(s.scene.narration);
        for(int i=0;i<sceneBar.getChildCount();i++)sceneBar.getChildAt(i).setBackground(round(i==s.index?p.accentSoft:p.surface2,10));
        road.show(s);
        if(s.phase==CoachDemo.Phase.CARD) {
            headline.setText(s.scene.title);distance.setText("");tag.setText(s.scene.lead);chips.setText("");facts.setText("");
            statusView(p.surface2,p.ink2,p.ink,"안내","재생을 누르면 장면이 이어집니다");return;
        }
        DriveCoach.TurnType type=s.scene.turn==null?null:s.scene.turn.type;
        headline.setText(s.phase==CoachDemo.Phase.PARKED?"정차 중":s.scene.noRoute?"내비게이션 경로 없음":type.label);
        distance.setText(s.meters<0?"":s.meters+"m");
        tag.setText(s.scene.tag);
        chips.setText(s.scene.driver.label+" 유형 · "+(s.passenger?"승객 탑승":"승객 없음"));
        facts.setText("약한 유형: "+(s.weak.isEmpty()?"없음":String.join(", ",s.weak))+"\n기억 지점: "+s.places+"곳 · 판단 거리: "+(s.limit>0?s.limit+"m":"-"));
        if(s.phase==CoachDemo.Phase.PARKED) {
            if(s.step.speak)statusView(p.accent,p.accentInk,p.accentInk,s.step.label,s.step.text);
            else statusView(p.surface2,p.ink2,p.ink,s.step.label,s.step.text);
            return;
        }
        if(s.speaking){statusView(p.accent,p.accentInk,p.accentInk,"말하는 중",s.spoken);return;}
        switch(s.phase) {
            case REROUTING:statusView(p.surface2,p.ink2,p.ink,"기록","내비게이션이 재탐색 중입니다");return;
            case QUESTION_WAITING:statusView(p.surface2,p.ink2,p.ink,"기록","승객이 내릴 때까지 질문을 미룹니다");return;
            case QUESTION:statusView(p.accent,p.accentInk,p.accentInk,"정차 중 질문",s.scene.question);return;
            case LEARNED:statusView(p.surface2,p.ink2,p.ink,"기록","운전자가 확인했습니다");return;
            case DECLINED:statusView(p.surface2,p.ink2,p.ink,"기록","운전자가 아니요라고 답해 기록하지 않았습니다");return;
            case MEMO:statusView(p.surface2,p.ink2,p.ink,"기록","“"+s.scene.memoText+"”");return;
            case MEMO_SAVED:statusView(p.surface2,p.ink2,p.ink,"기록","기억 지점으로 저장했습니다");return;
            default:break;
        }
        if(s.action==DriveCoach.Action.SILENT)statusView(p.strong,p.onStrong2,p.onStrong,"말하지 않음","방향지시등이 켜져 있어 준비 중으로 판단했습니다");
        else if(s.action==DriveCoach.Action.SPEAK)statusView(p.surface2,p.ink2,p.ink,"알림 완료",s.spoken);
        else if(s.action==DriveCoach.Action.WAIT)statusView(p.surface2,p.ink2,p.ink,"지켜보는 중","판단 거리 전입니다");
        else statusView(p.surface2,p.ink2,p.ink,"지켜보는 중","이 회전은 아직 기록이 없습니다");
    }

    private static final class RoadView extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private CoachDemo.State state;
        RoadView(Context context){super(context);}
        void show(CoachDemo.State next){state=next;invalidate();}
        private void stroke(Canvas c,Path path,int color,float width){paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setColor(color);paint.setStrokeWidth(width);c.drawPath(path,paint);}
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            if(state==null||state.phase==CoachDemo.Phase.CARD)return;
            float w=getWidth(),h=getHeight(),cx=w/2f,scale=h/360f;
            DriveCoach.TurnType type=state.scene.turn==null?null:state.scene.turn.type;
            boolean sided=type!=null&&type.side!=null,left=!sided||type.side==DriveCoach.Signal.LEFT;
            float dir=left?-1:1;
            Path main=new Path();main.moveTo(cx,h);main.lineTo(cx,0);
            Path branch=new Path();branch.moveTo(cx,120*scale);branch.cubicTo(cx+dir*40*scale,80*scale,cx+dir*120*scale,56*scale,cx+dir*320*scale,40*scale);
            stroke(c,main,SIM_ROAD,56*scale);
            if(sided)stroke(c,branch,SIM_ROAD,56*scale);
            if(!state.scene.noRoute) {
                Path route=new Path();route.moveTo(cx,h);
                if(sided){route.lineTo(cx,120*scale);route.addPath(branch);}else route.lineTo(cx,0);
                stroke(c,route,SIM_ROUTE,12*scale);
            }
            boolean missed=state.phase==CoachDemo.Phase.REROUTING||state.phase==CoachDemo.Phase.QUESTION_WAITING||state.phase==CoachDemo.Phase.QUESTION||state.phase==CoachDemo.Phase.LEARNED||state.phase==CoachDemo.Phase.DECLINED;
            boolean took=state.phase==CoachDemo.Phase.PASSED&&sided;
            float progress=state.meters<0||state.scene.from==0?0:1-state.meters/(float)state.scene.from;
            float y=(missed?56:took?71:290-progress*170)*scale,x=took?cx+dir*100*scale:cx;
            paint.setStyle(Paint.Style.FILL);paint.setColor(SIM_BG);c.drawCircle(x,y,16*scale,paint);paint.setColor(SIM_INK);c.drawCircle(x,y,12*scale,paint);
            float baseline=h-22*scale;paint.setTextSize(15*getResources().getDisplayMetrics().density);paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(SIM_PANEL);c.drawRoundRect(cx-100*scale,baseline-paint.getTextSize()*1.2f,cx+100*scale,baseline+paint.getTextSize()*0.6f,99,99,paint);
            paint.setColor(state.signal==DriveCoach.Signal.LEFT?SIM_GOOD:SIM_LANE);c.drawText("◀",cx-78*scale,baseline,paint);
            paint.setColor(state.signal==DriveCoach.Signal.RIGHT?SIM_GOOD:SIM_LANE);c.drawText("▶",cx+78*scale,baseline,paint);
            paint.setColor(SIM_INK2);c.drawText("방향지시등",cx,baseline,paint);
        }
    }
}
