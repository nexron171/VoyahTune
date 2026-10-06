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

/** Native dashboard tiles. Coordinates match the approved 1920×720 prototype. */
final class EnergyWidgetView extends View {
    static final String ENERGY = "energyWidget", CONSUMPTION = "energyConsumptionWidget", TRIP = "energyTripWidget",
            TIRES = "tirePressureWidget", ODO = "odometerWidget";
    static final String[] IDS = {ENERGY, CONSUMPTION, TRIP, TIRES, ODO};
    static final String[] NAMES = {"Заряд и топливо", "Расход за 2,5 км", "Текущая поездка", "Давление в шинах", "Общий пробег"};
    static final String[] COLORS = {"black", "white", "dark_gray", "dark_green", "burgundy", "gold_bronze", "sage_green"};
    static final String[] COLOR_NAMES = {"Чёрный", "Белый", "Тёмно-серый", "Тёмно-зелёный", "Бургунди", "Золотисто-бронзовый", "Серо-зелёный"};
    private static final int WHITE=EnergyWidgetStyle.WHITE, MUTED=EnergyWidgetStyle.MUTED, GREEN=EnergyWidgetStyle.GREEN,
            BLUE=EnergyWidgetStyle.BLUE, BORDER=EnergyWidgetStyle.BORDER;
    private static final Locale RU = new Locale("ru", "RU");
    // Native button: 52 dp, 12 dp bottom margin, and 8 dp above it.
    private static final float TRIP_HISTORY_FOOTER = 72;
    private static final ExecutorService IMAGES = Executors.newSingleThreadExecutor();
    private static final LruCache<String,EnergyCarImage> CACHE = new LruCache<>(2);
    private final String kind, color;
    private final int columns, rows;
    private final SharedPreferences prefs;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint carPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final float designW, designH;
    private float baseW,baseH;
    private EnergyChartLayout energyLayout;
    private EnergyConsumptionLayout consumptionLayout;
    private Bundle state = new Bundle();
    private EnergyCarImage car;
    private long tripMs=-1;
    private boolean tripHistoryVisible;
    private int window, selected=-1,selectedConsumption=-1;
    private EnergyConsumptionChart consumption=new EnergyConsumptionChart(0,null,null,null,null,null);
    private final Runnable clockTick=new Runnable(){@Override public void run(){invalidate();postDelayed(this,30_000);}};
    private float touchX, touchY, scale=1, offsetX, offsetY;

    static boolean isWidget(String id) {
        for (String s:IDS) if (s.equals(id)) return true;
        return false;
    }
    static int[] size(String id) {
        if (ENERGY.equals(id)) return new int[]{8,4};
        if (CONSUMPTION.equals(id)) return new int[]{2,2};
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
        this.columns=EnergyWidgetLayout.width(kind,columns);this.rows=EnergyWidgetLayout.height(kind,this.columns,rows);
        designW=EnergyWidgetLayout.pixelsWide(this.columns);designH=EnergyWidgetLayout.pixelsHigh(kind,this.rows);
        baseW=designW;baseH=designH;
        prefs=context.getSharedPreferences("DrivePreferences",Context.MODE_PRIVATE);
        window=EnergyWidgetSettings.window(prefs.getInt(EnergyWidgetSettings.WINDOW_KEY,EnergyWidgetSettings.DEFAULT_WINDOW_KM));
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
            float x=(touchX-offsetX)/scale, y=(touchY-offsetY)/scale;
            if(CONSUMPTION.equals(kind)) {
                EnergyConsumptionLayout chart=consumptionLayout;
                selectedConsumption=chart!=null&&chart.contains(x,y)&&selectedConsumption<0
                        ?consumption.nearest((x-chart.left)/(chart.right-chart.left)):-1;
                invalidate();return;
            }
            if(!ENERGY.equals(kind)) return;
            EnergyChartLayout chart=energyLayout;
            if(chart==null) return;
            int slot=chart.windowSlot(x,y);
            if(slot>=0) {
                window=EnergyWidgetSettings.WINDOWS[slot];selected=-1;
                prefs.edit().putInt(EnergyWidgetSettings.WINDOW_KEY,window).apply();
            } else if(chart.containsHistory(x,y)) {
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
            } else selected=-1;
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
        if(CONSUMPTION.equals(kind)) {
            consumption=new EnergyConsumptionChart(recordedKm(),state.getDoubleArray(EnergyWidgetProtocol.CONSUMPTION_START),
                    state.getDoubleArray(EnergyWidgetProtocol.CONSUMPTION_END),state.getFloatArray(EnergyWidgetProtocol.CONSUMPTION_EV),
                    state.getFloatArray(EnergyWidgetProtocol.CONSUMPTION_FUEL),state.getBooleanArray(EnergyWidgetProtocol.CONSUMPTION_BREAK));
            selectedConsumption=-1;
            for(int i=0;i<consumption.end.length;i++)if(consumption.end[i]==selectedEnd){selectedConsumption=i;break;}
        }
        selected=-1;
        float[] next=array(EnergyWidgetProtocol.HISTORY_X,0);
        for(int i=0;i<next.length;i++) if(next[i]==selectedKm) {selected=i;break;}
        setContentDescription(description()); invalidate();
    }
    void timer(long ms) {tripMs=ms; if(TRIP.equals(kind)) invalidate();}
    void showTripHistory(boolean visible) {
        boolean show = TRIP.equals(kind) && visible;
        if (tripHistoryVisible != show) {tripHistoryVisible=show; invalidate();}
    }
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
    private float tripValue(String key,int index) {
        float[] a=array(key,index+1);
        return state.getInt(EnergyWidgetProtocol.SCHEMA)==EnergyWidgetProtocol.VERSION&&a.length>index?a[index]:Float.NaN;
    }
    private double recordedKm() {
        float[] x=array(EnergyWidgetProtocol.HISTORY_X,0);double fallback=x.length==0?0:x[x.length-1];
        double km=state.getDouble(EnergyWidgetProtocol.RECORDED_KM,fallback);
        return Double.isFinite(km)&&km>=0?km:fallback;
    }
    private static String num(float value) {return Float.isFinite(value)?String.format(RU,"%.1f",value):"—";}
    private static String mileage(double value) {return Double.isFinite(value)?String.format(RU,"%,.0f",value):"—";}
    private String description() {
        if(CONSUMPTION.equals(kind))return "Расход электричества и бензина за последние 2,5 км. Шаг 100 метров";
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
        float availableHeight=Math.max(0,getHeight()-(tripHistoryVisible?TRIP_HISTORY_FOOTER*getResources().getDisplayMetrics().density:0));
        boolean energyTile=ENERGY.equals(kind)||CONSUMPTION.equals(kind)||TRIP.equals(kind);
        if(energyTile) {
            scale=getResources().getDisplayMetrics().density;
            baseW=getWidth()/scale;baseH=availableHeight/scale;offsetX=offsetY=0;
        } else {
            baseW=designW;baseH=designH;
            scale=Math.min(getWidth()/baseW,availableHeight/baseH);
            offsetX=(getWidth()-baseW*scale)/2;offsetY=(availableHeight-baseH*scale)/2;
        }
        c.save();c.translate(offsetX,offsetY);c.scale(scale,scale);
        if(ENERGY.equals(kind)) drawEnergy(c);
        else if(CONSUMPTION.equals(kind)) drawConsumption(c);
        else if(TRIP.equals(kind)) drawTrip(c,baseH);
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
    private float font(int resource) { return getResources().getDimension(resource)/scale; }
    private float valueFont() { return font(R.dimen.energy_value_text_size); }
    private float labelFont() { return font(R.dimen.energy_label_text_size); }
    private float unitFont() { return font(R.dimen.energy_unit_text_size); }
    private float captionFont() { return font(R.dimen.energy_caption_text_size); }
    private float valueWithUnit(Canvas c,String value,String unit,float x,float y,float size,float unitSize,int color) {
        text(c,value,x,y,size,color,false);
        float next=x+measured(value,size)+6;
        text(c,unit,next,y,unitSize,color,false);
        return next+measured(unit,unitSize);
    }
    private static String estimate(float value){return EnergyWidgetStyle.estimate(value);}
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
    private void drawTrip(Canvas c,float height) {
        boolean vertical=EnergyWidgetLayout.vertical(kind,columns),compact=columns<=6;
        float f=getResources().getDisplayMetrics().scaledDensity/getResources().getDisplayMetrics().density;
        float pad=18,rightEdge=baseW-pad,content=baseW-2*pad;
        text(c,"Текущая поездка",pad,28*f,font(R.dimen.energy_title_text_size),WHITE,true);
        if(tripMs<0) {
            if(vertical)text(c,"Ожидание статуса",pad,60*f,labelFont(),MUTED,false);
            else right(c,"Ожидание статуса",rightEdge,28*f,labelFont(),MUTED);
        }
        String time="—";
        if(tripMs>=0){long s=tripMs/1000;time=String.format(Locale.US,"%02d:%02d:%02d",s/3600,(s/60)%60,s%60);}
        String[] labels={"Время в пути","Пробег","Электричество","Бензин"};
        String[] values={time,num(tripValue(EnergyWidgetProtocol.TRIP,0)),estimate(tripValue(EnergyWidgetProtocol.TRIP,1)),estimate(tripValue(EnergyWidgetProtocol.TRIP,2))};
        String[] units={"","км","кВт·ч/100 км","л/100 км"};
        float first=vertical?86*f:compact?76*f:Math.max(62*f,height-96*f);
        float step=vertical?(height-first-134*f)/3:compact?height-first-112*f:0;
        for(int i=0;i<4;i++) {
            int row=vertical?i:compact?i%2:0,column=vertical?0:compact?i/2:i;
            float cell=vertical?content:content/(compact?2:4),x=pad+column*cell;
            float labelY=first+row*step,valueY=labelY+46*f;
            text(c,labels[i],x,labelY,labelFont(),MUTED,false);
            int color=i==2?GREEN:i==3?BLUE:WHITE;
            metric(c,values[i],units[i],x,valueY,vertical?cell:cell-12,color);
            if(!vertical&&column>0)line(c,x-10,labelY-20*f,x-10,valueY+16*f,BORDER,1);
        }
        float evKm=tripValue(EnergyWidgetProtocol.TRIP_OBSERVED_KM,0),fuelKm=tripValue(EnergyWidgetProtocol.TRIP_OBSERVED_KM,1);
        String note=!live()?"Нет связи с автомобилем":Math.max(evKm,fuelKm)<1?"Средние после 1 км наблюдения":
                Math.abs(evKm-fuelKm)<.1?"Учтено "+num(Math.min(evKm,fuelKm))+" км":
                "Учтено: электро "+num(evKm)+", бензин "+num(fuelKm)+" км";
        wrapped(c,note,pad,height-(vertical?36:4)*f,captionFont(),content,MUTED);
    }
    private void metric(Canvas c,String value,String unit,float x,float baseline,float width,int color) {
        float valueWidth=measured(value,valueFont());
        text(c,value,x,baseline,valueFont(),color,false);
        if(unit.isEmpty())return;
        if(valueWidth+8+measured(unit,unitFont())<=width)
            text(c,unit,x+valueWidth+8,baseline,unitFont(),color,false);
        else text(c,unit,x,baseline+26*(unitFont()/20),unitFont(),color,false);
    }
    private float wrapped(Canvas c,String value,float x,float baseline,float size,float width,int color) {
        String line="";
        for(String word:value.split(" ")) {
            String next=line.isEmpty()?word:line+" "+word;
            if(!line.isEmpty()&&measured(next,size)>width) {
                text(c,line,x,baseline,size,color,false);baseline+=size*1.25f;line=word;
            } else line=next;
        }
        if(!line.isEmpty())text(c,line,x,baseline,size,color,false);
        return baseline;
    }
    private void drawEnergy(Canvas c) {
        boolean vertical=EnergyWidgetLayout.vertical(kind,columns);
        float f=getResources().getDisplayMetrics().scaledDensity/getResources().getDisplayMetrics().density;
        EnergyChartLayout chart=new EnergyChartLayout(baseW,baseH,vertical,f);energyLayout=chart;
        float pad=chart.pad,button=chart.buttonWidth,buttonsLeft=chart.buttonLeft;
        text(c,"Заряд и топливо",pad,32*f,font(R.dimen.energy_title_text_size),WHITE,true);
        for(int i=0;i<3;i++) {
            int range=EnergyWidgetSettings.WINDOWS[i];float x=buttonsLeft+i*button;
            paint.setColor(range==window?0xff414b5c:0xff1d212a);c.drawRoundRect(x,chart.buttonTop,x+button-4,chart.buttonBottom,9,9,paint);
            String label=range+" км";
            float control=font(R.dimen.energy_control_text_size);
            text(c,label,x+(button-4-measured(label,control))/2,chart.buttonBottom-12*f,control,range==window?WHITE:MUTED,false);
        }
        float[] distances=array(EnergyWidgetProtocol.HISTORY_X,0),ev=array(EnergyWidgetProtocol.HISTORY_EV,distances.length),fuel=array(EnergyWidgetProtocol.HISTORY_FUEL,distances.length);
        boolean[] gaps=state.getBooleanArray(EnergyWidgetProtocol.HISTORY_BREAK);
        int n=Math.min(distances.length,Math.min(ev.length,fuel.length));
        EnergyChartAxis axis=new EnergyChartAxis(recordedKm(),window,current(EnergyWidgetProtocol.ODOMETER,0));
        float currentEv=current(EnergyWidgetProtocol.LEVELS,0),currentFuel=current(EnergyWidgetProtocol.LEVELS,1);
        boolean hasCurrent=Float.isFinite(currentEv)||Float.isFinite(currentFuel);
        if(selected>=0&&selected<n){currentEv=ev[selected];currentFuel=fuel[selected];}
        float batteryCapacity=capacity(EnergyWidgetProtocol.BATTERY_KWH,43),tankCapacity=capacity(EnergyWidgetProtocol.TANK_LITERS,56);
        float gap=12,cell=(baseW-2*pad-(vertical?1:3)*gap)/(vertical?2:4);
        for(int i=0;i<2;i++) {
            int color=i==0?GREEN:BLUE;float value=i==0?currentEv:currentFuel;
            float x=pad+(vertical?i:2*i)*(cell+gap);
            text(c,i==0?"Батарея, %":"Топливо, %",x,chart.readingLabel,labelFont(),MUTED,false);
            text(c,num(value),x,chart.readingValue,valueFont(),color,false);
            float restX=vertical?x:x+cell+gap;
            String rest=(cell<180?"Ост., ":"Осталось, ")+(i==0?"кВт·ч":"л");
            text(c,rest,restX,chart.remainingLabel,labelFont(),MUTED,false);
            text(c,estimate(value*(i==0?batteryCapacity:tankCapacity)/100),restX,chart.remainingValue,valueFont(),color,false);
        }
        String status=selected>=0?(Double.isFinite(axis.odometerAt(distances[selected]))?"Пробег ~"+mileage(axis.odometerAt(distances[selected]))+" км":"Пробег недоступен"):
                !live()?"Нет связи":hasCurrent?"":"Нет свежих данных";
        if(!status.isEmpty())right(c,status,chart.split,chart.levelsTitle,captionFont(),MUTED);
        float left=chart.left,right=chart.right,top=chart.top,bottom=chart.bottom;
        int ticks=bottom-top<48?1:bottom-top<100?2:4;
        for(int i=0;i<=ticks;i++){float y=top+(bottom-top)*i/ticks;line(c,left,y,right,y,BORDER,1);
            String label=(100-100*i/ticks)+"%";
            right(c,label,left-8,y+5,captionFont(),MUTED);
            text(c,label,right+8,y+5,captionFont(),MUTED,false);}
        int intervals=Math.max(2,Math.min(6,(int)((right-left)/(110*f))));
        double endOdo=axis.tick(0);float endWidth=measured(mileage(endOdo),captionFont()),endLeft=right-endWidth,tickRight=Float.NEGATIVE_INFINITY;
        for(int i=intervals;i>=0;i--) {
            float x=right-(right-left)*i/intervals;
            String label=mileage(endOdo-window*i/(double)intervals);float labelWidth=measured(label,captionFont());
            float start=i==intervals?x:i==0?x-labelWidth:x-labelWidth/2;
            boolean endpoint=i==0||(i==intervals&&labelWidth+endWidth+12<=right-left);
            boolean middle=i>0&&i<intervals&&start>=tickRight+12&&start+labelWidth<=endLeft-12;
            if(endpoint||middle){text(c,label,start,chart.axis,captionFont(),MUTED,false);tickRight=start+labelWidth;}
        }
        if(n<2)wrapped(c,!live()?"Нет истории":"Ожидание истории",left+6,(top+bottom)/2+5,labelFont(),right-left-12,MUTED);
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
            paint.setColor(color);paint.setStrokeWidth(EnergyWidgetStyle.LINE_WIDTH);paint.setStyle(Paint.Style.STROKE);
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
        EnergyPeriodEstimate period=EnergyPeriodEstimate.recent(distances,
                state.getDoubleArray(EnergyWidgetProtocol.HISTORY_EV_DROP),state.getDoubleArray(EnergyWidgetProtocol.HISTORY_FUEL_DROP),
                state.getDoubleArray(EnergyWidgetProtocol.HISTORY_EV_KM),state.getDoubleArray(EnergyWidgetProtocol.HISTORY_FUEL_KM),batteryCapacity,tankCapacity,
                state.getDoubleArray(EnergyWidgetProtocol.HISTORY_START_EV_DROP),state.getDoubleArray(EnergyWidgetProtocol.HISTORY_START_FUEL_DROP),
                state.getDoubleArray(EnergyWidgetProtocol.HISTORY_START_EV_KM),state.getDoubleArray(EnergyWidgetProtocol.HISTORY_START_FUEL_KM));
        int averageWindow=EnergyWidgetSettings.AVERAGE_WINDOW_KM;
        float coverage=Math.min(period.batteryKm,period.fuelKm);
        String note=Math.max(period.batteryKm,period.fuelKm)<1?"Нужно от 1 км истории"
                :Math.abs(period.batteryKm-period.fuelKm)>.1f?"Эл. "+num(period.batteryKm)+" · Бенз. "+num(period.fuelKm)+" из "+averageWindow+" км"
                :coverage<averageWindow-.1f?"Учтено "+num(coverage)+" из "+averageWindow+" км":"";
        float periodWidth=chart.split-pad,periodCell=(periodWidth-12)/2;
        text(c,"Средний расход · "+averageWindow+" км",pad,chart.periodLabel,labelFont(),MUTED,false);
        text(c,"кВт·ч/100 км",pad,chart.periodUnits,unitFont(),GREEN,false);
        text(c,"л/100 км",pad+periodCell+12,chart.periodUnits,unitFont(),BLUE,false);
        text(c,estimate(period.battery),pad,chart.periodValue,valueFont(),GREEN,false);
        text(c,estimate(period.fuel),pad+periodCell+12,chart.periodValue,valueFont(),BLUE,false);
        if(!note.isEmpty())text(c,note,pad,chart.periodNote,captionFont(),MUTED,false);
    }
    private static String axisQuantity(double value) {
        if(value==0)return "0";
        if(Math.abs(value)>=1000||Math.abs(value)<.001)return String.format(RU,"%.1e",value);
        return String.format(RU,"%.3f",value).replaceAll("0+$","").replaceAll(",$","");
    }
    private void drawConsumption(Canvas c) {
        float f=getResources().getDisplayMetrics().scaledDensity/getResources().getDisplayMetrics().density;
        EnergyConsumptionLayout chart=new EnergyConsumptionLayout(baseW,baseH,columns,rows,f);consumptionLayout=chart;
        float left=chart.left,right=chart.right,top=chart.top,bottom=chart.bottom,axisSize=(chart.compact?10:EnergyWidgetStyle.CHART_AXIS_SP)*f;
        float titleSize=(chart.compact?18:EnergyWidgetStyle.CHART_TITLE_SP)*f,unitSize=(chart.compact?12:EnergyWidgetStyle.CHART_UNIT_SP)*f;
        text(c,"Расход",chart.textInset,chart.title,titleSize,WHITE,false);
        float litersWidth=measured("л",unitSize);
        right(c,"кВт·ч",baseW-chart.textInset-litersWidth-12,chart.title,unitSize,GREEN);
        right(c,"л",baseW-chart.textInset,chart.title,unitSize,BLUE);
        String step=Math.round(EnergyWidgetProtocol.CONSUMPTION_STEP_KM*1000)+" м";
        float zero=top+(float)consumption.zero()*(bottom-top);
        paint.setColor(0x0970e1ab);c.drawRect(left,zero,right,bottom,paint);
        line(c,left,top,right,top,BORDER,1);line(c,left,bottom,right,bottom,BORDER,1);
        line(c,left,zero,right,zero,0xff616b7b,1);
        if(chart.axes) {
            if(consumption.electricMax>0)right(c,axisQuantity(consumption.electricMax),left-5,top+3,axisSize,GREEN);
            right(c,"0",left-5,zero+3,axisSize,GREEN);
            if(consumption.electricMin<0)right(c,axisQuantity(consumption.electricMin),left-5,bottom+3,axisSize,GREEN);
            if(consumption.fuelMax>0)text(c,axisQuantity(consumption.fuelMax),right+5,top+3,axisSize,BLUE,false);
            text(c,"0",right+5,zero+3,axisSize,BLUE,false);
        }
        int count=consumption.end.length,marker=selectedConsumption>=0?selectedConsumption:count-1;
        for(int series=0;series<2;series++) {
            final boolean electric=series==0;float[] values=electric?consumption.battery:consumption.fuel;
            int color=electric?GREEN:BLUE;Path path=new Path();
            EnergyChartCurve.trace(consumption.position,values,consumption.gaps,count,0,electric?Double.NEGATIVE_INFINITY:0,
                    Double.POSITIVE_INFINITY,new EnergyChartCurve.Sink() {
                private float x(double position){return left+(float)(position/EnergyConsumptionChart.WINDOW)*(right-left);}
                private float y(double value){return top+(float)(electric?consumption.electricPosition(value):consumption.fuelPosition(value))*(bottom-top);}
                @Override public void moveTo(double position,double value){path.moveTo(x(position),y(value));}
                @Override public void cubicTo(double x1,double y1,double x2,double y2,double x,double y){path.cubicTo(x(x1),y(y1),x(x2),y(y2),x(x),y(y));}
            });
            paint.setColor(color);paint.setStrokeWidth(2);paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);c.drawPath(path,paint);
            paint.setStrokeCap(Paint.Cap.BUTT);paint.setStrokeJoin(Paint.Join.MITER);paint.setStyle(Paint.Style.FILL);
            if(marker>=0&&Float.isFinite(values[marker])) {
                float x=left+(float)(consumption.position[marker]/EnergyConsumptionChart.WINDOW)*(right-left);
                float y=top+(float)(electric?consumption.electricPosition(values[marker]):consumption.fuelPosition(values[marker]))*(bottom-top);
                c.drawCircle(x,y,chart.markerRadius,paint);
            }
        }
        if(selectedConsumption>=0){float x=left+(float)(consumption.position[selectedConsumption]/EnergyConsumptionChart.WINDOW)*(right-left);line(c,x,top,x,bottom,0xff7b8799,1);}
        if(count==0)wrapped(c,!live()?"Нет данных":"Ожидание "+step,left,(top+bottom)/2+5,(chart.compact?14:EnergyWidgetStyle.CHART_MESSAGE_SP)*f,right-left,MUTED);
    }
}
