package kr.routememory.shared;

import java.io.IOException;
import java.time.Instant;
import kr.routememory.core.RoadRoute;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** All responses below are local fixtures; no location or network access is involved. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=29)
public final class NearbySearchClientTest {
    private final RoadRoute.Point center=new RoadRoute.Point(37.5665,126.978);
    private JSONObject feature(String id,String kind,double lat,double lon)throws Exception{
        JSONObject p=new JSONObject().put("osm_type","N").put("osm_id",Long.parseLong(id)).put("osm_key","amenity").put("osm_value",kind).put("countrycode","KR").put("name","테스트 "+id).put("city","서울특별시").put("district","중구").put("street","세종대로").put("housenumber","110");
        return new JSONObject().put("type","Feature").put("properties",p).put("geometry",new JSONObject().put("type","Point").put("coordinates",new JSONArray().put(lon).put(lat)));
    }
    private String payload(JSONObject... features)throws Exception{JSONArray a=new JSONArray();for(JSONObject f:features)a.put(f);return new JSONObject().put("type","FeatureCollection").put("features",a).toString();}
    @Test public void queryUsesFixedHttpsProviderChosenCenterAndBoundedRadius()throws Exception{
        String url=NearbySearchClient.requestUrl(center,"fuel",3000);assertEquals("https://photon.komoot.io/reverse?lat=37.566500&lon=126.978000&radius=3&limit=20&osm_tag=amenity:fuel",url);
        assertTrue(NearbySearchClient.requestUrl(center,"parking",1000).endsWith("radius=1&limit=20&osm_tag=amenity:parking"));
    }
    private void invalid(RoadRoute.Point point,String kind,int radius){try{NearbySearchClient.validate(point,kind,radius);fail("accepted invalid lookup");}catch(IOException expected){}}
    @Test public void rejectsInvalidCentersCategoriesAndRadiiBeforeNetwork(){invalid(null,"fuel",1000);invalid(new RoadRoute.Point(0,0),"fuel",1000);invalid(center,"restaurant",1000);invalid(center,"fuel",0);invalid(center,"fuel",2000);try{new RoadRoute.Point(Double.NaN,127);fail("accepted NaN point");}catch(IllegalArgumentException expected){}}
    @Test public void filtersWrongCategoryCountryRangeAndSortsByActualDistance()throws Exception{
        JSONObject foreign=feature("5","fuel",37.567,126.978);foreign.getJSONObject("properties").put("countrycode","JP");
        NearbySearchClient.Result result=NearbySearchClient.parse(payload(feature("1","fuel",37.571,126.978),feature("2","fuel",37.567,126.978),feature("3","parking",37.567,126.978),feature("4","fuel",37.6,126.978),foreign),center,"fuel",1000);
        assertEquals(2,result.places.size());assertEquals("테스트 2",result.places.get(0).name);assertEquals("서울특별시 중구 세종대로 110",result.places.get(0).address);
        assertEquals(55.6,result.places.get(0).distanceMeters,.5);assertEquals(1000,result.radiusMeters);assertEquals("fuel",result.kind);
        assertEquals(37.567,result.places.get(0).point().lat,.000001);
    }
    @Test public void photonFieldsNeverBecomeLivePriceOrAvailability()throws Exception{
        JSONObject f=feature("1","parking",37.567,126.978);f.getJSONObject("properties").put("capacity",100).put("available",0).put("priceWonPerLiter",1500).put("updatedAt",Instant.now().toString()).put("source","untrusted");
        NearbySearchClient.NearbyPlace place=NearbySearchClient.parse(payload(f),center,"parking",3000).places.get(0);
        assertEquals("unavailable",place.parking.status);assertNull(place.parking.available);assertNull(place.parking.capacity);assertNull(place.fuelPrice.priceWonPerLiter);
    }
    @Test public void deduplicatesIdsAndPositionsWithoutInventingAnEmptyResult()throws Exception{
        JSONObject a=feature("1","fuel",37.567,126.978),b=feature("1","fuel",37.568,126.978),c=feature("2","fuel",37.567,126.978);
        assertEquals(1,NearbySearchClient.parse(payload(a,b,c),center,"fuel",3000).places.size());
        assertTrue(NearbySearchClient.parse(payload(),center,"fuel",3000).places.isEmpty());
    }
    @Test public void requiresLiveProvenanceAndValidTimestamp(){
        String now=Instant.now().toString();assertNull(new NearbySearchClient.PriceInfo("reported",1700d,"휘발유","",now,"").priceWonPerLiter);
        assertNull(new NearbySearchClient.PriceInfo("reported",1700d,"휘발유","오피넷","broken","").priceWonPerLiter);
        assertNull(new NearbySearchClient.ParkingInfo("reported",100,20,"서울시","","").available);
        assertNull(new NearbySearchClient.ParkingInfo("reported",100,101,"서울시",now,"").available);
        assertNull(new NearbySearchClient.PriceInfo("reported",Double.NaN,"휘발유","오피넷",now,"").priceWonPerLiter);
    }
    @Test public void zeroParkingAvailabilityMeansReportedFullAndUnknownRemainsNull(){
        NearbySearchClient.ParkingInfo full=new NearbySearchClient.ParkingInfo("reported",100,0,"서울시",Instant.now().toString(),"");
        assertEquals("reported",full.status);assertEquals(Integer.valueOf(0),full.available);assertEquals(Integer.valueOf(100),full.capacity);
        assertNull(NearbySearchClient.ParkingInfo.unavailable().available);assertEquals("unavailable",NearbySearchClient.ParkingInfo.unavailable().status);
    }
    @Test public void fuelAndParkingHaveDifferentStalenessWindows(){
        String hourAgo=Instant.now().minusSeconds(3600).toString(),twoDaysAgo=Instant.now().minusSeconds(172800).toString();
        assertEquals("reported",new NearbySearchClient.PriceInfo("reported",1700d,"휘발유","오피넷",hourAgo,"").status);
        assertEquals("stale",new NearbySearchClient.PriceInfo("reported",1700d,"휘발유","오피넷",twoDaysAgo,"").status);
        assertEquals("stale",new NearbySearchClient.ParkingInfo("reported",100,20,"서울시",hourAgo,"").status);
        assertEquals("unavailable",new NearbySearchClient.ParkingInfo("reported",100,20,"서울시",Instant.now().plusSeconds(3600).toString(),"").status);
    }
    @Test public void rejectsMalformedAndOversizedPayloads()throws Exception{
        for(String payload:new String[]{"{}","[]","{\"type\":\"Feature\",\"features\":[]}"," ".repeat(512*1024+1)}){try{NearbySearchClient.parse(payload,center,"fuel",3000);fail("accepted bad response");}catch(IOException expected){}}
    }
}
