package kr.routememory.shared;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;

/** On-demand visible tiles only. Separate source identities; no bulk download or location history. */
final class RoadTileStore {
    private static RoadTileStore instance;
    static synchronized RoadTileStore get(Context context) {
        if(instance==null)instance=new RoadTileStore(context.getApplicationContext());
        return instance;
    }
    private final File directory;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService pool=Executors.newFixedThreadPool(2);
    private final LruCache<String,Bitmap> memory=new LruCache<String,Bitmap>(24*1024*1024){
        @Override protected int sizeOf(String key,Bitmap value){return value.getByteCount();}
    };
    private final Map<String,List<Runnable>> pending=new HashMap<>();
    private final Map<String,Long> failed=new HashMap<>();
    private final Map<RoadMapStyle,Long> providerRetryAfter=new EnumMap<>(RoadMapStyle.class);
    private volatile RoadMapStyle preferredStyle=RoadMapStyle.ROAD;
    private volatile RoadMapGateway highResolutionGateway;
    private long retryAfter;
    private RoadTileStore(Context context) {
        directory=new File(context.getCacheDir(),"map-visible-tiles");
        directory.mkdirs();
    }
    void useStyle(RoadMapStyle style){preferredStyle=style;}
    synchronized void setHighResolutionGateway(RoadMapGateway gateway){highResolutionGateway=gateway;providerRetryAfter.remove(RoadMapStyle.SATELLITE_HD);}
    synchronized Bitmap tile(RoadMapStyle style,int zoom,int x,int y,Runnable callback) {
        if(style==null||zoom<0||zoom>style.maxNativeZoom)return null;
        int count=1<<zoom;
        if(y<0||y>=count)return null;
        x=((x%count)+count)%count;
        RoadMapGateway gateway=highResolutionGateway;
        if(style==RoadMapStyle.SATELLITE_HD&&(gateway==null||!gateway.configured))return null;
        String key=style==RoadMapStyle.SATELLITE_HD?gateway.cacheId+"_"+zoom+"_"+x+"_"+y:style.tileKey(zoom,x,y);
        Bitmap value=memory.get(key);
        if(value!=null)return value;
        long now=System.currentTimeMillis();
        if(now<retryAfter||now<providerRetryAfter.getOrDefault(style,0L)||now-failed.getOrDefault(key,0L)<60_000)return null;
        List<Runnable> waiters=pending.get(key);
        if(waiters!=null){if(waiters.size()<8&&!waiters.contains(callback))waiters.add(callback);return null;}
        if(pending.size()>=32)return null;
        waiters=new ArrayList<>();waiters.add(callback);pending.put(key,waiters);
        final int tx=x;
        pool.execute(()->load(style,gateway,key,zoom,tx,y));
        return null;
    }
    private void load(RoadMapStyle style,String key,int zoom,int x,int y) {
        load(style,highResolutionGateway,key,zoom,x,y);
    }
    private void load(RoadMapStyle style,RoadMapGateway gateway,String key,int zoom,int x,int y) {
        Bitmap bitmap=null;
        boolean abandoned=false;
        try {
            if(preferredStyle!=style){abandoned=true;return;}
            if(style==RoadMapStyle.SATELLITE_HD&&(gateway==null||highResolutionGateway==null||!gateway.origin.equals(highResolutionGateway.origin))){abandoned=true;return;}
            File image=new File(directory,key+".img"),meta=new File(directory,key+".properties");
            Properties values=new Properties();
            if(meta.isFile())try(InputStream in=new FileInputStream(meta)){values.load(in);}
            long expiry=number(values.getProperty("expires"));
            if(image.isFile()&&expiry>System.currentTimeMillis())bitmap=BitmapFactory.decodeFile(image.getPath());
            if(bitmap==null) {
                URL url=new URL(style==RoadMapStyle.SATELLITE_HD?gateway.tileUrl(zoom,x,y):style.tileUrl(zoom,x,y));
                HttpURLConnection c=(HttpURLConnection)url.openConnection();
                c.setConnectTimeout(8_000);c.setReadTimeout(8_000);c.setInstanceFollowRedirects(false);
                c.setRequestProperty("User-Agent","RouteMemory/0.7.0 (Android; kr.routememory; noncommercial competition prototype)");
                c.setRequestProperty("Accept","image/png,image/jpeg");
                if(image.isFile()){
                    String etag=values.getProperty("etag"),modified=values.getProperty("modified");
                    if(etag!=null)c.setRequestProperty("If-None-Match",etag);
                    if(modified!=null)c.setRequestProperty("If-Modified-Since",modified);
                }
                try {
                    int status=c.getResponseCode();
                    if(status==304&&image.isFile())bitmap=BitmapFactory.decodeFile(image.getPath());
                    else if(status==200){
                        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                        try(InputStream in=c.getInputStream()){
                            byte[] chunk=new byte[8192];int n;
                            while((n=in.read(chunk))!=-1){if(bytes.size()+n>1_048_576)throw new IOException("Tile size");bytes.write(chunk,0,n);}
                        }
                        byte[] data=bytes.toByteArray();BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
                        BitmapFactory.decodeByteArray(data,0,data.length,options);
                        if(options.outWidth!=256||options.outHeight!=256)throw new IOException("Tile dimensions");
                        bitmap=BitmapFactory.decodeByteArray(data,0,data.length);
                        if(bitmap!=null&&!noStore(c))try(OutputStream out=new FileOutputStream(image)){out.write(data);}
                    } else {
                        if(status==429||status==503)synchronized(this){
                            if(style!=RoadMapStyle.SATELLITE_HD||highResolutionGateway!=null&&gateway.origin.equals(highResolutionGateway.origin))providerRetryAfter.put(style,System.currentTimeMillis()+60_000);
                        }
                        throw new IOException("Tile unavailable");
                    }
                    if(bitmap!=null&&!noStore(c)){
                        values.setProperty("expires",Long.toString(expiration(c)));
                        if(c.getHeaderField("ETag")!=null)values.setProperty("etag",c.getHeaderField("ETag"));
                        if(c.getHeaderField("Last-Modified")!=null)values.setProperty("modified",c.getHeaderField("Last-Modified"));
                        try(OutputStream out=new FileOutputStream(meta)){values.store(out,"Basemap tile cache metadata");}
                    }else if(noStore(c)){image.delete();meta.delete();}
                } finally {c.disconnect();}
            }
        } catch(IOException|RuntimeException ignored) {
            // Connection failures stay on the map as missing tiles; never draw invented roads.
        } finally {
            if(abandoned){
                final List<Runnable> waiters;synchronized(this){waiters=pending.remove(key);}
                if(waiters!=null)main.post(()->{if(preferredStyle==style)for(Runnable waiter:waiters)waiter.run();});
            }
        }
        final Bitmap loaded=bitmap;
        main.post(()->{
            List<Runnable> callbacks;
            synchronized(this){
                if(loaded!=null){memory.put(key,loaded);failed.remove(key);}else failed.put(key,System.currentTimeMillis());
                callbacks=pending.remove(key);
                if(failed.size()>128)failed.clear();
            }
            if(callbacks!=null)for(Runnable callback:callbacks)callback.run();
        });
        trim();
    }
    private static long number(String text){try{return Long.parseLong(text);}catch(Exception ignored){return 0;}}
    private static boolean noStore(URLConnection c){String cache=c.getHeaderField("Cache-Control");return cache!=null&&cache.toLowerCase(Locale.ROOT).contains("no-store");}
    private static long expiration(URLConnection c){
        long now=System.currentTimeMillis();String cache=c.getHeaderField("Cache-Control");
        if(cache!=null){
            if(cache.contains("no-cache")||cache.contains("no-store"))return now;
            for(String part:cache.split(","))if(part.trim().startsWith("max-age=")){
                long age=Math.max(0,number(part.trim().substring(8).replace("\"",""))-number(c.getHeaderField("Age")));
                return now+Math.min(age,365L*86400)*1000;
            }
        }
        long expires=c.getExpiration();return expires>0?expires:now+7L*86400*1000;
    }
    private void trim(){
        File[] files=directory.listFiles((dir,name)->name.endsWith(".img"));
        if(files==null||files.length<=256)return;
        Arrays.sort(files,Comparator.comparingLong(File::lastModified));
        for(int i=0;i<files.length-192;i++){
            files[i].delete();new File(directory,files[i].getName().replace(".img",".properties")).delete();
        }
    }
}
