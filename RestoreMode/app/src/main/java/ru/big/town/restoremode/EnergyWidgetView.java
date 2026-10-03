package ru.big.town.restoremode;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.MotionEvent;
import android.view.View;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import ru.big.town.common.EnergyWidgetProtocol;

/** Four native dashboard tiles. Coordinates match the approved 1920×720 prototype. */
final class EnergyWidgetView extends View {
    static final String ENERGY = "energyWidget", TRIP = "energyTripWidget",
            TIRES = "tirePressureWidget", ODO = "odometerWidget";
    static final String[] IDS = {ENERGY, TRIP, TIRES, ODO};
    static final String[] NAMES = {"Заряд и топливо", "Текущая поездка", "Давление в шинах", "Общий пробег"};
    static final String[] COLORS = {"black", "white", "dark_gray", "dark_green", "burgundy", "gold_bronze", "sage_green"};
    static final String[] COLOR_NAMES = {"Чёрный", "Белый", "Тёмно-серый", "Тёмно-зелёный", "Бургунди", "Золотисто-бронзовый", "Серо-зелёный"};
    private static final int WHITE=0xffeef1f6, MUTED=0xffaab3c4, GREEN=0xff66d3ad,
            BLUE=0xff79b5f1, BORDER=0xff373f4a;
    private static final Locale RU = new Locale("ru", "RU");
    private static final ExecutorService IMAGES = Executors.newSingleThreadExecutor();
    private static final LruCache<String,Bitmap> CACHE = new LruCache<>(2);
    private final String kind, color;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final float baseW, baseH;
    private Bundle state = new Bundle();
    private Bitmap car;
    private long tripMs=-1;
    private boolean inDrive;
    private int window=15, selected=-1;
    private float touchX, touchY, scale=1, offsetX, offsetY;

    static boolean isWidget(String id) {
        for (String s:IDS) if (s.equals(id)) return true;
        return false;
    }
    static int[] size(String id) {
        if (ENERGY.equals(id)) return new int[]{8,3};
        if (TRIP.equals(id)) return new int[]{8,2};
        if (TIRES.equals(id)) return new int[]{4,4};
        return new int[]{4,1};
    }
    static String color(String value) {
        for (String c:COLORS) if(c.equals(value)) return c;
        return "burgundy";
    }

    EnergyWidgetView(Context context, String kind, String color) {
        super(context); this.kind=kind; this.color=color(color);
        baseW=(ENERGY.equals(kind)||TRIP.equals(kind))?1168:580;
        baseH=ENERGY.equals(kind)?373:TRIP.equals(kind)?245:TIRES.equals(kind)?500:118;
        setFocusable(true); setClickable(true);
        if (TIRES.equals(kind)) {
            setLayerType(LAYER_TYPE_SOFTWARE,null); // alpha-shaped, soft bitmap shadow
            car=CACHE.get(this.color);
            if(car==null) IMAGES.execute(() -> {
                Bitmap loaded=null;
                try(InputStream in=context.getApplicationContext().getAssets().open("energy/car_"+this.color+".png")) {
                    BitmapFactory.Options options=new BitmapFactory.Options(); options.inSampleSize=2;
                    loaded=BitmapFactory.decodeStream(in,null,options);
                } catch(Exception ignored) {}
                if(loaded!=null) CACHE.put(this.color,loaded);
                Bitmap result=loaded; post(() -> {car=result; invalidate();});
            });
        }
        setOnClickListener(v -> {
            if(!ENERGY.equals(kind)) return;
            float x=(touchX-offsetX)/scale, y=(touchY-offsetY)/scale;
            if(y>=24&&y<=74&&x>=baseW-303) {
                int slot=Math.min(2,Math.max(0,(int)((x-(baseW-303))/90)));
                window=new int[]{5,15,30}[slot]; selected=-1;
            } else if(y>=160&&y<=320&&x>=77&&x<=baseW-77) {
                float[] distances=array(EnergyWidgetProtocol.HISTORY_X,0);
                if(distances.length>0) {
                    float end=distances[distances.length-1], start=Math.max(0,end-window);
                    float target=start+(x-77)/(baseW-154)*Math.max(1,end-start), best=Float.MAX_VALUE;
                    for(int i=0;i<distances.length;i++) if(distances[i]>=start&&Math.abs(distances[i]-target)<best) {
                        best=Math.abs(distances[i]-target); selected=i;
                    }
                }
            } else selected=-1;
            invalidate();
        });
    }

    void update(Bundle data) {
        float[] previous=array(EnergyWidgetProtocol.HISTORY_X,0);
        float selectedKm=selected>=0&&selected<previous.length?previous[selected]:Float.NaN;
        state=data==null?new Bundle():new Bundle(data);
        selected=-1;
        float[] next=array(EnergyWidgetProtocol.HISTORY_X,0);
        for(int i=0;i<next.length;i++) if(next[i]==selectedKm) {selected=i;break;}
        setContentDescription(description()); invalidate();
    }
    void timer(long ms, boolean drive) {tripMs=ms; inDrive=drive; if(TRIP.equals(kind)) invalidate();}
    @Override public boolean onTouchEvent(MotionEvent e) {
        touchX=e.getX(); touchY=e.getY(); return super.onTouchEvent(e);
    }
    private boolean live() {
        long time=state.getLong(EnergyWidgetProtocol.UPDATED,-1), now=SystemClock.elapsedRealtime();
        return state.getInt(EnergyWidgetProtocol.SCHEMA)==EnergyWidgetProtocol.VERSION
                &&state.getBoolean(EnergyWidgetProtocol.CONNECTED)&&time>=0&&now>=time
                &&now-time<=EnergyWidgetProtocol.UI_TIMEOUT_MS;
    }
    private float[] array(String key,int size) {
        float[] a=state.getFloatArray(key); return a!=null&&a.length>=size?a:new float[0];
    }
    private float current(String key,int index) {
        float[] a=array(key,index+1); return live()&&a.length>index?a[index]:Float.NaN;
    }
    private static String num(float value) {return Float.isFinite(value)?String.format(RU,"%.1f",value):"—";}
    private String description() {
        if(TIRES.equals(kind)) return "Давление в шинах, bar. Левое переднее "+num(current(EnergyWidgetProtocol.TIRES,0))
                +", правое переднее "+num(current(EnergyWidgetProtocol.TIRES,1))+", левое заднее "
                +num(current(EnergyWidgetProtocol.TIRES,2))+", правое заднее "+num(current(EnergyWidgetProtocol.TIRES,3));
        return ENERGY.equals(kind)?"Заряд батареи: "+num(current(EnergyWidgetProtocol.LEVELS,0))+" процентов, топливо "
                +num(current(EnergyWidgetProtocol.LEVELS,1))+" процентов":TRIP.equals(kind)?"Текущая поездка":"Общий пробег";
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        paint.setColor(0xff22252f); paint.setStyle(Paint.Style.FILL);
        c.drawRoundRect(0,0,getWidth(),getHeight(),20,20,paint);
        paint.setColor(BORDER);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1);
        c.drawRoundRect(.5f,.5f,getWidth()-.5f,getHeight()-.5f,20,20,paint);paint.setStyle(Paint.Style.FILL);
        scale=Math.min(getWidth()/baseW,getHeight()/baseH);
        offsetX=(getWidth()-baseW*scale)/2;offsetY=(getHeight()-baseH*scale)/2;
        c.save();c.translate(offsetX,offsetY);c.scale(scale,scale);
        if(ENERGY.equals(kind)) drawEnergy(c);
        else if(TRIP.equals(kind)) drawTrip(c);
        else if(TIRES.equals(kind)) drawTires(c);
        else drawOdo(c);
        c.restore();
    }
    private void text(Canvas c,String s,float x,float y,float size,int color,boolean bold) {
        paint.setColor(color);paint.setTextSize(size);paint.setTypeface(bold?Typeface.create("sans-serif-medium",Typeface.NORMAL):Typeface.create("sans-serif",Typeface.NORMAL));
        c.drawText(s,x,y,paint);
    }
    private void right(Canvas c,String s,float x,float y,float size,int color) {
        paint.setTextSize(size);paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
        text(c,s,x-paint.measureText(s),y,size,color,false);
    }
    private void line(Canvas c,float x1,float y1,float x2,float y2,int color,float width) {
        paint.setColor(color);paint.setStrokeWidth(width);c.drawLine(x1,y1,x2,y2,paint);
    }
    private void drawTires(Canvas c) {
        text(c,"Давление в шинах",27,55,28,WHITE,true);right(c,"bar",553,55,19,MUTED);
        if(car!=null) {
            float height=345,width=height*car.getWidth()/car.getHeight();
            paint.setColor(Color.WHITE);paint.setShadowLayer(9,0,8,0xb0000000);
            c.drawBitmap(car,null,new RectF((580-width)/2,78,(580+width)/2,423),paint);paint.clearShadowLayer();
        } else text(c,"Загрузка изображения…",186,275,16,MUTED,false);
        String[] names={"Левое переднее","Правое переднее","Левое заднее","Правое заднее"};
        for(int i=0;i<4;i++) {
            float y=i<2?152:327;
            if(i%2==0) {text(c,names[i],27,y,17,MUTED,false);text(c,num(current(EnergyWidgetProtocol.TIRES,i)),27,y+61,53,WHITE,false);line(c,162,y+32,192,y+32,BORDER,2);}
            else {right(c,names[i],553,y,17,MUTED);right(c,num(current(EnergyWidgetProtocol.TIRES,i)),553,y+61,53,WHITE);line(c,388,y+32,418,y+32,BORDER,2);}
        }
        text(c,live()?"Показания автомобиля":"Нет связи с автомобилем",27,462,16,MUTED,false);
        int index=java.util.Arrays.asList(COLORS).indexOf(color);right(c,COLOR_NAMES[index],553,462,16,MUTED);
    }
    private void drawOdo(Canvas c) {
        text(c,"Общий пробег",27,54,24,WHITE,true);text(c,"ODO",27,83,18,MUTED,false);
        float odo=current(EnergyWidgetProtocol.ODOMETER,0);
        String n=Float.isFinite(odo)?String.format(RU,"%,.0f",odo):"—";
        right(c,n,515,78,52,WHITE);right(c,"км",553,77,21,MUTED);
    }
    private void drawTrip(Canvas c) {
        text(c,"Текущая поездка",27,55,28,WHITE,true);
        right(c,!live()?"Нет связи":inDrive?"В пути":"На стоянке",1140,51,18,MUTED);
        String[] labels={"Время в пути","Пробег","Средний расход","Бензин · оценка"};
        float[] left={27,369,627,886};
        String time="—";
        if(tripMs>=0&&live()) {long s=tripMs/1000;time=String.format(Locale.US,"%02d:%02d:%02d",s/3600,(s/60)%60,s%60);}
        String[] numbers={time,num(current(EnergyWidgetProtocol.TRIP,0)),num(current(EnergyWidgetProtocol.TRIP,1)),num(current(EnergyWidgetProtocol.TRIP,2))};
        String[] units={"","км","кВт·ч/100 км","л/100 км"};
        for(int i=0;i<4;i++) {
            text(c,labels[i],left[i],107,20,MUTED,false);
            text(c,numbers[i],left[i],161,44,i==2?GREEN:i==3?BLUE:WHITE,false);
            paint.setTextSize(44);float end=left[i]+paint.measureText(numbers[i])+10;
            text(c,units[i],end,160,17,MUTED,false);
            if(i>0) line(c,left[i]-26,87,left[i]-26,175,BORDER,1);
        }
        text(c,"Время учитывается только в D",27,211,18,MUTED,false);
        float measuredKm=state.getFloat(EnergyWidgetProtocol.FUEL_ESTIMATE_KM,Float.NaN);
        right(c,!live()?"Нет связи с автомобилем":!Float.isFinite(measuredKm)?"Ожидание уровня топлива"
                :measuredKm<1?"Расчёт бензина после 1 км наблюдения"
                :"Бензин ≈ по уровню бака 56 л · за "+num(measuredKm)+" км",1140,211,16,MUTED);
    }
    private void drawEnergy(Canvas c) {
        text(c,"Заряд и топливо",27,55,28,WHITE,true);
        for(int i=0;i<3;i++) {
            int range=new int[]{5,15,30}[i];float x=baseW-303+i*90;
            paint.setColor(range==window?0xff414b5c:0xff1d212a);c.drawRoundRect(x,24,x+86,68,9,9,paint);
            text(c,range+" км",x+20,53,18,range==window?WHITE:MUTED,true);
        }
        float[] distances=array(EnergyWidgetProtocol.HISTORY_X,0), ev=array(EnergyWidgetProtocol.HISTORY_EV,distances.length), fuel=array(EnergyWidgetProtocol.HISTORY_FUEL,distances.length);
        boolean[] gaps=state.getBooleanArray(EnergyWidgetProtocol.HISTORY_BREAK);
        int n=Math.min(distances.length,Math.min(ev.length,fuel.length));
        float currentEv=current(EnergyWidgetProtocol.LEVELS,0), currentFuel=current(EnergyWidgetProtocol.LEVELS,1);
        boolean hasCurrent=Float.isFinite(currentEv)||Float.isFinite(currentFuel);
        if(selected>=0&&selected<n) {currentEv=ev[selected];currentFuel=fuel[selected];}
        text(c,"—",27,122,34,GREEN,false);text(c,num(currentEv),68,125,49,GREEN,true);
        text(c,"Батарея",235,117,17,GREEN,true);text(c,"%",180,153,18,MUTED,false);
        text(c,"⋯",370,122,34,BLUE,true);text(c,num(currentFuel),410,125,49,BLUE,true);
        text(c,"Топливо",564,117,17,BLUE,true);text(c,"%",487,153,18,MUTED,false);
        right(c,selected>=0?"Выбранная точка":!live()?"Нет связи с автомобилем"
                :hasCurrent?"Текущие уровни":"Нет свежих данных уровней",1140,124,18,MUTED);
        float end=n>0?Math.max(1,distances[n-1]):window,start=Math.max(0,end-window),span=end-start;
        float left=77,right=1091,top=174,bottom=292;
        for(int i=0;i<=4;i++) {
            float y=top+(bottom-top)*i/4, value=100-25*i;
            line(c,left,y,right,y,BORDER,1);
            right(c,String.format(RU,"%.0f%%",value),left-13,y+5,16,MUTED);
        }
        for(int i=0;i<6;i++) {float d=start+span*i/5; text(c,num(d),left+(right-left)*i/5-15,321,17,MUTED,false);}
        if(n<2) text(c,!live()?"Нет записанной истории":hasCurrent
                ?"История появится по мере движения":"Ожидание уровней батареи и топлива",290,242,22,MUTED,false);
        for(int series=0;series<2;series++) {
            Path path=new Path();boolean drawing=false;
            for(int i=0;i<n;i++) {
                float v=series==0?ev[i]:fuel[i];
                if(distances[i]<start||!Float.isFinite(v)||v<0||v>100) {drawing=false;continue;}
                float x=left+(distances[i]-start)/span*(right-left),y=bottom-v/100f*(bottom-top);
                if(!drawing||gaps==null||i>=gaps.length||gaps[i])path.moveTo(x,y);else path.lineTo(x,y);
                drawing=true;
                paint.setColor(series==0?GREEN:BLUE);c.drawCircle(x,y,2,paint);
            }
            paint.setColor(series==0?GREEN:BLUE);paint.setStrokeWidth(3);paint.setStyle(Paint.Style.STROKE);
            if(series==1)paint.setPathEffect(new DashPathEffect(new float[]{8,6},0));
            c.drawPath(path,paint);paint.setPathEffect(null);paint.setStyle(Paint.Style.FILL);
        }
        if(selected>=0&&selected<n&&distances[selected]>=start) {
            float x=left+(distances[selected]-start)/span*(right-left);line(c,x,top,x,bottom,0xff7b8799,1);
        }
        text(c,"Батарея — зелёный · топливо — голубой · шаг 100 м",27,348,17,MUTED,false);
        right(c,num(start)+"–"+num(end)+" км · пробег поездки",1140,348,16,MUTED);
    }
}
