package kr.routememory.pleos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import ai.pleos.playground.navi.constants.TurnType;
import ai.pleos.playground.navi.data.TBTInfo;
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue;
import ai.pleos.playground.vehicle.constant.control.VehicleGear;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class GuidanceActivityTest {
    private static void collect(View view,List<String> texts,List<Button> buttons) {
        if(view instanceof Button)buttons.add((Button)view);
        if(view instanceof TextView)texts.add(((TextView)view).getText().toString().replace("⁠",""));
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)collect(((ViewGroup)view).getChildAt(i),texts,buttons);
    }
    private static boolean shown(GuidanceActivity activity,String part){List<String> texts=new ArrayList<>();collect(activity.getWindow().getDecorView(),texts,new ArrayList<>());for(String t:texts)if(t.contains(part))return true;return false;}
    private static void press(GuidanceActivity activity,String title) {
        List<Button> buttons=new ArrayList<>();collect(activity.getWindow().getDecorView(),new ArrayList<>(),buttons);
        for(Button b:buttons)if(b.getText().toString().equals(title)){b.performClick();return;}
        throw new AssertionError("No button: "+title);
    }
    private static void settle(){shadowOf(Looper.getMainLooper()).idle();}

    @Test public void saysWhatItNeedsBeforeConnecting() {
        GuidanceActivity activity=Robolectric.buildActivity(GuidanceActivity.class).setup().get();
        assertTrue(shown(activity,GuidanceActivity.BADGE));
        assertTrue(shown(activity,"Vehicle SDK: 연결 전"));
        assertTrue(shown(activity,"NaviHelper SDK: 연결 전"));
        assertTrue(shown(activity,"연결 안 됨"));
    }
    @Test public void sdkEventsReachTheRulesAndTheQuestionIsAnswerable() {
        GuidanceActivity activity=Robolectric.buildActivity(GuidanceActivity.class).setup().get();
        PleosBridge bridge=activity.bridge();
        bridge.onSelected(VehicleGear.GEAR_DRIVE);
        bridge.onTurnSignalStateUpdated(TurnSignalValue.OFF);
        bridge.onTBTInfo(Collections.singletonList(new TBTInfo(TurnType.TURN_EXIT_IC_L,100.0,"강변북로 진출")));
        settle();
        assertTrue(shown(activity,"왼쪽 출구"));
        assertTrue(shown(activity,"100m"));
        bridge.onReRouteEnded();
        bridge.onSelected(VehicleGear.GEAR_PARK);
        settle();
        assertTrue(shown(activity,"아까 그 출구, 놓치셨나요?"));
        press(activity,"네, 놓쳤어요");settle();
        assertTrue(shown(activity,"기억 지점: 1곳"));
        assertEquals(false,shown(activity,"아까 그 출구, 놓치셨나요?"));
    }
    @Test public void turnTypesMapOntoTheRules() {
        assertEquals(kr.routememory.core.DriveCoach.TurnType.EXIT_RIGHT,PleosBridge.map(TurnType.TURN_EXIT_IC_R));
        assertEquals(kr.routememory.core.DriveCoach.TurnType.ROUNDABOUT,PleosBridge.map(TurnType.TURN_ENTER_ROUNDABOUT_04));
        assertEquals(kr.routememory.core.DriveCoach.TurnType.TURN_LEFT,PleosBridge.map(TurnType.TURN_SHARP_L));
        assertEquals(null,PleosBridge.map(TurnType.TURN_STRAIGHT));
        assertEquals("exit_left|강변북로 진출",PleosBridge.placeId(kr.routememory.core.DriveCoach.TurnType.EXIT_LEFT," 강변북로 진출 "));
    }
}
