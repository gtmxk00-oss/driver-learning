package kr.routememory.shared;

import java.io.IOException;
import java.util.Arrays;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Gateway fixtures contain no user keys and make no network request. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=29)
public final class RoadMapGatewayTest {
    private static String status(boolean configured){return "{\"provider\":\"vworld\",\"configured\":"+configured+",\"authentication\":\"unverified\",\"maxNativeZoom\":18}";}
    private RoadMapGateway gateway(String origin,boolean configured)throws IOException{return RoadMapGateway.parse(origin,status(configured),true);}
    @Test public void bareHttpsOriginsAreCanonicalAndPathsCredentialsQueriesAreRejected()throws Exception{
        assertEquals("https://maps.example.org",RoadMapGateway.normalizeOrigin("https://MAPS.example.org/",false));
        assertEquals("https://maps.example.org:8443",RoadMapGateway.normalizeOrigin("https://maps.example.org:8443",false));
        for(String value:Arrays.asList("", "https://user:secret@maps.example.org", "https://maps.example.org/api", "https://maps.example.org/?key=secret", "https://maps.example.org/#key", "ftp://maps.example.org", "https://maps.example.org/a/../", "https://maps.example.org:0", "https://maps.example.org\n"))try{RoadMapGateway.normalizeOrigin(value,false);fail(value);}catch(IOException expected){}
    }
    @Test public void cleartextIsLimitedToExactDebugUsbLoopback()throws Exception{
        assertEquals("http://127.0.0.1:4173",RoadMapGateway.normalizeOrigin("http://127.0.0.1:4173",true));
        assertEquals("http://localhost:4173",RoadMapGateway.normalizeOrigin("http://localhost:4173",true));
        for(String value:Arrays.asList("http://maps.example.org:4173", "http://127.0.0.1:8000", "http://192.168.1.2:4173"))try{RoadMapGateway.normalizeOrigin(value,true);fail(value);}catch(IOException expected){}
        try{RoadMapGateway.normalizeOrigin("http://127.0.0.1:4173",false);fail("release HTTP must be rejected");}catch(IOException expected){}
    }
    @Test public void statusRequiresKnownProviderBooleanConfigurationAndConservativeZoom()throws Exception{
        assertFalse(gateway("https://maps.example.org",false).configured);assertTrue(gateway("https://maps.example.org",true).configured);
        for(String body:Arrays.asList("{}", "{\"provider\":\"other\",\"configured\":true}", "{\"provider\":\"vworld\",\"configured\":\"true\"}",status(true).replace("18","20"),"not JSON"))try{RoadMapGateway.parse("https://maps.example.org",body,false);fail(body);}catch(IOException expected){}
    }
    @Test public void tilePathIgnoresServerSuppliedUrlsAndCachesAreOriginSpecific()throws Exception{
        RoadMapGateway a=RoadMapGateway.parse("https://a.example.org",status(true).replace("}",",\"tileUrl\":\"https://elsewhere.invalid/key\"}"),false),b=gateway("https://b.example.org",true);
        assertEquals("https://a.example.org/api/maps/vworld/18/223520/101504",a.tileUrl(18,223520,101504));assertNotEquals(a.cacheId,b.cacheId);
        assertEquals(a.cacheId,gateway("https://a.example.org/",true).cacheId);
        for(int[] tile:new int[][]{{19,1,1},{18,-1,1},{18,262144,1},{18,1,-1}})try{a.tileUrl(tile[0],tile[1],tile[2]);fail();}catch(IllegalArgumentException expected){}
        try{gateway("https://a.example.org",false).tileUrl(14,13970,6344);fail("missing server key cannot request tiles");}catch(IllegalArgumentException expected){}
    }
    @Test public void hdSelectionNeedsCheckedGatewayAndDisconnectPreservesRouteGpsAndDestination()throws Exception{
        RoadController state=RoadController.get(RuntimeEnvironment.getApplication());state.useSeoulSample();state.disconnectHighResolution();state.setMapStyle(RoadMapStyle.ROAD);
        kr.routememory.core.RoadRoute.Point destination=state.destination;state.setMapStyle(RoadMapStyle.SATELLITE_HD);assertEquals(RoadMapStyle.ROAD,state.mapStyle);
        state.setHighResolutionGateway(gateway("https://maps.example.org",false));assertFalse(state.highResolutionReady());
        state.setHighResolutionGateway(gateway("https://maps.example.org",true));state.setMapStyle(RoadMapStyle.SATELLITE_HD);assertTrue(state.highResolutionReady());assertEquals(RoadMapStyle.SATELLITE_HD,state.mapStyle);
        state.disconnectHighResolution();assertEquals(RoadMapStyle.ROAD,state.mapStyle);assertSame(destination,state.destination);assertFalse(state.gpsEnabled);assertFalse(state.guiding);assertFalse(state.loading);
    }
}
