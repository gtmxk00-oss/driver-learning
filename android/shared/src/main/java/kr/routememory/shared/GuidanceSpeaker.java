package kr.routememory.shared;

import android.content.Context;
import android.media.*;
import android.speech.tts.*;
import java.util.Locale;

/** One application-wide TTS instance, brief ducking focus, no repeated speech after stop. */
final class GuidanceSpeaker {
    private final TextToSpeech tts;
    private final AudioManager audio;
    private final AudioFocusRequest focus;
    private boolean ready;
    GuidanceSpeaker(Context context) {
        audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes attrs=new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs).setOnAudioFocusChangeListener(change->{
                if(change==AudioManager.AUDIOFOCUS_LOSS||change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)stop();
            }).build();
        // Initialization callbacks run asynchronously; finish setup on the main queue.
        tts=new TextToSpeech(context,status->new android.os.Handler(android.os.Looper.getMainLooper()).post(()->init(status)));
        tts.setAudioAttributes(attrs);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
            @Override public void onStart(String id) {}
            @Override public void onDone(String id) { audio.abandonAudioFocusRequest(focus); }
            @Override public void onError(String id) { audio.abandonAudioFocusRequest(focus); }
        });
    }
    private void init(int status) {
        if(status==TextToSpeech.SUCCESS) {
            int language=tts.setLanguage(Locale.KOREAN);
            ready=language!=TextToSpeech.LANG_MISSING_DATA&&language!=TextToSpeech.LANG_NOT_SUPPORTED;
            tts.setSpeechRate(0.94f);
        }
    }
    void speak(String text) {
        if(!ready||text.isEmpty())return;
        if(audio.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,"guidance-"+System.nanoTime());
    }
    void stop() { tts.stop(); audio.abandonAudioFocusRequest(focus); }
}
