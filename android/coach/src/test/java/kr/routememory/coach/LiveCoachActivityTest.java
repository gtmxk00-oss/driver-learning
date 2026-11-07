package kr.routememory.coach;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class LiveCoachActivityTest {
    private static void collect(View view,List<String> texts,List<Button> buttons) {
        if(view instanceof Button)buttons.add((Button)view);
        if(view instanceof TextView)texts.add(((TextView)view).getText().toString().replace("⁠",""));
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)collect(((ViewGroup)view).getChildAt(i),texts,buttons);
    }
    private static List<String> texts(LiveCoachActivity activity){List<String> texts=new ArrayList<>();collect(activity.getWindow().getDecorView(),texts,new ArrayList<>());return texts;}
    private static boolean shown(LiveCoachActivity activity,String part){for(String t:texts(activity))if(t.contains(part))return true;return false;}
    private static Button button(LiveCoachActivity activity,String title) {
        List<Button> buttons=new ArrayList<>();collect(activity.getWindow().getDecorView(),new ArrayList<>(),buttons);
        for(Button b:buttons)if(b.getText().toString().equals(title))return b;
        throw new AssertionError("No button: "+title);
    }
    // One degree of latitude is about 111.2 km.
    private static double north(double meters){return 37.5+meters/111195;}

    @Test public void saysWhatIsRealAndWhatIsNot() {
        LiveCoachActivity activity=Robolectric.buildActivity(LiveCoachActivity.class).setup().get();
        assertTrue(shown(activity,LiveCoachActivity.BADGE));
        assertTrue(shown(activity,"위치 꺼짐"));
        assertEquals(false,button(activity,"여기 헷갈려").isEnabled());
    }
    @Test public void aFreshFixUnlocksRememberAndTheNextApproachSpeaks() {
        LiveCoachActivity activity=Robolectric.buildActivity(LiveCoachActivity.class).setup().get();
        long now=System.currentTimeMillis();
        activity.feed(north(0),127,8,0,now-2000);
        assertTrue(shown(activity,"기억 지점이 없습니다"));
        button(activity,"여기 헷갈려").performClick();
        assertTrue(shown(activity,"지점 1을 기억 지점으로 저장했습니다"));
        assertTrue(shown(activity,"기억 지점: 1곳"));
        activity.feed(north(1000),127,8,10,now-1000);
        activity.feed(north(250),127,8,10,now);
        assertTrue(shown(activity,"헷갈렸던 지점입니다. 미리 확인하세요."));
        assertTrue(shown(activity,"주행 중"));
    }
    @Test public void driverTypeChangesTheReminderDistance() {
        LiveCoachActivity activity=Robolectric.buildActivity(LiveCoachActivity.class).setup().get();
        assertTrue(shown(activity,"판단 거리: 300m"));
        button(activity,"화물").performClick();
        assertTrue(shown(activity,"판단 거리: 600m"));
        assertTrue(shown(activity,"실제 위치 · 화물"));
    }
}
