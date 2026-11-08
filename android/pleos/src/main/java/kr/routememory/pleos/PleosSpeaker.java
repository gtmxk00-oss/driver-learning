package kr.routememory.pleos;

import ai.pleos.playground.tts.TextToSpeech;
import ai.pleos.playground.tts.constant.ErrorType;
import ai.pleos.playground.tts.constant.Mode;
import ai.pleos.playground.tts.listener.EventListener;
import ai.pleos.playground.tts.listener.OnInitializeListener;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.Locale;

/**
 * The companion's voice through the car's speakers, with the Pleos TextToSpeech SDK (on-device engine, Korean).
 * Everything the rules say goes through here: the one sentence, the parked question, the briefing, the summary, a memo
 * echo. Without the Speech Service (a phone, a plain emulator) Android's own speech synthesis stands in, and the screen
 * says which one is talking.
 */
final class PleosSpeaker {
    interface Host { void onSpeakerState(String state); }
    private static final String TAG="PleosSpeaker";
    private final Context context; private final Host host;
    private final Handler main=new Handler(Looper.getMainLooper());
    private TextToSpeech pleos; private boolean pleosReady;
    private android.speech.tts.TextToSpeech fallback; private boolean fallbackReady;
    private String state="준비 전";
    private final EventListener events=new EventListener() {
        @Override public void onStart(){}
        @Override public void onReady(){}
        @Override public void onDone(){}
        @Override public void onStop(){}
        @Override public void onUpdatedRms(double rms){}
        @Override public void onError(ErrorType error){post(()->setState("오류: "+error));}
    };

    PleosSpeaker(Context context,Host host) {
        this.context=context.getApplicationContext();this.host=host;
        try{fallback=new android.speech.tts.TextToSpeech(this.context,code->{fallbackReady=code==android.speech.tts.TextToSpeech.SUCCESS&&fallback!=null&&fallback.setLanguage(Locale.KOREAN)>=android.speech.tts.TextToSpeech.LANG_AVAILABLE;});}
        catch(RuntimeException e){fallback=null;}
    }
    String state(){return state;}
    boolean ready(){return pleosReady;}
    private void setState(String s){state=s;host.onSpeakerState(s);}
    private void post(Runnable r){if(Looper.myLooper()==Looper.getMainLooper())r.run();else main.post(r);}

    /** Connects to the car's speech service; until it answers, or if it never does, Android's voice is used. */
    void connect() {
        if(pleos!=null)return;
        try {
            TextToSpeech t=new TextToSpeech(context,Mode.ON_DEVICE);
            // The listener joins after the service answers: before initialize() the SDK has nothing to attach it to
            // (seen on the official emulator as UninitializedPropertyAccessException).
            t.initialize(new OnInitializeListener() {
                @Override public void onSuccess(){post(()->{try{t.addEventListener(events);t.setLocale(Locale.KOREA);}catch(RuntimeException e){android.util.Log.d(TAG,"tts after init",e);}pleosReady=true;setState("준비됨");});}
                @Override public void onFailure(ErrorType error){post(()->setState("연결 실패: "+error+(fallbackReady?" · Android 음성으로 대체":"")));}
            });
            pleos=t;setState("연결 중");
        } catch(RuntimeException|LinkageError e){android.util.Log.d(TAG,"tts init",e);pleos=null;setState("연결 실패: "+e.getClass().getSimpleName()+(fallbackReady?" · Android 음성으로 대체":""));}
    }
    void speak(String text) {
        if(text==null||text.trim().isEmpty())return;
        if(pleosReady&&pleos!=null) {
            try{pleos.speak(text,false);return;}
            catch(RuntimeException e){pleosReady=false;setState("오류: "+e.getClass().getSimpleName()+(fallbackReady?" · Android 음성으로 대체":""));}
        }
        if(fallbackReady&&fallback!=null)fallback.speak(text,android.speech.tts.TextToSpeech.QUEUE_ADD,null,"guidance");
    }
    void release() {
        main.removeCallbacksAndMessages(null);
        try{if(pleos!=null){pleos.stop();pleos.removeEventListener(events);pleos.release();}}catch(RuntimeException ignored){}
        pleos=null;pleosReady=false;state="연결 해제";
        try{if(fallback!=null)fallback.shutdown();}catch(RuntimeException ignored){}
        fallback=null;fallbackReady=false;
    }
}
