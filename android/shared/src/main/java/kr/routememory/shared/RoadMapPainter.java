package kr.routememory.shared;

import android.content.Context;
import android.graphics.*;
import java.util.*;
import kr.routememory.core.RoadRoute;
import kr.routememory.core.RoadTracker;

/** Native canvas map shared by the phone view and the vehicle's drawing surface. */
public final class RoadMapPainter {
    private final RoadTileStore tiles;
    private final Runnable repaint;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private RoadMapStyle mapStyle=RoadMapStyle.ROAD;
    private final float scale;
    private int zoom=14,zoomDelta,manualBaseZoom=14;
    private double centerX,centerY,world;
    private Rect area=new Rect();
    private boolean closed,manualCamera,follow=true;
    private double manualX,manualY;
    private int topOcclusion;
    private final RectF tileRect=new RectF();
    private final Rect tileCrop=new Rect();
    private final Path routePath=new Path();
    public RoadMapPainter(Context context,Runnable invalidate){
        tiles=RoadTileStore.get(context);scale=Math.max(1,Math.min(2,context.getResources().getDisplayMetrics().density));
        repaint=()->{if(!closed)invalidate.run();};
    }
    public void zoomIn(){zoomDelta=Math.min(4,zoomDelta+1);repaint.run();}
    public void zoomOut(){zoomDelta=Math.max(-5,zoomDelta-1);repaint.run();}
    public void fitRoute(){manualCamera=false;follow=false;zoomDelta=0;repaint.run();}
    public void followLocation(){manualCamera=false;follow=true;zoomDelta=0;repaint.run();}
    public void setTopOcclusion(int pixels){int value=Math.max(0,pixels);if(topOcclusion!=value){topOcclusion=value;repaint.run();}}
    public void setMapStyle(RoadMapStyle style){if(style!=null){tiles.useStyle(style);if(mapStyle!=style){mapStyle=style;repaint.run();}}}
    public RoadMapStyle getMapStyle(){return mapStyle;}
    private float focusY(){return area.exactCenterY()+Math.min(topOcclusion,Math.max(0,area.height()-80*scale))/2f;}
    public void panBy(float dx,float dy){
        if(world<=0)return;
        if(!manualCamera)manualBaseZoom=zoom-zoomDelta;
        manualX=((centerX-dx/world)%1+1)%1;manualY=Math.max(0,Math.min(1,centerY-dy/world));
        centerX=manualX;centerY=manualY;manualCamera=true;follow=false;repaint.run();
    }
    public void close(){closed=true;}
    private static double x(double lon){return (lon+180)/360;}
    private static double y(double lat){double s=Math.sin(Math.toRadians(Math.max(-85.05112878,Math.min(85.05112878,lat))));return 0.5-Math.log((1+s)/(1-s))/(4*Math.PI);}
    public RoadRoute.Point pointAt(float sx,float sy){
        if(area.isEmpty()||world==0)return null;
        double px=centerX+(sx-area.exactCenterX())/world,py=centerY+(sy-focusY())/world;
        double lon=((px*360+180)%360+360)%360-180,lat=Math.toDegrees(Math.atan(Math.sinh(Math.PI*(1-2*py))));
        return new RoadRoute.Point(Math.max(-85,Math.min(85,lat)),lon);
    }
    private float px(RoadRoute.Point p){double dx=x(p.lon)-centerX;if(dx>0.5)dx-=1;if(dx<-.5)dx+=1;return (float)(area.exactCenterX()+dx*world);}
    private float py(RoadRoute.Point p){return (float)(focusY()+(y(p.lat)-centerY)*world);}
    public void draw(Canvas c,Rect bounds,RoadRoute route,RoadTracker.Fix fix,RoadRoute.Point origin,RoadRoute.Point destination){
        if(closed||bounds.isEmpty())return;area.set(bounds);int save=c.save();c.clipRect(bounds);c.drawColor(Color.rgb(235,240,242));
        List<RoadRoute.Point> points=route!=null?route.geometry:Arrays.asList(origin,destination);
        double minX=1,maxX=0,minY=1,maxY=0;
        for(RoadRoute.Point p:points)if(p!=null){double xx=x(p.lon),yy=y(p.lat);minX=Math.min(minX,xx);maxX=Math.max(maxX,xx);minY=Math.min(minY,yy);maxY=Math.max(maxY,yy);}
        if(maxX<minX){minX=maxX=x(126.978);minY=maxY=y(37.5665);}
        centerX=(minX+maxX)/2;centerY=(minY+maxY)/2;
        double visibleHeight=bounds.height()-Math.min(topOcclusion,Math.max(0,bounds.height()-80*scale));
        double fit=Math.min(Math.max(1,bounds.width()-80*scale)/Math.max(0.00004,maxX-minX),Math.max(1,visibleHeight-70*scale)/Math.max(0.00004,maxY-minY));
        int base=(int)Math.floor(Math.log(fit/(256*scale))/Math.log(2));base=Math.max(3,Math.min(17,base));
        if(fix!=null&&follow){centerX=x(fix.lon);centerY=y(fix.lat);base=Math.max(base,15);}
        if(manualCamera){centerX=manualX;centerY=manualY;base=manualBaseZoom;}
        zoom=Math.max(3,Math.min(19,base+zoomDelta));world=(1<<zoom)*(double)Math.round(256*scale);
        double left=centerX*world-bounds.width()/2d,top=centerY*world-(focusY()-bounds.top);
        int size=Math.round(256*scale),firstX=(int)Math.floor(left/size),firstY=(int)Math.floor(top/size),missing=0;
        for(int tx=firstX;tx<=(int)Math.floor((left+bounds.width())/size);tx++)for(int ty=firstY;ty<=(int)Math.floor((top+bounds.height())/size);ty++){
            float sx=(float)(bounds.left+tx*(double)size-left),sy=(float)(bounds.top+ty*(double)size-top);
            int nativeZoom=Math.min(zoom,mapStyle.maxNativeZoom),overzoom=zoom-nativeZoom,factor=1<<overzoom;
            Bitmap tile=tiles.tile(mapStyle,nativeZoom,Math.floorDiv(tx,factor),Math.floorDiv(ty,factor),repaint);
            if(tile!=null){
                paint.setStyle(Paint.Style.FILL);paint.setAlpha(255);tileRect.set(sx,sy,sx+size+1,sy+size+1);
                int cropSize=256/factor,cropX=Math.floorMod(tx,factor)*cropSize,cropY=Math.floorMod(ty,factor)*cropSize;
                tileCrop.set(cropX,cropY,cropX+cropSize,cropY+cropSize);c.drawBitmap(tile,tileCrop,tileRect,paint);
            }
            else {missing++;paint.setColor(Color.rgb(205,216,219));paint.setStrokeWidth(1);c.drawLine(sx,sy,sx+size,sy,paint);c.drawLine(sx,sy,sx,sy+size,paint);}
        }
        if(route!=null&&route.geometry.size()>1){
            routePath.reset();boolean first=true;
            for(RoadRoute.Point p:route.geometry){if(first){routePath.moveTo(px(p),py(p));first=false;}else routePath.lineTo(px(p),py(p));}
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(9*scale);paint.setColor(Color.WHITE);c.drawPath(routePath,paint);
            paint.setStrokeWidth(6*scale);paint.setColor(Color.rgb(36,107,253));c.drawPath(routePath,paint);paint.setStyle(Paint.Style.FILL);
        }
        if(origin!=null)marker(c,origin,Color.rgb(38,111,168),"출발");
        if(destination!=null)marker(c,destination,Color.rgb(36,107,253),"도착");
        if(fix!=null){
            RoadRoute.Point point=new RoadRoute.Point(fix.lat,fix.lon);float cx=px(point),cy=py(point);
            double metersPerPixel=Math.cos(Math.toRadians(fix.lat))*40075016.686/world;
            float radius=(float)Math.min(Math.max(bounds.width(),bounds.height()),Math.max(0,fix.accuracy)/metersPerPixel);
            paint.setColor(Color.argb(45,31,119,245));c.drawCircle(cx,cy,radius,paint);
            paint.setColor(Color.WHITE);c.drawCircle(cx,cy,9*scale,paint);paint.setColor(Color.rgb(21,110,242));c.drawCircle(cx,cy,6*scale,paint);
        }
        paint.setStyle(Paint.Style.FILL);paint.setColor(Color.argb(237,255,255,255));
        String[] sources=mapStyle.attributionLines();
        int lines=sources.length+(mapStyle!=RoadMapStyle.ROAD?1:0)+(missing>0?1:0);
        float foot=(8+lines*12)*scale;c.drawRect(bounds.left,bounds.bottom-foot,bounds.right,bounds.bottom,paint);
        paint.setTypeface(Typeface.DEFAULT);paint.setColor(Color.rgb(74,89,110));paint.setTextSize(9*scale);
        int row=0;float baseline=bounds.bottom-foot+13*scale,available=Math.max(1,bounds.width()-16*scale);
        for(String source:sources){
            paint.setTextSize(9*scale);float measured=paint.measureText(source);if(measured>available)paint.setTextSize(9*scale*available/measured);
            c.drawText(source,bounds.left+8*scale,baseline+row++*12*scale,paint);
        }
        paint.setTextSize(9*scale);
        if(mapStyle!=RoadMapStyle.ROAD)c.drawText(mapStyle.imageNotice(),bounds.left+8*scale,baseline+row++*12*scale,paint);
        if(missing>0)c.drawText(mapStyle==RoadMapStyle.SATELLITE_HD?"고화질 영상 연결 확인 / 서버 키·인증 점검":mapStyle.label+" 불러오는 중 / 연결 확인",bounds.left+8*scale,baseline+row*12*scale,paint);
        c.restoreToCount(save);
    }
    private void marker(Canvas c,RoadRoute.Point p,int color,String label){
        float xx=px(p),yy=py(p);paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);c.drawCircle(xx,yy,8*scale,paint);
        paint.setColor(color);c.drawCircle(xx,yy,5*scale,paint);paint.setTextSize(11*scale);paint.setTypeface(Typeface.DEFAULT_BOLD);
        float width=paint.measureText(label)+12*scale;
        paint.setColor(Color.WHITE);c.drawRoundRect(xx-width/2,yy-30*scale,xx+width/2,yy-11*scale,4*scale,4*scale,paint);
        paint.setColor(Color.rgb(23,40,49));c.drawText(label,xx-width/2+6*scale,yy-16*scale,paint);paint.setTypeface(Typeface.DEFAULT);
    }
}
