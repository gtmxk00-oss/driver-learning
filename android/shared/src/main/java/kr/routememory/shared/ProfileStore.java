package kr.routememory.shared;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.util.*;
import kr.routememory.core.Engine;

/** Private on-device storage; backups disabled, no location or cloud transmission. */
public final class ProfileStore {
    private final SharedPreferences prefs;
    public ProfileStore(Context context) { prefs=context.getSharedPreferences("route_memory_v1",Context.MODE_PRIVATE); }
    public Engine.Profile load(String id) {
        Engine.Profile p=new Engine.Profile(id,id.equals("me")?"나의 운전":"새 운전자");
        try {
            JSONObject data=new JSONObject(prefs.getString(id,"{}"));
            JSONObject misses=data.optJSONObject("misses");
            if(misses!=null)for(Iterator<String> it=misses.keys();it.hasNext();) {
                String k=it.next(); p.confirmedMisses.put(k,Math.max(0,Math.min(100,misses.optInt(k))));
            }
            JSONArray avoid=data.optJSONArray("avoid");
            if(avoid!=null)for(int i=0;i<avoid.length();i++)p.avoid.add(avoid.getString(i));
            JSONObject notes=data.optJSONObject("notes");
            if(notes!=null)for(Iterator<String> it=notes.keys();it.hasNext();) {
                String k=it.next(); p.notes.put(k,notes.getString(k));
            }
            p.completedTrips=Math.max(0,data.optInt("trips"));
        } catch(JSONException ignored) { /* Malformed storage opens as a fresh profile. */ }
        return p;
    }
    public void save(Engine.Profile p) {
        try {
            JSONObject data=new JSONObject();
            data.put("misses",new JSONObject(p.confirmedMisses));
            data.put("avoid",new JSONArray(p.avoid));
            data.put("notes",new JSONObject(p.notes)); data.put("trips",p.completedTrips);
            prefs.edit().putString(p.id,data.toString()).apply();
        } catch(JSONException e) { throw new IllegalStateException(e); }
    }
    public String active() { return prefs.getString("active","me"); }
    public void setActive(String id) { prefs.edit().putString("active",id).apply(); }
}
