package ru.big.town.restoremode;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
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
    private static final LruCache<String,EnergyCarImage> CACHE = new LruCache<>(2);
    private final String kind, color;
    private final int columns, rows;
    private final SharedPreferences prefs;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint carPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final float baseW, baseH;
    private Bundle state = new Bundle();
    private EnergyCarImage car;
    private long tripMs=-1;
    private boolean inDrive;
    private int window, selected=-1,selectedConsumption=-1;
    private EnergyConsumptionChart consumption=new EnergyConsumptionChart(0,null,null,null,null,null);
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
            car=CACHE.get(this.color);
            if(car==null) IMAGES.execute(() -> {
                EnergyCarImage loaded=null;
                try(InputStream in=context.getApplicationContext().getAssets().open("energy/car_"+this.color+".png")) {
                    BitmapFactory.Options options=new BitmapFactory.Options(); options.inSampleSize=2;
                    Bitmap body=BitmapFactory.decodeStream(in,null,options);
                    if(body!=null)loaded=new EnergyCarImage(body);
                } catch(Exception ignored) {}
                if(loaded!=null) CACHE.put(this.color,loaded);
                EnergyCarImage result=loaded; post(() -> {car=result; invalidate();});
            });
        }
        setOnClickListener(v -> {
            if(!ENERGY.equals(kind)) return;
            float x=(touchX-offsetX)/scale, y=(touchY-offsetY)/scale;
            EnergyChartLayout chart=new EnergyChartLayout(baseW,columns<=5);
            float pad=columns<=5?20:27,button=columns<=5?65:90,buttonsLeft=baseW-pad-3*button;
            if(y>=24&&y<=68&&x>=buttonsLeft&&x<=baseW-pad) {
                int slot=Math.min(2,Math.max(0,(int)((x-buttonsLeft)/button)));
                window=EnergyWidgetSettings.WINDOWS[slot];selected=-1;
                prefs.edit().putInt(EnergyWidgetSettings.WINDOW_KEY,window).apply();
            } else if(y>=EnergyChartLayout.TOP-10&&y<=279&&x>=chart.left&&x<=chart.right) {
                if(selected>=0) selected=-1;
                else {
                    float[] distances=array(EnergyWidgetProtocol.HISTORY_X,0);
                    if(distances.length>0) {
                        EnergyChartAxis axis=new EnergyChartAxis(recordedKm(),window,Float.NaN);
                        double target=axis.distanceAt((x-chart.left)/(chart.right-chart.left)),best=Double.MAX_VALUE;
                        for(int i=0;i<distances.length;i++) if(axis.position(distances[i])>=0&&Math.abs(distances[i]-target)<best) {
                            best=Math.abs(distances[i]-target); selected=i;
                        }
                    }
                }
            } else if(y>=EnergyChartLayout.TOP-10&&y<=279&&x>=chart.shortLeft&&x<=chart.shortRight) {
                selectedConsumption=selectedConsumption>=0?-1:consumption.nearest((x-chart.shortLeft)/(chart.shortRight-chart.shortLeft));
            } else {selected=-1;selectedConsumption=-1;}
            invalidate();
        });
    }

    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();if(ODO.equals(kind))post(clockTick);}
    @Override protected void onDetachedFromWindow(){removeCallbacks(clockTick);super.onDetachedFromWindow();}
    void update(Bundle data) {
        double selectedEnd=selectedConsumption>=0&&selectedConsumption<consumption.end.length?consumption.end[selectedConsumption]:Double.NaN;
        float[] previous=array(EnergyWidgetProtocol.HISTORY_X,0);
        float selectedKm=selected>=0&&selected<previous.length?previous[selected]:Float.NaN;
        state=data==null?new Bundle():new Bundle(data);
        consumption=new EnergyConsumptionChart(recordedKm(),state.getDoubleArray(EnergyWidgetProtocol.CONSUMPTION_START),
                state.getDoubleArray(EnergyWidgetProtocol.CONSUMPTION_END),state.getFloatArray(EnergyWidgetProtocol.CONSUMPTION_EV),
                state.getFloatArray(EnergyWidgetProtocol.CONSUMPTION_FUEL),state.getBooleanArray(EnergyWidgetProtocol.CONSUMPTION_BREAK));
        selectedConsumption=-1;
        for(int i=0;i<consumption.end.length;i++)if(consumption.end[i]==selectedEnd){selectedConsumption=i;break;}
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
    private double recordedKm() {
        float[] x=array(EnergyWidgetProtocol.HISTORY_X,0);double fallback=x.length==0?0:x[x.length-1];
        double km=state.getDouble(EnergyWidgetProtocol.RECORDED_KM,fallback);
        return Double.isFinite(km)&&km>=0?km:fallback;
    }
    private static String num(float value) {return Float.isFinite(value)?String.format(RU,"%.1f",value):"—";}
    private static String mileage(double value) {return Double.isFinite(value)?String.format(RU,"%,.0f",value):"—";}
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
        if(car!=null)car.draw(c,carPaint,baseW/2,top,height);
        else text(c,"Загрузка…",baseW/2-42,baseH/2,16,MUTED,false);
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
        if(!live())text(c,"Нет связи",pad,shorter?baseH-27:462,columns==2?12:columns==3?13:16,MUTED,false);
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
                Math.abs(evKm-fuelKm)<.1?"Учтено "+num(Math.min(evKm,fuelKm))+" км":
                "Учтено: электро "+num(evKm)+", бензин "+num(fuelKm)+" км";
        float noteSize=compact?12:16;
        float maxNote=baseW/2-pad;
        if(measured(note,noteSize)>maxNote)noteSize*=maxNote/measured(note,noteSize);
        right(c,note,rightEdge,baseH-22,noteSize,MUTED);
    }
    private void drawEnergy(Canvas c) {
        boolean compact=columns<=5;
        EnergyChartLayout chart=new EnergyChartLayout(baseW,compact);
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
        EnergyChartAxis axis=new EnergyChartAxis(recordedKm(),window,current(EnergyWidgetProtocol.ODOMETER,0));
        float currentEv=current(EnergyWidgetProtocol.LEVELS,0),currentFuel=current(EnergyWidgetProtocol.LEVELS,1);
        boolean hasCurrent=Float.isFinite(currentEv)||Float.isFinite(currentFuel);
        if(selected>=0&&selected<n){currentEv=ev[selected];currentFuel=fuel[selected];}
        float batteryCapacity=capacity(EnergyWidgetProtocol.BATTERY_KWH,43),tankCapacity=capacity(EnergyWidgetProtocol.TANK_LITERS,56);
        for(int i=0;i<2;i++) {
            float x=i==0?pad:compact?baseW/2+8:370,value=i==0?currentEv:currentFuel;
            int color=i==0?GREEN:BLUE;float size=compact?40:51,valueX=x+(compact?30:38);
            text(c,"—",x,108,compact?26:34,color,false);
            float end=valueWithUnit(c,num(value),"%",valueX,110,size,size*.7f,color);
            text(c,i==0?"Батарея":"Топливо",end+10,108,compact?15:16,color,false);
            text(c,"Осталось "+estimate(value*(i==0?batteryCapacity:tankCapacity)/100)+(i==0?" кВт·ч":" л"),valueX,136,compact?15:16,color,false);
        }
        String status=selected>=0?(Double.isFinite(axis.odometerAt(distances[selected]))?"Пробег ~"+mileage(axis.odometerAt(distances[selected]))+" км":"Пробег недоступен"):
                !live()?"Нет связи с автомобилем":hasCurrent?"":"Нет свежих данных";
        right(c,status,baseW-pad,compact?153:122,compact?13:17,MUTED);
        text(c,"Уровни, %",pad,170,compact?12:14,MUTED,false);
        float left=chart.left,right=chart.right,top=EnergyChartLayout.TOP,bottom=EnergyChartLayout.BOTTOM;
        for(int i=0;i<=4;i++){float y=top+(bottom-top)*i/4;line(c,left,y,right,y,BORDER,1);
            right(c,(100-25*i)+"%",left-(compact?7:13),y+5,compact?14:17,MUTED);}
        for(int i=0;i<=EnergyChartAxis.INTERVALS;i++) {
            float x=right-(right-left)*i/EnergyChartAxis.INTERVALS;String label=mileage(axis.tick(i))+(i==0&&!compact?" км":"");
            float size=compact?11:17;
            if(i==0)right(c,label,x,276,size,MUTED);else text(c,label,i==EnergyChartAxis.INTERVALS?x:x-measured(label,size)/2,276,size,MUTED,false);
        }
        if(n<2)fitted(c,!live()?"Нет записанной истории":hasCurrent?"История появится по мере движения":"Ожидание уровней батареи и топлива",left+15,213,compact?18:22,right-left-25,MUTED);
        for(int series=0;series<2;series++) {
            Path path=new Path();int color=series==0?GREEN:BLUE;
            EnergyChartCurve.trace(distances,series==0?ev:fuel,gaps,n,axis.distanceAt(0),new EnergyChartCurve.Sink() {
                private float x(double km){return left+axis.position(km)*(right-left);}
                private float y(double level){return bottom-(float)(level/100)*(bottom-top);}
                @Override public void moveTo(double km,double level){path.moveTo(x(km),y(level));}
                @Override public void cubicTo(double km1,double level1,double km2,double level2,double km,double level) {
                    path.cubicTo(x(km1),y(level1),x(km2),y(level2),x(km),y(level));
                }
            });
            paint.setColor(color);paint.setStrokeWidth(3);paint.setStyle(Paint.Style.STROKE);
            paint.setPathEffect(null);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
            c.drawPath(path,paint);
            paint.setStrokeCap(Paint.Cap.BUTT);paint.setStrokeJoin(Paint.Join.MITER);paint.setStyle(Paint.Style.FILL);
            int marker=selected>=0&&selected<n?selected:n-1;
            if(marker>=0&&axis.position(distances[marker])>=0) {
                float v=series==0?ev[marker]:fuel[marker];
                if(Float.isFinite(v)&&v>=0&&v<=100) {
                    float x=left+axis.position(distances[marker])*(right-left),y=bottom-v/100*(bottom-top);
                    c.drawCircle(x,y,3,paint);
                }
            }
        }
        if(selected>=0&&selected<n&&axis.position(distances[selected])>=0){float x=left+axis.position(distances[selected])*(right-left);line(c,x,top,x,bottom,0xff7b8799,1);}
        EnergyPeriodEstimate period=EnergyPeriodEstimate.calculate(window,distances,
                state.getDoubleArray(EnergyWidgetProtocol.HISTORY_EV_DROP),state.getDoubleArray(EnergyWidgetProtocol.HISTORY_FUEL_DROP),
                state.getDoubleArray(EnergyWidgetProtocol.HISTORY_EV_KM),state.getDoubleArray(EnergyWidgetProtocol.HISTORY_FUEL_KM),batteryCapacity,tankCapacity,
                state.getDoubleArray(EnergyWidgetProtocol.HISTORY_START_EV_DROP),state.getDoubleArray(EnergyWidgetProtocol.HISTORY_START_FUEL_DROP),
                state.getDoubleArray(EnergyWidgetProtocol.HISTORY_START_EV_KM),state.getDoubleArray(EnergyWidgetProtocol.HISTORY_START_FUEL_KM));
        line(c,pad,288,chart.split,288,BORDER,1);
        text(c,"Средний расход за "+window+" км",pad,310,compact?13:16,MUTED,false);
        float coverage=Math.min(period.batteryKm,period.fuelKm);
        String note=Math.max(period.batteryKm,period.fuelKm)<1?"Нужно от 1 км истории":coverage<window-.1f?"Учтено "+num(coverage)+" из "+window+" км":"";
        right(c,note,chart.split,310,compact?10:13,MUTED);
        float last=valueWithUnit(c,estimate(period.battery),"кВт·ч/100 км",pad,348,compact?22:29,compact?11:15,GREEN);
        valueWithUnit(c,estimate(period.fuel),"л/100 км",last+(compact?16:32),348,compact?22:29,compact?11:15,BLUE);
        drawConsumption(c,chart,axis,compact);
    }
    private static String quantity(float value) {
        return Float.isFinite(value)?String.format(RU,"%.3f",Math.abs(value)<.0005?0:value):"—";
    }
    private static String axisQuantity(double value) {
        if(value==0)return "0";
        if(Math.abs(value)>=1000||Math.abs(value)<.001)return String.format(RU,"%.1e",value);
        return String.format(RU,"%.3f",value).replaceAll("0+$","").replaceAll(",$","");
    }
    private void drawConsumption(Canvas c,EnergyChartLayout chart,EnergyChartAxis axis,boolean compact) {
        float left=chart.shortLeft,right=chart.shortRight,top=EnergyChartLayout.TOP,bottom=EnergyChartLayout.BOTTOM;
        float axisSize=compact?9:11,content=chart.shortContent,end=baseW-chart.pad;
        float zero=top+(float)consumption.zero()*(bottom-top);
        line(c,chart.shortColumn,153,chart.shortColumn,baseH-17,BORDER,1);
        text(c,"Расход · 10 км",content,170,compact?12:14,MUTED,false);
        if(!compact)right(c,"шаг 250 м",end,170,11,MUTED);
        paint.setColor(0x0970e1ab);c.drawRect(left,zero,right,bottom,paint);
        line(c,left,top,right,top,BORDER,1);line(c,left,bottom,right,bottom,BORDER,1);
        line(c,left,zero,right,zero,0xff616b7b,1);
        if(consumption.electricMax>0)right(c,axisQuantity(consumption.electricMax),left-5,top+3,axisSize,GREEN);
        right(c,"0",left-5,zero+3,axisSize,GREEN);
        if(consumption.electricMin<0)right(c,axisQuantity(consumption.electricMin),left-5,bottom+3,axisSize,GREEN);
        if(consumption.fuelMax>0)text(c,axisQuantity(consumption.fuelMax),right+5,top+3,axisSize,BLUE,false);
        text(c,"0",right+5,zero+3,axisSize,BLUE,false);
        for(int i=0;i<=2;i++) {
            if(compact&&i==1)continue;
            float x=left+(right-left)*i/2;String label=mileage(axis.odometerAt(consumption.cursor-10+5*i));
            if(i==2)right(c,label,x,276,axisSize,MUTED);else text(c,label,i==0?x:x-measured(label,axisSize)/2,276,axisSize,MUTED,false);
        }
        int count=consumption.end.length,marker=selectedConsumption>=0?selectedConsumption:count-1;
        for(int series=0;series<2;series++) {
            final boolean electric=series==0;float[] values=electric?consumption.battery:consumption.fuel;
            int color=electric?GREEN:BLUE;Path path=new Path();
            EnergyChartCurve.trace(consumption.position,values,consumption.gaps,count,0,electric?Double.NEGATIVE_INFINITY:0,
                    Double.POSITIVE_INFINITY,new EnergyChartCurve.Sink() {
                private float x(double position){return left+(float)(position/10)*(right-left);}
                private float y(double value){return top+(float)(electric?consumption.electricPosition(value):consumption.fuelPosition(value))*(bottom-top);}
                @Override public void moveTo(double position,double value){path.moveTo(x(position),y(value));}
                @Override public void cubicTo(double x1,double y1,double x2,double y2,double x,double y){path.cubicTo(x(x1),y(y1),x(x2),y(y2),x(x),y(y));}
            });
            paint.setColor(color);paint.setStrokeWidth(1.2f);paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);c.drawPath(path,paint);
            paint.setStrokeCap(Paint.Cap.BUTT);paint.setStrokeJoin(Paint.Join.MITER);paint.setStyle(Paint.Style.FILL);
            if(marker>=0&&Float.isFinite(values[marker])) {
                float x=left+consumption.position[marker]/10*(right-left);
                float y=top+(float)(electric?consumption.electricPosition(values[marker]):consumption.fuelPosition(values[marker]))*(bottom-top);
                c.drawCircle(x,y,2,paint);
            }
        }
        if(selectedConsumption>=0){float x=left+consumption.position[selectedConsumption]/10*(right-left);line(c,x,top,x,bottom,0xff7b8799,1);}
        if(count==0)fitted(c,"Ожидание 250 м",content,218,compact?11:13,end-content,MUTED);
        line(c,content,288,end,288,BORDER,1);
        String label="Последние 250 м";
        if(marker>=0) {
            if(selectedConsumption>=0&&Double.isFinite(axis.odometerAt(consumption.start[marker])))
                label=String.format(RU,"%.2f–%.2f км",axis.odometerAt(consumption.start[marker]),axis.odometerAt(consumption.end[marker]));
            else label="Последние "+Math.round((consumption.end[marker]-consumption.start[marker])*1000)+" м";
        }
        fitted(c,label,content,310,compact?10:12,end-content,MUTED);
        float ev=marker>=0?consumption.battery[marker]:Float.NaN,fuel=marker>=0?consumption.fuel[marker]:Float.NaN;
        if(columns<=6){valueWithUnit(c,quantity(ev),"кВт·ч",content,332,18,11,GREEN);valueWithUnit(c,quantity(fuel),"л",content,354,18,11,BLUE);}
        else{float next=valueWithUnit(c,quantity(ev),"кВт·ч",content,348,25,13,GREEN);valueWithUnit(c,quantity(fuel),"л",next+16,348,25,13,BLUE);}
    }
}
