package kr.routememory.shared;

/** Fixed basemap providers. Satellite imagery is an acquired image, never live parking data. */
public enum RoadMapStyle {
    ROAD("road-osm", "일반지도", 19),
    SATELLITE("satellite-eox2025", "위성지도", 14),
    SATELLITE_HD("satellite-vworld", "고화질 항공지도", 18);

    public final String cacheId,label;
    public final int maxNativeZoom;
    RoadMapStyle(String cacheId,String label,int maxNativeZoom){this.cacheId=cacheId;this.label=label;this.maxNativeZoom=maxNativeZoom;}
    public RoadMapStyle next(){return this==ROAD?SATELLITE:ROAD;}
    public String tileKey(int zoom,int x,int y){return cacheId+"_"+zoom+"_"+x+"_"+y;}
    public String tileUrl(int zoom,int x,int y){
        if(zoom<0||zoom>maxNativeZoom)throw new IllegalArgumentException("Basemap source zoom");
        int count=1<<zoom;if(y<0||y>=count)throw new IllegalArgumentException("Basemap tile row");x=Math.floorMod(x,count);
        if(this==ROAD)return "https://tile.openstreetmap.org/"+zoom+"/"+x+"/"+y+".png";
        if(this==SATELLITE_HD)throw new IllegalStateException("A checked VWorld gateway is required");
        return "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2025_3857/default/GoogleMapsCompatible/"+zoom+"/"+y+"/"+x+".jpg";
    }
    public String[] attributionLines(){
        return this==ROAD?new String[]{"© OpenStreetMap contributors · openstreetmap.org/copyright"}:this==SATELLITE_HD?new String[]{"공간정보 오픈플랫폼(브이월드)","https://www.vworld.kr", "지역별 영상 시점·해상도 차이 있음"}:new String[]{
            "EOxCloudless https://cloudless.eox.at",
            "by EOX IT Services GmbH · CC BY-NC-SA 4.0",
            "(Contains modified Copernicus Sentinel data 2025)"};
    }
    public String imageNotice(){return this==ROAD?"":this==SATELLITE_HD?"항공영상 / 실시간 영상 아님":"2025년 영상 / 원본 10m / 실시간 아님";}
}
