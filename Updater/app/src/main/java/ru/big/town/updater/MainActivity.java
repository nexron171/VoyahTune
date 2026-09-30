package ru.big.town.updater;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** External intents only open this menu; root owns all downloads and installation. */
public final class MainActivity extends Activity {
    private static final String OPEN_INITIAL_SCREEN = "ru.big.town.updater.OPEN_INITIAL_SCREEN";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final RootClient client = new RootClient();
    private final Handler poll = new Handler(Looper.getMainLooper());
    private boolean polling, actionBusy, resumed, connected, dnsSupported, resetCompletedOnOpen, hideCompletedResult;
    private String connectionError = "", commandError = "", noticeShowing;
    private JSONObject state = new JSONObject(), settings = new JSONObject();
    private UpdatePresentation presentation;
    private ProgressBar progress;
    private Button primary, secondary;
    private AlertDialog settingsDialog, noticeDialog;
    private Button settingsSave, settingsRepeat;
    private TextView settingsMessage;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (!polling && !actionBusy) refresh();
            poll.postDelayed(this, 2000);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        resetCompletedOnOpen = getIntent().getBooleanExtra(OPEN_INITIAL_SCREEN, false);
        setContentView(R.layout.activity_updater);
        applyWindowInsets(findViewById(R.id.root));
        progress = findViewById(R.id.progress);
        primary = findViewById(R.id.primary);
        secondary = findViewById(R.id.secondary);
        primary.setOnClickListener(v -> primaryAction());
        secondary.setOnClickListener(v -> perform("check", false));
        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.settings).setOnClickListener(v -> openSettings());
        render();
    }
    private void applyWindowInsets(View root) {
        // Qinggan's dock overlays the app without a reported inset. Keep the
        // already verified inset contract; layout content never covers the dock.
        final int dock = dp(145);
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        final int statusBar = id > 0 ? getResources().getDimensionPixelSize(id) : 0;
        root.setPadding(dock, statusBar, 0, 0);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            int left = dock + bars.left, top = bars.top > 0 ? bars.top : statusBar;
            if (view.getPaddingLeft()!=left || view.getPaddingTop()!=top || view.getPaddingRight()!=bars.right || view.getPaddingBottom()!=bars.bottom)
                view.setPadding(left, top, bars.right, bars.bottom);
            return insets;
        });
        root.requestApplyInsets();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        resetCompletedOnOpen = intent.getBooleanExtra(OPEN_INITIAL_SCREEN, false);
        hideCompletedResult=false;
        if (!polling && !actionBusy) refresh();
    }
    @Override protected void onResume() { super.onResume(); resumed=true; poll.removeCallbacks(tick); poll.post(tick); }
    @Override protected void onPause() { resumed=false; poll.removeCallbacks(tick); super.onPause(); }
    @Override protected void onDestroy() {
        poll.removeCallbacks(tick);
        if (settingsDialog!=null) settingsDialog.dismiss();
        if (noticeDialog!=null) noticeDialog.dismiss();
        worker.shutdown(); super.onDestroy();
    }
    private boolean alive() { return !isDestroyed() && !isFinishing(); }
    private JSONObject request(String command) throws Exception { return new JSONObject().put("command",command); }
    private void accept(JSONObject response) throws Exception {
        state=response.getJSONObject("state");
        if(!"committed".equals(state.optString("phase"))) hideCompletedResult=false;
        JSONObject config=response.optJSONObject("settings");
        if(config!=null) settings=config;
        JSONArray capabilities=response.optJSONArray("capabilities");
        dnsSupported=false;
        if(capabilities!=null) for(int i=0;i<capabilities.length();i++) if("dns-settings".equals(capabilities.optString(i))) dnsSupported=true;
        connected=true; connectionError="";
        if(!response.isNull("settingsError")) commandError=response.optString("settingsError");
        render();
        if(resumed && settingsDialog==null && !actionBusy) showNotice();
    }
    private void refresh() {
        if(polling || worker.isShutdown()) return;
        // Finish only a completed update. The daemon keeps active work and repair errors intact.
        if(resetCompletedOnOpen && !actionBusy) {
            resetCompletedOnOpen=false;
            connected=false;
            connectionError="";
            state=new JSONObject();
            try { command(request("finish"), result -> {}); }
            catch(Exception e) { commandError=reason(e); render(); }
            return;
        }
        polling=true; // Transport bookkeeping never changes button styling.
        worker.execute(() -> {
            try {
                JSONObject result=client.call(request("status"));
                runOnUiThread(() -> { polling=false; if(!alive())return; try{accept(result);}catch(Exception e){disconnected(e);} });
            } catch(Exception e) { runOnUiThread(() -> {polling=false;if(alive())disconnected(e);}); }
        });
    }
    private void disconnected(Exception e) { connected=false; connectionError=reason(e); render(); }
    private interface Result { void apply(JSONObject result) throws Exception; }
    private void command(JSONObject input, Result result) {
        if(actionBusy || worker.isShutdown())return;
        actionBusy=true; commandError=""; render();
        worker.execute(() -> {
            try {
                JSONObject response;
                boolean legacyFinish=false;
                try { response=client.call(input); }
                catch(UnsupportedOperationException unsupported) {
                    if(!"finish".equals(input.optString("command"))) throw unsupported;
                    // Older daemons keep the durable result. Only its UI presentation is reset;
                    // the supported check command starts a new workflow when the owner asks.
                    response=new JSONObject(); legacyFinish=true;
                }
                final JSONObject reply=response;
                final boolean hideLegacyResult=legacyFinish;
                JSONObject fresh=client.call(request("status"));
                runOnUiThread(() -> {
                    actionBusy=false; if(!alive())return;
                    hideCompletedResult=hideCompletedResult||hideLegacyResult;
                    try{accept(fresh);result.apply(reply);}catch(Exception e){commandError=reason(e);}
                    render(); if(!polling)refresh();
                });
            } catch(Exception e) {
                runOnUiThread(() -> {actionBusy=false;if(!alive())return;commandError=reason(e);if(settingsDialog!=null&&settingsMessage!=null)settingsMessage.setText(commandError);render();if(!polling)refresh();});
            }
        });
    }
    private void perform(String name, boolean same) {
        try {
            JSONObject input=request(name);
            if("check".equals(name))input.put("same_version",same);
            command(input,result -> {});
        } catch(Exception e){commandError=reason(e);render();}
    }
    private void primaryAction() {
        if(!connected){refresh();return;}
        if(presentation==null)return;
        switch(presentation.command){
            case "finish": perform("finish",false); break;
            case "close": finish(); break;
            case "apply": confirmInstall(); break;
            case "check": perform("check",false); break;
            case "download": perform("download",false); break;
            default: break;
        }
    }
    private void confirmInstall() {
        String dns="";
        if(dnsSupported && !settings.isNull("dnsEnabled")) dns="\n\nDNS: "+(settings.optBoolean("dnsEnabled")?"Яндекс":"стандартный")+".";
        new AlertDialog.Builder(this).setTitle("Установить обновление?")
            .setMessage("Автомобиль должен стоять в P с включённым питанием. Головное устройство перезагрузится. Сохраняйте питание до завершения проверки запуска.\n\nНастройки VoyahTune сохранятся. При ошибке установите релиз через USB с компьютера."+dns)
            .setNegativeButton("Отмена",null).setPositiveButton("Установить и перезагрузить",(d,w)->perform("apply",false)).show();
    }
    private void render() {
        String phase=state.optString("phase","idle"), step=state.optString("step");
        String menuPhase=UpdatePresentation.menuPhase(phase,hideCompletedResult);
        boolean completedHidden=!menuPhase.equals(phase);
        if(completedHidden){phase=menuPhase;step="Готово к проверке обновлений";}
        JSONObject selected=completedHidden?null:state.optJSONObject("selected");
        JSONObject archive=selected==null?null:selected.optJSONObject("payload");
        presentation=UpdatePresentation.from(phase,selected!=null,step,state.optLong("bytes"),state.optLong("total"),state.optLong("completedSteps"),state.optLong("totalSteps"));
        UpdatePresentation p=presentation;
        String installed=state.optString("installedVersion","—");
        boolean failed="repair-required".equals(phase)||"failed".equals(phase);
        String error=!state.isNull("error")?state.optString("error"):commandError;
        if(!connected)error=connectionError;
        text(R.id.service,connected?"●  Служба доступна":connectionError.isEmpty()?"Подключение…":"●  Нет связи со службой");
        text(R.id.installed,"Установлено: "+installed);
        text(R.id.eyebrow,!connected?"СЛУЖБА ОБНОВЛЕНИЙ":p.eyebrow);
        text(R.id.title,!connected?(connectionError.isEmpty()?"Подключаемся к службе":"Служба обновления недоступна"):p.title);
        text(R.id.subtitle,!connected?"Если установка уже шла, её результат пока неизвестен.":p.subtitle);
        text(R.id.release,"VoyahTune "+(selected==null?installed:selected.optString("version")));
        text(R.id.meta,selected==null?"Установленная версия": "Релиз "+selected.optString("version")+(archive==null?"":" · "+archive.optLong("size")/(1024*1024)+" МБ")+(state.optBoolean("sameVersion")?" · Повторная установка":""));
        text(R.id.badge,!connected?"Нет связи":p.badge);
        text(R.id.detail,error);visible(R.id.detail,!error.isEmpty());
        ((TextView)findViewById(R.id.detail)).setTextColor(getColor(R.color.error));
        visible(R.id.meter,connected&&p.meter);
        if(progress.isIndeterminate()!=p.indeterminate)progress.setIndeterminate(p.indeterminate);
        if(!p.indeterminate&&progress.getProgress()!=p.percent)progress.setProgress(p.percent,true);
        text(R.id.progress_label,p.progressLabel);text(R.id.progress_note,p.progressNote);
        text(R.id.percent,p.percent+"%");visible(R.id.percent,!p.indeterminate);
        String[] labels={"Новый релиз","Скачивание","Установка","Готово"};int[] ids={R.id.nav0,R.id.nav1,R.id.nav2,R.id.nav3};
        for(int i=0;i<ids.length;i++){
            boolean done=connected&&(i<p.nav||p.success);
            text(ids[i],(done?"✓":new String[]{"①","②","③","④"}[i])+"  "+labels[i]);
            TextView view=findViewById(ids[i]);int color=getColor(connected&&(done||i==p.nav)?R.color.teal:R.color.muted);
            if(view.getCurrentTextColor()!=color)view.setTextColor(color);
        }
        boolean installing=p.busy&&p.nav==2;
        text(R.id.aside_title,failed||!error.isEmpty()?"Установите через USB":installing?"Сохраняйте питание":p.success?"Всё на месте":"Настройки останутся с вами");
        text(R.id.aside_text,failed||!error.isEmpty()?"Установите релиз через USB с компьютера. Причина ошибки показана на экране.":installing?"Оставьте автомобиль в P. Не выключайте головное устройство до завершения установки и проверки запуска.":p.success?"Настройки VoyahTune сохранены. Можно вернуться к привычным функциям приложения.":"Обновление сохраняет настройки VoyahTune.\nПеред установкой переведите автомобиль в P и сохраняйте питание до завершения.");
        text(R.id.footer_note,failed?"Для восстановления потребуется USB и компьютер.":installing?"Обновление выполняется автономно. Не отключайте питание.":p.success?"Установка и проверка запуска завершены.":"Проверка новых версий — автоматически, не чаще одного раза в 24 часа.");
        text(R.id.primary,!connected?"Обновить состояние":p.primary+("download".equals(p.command)&&archive!=null?" · "+archive.optLong("size")/(1024*1024)+" МБ":""));
        enabled(primary,!actionBusy&&(!connected||!p.busy));
        visible(R.id.secondary,connected&&p.secondary);enabled(secondary,!actionBusy&&!p.busy);
        if(settingsDialog!=null){
            if(settingsSave!=null)enabled(settingsSave,!actionBusy&&!p.busy);
            if(settingsRepeat!=null)enabled(settingsRepeat,!actionBusy&&!p.busy);
        }
        enabled(findViewById(R.id.settings),connected&&!p.busy&&!actionBusy&&!"repair-required".equals(phase));
    }

    private void openSettings() {
        if(settingsDialog!=null)return;
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(24),dp(12),dp(24),0);
        label(content,"Адрес каталога релизов",18);
        EditText url=new EditText(this);url.setSingleLine(true);url.setText(settings.optString("catalogUrl"));url.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);content.addView(url);
        label(content,"Смена адреса не запускает скачивание или установку.",15);
        Switch dns=new Switch(this);dns.setText("Яндекс DNS");dns.setTextSize(20);dns.setPadding(0,dp(18),0,dp(18));dns.setMinHeight(dp(60));content.addView(dns);dns.setEnabled(false);
        TextView dnsInfo=label(content,dnsSupported?"Определяем текущий DNS…":"Настройка DNS доступна после обновления root-службы через USB.",16);
        final boolean[] known={false};
        dns.setOnCheckedChangeListener((button,on)->{if(known[0])dnsInfo.setText(on?"При установке релиза будет включён Яндекс DNS.":"При установке релиза будет использован стандартный DNS.");});
        Button repeat=new Button(this);repeat.setAllCaps(false);repeat.setText("Проверить релиз для повторной установки");content.addView(repeat);
        label(content,"Позволяет скачать и установить ту же версию VoyahTune.",15);
        ScrollView scroll=new ScrollView(this);scroll.addView(content);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Настройки обновлений").setView(scroll).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null).create();
        settingsDialog=dialog;settingsRepeat=repeat;settingsMessage=dnsInfo;
        dialog.setOnDismissListener(d->{if(settingsDialog==dialog){settingsDialog=null;settingsSave=null;settingsRepeat=null;settingsMessage=null;}});
        dialog.show();settingsSave=dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        repeat.setOnClickListener(v->{dialog.dismiss();perform("check",true);});
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(actionBusy)return;
            try{
                JSONObject input=request(dnsSupported?"set_settings":"set_catalog_url").put("url",url.getText().toString().trim());
                if(dnsSupported)input.put("dns_enabled",known[0]?dns.isChecked():JSONObject.NULL);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                command(input,result->{settings=result.getJSONObject("settings");dialog.dismiss();Toast.makeText(this,"Настройки сохранены",Toast.LENGTH_SHORT).show();});
                // Errors remain visible inside the dialog rather than hidden behind it.
            }catch(Exception e){dnsInfo.setText(reason(e));}
        });
        if(dnsSupported){
            try{
                command(request("get_settings"),result->{
                    if(!dialog.isShowing())return;
                    JSONObject config=result.optJSONObject("settings");if(config!=null)settings=config;
                    String current=result.optString("dnsStatus");known[0]="on".equals(current)||"off".equals(current);
                    dns.setChecked(known[0]&&(!settings.isNull("dnsEnabled")?settings.optBoolean("dnsEnabled"):"on".equals(current)));
                    dns.setEnabled(known[0]);
                    dnsInfo.setText(known[0]?"Сейчас на ГУ: "+("on".equals(current)?"Яндекс DNS":"стандартный DNS")+". Выбор применяется при установке релиза.":"DNS не определён или изменён извне. Оставляем без изменений."+(!result.isNull("dnsError")?"\n"+result.optString("dnsError"):""));
                });
            }catch(Exception e){dnsInfo.setText(reason(e));}
        }
    }
    private void showNotice() {
        if(state.isNull("notice")){noticeShowing=null;return;}
        String notice=state.optString("notice");
        if(hideCompletedResult && "success".equals(notice))return;
        if(notice.equals(noticeShowing)||noticeDialog!=null)return;
        noticeShowing=notice;
        String title="error".equals(notice)?"Ошибка обновления VoyahTune":"success".equals(notice)?"VoyahTune обновлён":"Доступна новая версия VoyahTune";
        String message="error".equals(notice)?state.optString("error")+"\nУстановите релиз через USB с компьютера.":"success".equals(notice)?"Проверка запуска служб завершена успешно.":"Версия "+notice+". Скачать её можно в меню обновления.";
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Открыть меню",(d,w)->{}).setNegativeButton("Скрыть",(d,w)->finish()).create();
        noticeDialog=dialog;
        dialog.setOnDismissListener(d->{noticeDialog=null;if(!worker.isShutdown())worker.execute(()->{try{client.call(request("dismiss"));}catch(Exception ignored){}});});
        dialog.setOnCancelListener(d->finish());dialog.show();
    }
    private TextView label(LinearLayout parent,String text,int size){TextView view=new TextView(this);view.setText(text);view.setTextColor(getColor(R.color.muted));view.setTextSize(size);view.setPadding(0,dp(8),0,dp(8));parent.addView(view);return view;}
    private void text(int id,String value){TextView view=findViewById(id);if(!TextUtils.equals(view.getText(),value))view.setText(value);}
    private void visible(int id,boolean visible){View v=findViewById(id);int target=visible?View.VISIBLE:View.GONE;if(v.getVisibility()!=target)v.setVisibility(target);}
    private void enabled(View v,boolean enabled){if(v.isEnabled()!=enabled)v.setEnabled(enabled);}
    private String reason(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
