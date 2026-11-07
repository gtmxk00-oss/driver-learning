package kr.routememory.shared;

import android.graphics.*;
import kr.routememory.core.Engine;

/** Offline schematic, deliberately labelled virtual; never presented as a road map. */
public final class RoutePainter {
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    public void draw(Canvas c,Rect area,DemoController state) {
        c.drawColor(Color.rgb(12,24,36));
        c.save(); c.clipRect(area);
        float w=area.width(),h=area.height(); c.translate(area.left,area.top);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1); p.setColor(Color.rgb(29,44,57));
        for(int x=0;x<w;x+=60)c.drawLine(x,0,x,h,p);
        for(int y=0;y<h;y+=60)c.drawLine(0,y,w,y,p);
        float cx=w*0.53f, top=h*0.28f, bottom=h*0.87f;
        p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        Path route=new Path(); route.moveTo(cx,bottom); route.lineTo(cx,h*.60f);
        boolean detour=state.trip!=null&&state.trip.activeRoute==Engine.RECOVERY;
        float turnX=detour?w*.23f:w*.79f;
        route.quadTo(cx,h*.47f,detour?w*.38f:w*.68f,h*.47f); route.lineTo(turnX,h*.47f); route.lineTo(turnX,top);
        p.setStrokeWidth(30); p.setColor(Color.rgb(44,64,78)); c.drawPath(route,p);
        p.setStrokeWidth(11); p.setColor(Color.rgb(188,242,124)); c.drawPath(route,p);
        p.setColor(Color.rgb(65,85,98)); p.setStrokeWidth(20);
        c.drawLine(cx,h*.60f,cx,top,p); c.drawLine(w*.67f,h*.47f,w*.89f,h*.70f,p);
        p.setStyle(Paint.Style.FILL); p.setColor(Color.rgb(188,242,124));
        float progress=state.trip==null?0:1f-(float)state.trip.metersLeft()/state.trip.activeRoute.meters();
        float carY=bottom-Math.min(1,progress)*h*.2f;
        c.drawCircle(cx,carY,19,p); p.setColor(Color.rgb(12,24,36));
        Path arrow=new Path(); arrow.moveTo(cx,carY-11); arrow.lineTo(cx-8,carY+9); arrow.lineTo(cx+8,carY+9); arrow.close(); c.drawPath(arrow,p);
        p.setColor(Color.WHITE); p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        p.setTextSize(Math.max(17,Math.min(24,w/28))); c.drawText("길기억  /  ROUTE MEMORY",24,40,p);
        p.setColor(Color.rgb(160,181,196)); p.setTextSize(Math.max(14,Math.min(19,w/35)));
        c.drawText("가상 경로 · GPS 미사용",24,h-20,p);
        if(state.trip!=null) {
            String tag=state.trip.status==Engine.Trip.Status.REROUTING?"대체 경로 연결 중":state.trip.segment().name;
            c.drawText(tag,24,72,p);
        }
        c.restore();
    }
}
