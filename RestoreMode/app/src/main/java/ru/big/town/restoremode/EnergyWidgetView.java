package ru.big.town.restoremode;

import android.content.Context;
import android.content.SharedPreferences;
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
import ru.big.town.common.EnergyWidgetSettings;

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
    private final int columns, rows;
    private final SharedPreferences prefs;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final float baseW, baseH;
    private Bundle state = new Bundle();
    private Bitmap car;
    private long tripMs=-1;
    private boolean inDrive;
    private int window, selected=-1;
    private final Runnable clockTick=new Runnable(){@Override public void run(){invalidate();postDelayed(this,30_000);}};
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

    EnergyWidgetView(Context context, String kind, String color, int columns, int rows) {
        super(context); this.kind=kind; this.color=color(color);
        this.columns=EnergyWidgetLayout.width(kind,columns);this.rows=EnergyWidgetLayout.height(kind,rows);
        baseW=EnergyWidgetLayout.pixelsWide(this.columns);baseH=EnergyWidgetLayout.pixelsHigh(kind,this.rows);
        prefs=context.getSharedPreferences("DrivePreferences",Context.MODE_PRIVATE);
        window=EnergyWidgetSettings.window(prefs.getInt(EnergyWidgetSettings.WINDOW_KEY,75));
        prefs.edit().putInt(EnergyWidgetSettings.WINDOW_KEY,window).apply();
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
            float pad=columns<=5?20:27,button=columns<=5?65:90,buttonsLeft=baseW-pad-3*button;
            if(y>=24&&y<=68&&x>=buttonsLeft&&x<=baseW-pad) {
                int slot=Math.min(2,Math.max(0,(int)((x-buttonsLeft)/button)));
                window=EnergyWidgetSettings.WINDOWS[slot];selected=-1;
                prefs.edit().putInt(EnergyWidgetSettings.WINDOW_KEY,window).apply();
            } else if(y>=155&&y<=279&&x>=pad+57&&x<=baseW-pad-22) {
                float[] distances=array(EnergyWidgetProtocol.HISTORY_X,0);
                if(distances.length>0) {
                    float end=distances[distances.length-1], start=Math.max(0,end-window);
                    float target=start+(x-pad-57)/(baseW-2*pad-79)*Math.max(.1f,end-start), best=Float.MAX_VALUE;
                    for(int i=0;i<distances.length;i++) if(distances[i]>=start&&Math.abs(distances[i]-target)<best) {
                        best=Math.abs(distances[i]-target); selected=i;
                    }
                }
            } else selected=-1;
            invalidate();
        });
    }

    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();if(ODO.equals(kind))post(clockTick);}
    @Override protected void onDetachedFromWindow(){removeCallbacks(clockTick);super.onDetachedFromWindow();}
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
    private float measured(String value,float size) {
        paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));paint.setTextSize(size);return paint.measureText(value);
    }
    private float valueWithUnit(Canvas c,String value,String unit,float x,float y,float size,float unitSize,int color) {
        text(c,value,x,y,size,color,false);
        float next=x+measured(value,size)+6;
        text(c,unit,next,y,unitSize,color,false);
        return next+measured(unit,unitSize);
    }
    private void fitted(Canvas c,String value,float x,float y,float size,float maxWidth,int color) {
        float width=measured(value,size);text(c,value,x,y,width>maxWidth?size*maxWidth/width:size,color,false);
    }
    private static String estimate(float value){return Float.isFinite(value)?"~"+num(value):"—";}
    private float capacity(String key,float fallback) {
        float value=state.getFloat(key,fallback);return EnergyWidgetSettings.validCapacity(value)?value:fallback;
    }
    private void drawTires(Canvas c) {
        boolean narrow=columns<4,shorter=rows==3;
        float pad=columns==2?16:narrow?20:27,titleSize=columns==2?20:narrow?24:28;
        float titleY=shorter?40:55;
        text(c,"Давление в шинах",pad,titleY,titleSize,WHITE,true);right(c,"bar",baseW-pad,titleY,columns==2?14:19,MUTED);
        float height=shorter?258:columns==2?325:columns==3?335:345,top=shorter?60:78;
        if(car!=null) {
            float width=height*car.getWidth()/car.getHeight();paint.setColor(Color.WHITE);paint.setShadowLayer(9,0,8,0xb0000000);
            c.drawBitmap(car,null,new RectF((baseW-width)/2,top,(baseW+width)/2,top+height),paint);paint.clearShadowLayer();
        } else text(c,"Загрузка…",baseW/2-42,baseH/2,16,MUTED,false);
        String[] names={"Левое переднее","Правое переднее","Левое заднее","Правое заднее"};
        float pressureSize=shorter?(columns==2?30:columns==3?34:40):(columns==2?36:columns==3?42:53);
        float labelSize=columns==2?12:columns==3?14:17,plateW=columns==2?120:137;
        for(int i=0;i<4;i++) {
            boolean left=i%2==0;
            float y=shorter?(i<2?110:247):(i<2?152:327);
            float valueY=y+pressureSize+10;
            if(narrow){paint.setColor(0x7a000000);float x=left?pad:baseW-pad-plateW;
                c.drawRoundRect(x,y-labelSize-8,x+plateW,valueY+8,4,4,paint);}
            float x=left?pad+(narrow?8:0):baseW-pad-(narrow?8:0);
            if(left){text(c,names[i],x,y,labelSize,MUTED,false);text(c,num(current(EnergyWidgetProtocol.TIRES,i)),x,valueY,pressureSize,WHITE,false);}
            else {right(c,names[i],x,y,labelSize,MUTED);right(c,num(current(EnergyWidgetProtocol.TIRES,i)),x,valueY,pressureSize,WHITE);}
            if(!narrow)line(c,left?162:baseW-192,y+32,left?192:baseW-162,y+32,BORDER,1);
        }
        float footer=shorter?baseH-27:462,footerSize=columns==2?12:columns==3?13:16;
        text(c,live()?"Показания авто":"Нет связи",pad,footer,footerSize,MUTED,false);
        int index=java.util.Arrays.asList(COLORS).indexOf(color);right(c,COLOR_NAMES[index],baseW-pad,footer,footerSize,MUTED);
    }
    private void drawOdo(Canvas c) {
        float pad=columns==2?20:columns==3?22:27;
        float odo=current(EnergyWidgetProtocol.ODOMETER,0);
        String value=Float.isFinite(odo)?String.format(RU,"%,.0f",odo):"—";
        if(columns==2){text(c,"Общий пробег",pad,33,20,WHITE,true);text(c,"ODO",166,33,14,MUTED,false);
            valueWithUnit(c,value,"км",pad,74,35,18,WHITE);
        }else{ text(c,"Общий пробег",pad,42,columns==3?20:23,WHITE,true);text(c,"ODO",pad,66,17,MUTED,false);
            float size=columns==3?38:49,end=baseW-pad;
            right(c,"км",end,77,columns==3?18:22,MUTED);right(c,value,end-42,78,size,WHITE);}
        String date=new java.text.SimpleDateFormat("dd.MM.yyyy",RU).format(new java.util.Date());
        text(c,date,pad,baseH-13,columns==2?14:16,MUTED,false);
    }
    private void drawTrip(Canvas c) {
        boolean compact=columns<=6;
        float pad=compact?22:27,rightEdge=baseW-pad;
        text(c,"Текущая поездка",pad,compact?46:55,compact?24:28,WHITE,true);
        right(c,!live()?"Нет связи":inDrive?"В пути":"На стоянке",rightEdge,compact?43:51,compact?13:18,MUTED);
        String time="—";
        if(tripMs>=0&&live()){long s=tripMs/1000;time=String.format(Locale.US,"%02d:%02d:%02d",s/3600,(s/60)%60,s%60);}
        String[] labels={"Время в пути","Пробег","Электричество","Бензин"};
        String[] values={time,num(current(EnergyWidgetProtocol.TRIP,0)),estimate(current(EnergyWidgetProtocol.TRIP,1)),estimate(current(EnergyWidgetProtocol.TRIP,2))};
        String[] units={"","км","кВт·ч/100 км","л/100 км"};
        float content=baseW-2*pad,cell=content/4;
        for(int i=0;i<4;i++) {
            float x=compact?(i<2?pad:baseW/2+20):pad+cell*i+(i==0?0:18);
            float y=compact?(i%2==0?78:151):107,numberY=compact?y+39:161;
            float labelSize=compact?15:20,numberSize=compact?(i==0?32:34):columns==7?36:43;
            float available=compact?baseW/2-2*pad-18:cell-(i==0?0:18);
            float unitSize=compact?14:columns==7?15:17;
            float total=measured(values[i],numberSize)+6+measured(units[i],unitSize);
            if(total>available)numberSize=Math.max(22,numberSize-(total-available)/Math.max(1,values[i].length()*.55f));
            text(c,labels[i],x,y,labelSize,MUTED,false);valueWithUnit(c,values[i],units[i],x,numberY,numberSize,unitSize,i==2?GREEN:i==3?BLUE:WHITE);
            if(!compact&&i>0)line(c,x-18,87,x-18,175,BORDER,1);
        }
        if(compact)line(c,baseW/2,63,baseW/2,198,BORDER,1);
        text(c,"Время учитывается только в D",pad,baseH-22,compact?12:17,MUTED,false);
        float evKm=current(EnergyWidgetProtocol.TRIP_OBSERVED_KM,0),fuelKm=current(EnergyWidgetProtocol.TRIP_OBSERVED_KM,1);
        String note=!live()?"Нет связи с автомобилем":Math.max(evKm,fuelKm)<1?"Средние после 1 км наблюдения":
                Math.abs(evKm-fuelKm)<.1?"Учтено "+num(Math.min(evKm,fuelKm))+" км · только снижение":
                "Учтено: электро "+num(evKm)+", бензин "+num(fuelKm)+" км";
        float noteSize=compact?12:16;
        float maxNote=baseW/2-pad;
        if(measured(note,noteSize)>maxNote)noteSize*=maxNote/measured(note,noteSize);
        right(c,note,rightEdge,baseH-22,noteSize,MUTED);
    }
    private void drawEnergy(Canvas c) {
        boolean compact=columns<=5;
        float pad=compact?20:27,button=compact?65:90,buttonsLeft=baseW-pad-3*button;
        text(c,"Заряд и топливо",pad,52,compact?24:28,WHITE,true);
        for(int i=0;i<3;i++) {
            int range=EnergyWidgetSettings.WINDOWS[i];float x=buttonsLeft+i*button;
            paint.setColor(range==window?0xff414b5c:0xff1d212a);c.drawRoundRect(x,24,x+button-4,68,9,9,paint);
            String label=range+" км";float size=compact?16:18;
            text(c,label,x+(button-4-measured(label,size))/2,52,size,range==window?WHITE:MUTED,false);
        }
        float[] distances=array(EnergyWidgetProtocol.HISTORY_X,0),ev=array(EnergyWidgetProtocol.HISTORY_EV,distances.length),fuel=array(EnergyWidgetProtocol.HISTORY_FUEL,distances.length);
        boolean[] gaps=state.getBooleanArray(EnergyWidgetProtocol.HISTORY_BREAK);
        int n=Math.min(distances.length,Math.min(ev.length,fuel.length));
        float currentEv=current(EnergyWidgetProtocol.LEVELS,0),currentFuel=current(EnergyWidgetProtocol.LEVELS,1);
        boolean hasCurrent=Float.isFinite(currentEv)||Float.isFinite(currentFuel);
        if(selected>=0&&selected<n){currentEv=ev[selected];currentFuel=fuel[selected];}
        float batteryCapacity=capacity(EnergyWidgetProtocol.BATTERY_KWH,43),tankCapacity=capacity(EnergyWidgetProtocol.TANK_LITERS,56);
        for(int i=0;i<2;i++) {
            float x=i==0?pad:compact?baseW/2+8:370,value=i==0?currentEv:currentFuel;
            int color=i==0?GREEN:BLUE;float size=compact?40:51,valueX=x+(compact?30:38);
            text(c,i==0?"—":"⋯",x,108,compact?26:34,color,false);
            float end=valueWithUnit(c,num(value),"%",valueX,110,size,size*.7f,color);
            text(c,i==0?"Батарея":"Топливо",end+10,108,compact?15:16,color,false);
            text(c,"Осталось "+estimate(value*(i==0?batteryCapacity:tankCapacity)/100)+(i==0?" кВт·ч":" л"),valueX,136,compact?15:16,color,false);
        }
        String status=selected>=0?"На "+num(distances[selected])+" км":!live()?"Нет связи с автомобилем":hasCurrent?"Текущие уровни":"Нет свежих данных";
        right(c,status,baseW-pad,compact?153:122,compact?13:17,MUTED);
        float end=n>0?Math.max(.1f,distances[n-1]):window,start=Math.max(0,end-window),span=end-start;
        float left=pad+57,right=baseW-pad-22,top=163,bottom=250;
        for(int i=0;i<=4;i++){float y=top+(bottom-top)*i/4;line(c,left,y,right,y,BORDER,1);
            right(c,(100-25*i)+"%",left-13,y+5,compact?14:17,MUTED);}
        int divisions=compact?3:5;
        for(int i=0;i<=divisions;i++) {
            float x=left+(right-left)*i/divisions;String label=num(start+span*i/divisions)+(i==divisions?" км":"");
            if(i==divisions)right(c,label,x,276,compact?14:17,MUTED);else text(c,label,x-measured(label,compact?14:17)/2,276,compact?14:17,MUTED,false);
        }
        if(n<2)fitted(c,!live()?"Нет записанной истории":hasCurrent?"История появится по мере движения":"Ожидание уровней батареи и топлива",left+15,213,compact?18:22,right-left-25,MUTED);
        for(int series=0;series<2;series++) {
            Path path=new Path();boolean drawing=false;int color=series==0?GREEN:BLUE;
            for(int i=0;i<n;i++) {
                float v=series==0?ev[i]:fuel[i];
                if(distances[i]<start||!Float.isFinite(v)||v<0||v>100){drawing=false;continue;}
                float x=left+(distances[i]-start)/span*(right-left),y=bottom-v/100*(bottom-top);
                if(!drawing||gaps==null||i>=gaps.length||gaps[i])path.moveTo(x,y);else path.lineTo(x,y);
                drawing=true;paint.setColor(color);c.drawCircle(x,y,1.5f,paint);
            }
            paint.setColor(color);paint.setStrokeWidth(3);paint.setStyle(Paint.Style.STROKE);
            if(series==1)paint.setPathEffect(new DashPathEffect(new float[]{8,6},0));
            c.drawPath(path,paint);paint.setPathEffect(null);paint.setStyle(Paint.Style.FILL);
        }
        if(selected>=0&&selected<n&&distances[selected]>=start){float x=left+(distances[selected]-start)/span*(right-left);line(c,x,top,x,bottom,0xff7b8799,1);}
        EnergyPeriodEstimate period=EnergyPeriodEstimate.calculate(window,distances,
                state.getDoubleArray(EnergyWidgetProtocol.HISTORY_EV_DROP),state.getDoubleArray(EnergyWidgetProtocol.HISTORY_FUEL_DROP),
                state.getDoubleArray(EnergyWidgetProtocol.HISTORY_EV_KM),state.getDoubleArray(EnergyWidgetProtocol.HISTORY_FUEL_KM),batteryCapacity,tankCapacity,
                state.getDoubleArray(EnergyWidgetProtocol.HISTORY_START_EV_DROP),state.getDoubleArray(EnergyWidgetProtocol.HISTORY_START_FUEL_DROP),
                state.getDoubleArray(EnergyWidgetProtocol.HISTORY_START_EV_KM),state.getDoubleArray(EnergyWidgetProtocol.HISTORY_START_FUEL_KM));
        line(c,pad,288,baseW-pad,288,BORDER,1);
        text(c,"Средний расход за "+window+" км",pad,310,compact?15:16,MUTED,false);
        float coverage=Math.min(period.batteryKm,period.fuelKm);
        String note=Math.max(period.batteryKm,period.fuelKm)<1?"Нужно от 1 км истории":coverage<window-.1f?"Учтено "+num(coverage)+" из "+window+" км":"Только снижение";
        right(c,note,baseW-pad,310,compact?12:13,MUTED);
        float last=valueWithUnit(c,estimate(period.battery),"кВт·ч/100 км",pad,348,compact?26:29,compact?14:15,GREEN);
        valueWithUnit(c,estimate(period.fuel),"л/100 км",last+32,348,compact?26:29,compact?14:15,BLUE);
    }
}
