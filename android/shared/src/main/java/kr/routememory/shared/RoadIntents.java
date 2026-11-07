package kr.routememory.shared;

import android.content.Intent;
import android.net.Uri;
import java.util.regex.*;
import kr.routememory.core.RoadRoute;

/** Only explicit geo coordinates are accepted; address text needs a future geocoder. */
public final class RoadIntents {
    private RoadIntents(){}
    public static RoadRoute.Point destination(Intent intent){
        Uri uri=intent==null?null:intent.getData();if(uri==null||!"geo".equals(uri.getScheme()))return null;
        String part=uri.getSchemeSpecificPart();if(part==null)return null;
        String coords=part.split("\\?",2)[0];
        int query=part.indexOf('?');
        if(query>=0){String q=Uri.parse("https://coordinates.invalid/?"+part.substring(query+1)).getQueryParameter("q");if(q!=null)coords=q;}
        Matcher matcher=Pattern.compile("^\\s*([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*,\\s*([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))(?:\\s*\\(.*\\))?\\s*$").matcher(coords);
        if(!matcher.matches())return null;
        try{double lat=Double.parseDouble(matcher.group(1)),lon=Double.parseDouble(matcher.group(2));
            if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>85.05112878||Math.abs(lon)>180)return null;
            return new RoadRoute.Point(lat,lon);
        }catch(NumberFormatException ignored){return null;}
    }
}
