package kr.routememory.shared;

import android.graphics.*;
import android.app.AlertDialog;
import android.os.Looper;
import android.util.LruCache;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.TreeSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.json.*;
import kr.routememory.core.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Opt-in native layout proof; public sample only. Not a device/GPS validation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=29)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class RoadUiRenderTest {
    @Test @Config(qualifiers="w412dp-h860dp-mdpi")
    public void portrait() throws Exception {render(412,860,"android-map-portrait.png");}
    @Test @Config(qualifiers="w960dp-h540dp-land-mdpi")
    public void landscape() throws Exception {render(960,540,"android-map-landscape.png");}
    @Test @Config(qualifiers="w412dp-h860dp-mdpi")
    public void satellitePortrait() throws Exception {render(412,860,"android-satellite-portrait.png",RoadMapStyle.SATELLITE);}
    @Test @Config(qualifiers="w960dp-h540dp-land-mdpi")
    public void satelliteLandscape() throws Exception {render(960,540,"android-satellite-landscape.png",RoadMapStyle.SATELLITE);}
    @Test @Config(qualifiers="w412dp-h860dp-mdpi")
    public void highResolutionSetup()throws Exception{render(412,860,"android-hd-setup.png",RoadMapStyle.ROAD,true);}
    @Test @Config(qualifiers="w412dp-h860dp-mdpi")
    @SuppressWarnings("unchecked")
    public void placeSearchResults() throws Exception {
        String output=System.getenv("ROUTE_MEMORY_UI_OUTPUT"),fixture=System.getenv("ROUTE_MEMORY_SEARCH_FIXTURE");
        if(output==null||fixture==null)throw new IllegalStateException("Set ROUTE_MEMORY_UI_OUTPUT and ROUTE_MEMORY_SEARCH_FIXTURE for native search layout preview");
        JSONObject captured=new JSONObject(new String(Files.readAllBytes(new File(fixture).toPath()),StandardCharsets.UTF_8));
        JSONArray rows=captured.getJSONArray("results");List<PlaceSearchClient.Place> places=new ArrayList<>();
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);places.add(new PlaceSearchClient.Place(row.getString("id"),row.getString("name"),row.getString("address"),row.getString("category"),row.getDouble("lat"),row.getDouble("lon")));}
        assertFalse(places.isEmpty());
        RoadController state=RoadController.get(RuntimeEnvironment.getApplication());state.stopAll();state.useSeoulSample();
        RoadTileStore tiles=RoadTileStore.get(RuntimeEnvironment.getApplication());Field retry=RoadTileStore.class.getDeclaredField("retryAfter");retry.setAccessible(true);long oldRetry=retry.getLong(tiles);retry.setLong(tiles,Long.MAX_VALUE);
        String tileDirectory=System.getenv("ROUTE_MEMORY_TILE_DIR");
        if(tileDirectory!=null){Field cache=RoadTileStore.class.getDeclaredField("memory");cache.setAccessible(true);LruCache<String,Bitmap> memory=(LruCache<String,Bitmap>)cache.get(tiles);File[] files=new File(tileDirectory).listFiles((dir,file)->file.matches("[0-9]+_[0-9]+_[0-9]+\\.png"));if(files!=null)for(File file:files){Bitmap bitmap=BitmapFactory.decodeFile(file.getAbsolutePath());if(bitmap!=null)memory.put(RoadMapStyle.ROAD.cacheId+"_"+file.getName().replace(".png",""),bitmap);}}
        ActivityController<RoadActivity> controller=null;
        try{
            controller=Robolectric.buildActivity(RoadActivity.class).create().start().resume();RoadActivity app=controller.get();
            app.placeClient=new PlaceSearchClient(){@Override public List<Place> search(String query){return places;}};
            app.requestRoute=()->fail("A layout preview must not prepare a route");
            ViewGroup content=app.findViewById(android.R.id.content);View screen=content.getChildAt(0);layout(screen,412,860);
            app.placeSearch();AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            EditText query=(EditText)find(dialog.getWindow().getDecorView(),"주소·장소명 검색어",true);assertNotNull(query);query.setText("서울특별시 종로구 사직로161");
            TextView search=(TextView)find(dialog.getWindow().getDecorView(),"검색",false);assertNotNull(search);search.performClick();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(find(dialog.getWindow().getDecorView(),places.get(0).name,false)==null&&System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5);}
            assertNotNull(find(dialog.getWindow().getDecorView(),places.get(0).name,false));
            View decor=dialog.getWindow().getDecorView();decor.forceLayout();decor.measure(View.MeasureSpec.makeMeasureSpec(380,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(812,View.MeasureSpec.AT_MOST));decor.layout(0,0,decor.getMeasuredWidth(),decor.getMeasuredHeight());
            Bitmap result=Bitmap.createBitmap(412,860,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(result);screen.draw(canvas);
            canvas.drawColor(Color.argb(Math.round(dialog.getWindow().getAttributes().dimAmount*255),0,0,0));int save=canvas.save();canvas.translate((412-decor.getWidth())/2f,(860-decor.getHeight())/2f);decor.draw(canvas);canvas.restoreToCount(save);
            File directory=new File(output);if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create render output");
            try(OutputStream stream=new FileOutputStream(new File(directory,"android-place-search.png"))){assertTrue(result.compress(Bitmap.CompressFormat.PNG,100,stream));}
            assertFalse(state.gpsEnabled);assertFalse(state.guiding);assertNull(state.fix);assertFalse(state.loading);
        }finally{if(controller!=null)controller.pause().stop().destroy();retry.setLong(tiles,oldRetry);state.stopAll();}
    }
    @Test @Config(qualifiers="w412dp-h860dp-mdpi")
    @SuppressWarnings("unchecked")
    public void nearbyResults()throws Exception{
        String output=System.getenv("ROUTE_MEMORY_UI_OUTPUT");if(output==null)throw new IllegalStateException("Set ROUTE_MEMORY_UI_OUTPUT for opt-in nearby layout preview");
        RoadController state=RoadController.get(RuntimeEnvironment.getApplication());state.stopAll();state.useSeoulSample();
        RoadTileStore tiles=RoadTileStore.get(RuntimeEnvironment.getApplication());Field retry=RoadTileStore.class.getDeclaredField("retryAfter");retry.setAccessible(true);long oldRetry=retry.getLong(tiles);retry.setLong(tiles,Long.MAX_VALUE);
        String tileDirectory=System.getenv("ROUTE_MEMORY_TILE_DIR");if(tileDirectory!=null){Field cache=RoadTileStore.class.getDeclaredField("memory");cache.setAccessible(true);LruCache<String,Bitmap> memory=(LruCache<String,Bitmap>)cache.get(tiles);File[] files=new File(tileDirectory).listFiles((dir,file)->file.matches("[0-9]+_[0-9]+_[0-9]+\\.png"));if(files!=null)for(File file:files){Bitmap bitmap=BitmapFactory.decodeFile(file.getAbsolutePath());if(bitmap!=null)memory.put(RoadMapStyle.ROAD.cacheId+"_"+file.getName().replace(".png",""),bitmap);}}
        ActivityController<RoadActivity> controller=null;
        try{
            controller=Robolectric.buildActivity(RoadActivity.class).create().start().resume();RoadActivity app=controller.get();
            app.nearbyClient=new NearbySearchClient(){@Override public Result search(RoadRoute.Point center,String kind,int radius){return new Result(kind,"레이아웃 검사 데이터",center,radius,java.time.Instant.now().toString(),List.of(
                new NearbyPlace("layout-1","레이아웃 검사 주차장","서울특별시 종로구 · 검사 전용 주소","주차장",37.5797,126.978,111,null,null),
                new NearbyPlace("layout-2","레이아웃 검사 주차장 2","서울특별시 종로구 · 검사 전용 주소","주차장",37.581,126.978,220,null,null)),"검사용 데이터 · 실제 장소가 아닙니다. 유가·잔여 면수 미연결");}};
            app.requestRoute=()->fail("A layout preview must not prepare a route");ViewGroup content=app.findViewById(android.R.id.content);View screen=content.getChildAt(0);layout(screen,412,860);
            app.nearbySearch("parking");AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();View search=find(dialog.getWindow().getDecorView(),"주변 주차장 조회",false);assertNotNull(search);search.performClick();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(find(dialog.getWindow().getDecorView(),"레이아웃 검사 주차장",false)==null&&System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5);}assertNotNull(find(dialog.getWindow().getDecorView(),"레이아웃 검사 주차장",false));assertNotNull(find(dialog.getWindow().getDecorView(),"잔여 면수 미제공",false));
            View decor=dialog.getWindow().getDecorView();decor.forceLayout();decor.measure(View.MeasureSpec.makeMeasureSpec(380,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(812,View.MeasureSpec.AT_MOST));decor.layout(0,0,decor.getMeasuredWidth(),decor.getMeasuredHeight());
            Bitmap result=Bitmap.createBitmap(412,860,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(result);screen.draw(canvas);canvas.drawColor(Color.argb(Math.round(dialog.getWindow().getAttributes().dimAmount*255),0,0,0));int save=canvas.save();canvas.translate((412-decor.getWidth())/2f,(860-decor.getHeight())/2f);decor.draw(canvas);canvas.restoreToCount(save);
            File directory=new File(output);if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create render output");try(OutputStream stream=new FileOutputStream(new File(directory,"android-nearby-parking.png"))){assertTrue(result.compress(Bitmap.CompressFormat.PNG,100,stream));}
            assertFalse(state.gpsEnabled);assertFalse(state.guiding);assertNull(state.fix);assertFalse(state.loading);
        }finally{if(controller!=null)controller.pause().stop().destroy();retry.setLong(tiles,oldRetry);state.stopAll();}
    }
    private static void layout(View screen,int width,int height){
        for(int pass=0;pass<3;pass++){screen.forceLayout();if(screen instanceof ViewGroup)for(int i=0;i<((ViewGroup)screen).getChildCount();i++)((ViewGroup)screen).getChildAt(i).forceLayout();screen.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));screen.layout(0,0,width,height);}
    }
    private static View find(View view,String value,boolean description){
        if(description&&value.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;
        if(!description&&view instanceof TextView&&!(view instanceof EditText)&&value.contentEquals(((TextView)view).getText()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),value,description);if(found!=null)return found;}
        return null;
    }
    private static void previewScrollIndicators(View view){
        // The preview draws a separately measured dialog decor without a real Window AttachInfo.
        // Native scroll indicators require AttachInfo; production scrolling remains unchanged.
        view.setScrollIndicators(0);
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)previewScrollIndicators(((ViewGroup)view).getChildAt(i));
    }
    @SuppressWarnings("unchecked")
    private void render(int width,int height,String name) throws Exception {render(width,height,name,RoadMapStyle.ROAD);}
    @SuppressWarnings("unchecked")
    private void render(int width,int height,String name,RoadMapStyle style) throws Exception {render(width,height,name,style,false);}
    @SuppressWarnings("unchecked")
    private void render(int width,int height,String name,RoadMapStyle style,boolean setup) throws Exception {
        String output=System.getenv("ROUTE_MEMORY_UI_OUTPUT");
        if(output==null||output.isEmpty())throw new IllegalStateException("Set ROUTE_MEMORY_UI_OUTPUT for opt-in layout render");
        RoadController state=RoadController.get(RuntimeEnvironment.getApplication());state.stopAll();state.useSeoulSample();state.disconnectHighResolution();state.setMapStyle(style);
        try(InputStream stream=getClass().getResourceAsStream("/osrm-seoul.json")){
            assertNotNull(stream);state.route=OsrmClient.parse(new String(stream.readAllBytes(),StandardCharsets.UTF_8));
        }
        RoadTileStore tiles=RoadTileStore.get(RuntimeEnvironment.getApplication());
        Field retry=RoadTileStore.class.getDeclaredField("retryAfter");retry.setAccessible(true);long oldRetry=retry.getLong(tiles);retry.setLong(tiles,Long.MAX_VALUE);
        // Optional on-demand public map tiles collected separately by QA. Never fetch from this test.
        String tileDirectory=System.getenv(style==RoadMapStyle.SATELLITE?"ROUTE_MEMORY_SATELLITE_TILE_DIR":"ROUTE_MEMORY_TILE_DIR");
        if(tileDirectory!=null){
            Field cache=RoadTileStore.class.getDeclaredField("memory");cache.setAccessible(true);LruCache<String,Bitmap> memory=(LruCache<String,Bitmap>)cache.get(tiles);
            File[] files=new File(tileDirectory).listFiles((dir,file)->file.matches("[0-9]+_[0-9]+_[0-9]+\\.(?:png|jpg)"));
            if(files!=null)for(File file:files){Bitmap bitmap=BitmapFactory.decodeFile(file.getAbsolutePath());if(bitmap!=null)memory.put(style.cacheId+"_"+file.getName().replaceFirst("\\.(?:png|jpg)$",""),bitmap);}
        }
        ActivityController<RoadActivity> controller=null;
        try{
            controller=Robolectric.buildActivity(RoadActivity.class).create().start().resume();RoadActivity app=controller.get();
            ViewGroup content=app.findViewById(android.R.id.content);View screen=content.getChildAt(0);
            for(int pass=0;pass<3;pass++){
                screen.forceLayout();for(int i=0;i<((ViewGroup)screen).getChildCount();i++)((ViewGroup)screen).getChildAt(i).forceLayout();
                screen.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));screen.layout(0,0,width,height);
            }
            View map=((ViewGroup)screen).getChildAt(0);android.widget.FrameLayout.LayoutParams mapParams=(android.widget.FrameLayout.LayoutParams)map.getLayoutParams();
            assertEquals("Map attribution stays above the persistent bottom sheet",height-mapParams.bottomMargin,map.getHeight());
            Bitmap result=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);Canvas rendered=new Canvas(result);screen.draw(rendered);
            if(setup){
                app.gatewayLookup=origin->{throw new AssertionError("A setup layout preview cannot contact a map gateway");};app.mapConnection();
                AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();View decor=dialog.getWindow().getDecorView();previewScrollIndicators(decor);
                decor.forceLayout();decor.measure(View.MeasureSpec.makeMeasureSpec(width-32,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height-48,View.MeasureSpec.AT_MOST));decor.layout(0,0,decor.getMeasuredWidth(),decor.getMeasuredHeight());
                rendered.drawColor(Color.argb(Math.round(dialog.getWindow().getAttributes().dimAmount*255),0,0,0));int save=rendered.save();rendered.translate((width-decor.getWidth())/2f,(height-decor.getHeight())/2f);decor.draw(rendered);rendered.restoreToCount(save);
            }
            File directory=new File(output);if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create render output");
            Field painterField=RoadMapView.class.getDeclaredField("painter");painterField.setAccessible(true);Object painter=painterField.get(map);
            Field z=RoadMapPainter.class.getDeclaredField("zoom"),wx=RoadMapPainter.class.getDeclaredField("world"),cx=RoadMapPainter.class.getDeclaredField("centerX"),cy=RoadMapPainter.class.getDeclaredField("centerY"),scale=RoadMapPainter.class.getDeclaredField("scale");
            for(Field field:new Field[]{z,wx,cx,cy,scale})field.setAccessible(true);
            Method focus=RoadMapPainter.class.getDeclaredMethod("focusY");focus.setAccessible(true);
            int zoom=z.getInt(painter),tileSize=Math.round(256*scale.getFloat(painter)),count=1<<zoom;
            double left=cx.getDouble(painter)*wx.getDouble(painter)-map.getWidth()/2d,top=cy.getDouble(painter)*wx.getDouble(painter)-((Number)focus.invoke(painter)).doubleValue();
            TreeSet<String> keys=new TreeSet<>();
            int nativeZoom=Math.min(zoom,style.maxNativeZoom),factor=1<<(zoom-nativeZoom),nativeCount=1<<nativeZoom;
            for(int x=(int)Math.floor(left/tileSize);x<=(int)Math.floor((left+map.getWidth())/tileSize);x++)for(int y=(int)Math.floor(top/tileSize);y<=(int)Math.floor((top+map.getHeight())/tileSize);y++)if(y>=0&&y<count)keys.add(nativeZoom+"_"+Math.floorMod(Math.floorDiv(x,factor),nativeCount)+"_"+Math.floorDiv(y,factor));
            Files.write(new File(directory,name.replace(".png","-tiles.txt")).toPath(),keys,StandardCharsets.UTF_8);
            try(OutputStream stream=new FileOutputStream(new File(directory,name))){assertTrue(result.compress(Bitmap.CompressFormat.PNG,100,stream));}
            assertFalse(state.gpsEnabled);assertFalse(state.guiding);assertNull(state.fix);
        }finally{
            if(controller!=null)controller.pause().stop().destroy();retry.setLong(tiles,oldRetry);state.stopAll();
        }
    }
}
