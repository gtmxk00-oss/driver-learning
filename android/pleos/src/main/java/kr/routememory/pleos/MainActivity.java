package kr.routememory.pleos;

import android.content.Intent;
import kr.routememory.coach.CoachActivity;

/** Entry point of the Pleos Connect build: the virtual demo, the live position mode and the Pleos SDK link. Location is asked only for the live position mode; no network permission is used. */
public final class MainActivity extends CoachActivity {
    /** Opens the car-link screen in its own task, so the Pleos home screen sees a distraction-optimized root while driving. */
    @Override protected boolean pleosLook(){return true;}
    @Override protected Intent vehicleLinkIntent(){return new Intent(this,GuidanceActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);}
}
