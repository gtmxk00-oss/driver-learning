package kr.routememory.shared;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.function.Consumer;
import kr.routememory.core.RoadRoute;
import kr.routememory.core.RoadTracker;

/** Tap map to choose a destination while preparing the route on the phone. */
public final class RoadMapView extends View {
    private final RoadMapPainter painter;
    private RoadRoute route;
    private RoadTracker.Fix fix;
    private RoadRoute.Point origin,destination;
    private Consumer<RoadRoute.Point> onDestination;
    private float downX,downY,lastX,lastY;
    private boolean dragged;
    private final Rect bounds=new Rect();
    private ScaleGestureDetector scaleDetector;
    public RoadMapView(Context context){
        super(context);painter=new RoadMapPainter(context,this::invalidate);setContentDescription("실제 OpenStreetMap 지도. 드래그로 이동, 지도 터치로 목적지 선택");setClickable(true);
        scaleDetector=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            private float accumulated=1;
            @Override public boolean onScaleBegin(ScaleGestureDetector detector){accumulated=1;dragged=true;return true;}
            @Override public boolean onScale(ScaleGestureDetector detector){accumulated*=detector.getScaleFactor();if(accumulated>1.3f){painter.zoomIn();accumulated=1;}else if(accumulated<.77f){painter.zoomOut();accumulated=1;}return true;}
        });
    }
    public void setData(RoadRoute route,RoadTracker.Fix fix,RoadRoute.Point origin,RoadRoute.Point destination){this.route=route;this.fix=fix;this.origin=origin;this.destination=destination;invalidate();}
    public void setOnDestinationSelected(Consumer<RoadRoute.Point> listener){onDestination=listener;}
    public void zoomIn(){painter.zoomIn();}
    public void zoomOut(){painter.zoomOut();}
    public void fitRoute(){painter.fitRoute();}
    public void followLocation(){painter.followLocation();}
    public void setTopOcclusion(int pixels){painter.setTopOcclusion(pixels);}
    public void setMapStyle(RoadMapStyle style){painter.setMapStyle(style);setContentDescription(style.label+" · 드래그로 이동, 지도 터치로 목적지 선택");}
    public RoadMapStyle getMapStyle(){return painter.getMapStyle();}
    public void close(){painter.close();}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);bounds.set(0,0,getWidth(),getHeight());painter.draw(canvas,bounds,route,fix,origin,destination);}
    @Override public boolean onTouchEvent(MotionEvent event){
        scaleDetector.onTouchEvent(event);
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){downX=lastX=event.getX();downY=lastY=event.getY();dragged=false;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);return true;}
        if(event.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN){dragged=true;return true;}
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE){
            if(Math.hypot(event.getX()-downX,event.getY()-downY)>ViewConfiguration.get(getContext()).getScaledTouchSlop())dragged=true;
            if(dragged&&!scaleDetector.isInProgress()&&event.getPointerCount()==1)painter.panBy(event.getX()-lastX,event.getY()-lastY);
            lastX=event.getX();lastY=event.getY();return true;
        }
        if(event.getActionMasked()==MotionEvent.ACTION_UP){if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);if(!dragged){performClick();RoadRoute.Point p=painter.pointAt(event.getX(),event.getY());if(p!=null&&onDestination!=null)onDestination.accept(p);}return true;}
        if(event.getActionMasked()==MotionEvent.ACTION_CANCEL){if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);return true;}
        return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
