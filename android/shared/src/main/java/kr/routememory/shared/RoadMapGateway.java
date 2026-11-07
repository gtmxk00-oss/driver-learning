package kr.routememory.shared;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import org.json.*;

/** A manually checked gateway origin. The VWorld API key never enters the app. */
public final class RoadMapGateway {
    public final String origin,cacheId;
    public final boolean configured;
    private RoadMapGateway(String origin,boolean configured){
        this.origin=origin;this.configured=configured;cacheId="vworld-"+fingerprint(origin);
    }
    public static String normalizeOrigin(String input,boolean allowDebugLoopback)throws IOException{
        if(input==null||input.length()>240||input.matches("(?s).*[\\p{Cc}\\s].*"))throw invalidOrigin();
        try{
            URI uri=new URI(input.trim());String scheme=uri.getScheme(),host=uri.getHost(),path=uri.getRawPath();
            if(host==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||path!=null&&!path.isEmpty()&&!path.equals("/"))throw invalidOrigin();
            boolean loopback="127.0.0.1".equals(host)||"localhost".equalsIgnoreCase(host);
            if(!"https".equalsIgnoreCase(scheme)&&!(allowDebugLoopback&&"http".equalsIgnoreCase(scheme)&&loopback&&uri.getPort()==4173))throw invalidOrigin();
            if(uri.getPort()==0||uri.getPort()>65535)throw invalidOrigin();
            return new URI(scheme.toLowerCase(Locale.ROOT),null,host.toLowerCase(Locale.ROOT),uri.getPort(),null,null,null).toASCIIString();
        }catch(URISyntaxException|IllegalArgumentException e){throw invalidOrigin();}
    }
    public static RoadMapGateway check(String input,boolean allowDebugLoopback)throws IOException{
        String origin=normalizeOrigin(input,allowDebugLoopback);HttpURLConnection connection=null;
        try{
            connection=(HttpURLConnection)new URL(origin+"/api/maps/status").openConnection();connection.setConnectTimeout(8000);connection.setReadTimeout(10000);connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
            connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("User-Agent","RouteMemory/0.7 (Android; manual map gateway check)");
            if(connection.getResponseCode()!=200)throw new IOException("지도 서버에 연결하지 못했습니다. 서버 주소와 실행 상태를 확인하세요");
            if(connection.getContentLengthLong()>16384)throw invalidStatus();ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            long deadline=System.nanoTime()+15000000000L;
            try(InputStream stream=connection.getInputStream()){
                byte[] chunk=new byte[2048];int count;while((count=stream.read(chunk))!=-1){if(Thread.currentThread().isInterrupted())throw new IOException("연결 확인을 취소했습니다");if(System.nanoTime()>deadline)throw new IOException("지도 서버 응답이 늦습니다. 다시 확인하세요");if(bytes.size()+count>16384)throw invalidStatus();bytes.write(chunk,0,count);}
            }
            return parse(origin,bytes.toString(StandardCharsets.UTF_8.name()),allowDebugLoopback);
        }catch(SocketTimeoutException e){throw new IOException("지도 서버 응답이 늦습니다. 다시 확인하세요");}
        catch(UnknownHostException|javax.net.ssl.SSLException e){throw new IOException("지도 서버 주소와 HTTPS 인증서를 확인하세요");}
        finally{if(connection!=null)connection.disconnect();}
    }
    static RoadMapGateway parse(String input,String body,boolean allowDebugLoopback)throws IOException{
        String origin=normalizeOrigin(input,allowDebugLoopback);
        if(body==null||body.length()>16384)throw invalidStatus();
        try{
            JSONObject json=new JSONObject(body);
            if(!"vworld".equals(json.optString("provider"))||!(json.opt("configured") instanceof Boolean))throw invalidStatus();
            int max=json.optInt("maxNativeZoom",18);if(max!=18)throw invalidStatus();
            return new RoadMapGateway(origin,json.getBoolean("configured"));
        }catch(JSONException e){throw invalidStatus();}
    }
    public String tileUrl(int zoom,int x,int y){
        int n=1<<zoom;if(!configured||zoom<0||zoom>18||x<0||x>=n||y<0||y>=n)throw new IllegalArgumentException("Gateway tile coordinates");
        return origin+"/api/maps/vworld/"+zoom+"/"+x+"/"+y;
    }
    private static String fingerprint(String origin){
        try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(origin.getBytes(StandardCharsets.UTF_8));StringBuilder value=new StringBuilder();for(int i=0;i<12;i++)value.append(String.format(Locale.ROOT,"%02x",digest[i]&255));return value.toString();}catch(Exception e){throw new IllegalStateException("SHA-256 unavailable");}
    }
    private static IOException invalidOrigin(){return new IOException("경로·키가 없는 HTTPS 서버 주소를 입력하세요. USB 개발 연결은 http://127.0.0.1:4173 입니다");}
    private static IOException invalidStatus(){return new IOException("길기억 지도 서버의 올바른 연결 상태를 받지 못했습니다");}
}
