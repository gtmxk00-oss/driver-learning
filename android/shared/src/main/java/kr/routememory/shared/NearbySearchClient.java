package kr.routememory.shared;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import kr.routememory.core.RoadRoute;
import org.json.*;

/** Manual nearby lookup. Coordinates and results are kept only in a bounded memory cache. */
public class NearbySearchClient {
    private static final int MAX_BODY=512*1024;
    private static final long CACHE_MS=60000, PARKING_LIVE_MS=300000, FUEL_LIVE_MS=86400000;
    private static final Object LOCK=new Object();
    private static final LinkedHashMap<String,Cached> CACHE=new LinkedHashMap<String,Cached>(16,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,Cached> entry){return size()>16;}
    };
    public static final class PriceInfo {
        public final String status,fuelType,source,updatedAt,reason;
        public final Double priceWonPerLiter;
        public PriceInfo(String status,Double priceWonPerLiter,String fuelType,String source,String updatedAt,String reason){
            this.fuelType=clean(fuelType,40);this.source=clean(source,120);this.updatedAt=clean(updatedAt,60);
            boolean valid=("reported".equals(status)||"stale".equals(status))&&priceWonPerLiter!=null&&Double.isFinite(priceWonPerLiter)&&priceWonPerLiter>0&&priceWonPerLiter<=100000&&trustedTime(this.source,this.updatedAt);
            this.priceWonPerLiter=valid?priceWonPerLiter:null;
            this.status=valid?("stale".equals(status)||old(this.updatedAt,FUEL_LIVE_MS)?"stale":"reported"):"unavailable";
            this.reason=clean(reason,240);
        }
        public static PriceInfo unavailable(){return new PriceInfo("unavailable",null,"","","","실시간 유가 제공자 미연결");}
    }
    public static final class ParkingInfo {
        public final String status,source,updatedAt,reason;
        public final Integer capacity,available;
        public ParkingInfo(String status,Integer capacity,Integer available,String source,String updatedAt,String reason){
            this.source=clean(source,120);this.updatedAt=clean(updatedAt,60);
            boolean trusted=trustedTime(this.source,this.updatedAt);
            this.capacity=trusted&&capacity!=null&&capacity>=0?capacity:null;
            boolean valid=("reported".equals(status)||"stale".equals(status))&&trusted&&available!=null&&available>=0&&(this.capacity==null||available<=this.capacity);
            this.available=valid?available:null;
            this.status=valid?("stale".equals(status)||old(this.updatedAt,PARKING_LIVE_MS)?"stale":"reported"):"unavailable";
            this.reason=clean(reason,240);
        }
        public static ParkingInfo unavailable(){return new ParkingInfo("unavailable",null,null,"","","실시간 잔여 면수 제공자 미연결");}
    }
    public static final class NearbyPlace {
        public final String id,name,address,category;
        public final double lat,lon,distanceMeters;
        public final PriceInfo fuelPrice;
        public final ParkingInfo parking;
        public NearbyPlace(String id,String name,String address,String category,double lat,double lon,double distanceMeters,PriceInfo fuelPrice,ParkingInfo parking){
            if(!korea(lat,lon)||!Double.isFinite(distanceMeters)||distanceMeters<0)throw new IllegalArgumentException("국내 장소와 올바른 직선 거리가 필요합니다");
            this.id=clean(id,100);this.name=clean(name,160);this.address=clean(address,240);this.category=clean(category,40);
            if(this.name.isEmpty())throw new IllegalArgumentException("장소 이름이 필요합니다");
            this.lat=lat;this.lon=lon;this.distanceMeters=distanceMeters;
            this.fuelPrice=fuelPrice==null?PriceInfo.unavailable():fuelPrice;this.parking=parking==null?ParkingInfo.unavailable():parking;
        }
        public RoadRoute.Point point(){return new RoadRoute.Point(lat,lon);}
    }
    public static final class Result {
        public final String kind,provider,fetchedAt,notice;
        public final RoadRoute.Point center;
        public final int radiusMeters;
        public final List<NearbyPlace> places;
        public Result(String kind,String provider,RoadRoute.Point center,int radiusMeters,String fetchedAt,List<NearbyPlace> places,String notice){
            this.kind=kind;this.provider=clean(provider,120);this.center=center;this.radiusMeters=radiusMeters;this.fetchedAt=clean(fetchedAt,60);
            this.places=Collections.unmodifiableList(new ArrayList<>(places));this.notice=clean(notice,500);
        }
    }
    /** Worker only. Explicit lookup sends the chosen center to Photon; never called by typing or GPS updates. */
    public Result search(RoadRoute.Point center,String kind,int radiusMeters)throws IOException{
        validate(center,kind,radiusMeters);
        String key=String.format(Locale.US,"%s:%d:%.6f:%.6f",kind,radiusMeters,center.lat,center.lon);
        synchronized(LOCK){
            Cached cached=CACHE.get(key);long now=System.nanoTime();
            if(cached!=null&&now-cached.at<CACHE_MS*1000000L)return cached.result;
            if(cached!=null)CACHE.remove(key);
            PhotonRequestGate.await();HttpURLConnection connection=null;
            try{
                connection=(HttpURLConnection)new URL(requestUrl(center,kind,radiusMeters)).openConnection();
                connection.setConnectTimeout(8000);connection.setReadTimeout(10000);connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
                connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("User-Agent","RouteMemory/0.5 (Android; nearby prototype)");
                int status=connection.getResponseCode();if(status==429)throw new IOException("주변 검색 요청이 많습니다. 잠시 후 다시 조회해 주세요");
                if(status!=200)throw new IOException("주변 장소 서버에 연결하지 못했습니다. 다시 조회해 주세요");
                if(connection.getContentLengthLong()>MAX_BODY)throw invalid();
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();long deadline=System.nanoTime()+15000000000L;
                try(InputStream input=connection.getInputStream()){
                    byte[] chunk=new byte[8192];int count;
                    while((count=input.read(chunk))!=-1){
                        if(Thread.currentThread().isInterrupted())throw new IOException("주변 검색을 취소했습니다");
                        if(System.nanoTime()>deadline)throw new IOException("주변 검색 응답이 지연됩니다. 다시 조회해 주세요");
                        if(bytes.size()+count>MAX_BODY)throw invalid();bytes.write(chunk,0,count);
                    }
                }
                Result answer=parse(bytes.toString(StandardCharsets.UTF_8.name()),center,kind,radiusMeters);
                CACHE.put(key,new Cached(answer,System.nanoTime()));return answer;
            }catch(SocketTimeoutException e){throw new IOException("주변 검색 응답이 지연됩니다. 다시 조회해 주세요");}
            catch(UnknownHostException|javax.net.ssl.SSLException e){throw new IOException("인터넷 연결을 확인한 뒤 다시 조회해 주세요");}
            finally{if(connection!=null)connection.disconnect();}
        }
    }
    static String requestUrl(RoadRoute.Point center,String kind,int radiusMeters)throws IOException{
        validate(center,kind,radiusMeters);
        return String.format(Locale.US,"https://photon.komoot.io/reverse?lat=%.6f&lon=%.6f&radius=%d&limit=20&osm_tag=amenity:%s",center.lat,center.lon,radiusMeters/1000,"fuel".equals(kind)?"fuel":"parking");
    }
    static void validate(RoadRoute.Point center,String kind,int radiusMeters)throws IOException{
        if(center==null||!korea(center.lat,center.lon))throw new IOException("국내 목적지 또는 현재 위치를 선택해 주세요");
        if(!"fuel".equals(kind)&&!"parking".equals(kind))throw new IOException("주유소 또는 주차장을 선택해 주세요");
        if(radiusMeters!=1000&&radiusMeters!=3000)throw new IOException("검색 반경은 1km 또는 3km로 선택해 주세요");
    }
    static Result parse(String payload,RoadRoute.Point center,String kind,int radiusMeters)throws IOException{
        validate(center,kind,radiusMeters);if(payload==null||payload.length()>MAX_BODY)throw invalid();
        try{
            JSONObject root=new JSONObject(payload);if(!"FeatureCollection".equals(root.optString("type")))throw invalid();
            JSONArray features=root.getJSONArray("features");if(features.length()>100)throw invalid();
            List<NearbyPlace> places=new ArrayList<>();Set<String> ids=new HashSet<>(),points=new HashSet<>();
            for(int i=0;i<features.length();i++){
                JSONObject feature=features.optJSONObject(i);if(feature==null)continue;
                JSONObject p=feature.optJSONObject("properties"),g=feature.optJSONObject("geometry");if(p==null||g==null||!"Point".equals(g.optString("type")))continue;
                if(!"amenity".equals(p.optString("osm_key"))||!kind.equals(p.optString("osm_value")))continue;
                String country=string(p,"countrycode",8);if(!country.isEmpty()&&!country.equalsIgnoreCase("KR"))continue;
                JSONArray coordinates=g.optJSONArray("coordinates");if(coordinates==null||coordinates.length()<2||!(coordinates.opt(0) instanceof Number)||!(coordinates.opt(1) instanceof Number))continue;
                double lon=coordinates.getDouble(0),lat=coordinates.getDouble(1);if(!korea(lat,lon))continue;
                double distance=distanceMeters(center.lat,center.lon,lat,lon);if(distance>radiusMeters)continue;
                LinkedHashSet<String> parts=new LinkedHashSet<>();for(String field:new String[]{"state","city","county","district","locality"}){String part=string(p,field,100);if(!part.isEmpty())parts.add(part);}
                String road=(string(p,"street",120)+" "+string(p,"housenumber",30)).trim();if(!road.isEmpty())parts.add(road);
                String address=clean(String.join(" ",parts),240),name=string(p,"name",160);
                if(name.isEmpty())name=!road.isEmpty()?road+" "+("fuel".equals(kind)?"주유소":"주차장"):"이름 미등록 "+("fuel".equals(kind)?"주유소":"주차장");
                String type=string(p,"osm_type",3).toUpperCase(Locale.ROOT),id=string(p,"osm_id",30);Object numericId=p.opt("osm_id");
                if(numericId instanceof Number){double n=((Number)numericId).doubleValue();id=Double.isFinite(n)&&n>0&&n==Math.rint(n)&&n<=9007199254740991d?Long.toString(((Number)numericId).longValue()):"";}
                String point=String.format(Locale.US,"%.6f,%.6f",lat,lon);
                id=type.matches("[NWR]")&&id.matches("[1-9][0-9]{0,18}")?"photon-"+type+"-"+id:"photon-point-"+point;
                if(!ids.add(id)||!points.add(point))continue;
                // Photon is place data only. Even similarly named fields are not a live price or vacancy feed.
                places.add(new NearbyPlace(id,name,address,"fuel".equals(kind)?"주유소":"주차장",lat,lon,distance,PriceInfo.unavailable(),ParkingInfo.unavailable()));
            }
            places.sort(Comparator.comparingDouble(place->place.distanceMeters));if(places.size()>20)places=new ArrayList<>(places.subList(0,20));
            return new Result(kind,"Photon · OpenStreetMap",center,radiusMeters,Instant.now().toString(),places,"가까운 20곳까지 직선 거리순입니다. 공개 지도에 등록된 장소로 실제 진입 경로·영업 여부와 다를 수 있습니다. 실시간 유가·잔여 면수는 미연결입니다.");
        }catch(JSONException|IllegalArgumentException e){throw invalid();}
    }
    static double distanceMeters(double lat1,double lon1,double lat2,double lon2){double a=Math.sin(Math.toRadians(lat2-lat1)/2),b=Math.sin(Math.toRadians(lon2-lon1)/2);double h=a*a+Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*b*b;return 6371000*2*Math.atan2(Math.sqrt(Math.min(1,h)),Math.sqrt(Math.max(0,1-h)));}
    private static boolean trustedTime(String source,String time){if(source.isEmpty()||time.isEmpty())return false;try{return Instant.parse(time).toEpochMilli()<=System.currentTimeMillis()+60000;}catch(DateTimeParseException|ArithmeticException e){return false;}}
    private static boolean old(String time,long threshold){try{return System.currentTimeMillis()-Instant.parse(time).toEpochMilli()>threshold;}catch(DateTimeParseException|ArithmeticException e){return true;}}
    private static boolean korea(double lat,double lon){return Double.isFinite(lat)&&Double.isFinite(lon)&&lat>=32&&lat<=39.5&&lon>=124&&lon<=132;}
    private static String string(JSONObject object,String key,int max){Object value=object.opt(key);return value instanceof String?clean((String)value,max):"";}
    private static String clean(String value,int max){if(value==null)return "";String text=value.replaceAll("\\p{Cc}"," ").trim().replaceAll("\\s+"," ");return text.substring(0,Math.min(max,text.length()));}
    private static IOException invalid(){return new IOException("올바른 주변 장소 결과를 받지 못했습니다. 다시 조회해 주세요");}
    private static final class Cached{final Result result;final long at;Cached(Result result,long at){this.result=result;this.at=at;}}
}
