package kr.routememory.shared;

import java.io.IOException;
import java.util.List;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** External-data and address normalization checks; no request or actual device location. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=29)
public final class PlaceSearchClientTest {
    private JSONObject feature(String name,double lon,double lat,long id)throws Exception{
        return new JSONObject().put("type","Feature").put("geometry",new JSONObject().put("type","Point").put("coordinates",new JSONArray().put(lon).put(lat)))
            .put("properties",new JSONObject().put("name",name).put("countrycode","KR").put("state","서울특별시").put("city","서울특별시").put("district","종로구").put("street","사직로").put("housenumber","161").put("osm_type","W").put("osm_id",id).put("osm_key","tourism").put("osm_value","attraction"));
    }
    private String response(JSONObject... values)throws Exception{JSONArray features=new JSONArray();for(JSONObject value:values)features.put(value);return new JSONObject().put("type","FeatureCollection").put("features",features).toString();}
    @Test public void koreanResultUsesActualNameAddressAndCoordinates()throws Exception{
        PlaceSearchClient.Place place=PlaceSearchClient.parse(response(feature("경복궁",126.97668,37.57975,123))).get(0);
        assertEquals("경복궁",place.name);assertEquals("서울특별시 종로구 사직로 161",place.address);assertEquals("photon-W-123",place.id);
        assertEquals("관광지",place.category);assertEquals(37.57975,place.point().lat,.000001);assertEquals(126.97668,place.point().lon,.000001);
    }
    @Test public void queryTrimsSpacesAndNormalizesKoreanRoadNumbers()throws Exception{
        assertEquals("서울특별시 종로구 사직로 161",PlaceSearchClient.normalizeQuery(" 서울특별시  종로구 사직로161 "));
        assertEquals("북촌로5길 48",PlaceSearchClient.normalizeQuery("북촌로5길48"));
        assertEquals("강남대로 12-3",PlaceSearchClient.normalizeQuery("강남대로12-3"));
        assertEquals("서울역",PlaceSearchClient.normalizeQuery(" 서울역 "));
    }
    @Test public void invalidQueriesAreRejectedBeforeAnyRequest()throws Exception{
        for(String value:new String[]{null,"","가","검색\n어","a\u0000b","a".repeat(121)}){
            try{PlaceSearchClient.normalizeQuery(value);fail("Invalid query accepted");}catch(IOException expected){}
        }
    }
    @Test public void emptyResultsStayEmptyWithoutInventedFallback()throws Exception{assertTrue(PlaceSearchClient.parse("{\"features\":[]}").isEmpty());}
    @Test public void malformedResponsesFailSafely()throws Exception{
        for(String value:new String[]{null,"not-json","{}","{\"features\":{}}","{\"type\":\"Other\",\"features\":[]}"}){
            try{PlaceSearchClient.parse(value);fail("Invalid response accepted");}catch(IOException expected){assertFalse(expected.getMessage().contains("not-json"));}
        }
    }
    @Test public void invalidForeignAndNonPointResultsAreSkipped()throws Exception{
        JSONObject foreign=feature("외국",126.9,37.5,1);foreign.getJSONObject("properties").put("countrycode","JP");
        JSONObject outside=feature("범위 밖",0,0,2);
        JSONObject polygon=feature("도형",126.9,37.5,3);polygon.getJSONObject("geometry").put("type","Polygon");
        JSONObject strings=feature("문자 좌표",126.9,37.5,4);strings.getJSONObject("geometry").put("coordinates",new JSONArray().put("126.9").put("37.5"));
        assertTrue(PlaceSearchClient.parse(response(foreign,outside,polygon,strings)).isEmpty());
    }
    @Test public void duplicateIdsAndCoordinatesAreRemoved()throws Exception{
        List<PlaceSearchClient.Place> values=PlaceSearchClient.parse(response(feature("첫 장소",126.9,37.5,1),feature("같은 ID",126.91,37.51,1),feature("같은 좌표",126.9,37.5,2)));
        assertEquals(1,values.size());assertEquals("첫 장소",values.get(0).name);
    }
    @Test public void responseReturnsAtMostEightPlaces()throws Exception{
        JSONArray features=new JSONArray();for(int i=0;i<12;i++)features.put(feature("장소 "+i,126.9+i*.001,37.5,i+1));
        assertEquals(8,PlaceSearchClient.parse(new JSONObject().put("features",features).toString()).size());
    }
    @Test public void absentNameFallsBackOnlyToProviderAddress()throws Exception{
        JSONObject value=feature("",126.9,37.5,9);
        PlaceSearchClient.Place place=PlaceSearchClient.parse(response(value)).get(0);
        assertEquals("사직로 161",place.name);assertEquals("서울특별시 종로구 사직로 161",place.address);
    }
    @Test public void stringAndInvalidOsmIdsAreHandledWithoutUnsafeNumericCoercion()throws Exception{
        JSONObject good=feature("문자 ID",126.9,37.5,1);good.getJSONObject("properties").put("osm_id","123456");
        assertEquals("photon-W-123456",PlaceSearchClient.parse(response(good)).get(0).id);
        JSONObject bad=feature("잘못된 ID",126.9,37.5,1);bad.getJSONObject("properties").put("osm_id",1.5);
        assertEquals("photon-point-37.500000-126.900000",PlaceSearchClient.parse(response(bad)).get(0).id);
    }
    @Test public void resultStringsAreBoundedAndControlCharactersRemoved()throws Exception{
        JSONObject value=feature("이름\n"+"가".repeat(180),126.9,37.5,1);value.getJSONObject("properties").put("state","서".repeat(200));
        PlaceSearchClient.Place place=PlaceSearchClient.parse(response(value)).get(0);
        assertEquals(160,place.name.length());assertFalse(place.name.contains("\n"));assertTrue(place.address.length()<=240);
    }
    @Test public void stationAndCafeCategoriesAreLocalized()throws Exception{
        JSONObject station=feature("서울역",126.9,37.5,1);station.getJSONObject("properties").put("osm_key","railway").put("osm_value","station");
        JSONObject cafe=feature("카페",126.91,37.51,2);cafe.getJSONObject("properties").put("osm_key","amenity").put("osm_value","cafe");
        List<PlaceSearchClient.Place> places=PlaceSearchClient.parse(response(station,cafe));assertEquals("역",places.get(0).category);assertEquals("카페",places.get(1).category);
    }
    @Test public void resultsAreImmutable()throws Exception{
        List<PlaceSearchClient.Place> places=PlaceSearchClient.parse(response(feature("경복궁",126.9,37.5,1)));
        try{places.clear();fail("Mutable results");}catch(UnsupportedOperationException expected){}
    }
    @Test public void oversizedFeatureListsAreRejected()throws Exception{
        JSONArray features=new JSONArray();for(int i=0;i<101;i++)features.put(feature("장소",126.9,37.5,i+1));
        try{PlaceSearchClient.parse(new JSONObject().put("features",features).toString());fail("Oversized response accepted");}catch(IOException expected){}
    }
}
