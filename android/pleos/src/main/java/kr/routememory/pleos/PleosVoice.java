package kr.routememory.pleos;

import ai.pleos.playground.stt.SpeechToText;
import ai.pleos.playground.stt.constant.ErrorType;
import ai.pleos.playground.stt.constant.Mode;
import ai.pleos.playground.stt.listener.OnInitializeListener;
import ai.pleos.playground.stt.listener.ResultListener;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.Locale;

/**
 * One spoken phrase through the car's microphone, with the Pleos SpeechToText SDK (on-device engine, Korean).
 * Used for a memo in the driver's own words and for answering the parked question. Without the Speech Service (a phone,
 * a plain emulator) it reports itself unavailable and the screen keeps its buttons. A test can hand it a recording
 * (16 kHz, 16-bit mono PCM) instead of opening the microphone, which is how it is checked on the emulator.
 */
final class PleosVoice {
    interface Host { void onVoiceState(String state); void onHeard(String words); }
    private static final long OPEN_TIMEOUT_MS=8000,LISTEN_TIMEOUT_MS=9000,FINAL_WAIT_MS=600;
    private final Context context; private final Host host;
    private final Handler main=new Handler(Looper.getMainLooper());
    private SpeechToText stt; private boolean ready,listening,begun; private String lastWords="",state="준비 전";
    private final Runnable openTimeout=()->finish(""),listenTimeout=()->finish(lastWords),finalWait=()->finish(lastWords);
    private final ResultListener listener=new ResultListener() {
        @Override public void onReady(){post(PleosVoice.this::began);}
        @Override public void onStartedRecognition(){post(PleosVoice.this::began);}
        @Override public void onUpdatedRms(double rms){post(PleosVoice.this::began);}
        @Override public void onUpdatedEpdData(long on,long off){}
        @Override public void onUpdated(String words,boolean completed){post(()->{began();if(words!=null&&!words.trim().isEmpty())lastWords=words.trim();if(completed)finish(lastWords);});}
        @Override public void onEndedRecognition(){post(()->{if(!listening)return;main.removeCallbacks(listenTimeout);main.removeCallbacks(finalWait);main.postDelayed(finalWait,FINAL_WAIT_MS);});}
        @Override public void onError(ErrorType error){post(()->{if(listening)finish(error==ErrorType.TIMEOUT?lastWords:"");setState("오류: "+error);});}
    };

    PleosVoice(Context context,Host host){this.context=context.getApplicationContext();this.host=host;}
    boolean ready(){return ready;}
    boolean listening(){return listening;}
    String state(){return state;}
    private void setState(String s){state=s;host.onVoiceState(s);}
    private void post(Runnable r){if(Looper.myLooper()==Looper.getMainLooper())r.run();else main.post(r);}

    void connect() {
        if(stt!=null)return;
        try {
            SpeechToText s=new SpeechToText(context,Mode.ON_DEVICE);
            s.addListener(listener);
            s.initialize(new OnInitializeListener() {
                @Override public void onSuccess(){post(()->{try{s.setLocale(Locale.KOREA);ready=true;setState("준비됨");}catch(RuntimeException e){setState("연결 실패: "+e.getClass().getSimpleName());}});}
                @Override public void onFailure(ErrorType error){post(()->setState("연결 실패: "+error));}
            });
            stt=s;setState("연결 중");
        } catch(RuntimeException|LinkageError e){stt=null;setState("연결 실패: "+e.getClass().getSimpleName());}
    }
    /** Opens the microphone for one phrase, or feeds a recording instead; false when not ready or already listening. */
    boolean listen(byte[] recording) {
        if(!ready||stt==null||listening)return false;
        listening=true;begun=false;lastWords="";setState("듣는 중");
        main.postDelayed(openTimeout,OPEN_TIMEOUT_MS);
        try {
            if(recording==null)stt.request();
            else feed(recording);
        } catch(RuntimeException e){finish("");setState("오류: "+e.getClass().getSimpleName());}
        return true;
    }
    private void began(){if(!listening||begun)return;begun=true;main.removeCallbacks(openTimeout);main.postDelayed(listenTimeout,LISTEN_TIMEOUT_MS);}
    /** A recording goes to the service the way a microphone would: 100 ms of 16 kHz 16-bit mono at a time, then the end mark. */
    private void feed(byte[] recording) {
        Thread t=new Thread(()->{
            try {
                for(int at=0;at<recording.length&&listening;at+=3200){int n=Math.min(3200,recording.length-at);byte[] chunk=new byte[n];System.arraycopy(recording,at,chunk,0,n);stt.sendAudio(chunk);Thread.sleep(100);}
                if(listening)stt.completeAudioSend();
            } catch(InterruptedException|RuntimeException e){post(()->{finish("");setState("오류: "+e.getClass().getSimpleName());});}
        },"stt-feed");
        t.setDaemon(true);t.start();
    }
    private void finish(String words) {
        if(!listening)return;
        listening=false;main.removeCallbacks(openTimeout);main.removeCallbacks(listenTimeout);main.removeCallbacks(finalWait);
        try{stt.stop();}catch(RuntimeException ignored){}
        setState(words.isEmpty()?"못 들었어요":"준비됨");
        host.onHeard(words);
    }
    void cancel(){if(listening){listening=false;main.removeCallbacksAndMessages(null);try{stt.stop();}catch(RuntimeException ignored){}setState("준비됨");}}
    void release() {
        cancel();main.removeCallbacksAndMessages(null);
        try{if(stt!=null){stt.removeListener(listener);stt.release();}}catch(RuntimeException ignored){}
        stt=null;ready=false;state="연결 해제";
    }
}
