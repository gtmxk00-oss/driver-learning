package kr.routememory.shared;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import kr.routememory.core.RoadRoute;
import org.json.*;

/** Explicit-submit Korean place search. No GPS bias, query logs, or persistent search history. */
public class PlaceSearchClient {
    private static final Object LOCK=new Object();
    private static final int MAX_BODY=512*1024;
    private static final long CACHE_MS=300000;
    private static final LinkedHashMap<String,Cached> CACHE=new LinkedHashMap<String,Cached>(32,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,Cached> entry){return size()>30;}
    };
    public static final class Place {
        public final String id,name,address,category;
        public final double lat,lon;
        public Place(String id,String name,String address,String category,double lat,double lon){
            if(!Double.isFinite(lat)||!Double.isFinite(lon)||lat<32||lat>39.5||lon<124||lon>132)throw new IllegalArgumentException("국내 검색 결과 좌표가 필요합니다");
            this.id=clean(id,100);this.name=clean(name,160);this.address=clean(address,240);this.category=clean(category,40);
            if(this.name.isEmpty())throw new IllegalArgumentException("장소 이름이 필요합니다");
            this.lat=lat;this.lon=lon;
        }
        public RoadRoute.Point point(){return new RoadRoute.Point(lat,lon);}
    }
    public static String normalizeQuery(String value)throws IOException{
        if(value==null||value.matches("(?s).*\\p{Cc}.*"))throw new IOException("주소나 장소 이름을 2~120자로 입력해 주세요");
        String query=Normalizer.normalize(value,Normalizer.Form.NFC).trim().replaceAll("\\s+"," ");
        query=query.replaceAll("([가-힣A-Za-z0-9·]+(?:대로|로|길))\\s*(\\d+(?:-\\d+)?)(?=\\s|$)","$1 $2");
        if(query.length()<2||query.length()>120)throw new IOException("주소나 장소 이름을 2~120자로 입력해 주세요");
        return query;
    }
    /** Must run on a worker. All app instances share a one-request-per-second limiter and memory cache. */
    public List<Place> search(String value)throws IOException{
        String query=normalizeQuery(value);
        synchronized(LOCK){
            Cached cached=CACHE.get(query);
            long now=System.nanoTime();
            if(cached!=null&&now-cached.at<CACHE_MS*1000000L)return cached.places;
            if(cached!=null)CACHE.remove(query);
            PhotonRequestGate.await();
            HttpURLConnection connection=null;
            try{
                URL url=new URL("https://photon.komoot.io/api?q="+URLEncoder.encode(query,StandardCharsets.UTF_8.name())+"&limit=8&countrycode=KR");
                connection=(HttpURLConnection)url.openConnection();
                connection.setConnectTimeout(8000);connection.setReadTimeout(10000);connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
                connection.setRequestProperty("User-Agent","RouteMemory/0.4 (Android; kr.routememory; place search prototype)");
                connection.setRequestProperty("Accept","application/json");
                int status=connection.getResponseCode();
                if(status==429)throw new IOException("검색 요청이 많습니다. 잠시 후 다시 검색해 주세요");
                if(status!=200)throw new IOException("장소 검색 서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요");
                if(connection.getContentLengthLong()>MAX_BODY)throw new IOException("검색 응답이 너무 큽니다. 더 구체적인 이름을 입력해 주세요");
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();long deadline=System.nanoTime()+15000000000L;
                try(InputStream input=connection.getInputStream()){
                    byte[] chunk=new byte[8192];int count;
                    while((count=input.read(chunk))!=-1){
                        if(Thread.currentThread().isInterrupted())throw new IOException("검색을 취소했습니다");
                        if(System.nanoTime()>deadline)throw new IOException("검색 응답이 지연됩니다. 다시 검색해 주세요");
                        if(bytes.size()+count>MAX_BODY)throw new IOException("검색 응답이 너무 큽니다. 더 구체적인 이름을 입력해 주세요");
                        bytes.write(chunk,0,count);
                    }
                }
                List<Place> answer=parse(bytes.toString(StandardCharsets.UTF_8.name()));
                CACHE.put(query,new Cached(answer,System.nanoTime()));return answer;
            }catch(java.net.SocketTimeoutException e){throw new IOException("검색 응답이 지연됩니다. 다시 검색해 주세요");}
            catch(java.net.UnknownHostException|javax.net.ssl.SSLException e){throw new IOException("인터넷 연결을 확인한 뒤 다시 검색해 주세요");}
            finally{if(connection!=null)connection.disconnect();}
        }
    }
    static List<Place> parse(String payload)throws IOException{
        if(payload==null||payload.length()>MAX_BODY)throw invalid();
        try{
            JSONObject root=new JSONObject(payload);if(root.has("type")&&!"FeatureCollection".equals(root.optString("type")))throw invalid();JSONArray features=root.getJSONArray("features");
            if(features.length()>100)throw invalid();
            List<Place> answer=new ArrayList<>();Set<String> ids=new HashSet<>(),points=new HashSet<>();
            for(int i=0;i<features.length()&&answer.size()<8;i++){
                Object item=features.opt(i);if(!(item instanceof JSONObject))continue;
                JSONObject feature=(JSONObject)item,geometry=feature.optJSONObject("geometry"),properties=feature.optJSONObject("properties");
                if(geometry==null||properties==null||!"Point".equals(geometry.optString("type")))continue;
                JSONArray coords=geometry.optJSONArray("coordinates");
                if(coords==null||coords.length()<2||!(coords.opt(0) instanceof Number)||!(coords.opt(1) instanceof Number))continue;
                double lon=coords.getDouble(0),lat=coords.getDouble(1);
                if(!Double.isFinite(lat)||!Double.isFinite(lon)||lat<32||lat>39.5||lon<124||lon>132)continue;
                String country=string(properties,"countrycode",8);
                if(!country.isEmpty()&&!country.equalsIgnoreCase("KR"))continue;
                LinkedHashSet<String> parts=new LinkedHashSet<>();
                for(String field:new String[]{"state","city","county","district","locality"}){String text=string(properties,field,100);if(!text.isEmpty())parts.add(text);}
                String street=string(properties,"street",120),number=string(properties,"housenumber",30);
                String road=(street+" "+number).trim();if(!road.isEmpty())parts.add(road);
                String address=clean(String.join(" ",parts),240),name=string(properties,"name",160);
                if(name.isEmpty())name=!road.isEmpty()?road:address;
                if(name.isEmpty())continue;
                String type=string(properties,"osm_type",3).toUpperCase(Locale.ROOT),osmId=string(properties,"osm_id",30);
                Object idValue=properties.opt("osm_id");if(idValue instanceof Number){double numeric=((Number)idValue).doubleValue();osmId=Double.isFinite(numeric)&&numeric>0&&numeric==Math.rint(numeric)&&numeric<=9007199254740991d?Long.toString(((Number)idValue).longValue()):"";}
                String point=String.format(Locale.US,"%.6f,%.6f",lat,lon);
                String id=type.matches("[NWR]")&&osmId.matches("[1-9][0-9]{0,18}")?"photon-"+type+"-"+osmId:"photon-point-"+String.format(Locale.US,"%.6f-%.6f",lat,lon);
                if(!ids.add(id)||!points.add(point))continue;
                answer.add(new Place(id,name,address,category(properties),lat,lon));
            }
            return Collections.unmodifiableList(answer);
        }catch(JSONException|IllegalArgumentException e){throw invalid();}
    }
    private static String category(JSONObject properties){
        String key=string(properties,"osm_key",60),value=string(properties,"osm_value",60);
        if(key.equals("railway")&&Arrays.asList("station","stop","halt","tram_stop").contains(value))return "역";
        if(key.equals("tourism"))return "관광지";
        if(key.equals("amenity")&&Arrays.asList("restaurant","fast_food","food_court").contains(value))return "음식점";
        if(key.equals("amenity")&&value.equals("cafe"))return "카페";
        if(key.equals("amenity")&&Arrays.asList("hospital","clinic").contains(value))return "의료시설";
        if(key.equals("amenity")&&Arrays.asList("school","college","university").contains(value))return "학교";
        if(key.equals("amenity")&&value.equals("parking"))return "주차장";
        if(key.equals("shop"))return "상점";
        if(key.equals("leisure"))return "시설";
        if(key.equals("building"))return "건물";
        if(key.equals("place"))return "지역";
        return "장소";
    }
    private static String string(JSONObject object,String key,int max){Object value=object.opt(key);return value instanceof String?clean((String)value,max):"";}
    private static String clean(String value,int max){if(value==null)return "";String text=value.replaceAll("\\p{Cc}"," ").trim().replaceAll("\\s+"," ");return text.substring(0,Math.min(max,text.length()));}
    private static IOException invalid(){return new IOException("올바른 장소 검색 결과를 받지 못했습니다. 다시 검색해 주세요");}
    private static final class Cached{final List<Place> places;final long at;Cached(List<Place> places,long at){this.places=places;this.at=at;}}
}
