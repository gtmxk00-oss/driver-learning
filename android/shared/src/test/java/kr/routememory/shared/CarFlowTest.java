package kr.routememory.shared;

import android.content.Context;
import androidx.car.app.model.*;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.car.app.testing.TestCarContext;
import kr.routememory.core.Engine;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Builds real Car App Library models, enforcing their runtime content constraints. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=29)
public final class CarFlowTest {
    private MemoryCarScreen screen;
    private DemoController state;
    @Before public void setup() {
        Context app=RuntimeEnvironment.getApplication();
        state=DemoController.get(app);state.stop();state.trip=null;state.clearCurrentDriver();
        screen=new MemoryCarScreen(TestCarContext.createCarContext(app));
        screen.onStart(screen);
    }
    @After public void cleanup() {screen.onDestroy(screen);}
    @Test public void homeHasTwoParkedActions() {
        PaneTemplate home=(PaneTemplate)screen.onGetTemplate();
        assertEquals(2,home.getPane().getActions().size());
        assertTrue(home.getPane().getActions().get(0).getOnClickDelegate().isParkedOnly());
        assertTrue(home.getPane().getActions().get(1).getOnClickDelegate().isParkedOnly());
    }
    @Test public void driveBuildsNavigationTemplateAndStopAction() {
        state.start(false);state.pause(true);
        NavigationTemplate driving=(NavigationTemplate)screen.onGetTemplate();
        assertNotNull(driving.getNavigationInfo());
        assertEquals(1,driving.getActionStrip().getActions().size());
    }
    @Test public void preDriveBriefShowsThreeJunctions() {
        MemoryCarScreen.BriefScreen brief=new MemoryCarScreen.BriefScreen(
            TestCarContext.createCarContext(RuntimeEnvironment.getApplication()),true);
        PaneTemplate model=(PaneTemplate)brief.onGetTemplate();
        assertEquals(3,model.getPane().getRows().size());
        assertTrue(model.getPane().getActions().get(0).getOnClickDelegate().isParkedOnly());
    }
    @Test public void rerouteBuildsLoadingTemplate() {
        state.start(true);state.pause(true);
        for(int i=0;i<12;i++)state.trip.tick(state.engine);
        assertEquals(Engine.Trip.Status.REROUTING,state.trip.status);
        assertTrue(screen.onGetTemplate() instanceof NavigationTemplate);
    }
    @Test public void reviewIsParkedAndChangesNextTripTiming() {
        state.start(true);state.pause(true);
        while(state.trip.status!=Engine.Trip.Status.FINISHED)state.trip.tick(state.engine);
        PaneTemplate review=(PaneTemplate)screen.onGetTemplate();
        assertTrue(review.getPane().getActions().get(0).getOnClickDelegate().isParkedOnly());
        state.review(true);assertEquals(450,state.lead());
        assertTrue(screen.onGetTemplate() instanceof PaneTemplate);
    }
    @Test public void profileStoreRoundTripKeepsDriversSeparate() {
        Context app=RuntimeEnvironment.getApplication();ProfileStore store=new ProfileStore(app);
        Engine.Profile first=new Engine.Profile("one","One");first.confirmedMisses.put("exit-a",2);
        first.avoid.add("merge-c");first.notes.put("merge-c","합류 어려움");store.save(first);
        Engine.Profile restored=store.load("one");
        assertEquals(2,restored.misses("exit-a"));assertTrue(restored.avoid.contains("merge-c"));
        assertEquals("합류 어려움",restored.notes.get("merge-c"));assertEquals(0,store.load("two").misses("exit-a"));
    }
}
