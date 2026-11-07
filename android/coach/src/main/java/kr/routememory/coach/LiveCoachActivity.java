package kr.routememory.coach;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;
import kr.routememory.core.DriveCoach;
import kr.routememory.core.DriveCoach.Action;
import kr.routememory.core.DriveCoach.DriverType;
import kr.routememory.core.LiveCoach;
import kr.routememory.core.LiveCoach.Fix;
import kr.routememory.core.LiveCoach.Nearest;
import kr.routememory.core.LiveCoach.Observation;
import kr.routememory.core.LiveCoach.Point;
import kr.routememory.core.LiveCoach.Remembered;
import kr.routememory.core.LiveCoach.Session;
import kr.routememory.core.LiveCoach.Status;

/**
 * Live mode on this device's position. Position, speed and accuracy are real; there is no turn signal and no
 * navigation route until the Pleos SDK is connected, so the screen says so and only reminds remembered points.
 */
public class LiveCoachActivity extends Activity {
    static final String BADGE="실제 위치 · 내비·방향지시등 정보 없음 · Pleos SDK 연동 전";
    private static final int SIM_BG=0xFF14151A,SIM_PANEL=0xFF1D1F25,SIM_INK=0xFFF1EEE8,SIM_INK2=0xFFB6B3AC,SIM_EDGE=0xFF3A3D45;
    private static final long TICK_MS=1000,SPEAK_MS=4400,FLASH_MS=4000;
    private static final int PERMISSION_REQUEST=41;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable tick=this::render;
    private CoachActivity.Palette p;
    private Session session;
    private LocationManager locations; private LocationListener listener;
    private TextToSpeech voice; private boolean voiceReady;
    private boolean tracking,waiting,denied;
    private String flashLabel,flashText; private long flashAt;
    private TextView headline,speed,metrics,tag,chips,statusLabel,status,facts;
    private LinearLayout statusBox,typeBar; private Button toggle,memo,forget;

    @Override protected void onCreate(Bundle saved) {
        boolean night=(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        String mode=getSharedPreferences("coach",MODE_PRIVATE).getString("mode","auto");
        boolean dark=mode.equals("dark")||mode.equals("auto")&&night;
        p=new CoachActivity.Palette(dark);
        setTheme(dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);super.onCreate(saved);
        load();build();render();
        voice=new TextToSpeech(getApplicationContext(),code->{
            voiceReady=code==TextToSpeech.SUCCESS&&voice!=null&&voice.setLanguage(Locale.KOREAN)>=TextToSpeech.LANG_AVAILABLE;
        });
    }
    @Override protected void onStop(){stopTracking();super.onStop();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);if(voice!=null)voice.shutdown();super.onDestroy();}

    // Points and the driver type stay on this device only.
    private void load() {
        SharedPreferences prefs=getSharedPreferences("coach-live",MODE_PRIVATE);
        DriverType type=DriverType.GENERAL;
        try{type=DriverType.valueOf(prefs.getString("driver","GENERAL"));}catch(IllegalArgumentException ignored){}
        List<Point> points=new ArrayList<>();
        try {
            JSONArray array=new JSONArray(prefs.getString("points","[]"));
            for(int i=0;i<array.length();i++){JSONObject o=array.getJSONObject(i);points.add(new Point(o.getString("id"),o.getDouble("lat"),o.getDouble("lon"),o.optString("label",""),o.optLong("at",0)));}
        } catch(Exception ignored){points.clear();}
        session=LiveCoach.createSession(new DriveCoach.Profile("live","실제 위치",type),points);
    }
    private void save() {
        JSONArray array=new JSONArray();
        try{for(Point point:session.points()){JSONObject o=new JSONObject();o.put("id",point.id);o.put("lat",point.lat);o.put("lon",point.lon);o.put("label",point.label);o.put("at",point.at);array.put(o);}}
        catch(Exception ignored){}
        getSharedPreferences("coach-live",MODE_PRIVATE).edit().putString("driver",session.profile().driverType.name()).putString("points",array.toString()).apply();
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
        headline=text(24,SIM_INK,true);speed=text(24,SIM_INK,true);
        head.addView(headline,new LinearLayout.LayoutParams(0,-2,1));head.addView(speed);navi.addView(head);
        metrics=text(15,SIM_INK2,false);metrics.setPadding(dp(18),dp(14),dp(18),dp(14));metrics.setLineSpacing(dp(6),1);navi.addView(metrics);
        stage.addView(navi,wide?new LinearLayout.LayoutParams(0,-1,6):new LinearLayout.LayoutParams(-1,0,5));

        LinearLayout app=new LinearLayout(this);app.setOrientation(LinearLayout.VERTICAL);app.setBackgroundColor(p.surface);app.setPadding(dp(16),dp(12),dp(16),dp(12));
        TextView brand=text(18,p.accentText,true);brand.setText("길기억");app.addView(brand);
        tag=text(15,p.ink,true);app.addView(tag);chips=text(13,p.ink2,false);app.addView(chips);
        statusBox=new LinearLayout(this);statusBox.setOrientation(LinearLayout.VERTICAL);statusBox.setGravity(Gravity.CENTER_VERTICAL);statusBox.setPadding(dp(14),dp(10),dp(14),dp(10));
        statusLabel=text(12,p.ink2,true);status=text(18,p.ink,true);statusBox.addView(statusLabel);statusBox.addView(status);
        LinearLayout.LayoutParams boxParams=new LinearLayout.LayoutParams(-1,0,1);boxParams.topMargin=dp(8);boxParams.bottomMargin=dp(8);app.addView(statusBox,boxParams);
        facts=text(13,p.ink2,false);app.addView(facts);
        stage.addView(app,wide?new LinearLayout.LayoutParams(0,-1,4):new LinearLayout.LayoutParams(-1,0,4));

        TextView note=text(14,p.ink,false);note.setPadding(dp(16),dp(8),dp(16),dp(4));
        note.setText("헷갈린 곳에서 여기 헷갈려를 누르면 기억 지점으로 저장하고, 다음에 다가가면 음성으로 알립니다. 위치는 이 기기 밖으로 보내지 않습니다.");root.addView(note);
        LinearLayout controls=new LinearLayout(this);controls.setPadding(dp(16),dp(4),dp(16),dp(4));
        toggle=button("위치 켜기",true,()->{if(tracking)stopTracking();else startTracking();});
        controls.addView(toggle,new LinearLayout.LayoutParams(0,-2,1));
        memo=button("여기 헷갈려",false,this::rememberHere);
        LinearLayout.LayoutParams second=new LinearLayout.LayoutParams(0,-2,1);second.leftMargin=dp(8);controls.addView(memo,second);
        forget=button("기억 지점 지우기",false,()->{LiveCoach.forget(session);save();flash("기록","이 기기의 기억 지점을 모두 지웠습니다");render();});
        LinearLayout.LayoutParams third=new LinearLayout.LayoutParams(0,-2,1);third.leftMargin=dp(8);controls.addView(forget,third);
        Button back=button("가상 시연",false,this::finish);
        LinearLayout.LayoutParams fourth=new LinearLayout.LayoutParams(0,-2,1);fourth.leftMargin=dp(8);controls.addView(back,fourth);
        root.addView(controls);
        typeBar=new LinearLayout(this);typeBar.setPadding(dp(16),dp(4),dp(16),dp(10));
        for(DriverType type:DriverType.values()) {
            Button b=button(type.label,false,()->{LiveCoach.setDriverType(session,type);save();render();});
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-2,1);params.rightMargin=dp(6);typeBar.addView(b,params);
        }
        root.addView(typeBar);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        setContentView(root);
    }

    // Location starts only from the button and asks for the permission then.
    private void startTracking() {
        if(tracking)return;
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},PERMISSION_REQUEST);
            return;
        }
        locations=(LocationManager)getSystemService(Context.LOCATION_SERVICE);
        if(locations==null){flash("위치 사용 불가","이 기기에서 위치 서비스를 찾지 못했습니다");render();return;}
        listener=location->feed(location.getLatitude(),location.getLongitude(),location.hasAccuracy()?location.getAccuracy():Double.NaN,location.hasSpeed()?location.getSpeed():Double.NaN,location.getTime());
        boolean any=false;
        for(String provider:new String[]{LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER}) {
            try{if(locations.isProviderEnabled(provider)){locations.requestLocationUpdates(provider,1000L,0f,listener);any=true;}}
            catch(SecurityException|IllegalArgumentException ignored){}
        }
        if(!any){flash("위치 사용 불가","기기의 위치 설정이 꺼져 있습니다");render();return;}
        tracking=true;waiting=true;denied=false;
        handler.removeCallbacks(tick);handler.postDelayed(tick,TICK_MS);render();
    }
    private void stopTracking() {
        if(locations!=null&&listener!=null){try{locations.removeUpdates(listener);}catch(SecurityException ignored){}}
        listener=null;tracking=false;waiting=false;handler.removeCallbacks(tick);render();
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(code,permissions,results);
        if(code!=PERMISSION_REQUEST)return;
        boolean granted=results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED;
        denied=!granted;
        if(granted)startTracking();else render();
    }
    /** One position from the receiver. Package-private so tests can drive it without a location service. */
    void feed(double lat,double lon,double accuracy,double speed,long time) {
        Observation seen=LiveCoach.observe(session,new Fix(lat,lon,accuracy,speed,time));
        // A fix can only come from a receiver we asked, so the screen is tracking.
        tracking=true;waiting=false;denied=false;
        if(seen.accepted&&seen.decision.action==Action.SPEAK&&voiceReady)voice.speak(seen.decision.speech,TextToSpeech.QUEUE_ADD,null,"live");
        render();
    }
    private void rememberHere() {
        Remembered saved=LiveCoach.remember(session,System.currentTimeMillis());
        if(!saved.ok)flash("기록",saved.reason.equals("poor-accuracy")?"정확도가 낮아 저장하지 않았습니다":"최신 위치가 없어 저장하지 않았습니다");
        else if(saved.merged)flash("기록","이미 저장된 지점입니다 ("+saved.point.label+")");
        else flash("기록",saved.point.label+"을 기억 지점으로 저장했습니다");
        save();render();
    }
    private void flash(String label,String text){flashLabel=label;flashText=text;flashAt=System.currentTimeMillis();}
    private static String keepWords(String value) {
        StringBuilder out=new StringBuilder();
        for(int i=0;i<value.length();i++){char c=value.charAt(i);out.append(c);if(i+1<value.length()&&c!=' '&&value.charAt(i+1)!=' ')out.append('⁠');}
        return out.toString();
    }
    private void statusView(int background,int label,int body,String labelText,String bodyText) {
        statusBox.setBackground(round(background,12));statusLabel.setTextColor(label);status.setTextColor(body);statusLabel.setText(labelText);status.setText(keepWords(bodyText));
    }
    private void render() {
        long now=System.currentTimeMillis();
        Status st=LiveCoach.status(session,now);Fix fix=session.last();Nearest near=fix==null?null:LiveCoach.nearest(session,fix);
        int limit=LiveCoach.limitMeters(session);DriverType type=session.profile().driverType;
        headline.setText(!tracking?"위치 꺼짐":fix==null?"위치 확인 중":!st.fresh?"위치 대기":st.parked?"정차 중":st.moving?"주행 중":"느리게 이동");
        speed.setText(st.fresh&&!Double.isNaN(session.speed())?Math.round(session.speed()*3.6)+" km/h":"");
        metrics.setText("정확도: "+(fix==null?"-":"±"+Math.round(fix.accuracy)+"m")+"\n마지막 갱신: "+(fix==null?"-":Math.max(0,Math.round(st.ageMs/1000.0))+"초 전")
            +"\n위치: "+(fix==null?"-":String.format(Locale.ROOT,"%.5f, %.5f",fix.lat,fix.lon))+"\n가장 가까운 기억 지점: "+(near==null?"없음":near.point.label+" · "+Math.round(near.meters)+"m"));
        tag.setText("실제 위치 · "+type.label);
        chips.setText(type.label+" 유형 · "+(tracking?"위치 켜짐":"위치 꺼짐"));
        facts.setText("기억 지점: "+session.points().size()+"곳 · 판단 거리: "+limit+"m\n알림 조건: 주행 중 · 정확도 ±"+(int)LiveCoach.TRUSTED_ACCURACY+"m 이내");
        toggle.setText(tracking?"위치 끄기":"위치 켜기");
        memo.setEnabled(tracking&&fix!=null&&st.fresh&&st.trusted);
        forget.setEnabled(!session.points().isEmpty());
        for(int i=0;i<typeBar.getChildCount();i++)typeBar.getChildAt(i).setBackground(round(DriverType.values()[i]==type?p.accentSoft:p.surface2,10));
        if(tracking){handler.removeCallbacks(tick);handler.postDelayed(tick,TICK_MS);}
        // What the app is saying outranks every note.
        if(session.spoken()!=null&&now-session.spoken().at<SPEAK_MS){statusView(p.accent,p.accentInk,p.accentInk,"말하는 중",session.spoken().text);return;}
        if(flashText!=null&&now-flashAt<FLASH_MS){statusView(p.surface2,p.ink2,p.ink,flashLabel,flashText);return;}
        if(denied){statusView(p.surface2,p.ink2,p.ink,"위치 권한 없음","설정에서 위치 권한을 허용하면 다시 켤 수 있습니다");return;}
        if(!tracking){statusView(p.surface2,p.ink2,p.ink,"위치 꺼짐","위치 켜기를 누르면 현재 위치를 따라갑니다");return;}
        if(fix==null){statusView(p.surface2,p.ink2,p.ink,"위치 확인 중","위치를 기다리고 있습니다");return;}
        if(!st.fresh){statusView(p.surface2,p.ink2,p.ink,"위치 대기","최근 15초 동안 새 위치가 없습니다");return;}
        if(!st.trusted){statusView(p.surface2,p.ink2,p.ink,"정확도 낮음","정확도 ±"+Math.round(fix.accuracy)+"m라 기억 지점을 알리지 않습니다");return;}
        if(near==null){statusView(p.surface2,p.ink2,p.ink,"지켜보는 중","기억 지점이 없습니다. 헷갈린 곳에서 여기 헷갈려를 누르세요");return;}
        if(near.meters<=limit&&session.announced(near.point.id)){statusView(p.surface2,p.ink2,p.ink,"알림 완료",session.spoken()!=null?session.spoken().text:near.point.label+" 근처입니다");return;}
        if(near.meters<=limit&&!st.moving){statusView(p.surface2,p.ink2,p.ink,st.parked?"정차 중":"지켜보는 중","출발하면 "+near.point.label+"을 알립니다");return;}
        statusView(p.surface2,p.ink2,p.ink,"지켜보는 중","가장 가까운 기억 지점까지 "+Math.round(near.meters)+"m");
    }
}
