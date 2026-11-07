package kr.routememory.pleos;

import android.content.Intent;
import kr.routememory.coach.CoachActivity;

/** Entry point of the Pleos Connect build: the virtual demo, the live position mode and the Pleos SDK link. Location is asked only for the live position mode; no network permission is used. */
public final class MainActivity extends CoachActivity {
    @Override protected Intent vehicleLinkIntent(){return new Intent(this,GuidanceActivity.class);}
}
