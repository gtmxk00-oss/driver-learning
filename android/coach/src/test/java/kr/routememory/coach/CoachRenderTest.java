package kr.routememory.coach;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import kr.routememory.core.CoachDemo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Opt-in layout proof with ROUTE_MEMORY_RENDER_UI=1. Not a device, Emulator or vehicle validation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=29)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class CoachRenderTest {
    private static double at(String id,double offset) {
        for(int i=0;i<CoachDemo.scenes().size();i++)if(CoachDemo.scenes().get(i).id.equals(id))return CoachDemo.startOf(i)+offset;
        throw new AssertionError(id);
    }
    private static void render(int width,int height,double seconds,String file) throws Exception {
        CoachActivity activity=Robolectric.buildActivity(CoachActivity.class).setup().get();
        activity.seek(seconds);
        View root=activity.findViewById(android.R.id.content);
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
        root.layout(0,0,width,height);
        Bitmap image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(image));
        try(FileOutputStream out=new FileOutputStream(new File(System.getenv("ROUTE_MEMORY_UI_OUTPUT"),file))){image.compress(Bitmap.CompressFormat.PNG,100,out);}
    }
    @Test @Config(qualifiers="w960dp-h540dp-land-mdpi")
    public void landscapeSpeak() throws Exception {render(960,540,at("speak",3),"coach-landscape-speak.png");}
    @Test @Config(qualifiers="w412dp-h860dp-mdpi")
    public void portraitSilent() throws Exception {render(412,860,at("silent",4),"coach-portrait-silent.png");}
}
