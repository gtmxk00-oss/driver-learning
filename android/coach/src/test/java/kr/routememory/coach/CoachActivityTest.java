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
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class CoachActivityTest {
    private static void collect(View view,List<String> texts,List<Button> buttons) {
        if(view instanceof Button)buttons.add((Button)view);
        if(view instanceof TextView)texts.add(((TextView)view).getText().toString());
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)collect(((ViewGroup)view).getChildAt(i),texts,buttons);
    }
    private static List<String> texts(CoachActivity activity) {
        List<String> texts=new ArrayList<>();collect(activity.getWindow().getDecorView(),texts,new ArrayList<>());return texts;
    }
    private static void press(CoachActivity activity,String title) {
        List<Button> buttons=new ArrayList<>();collect(activity.getWindow().getDecorView(),new ArrayList<>(),buttons);
        for(Button b:buttons)if(b.getText().toString().equals(title)){b.performClick();return;}
        throw new AssertionError("No button: "+title);
    }
    @Test public void alwaysSaysThisIsAVirtualDemo() {
        CoachActivity activity=Robolectric.buildActivity(CoachActivity.class).setup().get();
        assertTrue(texts(activity).contains("가상 시연 · Pleos SDK 연동 전 · 신호는 시뮬레이션"));
    }
    @Test public void sceneButtonShowsThatScene() {
        CoachActivity activity=Robolectric.buildActivity(CoachActivity.class).setup().get();
        press(activity,"택시 · 승객 탑승");
        List<String> shown=texts(activity);
        assertTrue(shown.contains("택시 유형 · 승객 탑승"));
        assertTrue(shown.contains("왼쪽 출구"));
    }
    @Test public void playButtonTogglesAndStoppingPauses() {
        ActivityController<CoachActivity> controller=Robolectric.buildActivity(CoachActivity.class).setup();
        press(controller.get(),"재생");
        assertTrue(texts(controller.get()).contains("일시정지"));
        controller.stop();
        assertTrue(texts(controller.get()).contains("재생"));
        assertEquals(false,texts(controller.get()).contains("일시정지"));
    }
    @Test public void parkedScenesShowWhatTheRulesSaid() {
        CoachActivity activity=Robolectric.buildActivity(CoachActivity.class).setup().get();
        press(activity,"가족 · 출발 전 브리핑");
        List<String> shown=texts(activity);
        assertTrue(shown.contains("정차 중"));
        assertTrue(shown.contains("목적지 병원 · 정차 중".replaceAll("(?<=[^ ])(?=[^ ])","\u2060")));
    }
    @Test public void busSceneHasNoRouteHeadline() {
        CoachActivity activity=Robolectric.buildActivity(CoachActivity.class).setup().get();
        press(activity,"버스 · 다음 운행");
        assertTrue(texts(activity).contains("내비게이션 경로 없음"));
    }
}
