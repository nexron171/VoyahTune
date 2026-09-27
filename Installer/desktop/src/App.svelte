<script lang="ts">
  import { onMount } from 'svelte';
  import { command, subscribe, onCloseBlocked, onPayloadProgress, failure, type CatalogState, type Release, type CachedPayload, type PayloadProgress, type Action, type Dns, type Device, type Plan, type Event, type Failure } from './api';
  let screen = $state(0), action = $state<Action|null>(null), dns = $state<Dns>('keep');
  let devices = $state<Device[]>([]), busy = $state(false), confirmed = $state(false), removeConfirmed = $state(false);
  let plan = $state<Plan|null>(null), error = $state<Failure|null>(null), events = $state<Event[]>([]);
  let date = $state(''), code = $state(''), manualDate = $state(false), logOpen = $state(false), stopRequested = $state(false);
  let outcome = $state<'running'|'success'|'failed'|'cancelled'|'paused'>('running'), reportPath = $state('');
  let closeNotice = $state(false), eventsReady = $state(false), eventError = $state<Failure|null>(null);
  let completed = $state<string[]>([]), current = $state<string|undefined>();
  let canbusNotice = $state(''), canbusAnswerPending = $state(false), stopMessage = $state('');
  let localPath = $state('');
  let releasePath = $state(''), releaseVersion = $state('');
  let catalog = $state<CatalogState|null>(null), selectedVersion = $state('');
  let downloadBusy = $state(false), downloadProgress = $state<PayloadProgress|null>(null);
  type ReleaseRow = {version:string; release?:Release; cached?:CachedPayload};
  const releaseRows = $derived.by(() => {
    const stable = catalog?.catalog.releases.filter(r=>r.channel==='stable') || [];
    const cached = catalog?.cached.filter(c=>!c.version.includes('-')) || [];
    const rows:ReleaseRow[] = stable.map(release=>({version:release.version,release,cached:cached.find(c=>c.path.replaceAll('\\','/').endsWith('/'+release.payload.sha256))}));
    for(const entry of cached) if(!rows.some(row=>row.cached?.path===entry.path)) rows.push({version:entry.version,cached:entry});
    return rows.sort((a,b)=>b.version.localeCompare(a.version,undefined,{numeric:true}));
  });
  const nextBlocked = $derived(busy ? (downloadBusy?'Дождитесь загрузки релиза':'Дождитесь завершения проверки') : !action ? 'Не выбрано действие' : action!=='remove'&&!releaseVersion ? 'Не выбран релиз' : !eventsReady ? 'Журнал установки недоступен' : '');
  const downloadPercent = $derived(downloadProgress?.stage==='download'&&downloadProgress.total>0 ? Math.min(100,Math.round(100*downloadProgress.bytes/downloadProgress.total)) : undefined);
  const progressText = $derived(downloadProgress?.stage==='verify'?'Проверяем релиз…':downloadProgress?.stage==='extract'?'Распаковываем…':'Скачиваем…');
  function showLog(node: HTMLDialogElement) { node.showModal(); return {destroy(){node.close();}}; }
  const stages = ['Выбор действия','Подключение','Проверка','Выполнение','Результат'];
  const operations:{id:Action;name:string;subtitle:string;features:string[];details:string}[] = [
    {id:'install',name:'Установка',subtitle:'Установить или обновить',features:['Сохранение настроек автомобиля','Кнопки руля и два приложения рядом','Окна и штатная клавиатура'],details:'Установка полного релиза VoyahTune: настройки автомобиля, кнопки руля, разделение экрана, управление окнами и штатная клавиатура.'},
    {id:'remove',name:'Удаление',subtitle:'Полная очистка VoyahTune',features:['Приложения и их настройки','Системные компоненты','Остатки предыдущих установок'],details:'Удалим известные компоненты и настройки VoyahTune, восстановим собственные изменения DNS. Журнал и резервные копии на компьютере сохранятся.'}
  ];
  const connected = $derived(devices.length === 1 && devices[0].state === 'device');
  const operationName = $derived(({install:'Установка',update:'Обновление',repair:'Восстановление',remove:'Удаление'}[plan?.operation || 'install'] || 'Установка') + ' VoyahTune');
  const inventoryName = $derived(plan ? ({absent:'VoyahTune не установлен',complete:`VoyahTune · ${plan.inventory.version}`,unknown:'Старая сборка: версия не определена',partial:'Неполная установка',mixed:'Компоненты разных версий',remnants:'Остатки предыдущей установки'}[plan.inventory.state] || plan.inventory.state) : '');
  function acceptEvent(event:Event){
    if(events.some(e=>e.operationId===event.operationId&&e.sequence===event.sequence))return;
    events=[...events,event].slice(-1500);
    if(event.type==='canbus-conflict'&&event.data.awaitingConfirmation){canbusNotice=event.message;canbusAnswerPending=false;}
    if(event.type==='canbus-removal-started'){canbusNotice='';canbusAnswerPending=false;}
    if(['operation-cancelled','operation-paused'].includes(event.type)){canbusNotice='';error=null;stopMessage=event.data.report?.error?.message||event.message;outcome=event.type==='operation-paused'?'paused':'cancelled';screen=4;busy=false;reportPath=event.data.reportPath||'';}
    if(event.type==='step-started')current=event.stepId;
    if(event.type==='step-completed'&&event.stepId&&!completed.includes(event.stepId))completed=[...completed,event.stepId];
    if(event.type==='operation-failed') {canbusNotice='';error=event.data.report?.error || failure(event.message);outcome='failed';screen=4;busy=false;reportPath=event.data.reportPath||'';}
    if(event.type==='operation-completed') {canbusNotice='';outcome='success';screen=4;busy=false;reportPath=event.data.reportPath||'';}
  }
  async function recoverEvents(){
    try{for(const event of await command<Event[]>('operation_events'))acceptEvent(event);}
    catch(e){eventError=failure(e);}
  }
  onMount(()=>{
    let disposed=false, unsubscribe=()=>{}, unsubscribeClose=()=>{}, unsubscribeDownload=()=>{};
    onPayloadProgress(p=>downloadProgress=p).then(off=>{if(disposed)off();else unsubscribeDownload=off;}).catch(e=>error=failure(e));
    onCloseBlocked(()=>closeNotice=true).then(off=>{if(disposed)off();else unsubscribeClose=off;}).catch(e=>eventError=failure(e));
    subscribe(acceptEvent).then(off=>{if(disposed)off();else {unsubscribe=off;eventsReady=true;}}).catch(e=>{
      eventError={code:'EVENTS_UNAVAILABLE',message:'Не удалось подключить журнал и прогресс установки',detail:String(e),nextAction:'Перезапустите установщик. Установка не начнётся без подключения журнала.',retryable:false};
    });
    updateCode(); updateCatalog(true); return ()=>{disposed=true;unsubscribe();unsubscribeClose();unsubscribeDownload();};
  });
  async function updateCatalog(refresh:boolean){busy=true;if(refresh)error=null;try{
    catalog=await command<CatalogState>('release_catalog',{refresh});
  }catch(e){error=failure(e);}finally{busy=false;}}
  function selectInfo(info:{manifest:{releaseVersion:string};payloadRoot:string}){releaseVersion=info.manifest.releaseVersion;releasePath=info.payloadRoot;plan=null;confirmed=false;}
  async function loadRelease(path=localPath){busy=true;downloadBusy=true;downloadProgress=null;error=null;try{
    selectInfo(await command('release_info',{path}));
  }catch(e){error=failure(e);}finally{downloadBusy=false;await updateCatalog(false);}}
  async function selectRelease(row:ReleaseRow){
    if(busy||row.release?.compatible===false)return;
    selectedVersion=row.version;
    if(row.cached){await loadRelease(row.cached.path);return;}
    busy=true;downloadBusy=true;downloadProgress=null;error=null;
    try{selectInfo(await command('download_payload',{version:row.version}));}
    catch(e){error=failure(e);}
    finally{downloadBusy=false;await updateCatalog(false);}
  }
  async function deleteRelease(cached:CachedPayload){
    if(busy||!cached.deletable)return;
    busy=true;error=null;
    try{
      const result=await command<{deselected:boolean}>('delete_payload',{path:cached.path});
      if(result.deselected||releasePath===cached.path){releasePath='';releaseVersion='';plan=null;confirmed=false;}
    }catch(e){error=failure(e);}
    finally{await updateCatalog(false);}
  }
  async function openLink(url:string){try{await command('open_release_link',{url});}catch(e){error=failure(e);}}
  async function cancelDownload(){try{await command('cancel');}catch(e){error=failure(e);}}
  async function updateCode(){try{const c=await command<{code:string;date:string}>('engineering_code',{date:manualDate?date:null});code=c.code;date=c.date;}catch(e){error=failure(e);}}
  function choose(value:Action){action=value;plan=null;confirmed=false;error=null;}
  async function next(){if(nextBlocked)return;screen=1;await refresh();}
  async function refresh(){busy=true;confirmed=false;plan=null;error=null;devices=[];try{devices=(await command<{devices:Device[]}>('devices')).devices;}catch(e){error=failure(e);}finally{busy=false;}}
  async function review(){if(!connected||!confirmed)return;busy=true;error=null;plan=null;try{plan=await command<Plan>('plan',{serial:devices[0].serial,action,dns});screen=2;removeConfirmed=false;}catch(e){error=failure(e);}finally{busy=false;}}
  async function start(){if(!eventsReady||!plan || (action==='remove'&&!removeConfirmed))return;busy=true;error=null;canbusNotice='';canbusAnswerPending=false;stopMessage='';events=[];completed=[];current=undefined;reportPath='';stopRequested=false;outcome='running';screen=3;
    try{await command('apply',{request:{...plan.request,dns,confirmed:true}});}catch(e){error=failure(e);outcome='failed';screen=4;busy=false;}finally{await recoverEvents();}}
  async function resolveCanbus(approved:boolean){canbusAnswerPending=true;try{await command('resolve_canbus_conflict',{approved});}catch(e){error=failure(e);canbusAnswerPending=false;}}
  async function stop(){try{await command('cancel');stopRequested=true;}catch(e){error=failure(e);}}
  async function retry(){screen=1;error=null;await refresh();}
  async function saveReport(){try{const path=await command<string>('save_report',{events});reportPath=path;}catch(e){error=failure(e);}}
</script>
<div class="installer-window">
  <div class="window-body">
    <aside class="sidebar"><div class="brand">VoyahTune<span>Установка на автомобиль</span></div>
      <nav aria-label="Этапы установки"><ol>{#each stages as stage,i}<li class:current={i===screen} class:done={i<screen} class="nav-item" aria-current={i===screen?'step':undefined}><span class="nav-number">{i<screen?'✓':i+1}</span><span class="nav-label">{stage}</span></li>{/each}</ol></nav>
      <div class="sidebar-bottom"><span class="offline-dot"></span> Установка из загруженного релиза<small>Установщик {catalog?.installerVersion||"…"}</small></div>
    </aside>
    <main id="main" aria-busy={busy&&!canbusNotice}>
      <div class="screen">
      {#if screen===0}
        <div class="screen-heading"><span class="eyebrow">НАЧАЛО РАБОТЫ</span><h1>Что вы хотите сделать?</h1><p class="subtitle">Выберите набор функций для автомобиля или удалите VoyahTune.</p></div>
        <section class="plan-panel release-picker">
          <div class="release-title"><h2>{releaseVersion ? `Выбран VoyahTune ${releaseVersion}` : 'Выберите версию VoyahTune'}</h2><button class="button secondary" disabled={busy} onclick={()=>updateCatalog(true)}>Обновить список</button></div>
          {#if catalog?.warning}<p class="info-note">{catalog.warning} Скачанные релизы доступны без интернета.</p>{/if}
          {#if catalog?.catalog.generatedAt}<p class="release-date">Каталог: {catalog.catalog.generatedAt}</p>{/if}
          {#if releaseRows.length}
            <!-- svelte-ignore a11y_no_noninteractive_tabindex (Keyboard users must be able to scroll the release list.) -->
            <div class="release-table-scroll" tabindex="0" role="region" aria-label="Доступные релизы">
              <table class="release-table">
                <thead><tr><th scope="col">Релиз</th><th scope="col">Скачан</th><th scope="col">Выбран</th><th scope="col"><span class="sr-only">Действия</span></th></tr></thead>
                <tbody>{#each releaseRows as row (row.cached?.path||row.version)}
                  {@const selected=!!row.cached&&releasePath===row.cached.path&&!!releaseVersion}
                  <tr class:selected>
                    <td><div class="release-name"><strong>{row.version}</strong>{#if row.release}<button class="link-button release-notes" onclick={()=>openLink(row.release!.notesUrl)}>Что нового</button>{/if}</div><span class="release-meta">{row.release ? `${row.release.publishedAt} · ${(row.release.payload.size/1048576).toFixed(1)} МБ` : 'Локальный релиз'}</span>
                      {#if row.release?.compatible===false}<span class="release-incompatible">{row.release.incompatibility||`Нужен установщик ${row.release.requirements.minInstallerVersion}`}</span>
                        {#each row.release.installerUpdates||[] as update}<button class="link-button" onclick={()=>openLink(update.url)}>Установщик {update.version} · {update.platform}</button>{/each}
                      {/if}
                    </td>
                    <td class="release-status"><span class:teal={!!row.cached} aria-label={row.cached?'Скачан':'Не скачан'}>{row.cached?'✓':'—'}</span></td>
                    <td class="release-status"><span class:teal={selected} aria-label={selected?'Выбран':'Не выбран'}>{selected?'✓':'—'}</span></td>
                    <td><div class="release-actions"><button class="button secondary" aria-label={`Выбрать релиз ${row.version}`} disabled={busy||row.release?.compatible===false||selected} onclick={()=>selectRelease(row)}>{downloadBusy&&selectedVersion===row.version?'Загрузка…':'Выбрать'}</button><button class="button delete-cache" aria-label={`Удалить скачанный релиз ${row.version}`} title={row.cached?.deletable?'Удалить релиз с компьютера':'Нет скачанного релиза для удаления'} disabled={busy||!row.cached?.deletable} onclick={()=>row.cached&&deleteRelease(row.cached)}>Удалить</button></div></td>
                  </tr>
                {/each}</tbody>
              </table>
            </div>
          {:else}<p>В каталоге пока нет релизов. Откройте локальный ZIP или папку релиза.</p>{/if}
          {#if downloadBusy}<div class="download-progress"><div class="download-meter"><div class="download-label" role="status"><span>{progressText}</span><span>{downloadPercent===undefined?'':`${downloadPercent}%`}</span></div><progress aria-label={progressText} max="100" value={downloadPercent}></progress>{#if downloadProgress?.stage==='download'&&downloadProgress.total>0}<small>{(downloadProgress.bytes/1048576).toFixed(1)} из {(downloadProgress.total/1048576).toFixed(1)} МБ</small>{/if}</div><button class="button secondary" onclick={cancelDownload}>Отменить загрузку</button></div>{/if}
          <details><summary>Открыть локальный ZIP или папку</summary><label>Путь к ZIP или релизу<input aria-label="Путь к релизу" type="text" bind:value={localPath} disabled={busy} placeholder="/путь/payload_3.13.0.zip"></label><button class="button secondary" onclick={()=>loadRelease()} disabled={busy||!localPath}>Открыть релиз</button></details>

        </section>
        <fieldset class="mode-picker"><legend>Операция</legend><div class="mode-selectors">{#each operations as v}<label class="mode-selector" class:selected={action===v.id} class:danger={v.id==='remove'}><input type="radio" name="action" value={v.id} checked={action===v.id} disabled={busy} onchange={()=>choose(v.id)}><span><strong>{v.name}</strong><small>{v.subtitle}</small></span></label>{/each}</div></fieldset>
        {#if action}<p class="mode-description">{operations.find(v=>v.id===action)?.details}</p>{/if}
      {:else if screen===1}
        <div class="screen-heading"><span class="eyebrow">ПОДКЛЮЧЕНИЕ ПО USB</span><h1>Подключите автомобиль</h1><p class="subtitle">Включите отладку по USB в инженерном меню и подключите компьютер к головному устройству.</p></div>
        <div class="connection-grid"><section class="status-panel" class:success={connected} class:warning={!connected}>
          <h2>{busy?'Проверяем подключение…':devices.length>1?'Найдено несколько устройств':connected?'Автомобиль найден':devices[0]?.state==='unauthorized'?'Разрешите доступ на автомобиле':devices.length?'Автомобиль не отвечает':'Автомобиль не найден'}</h2>
          <p>{devices.length>1?'Отключите другие Android-устройства и эмуляторы, затем нажмите «Обновить».':connected?'Проверьте сведения и подтвердите, что подключён нужный автомобиль.':devices[0]?.state==='unauthorized'?'Подтвердите запрос «Разрешить отладку по USB» на экране автомобиля.':'Проверьте USB-кабель и включите отладку по USB. В Windows может потребоваться драйвер, в Linux — разрешение доступа к USB.'}</p>
          <div class="device-list">{#each devices as d}<div class="device"><div><strong>{d.model||'Android-устройство'}</strong><code>{d.serial}</code></div><span class="device-status">{d.state}</span></div>{/each}</div>
          <button class="button secondary" onclick={refresh} disabled={busy}>↻ Обновить</button>
          {#if connected}<label class="confirmation"><input type="checkbox" bind:checked={confirmed} disabled={busy}> Это мой автомобиль, подключённый для {action==='remove'?'удаления':'установки'} VoyahTune</label>{/if}
        </section>
        <aside class="code-panel" aria-label="Код инженерного меню"><h2>Инженерное меню</h2><p class="code-label">Код для входа</p><div class="code-line"><output class="engineering-code">{code||'—'}</output></div><p class="code-date">Дата расчёта: {date.split('-').reverse().join('.')} · Пекин</p><p class="code-source">{manualDate?'Дата задана вручную.':'По часам компьютера. Часы автомобиля пока не проверены.'}</p><label class="date-editor">Изменить дату расчёта<input type="date" min="0001-01-01" max="9999-12-31" bind:value={date} onchange={()=>{manualDate=true;updateCode();}}></label><p class="code-footnote">Если код не подходит, попробуйте увеличить число вручную: например, вместо 2921 введите 2922, или выберите следующий день в календаре. Возможна небольшая разница во времени; точный алгоритм смены кода в автомобиле неизвестен.</p></aside></div>
      {:else if screen===2 && plan}
        <div class="screen-heading"><span class="eyebrow">ПРОВЕРКА ЗАВЕРШЕНА</span><h1>{operationName}</h1><p class="subtitle">Проверьте план. Изменение файлов начнётся после вашего подтверждения.</p></div>
        <section class="plan-panel"><div class="plan-summary"><div><h2>{plan.inventory.model}</h2><p>{plan.inventory.serial} · Android API {plan.inventory.sdk}</p></div><span class="badge">{action==='remove'?'Удаление':'Установка'}</span></div><div class="plan-body"><p><strong>Релиз:</strong> {releaseVersion||'Встроенные ресурсы удаления'}</p><p><strong>Сейчас в автомобиле:</strong> {inventoryName}</p><details><summary>По каким признакам определено состояние</summary><p>{action==='remove'?'Проверены наличие и пути приложений, системного Native и файлов VoyahTune. Подписи установленных APK при удалении не проверяются.':'Проверены активные APK, системный Native, подписанные метаданные релиза, наличие загрузочных файлов и их SHA-256.'}</p><pre>{JSON.stringify({packages:plan.inventory.packages,files:plan.inventory.files},null,2)}</pre></details><ol class="plan-list">{#each plan.steps as step}<li>{step.title}</li>{/each}</ol></div></section>
        {#if action!=='remove'}<fieldset class="dns-box"><legend>Яндекс DNS</legend><p>Эта настройка позволяет иметь доступ к сервисам, которые доступны в белых списках.</p><div class="dns-options">{#each [{id:'keep',label:'Оставить как есть'},{id:'on',label:'Включить'},{id:'off',label:'Выключить'}] as item}<label><input type="radio" name="dns" value={item.id} bind:group={dns}>{item.label}</label>{/each}</div></fieldset>{/if}
        {#each plan.warnings as warning}<p class="info-note">ⓘ {warning}</p>{/each}
        {#if action==='remove'}<label class="confirmation"><input type="checkbox" bind:checked={removeConfirmed}> Подтверждаю удаление обоих наборов VoyahTune, их настроек и данных</label>{/if}
      {:else if screen===3 && plan}
        <div class="screen-heading"><span class="eyebrow">{action==='remove'?'УДАЛЕНИЕ':'УСТАНОВКА'} VOYAHTUNE</span><h1>{plan.steps.find(s=>s.id===current)?.title||'Повторная проверка'}</h1><p class="subtitle">Не отключайте кабель и питание автомобиля. Здесь будет показан результат каждого шага.</p></div>
        {#if canbusNotice}<section class="status-panel warning" role="alert" aria-label="Конфликт разрешения WRITE_CANBUS"><h2>Нужно удалить конфликтующее приложение</h2><p>{canbusNotice}</p><div class="canbus-actions"><button class="button danger" disabled={canbusAnswerPending||stopRequested} onclick={()=>resolveCanbus(true)}>Удалить приложение и продолжить</button><button class="button secondary" disabled={canbusAnswerPending||stopRequested} onclick={()=>resolveCanbus(false)}>Отменить установку</button></div></section>{/if}
        <div class="progress-track indeterminate"><div class="progress-bar"></div></div><p class="progress-text">Завершено {completed.length} из {plan.steps.length} шагов</p>
        <ol class="step-list">{#each plan.steps as step,i}<li class="step-row" class:done={completed.includes(step.id)} class:active={current===step.id&&!completed.includes(step.id)}><span class="step-indicator">{completed.includes(step.id)?'✓':i+1}</span><span>{step.title}</span></li>{/each}</ol>
        {#if events.filter(e=>e.stepId===current&&['package-reset','signing-reset-reboot','canbus-removal-started','canbus-backup','canbus-removal-reboot','canbus-conflict-resolved'].includes(e.type)).at(-1)}<p class="info-note" role="status">{events.filter(e=>e.stepId===current&&['package-reset','signing-reset-reboot','canbus-removal-started','canbus-backup','canbus-removal-reboot','canbus-conflict-resolved'].includes(e.type)).at(-1)?.message}</p>{/if}
        {#if stopRequested}<p class="info-note">Остановка запрошена. Текущий шаг завершится, затем операция остановится.</p>{/if}
      {:else if screen===4}
        <div class="screen-heading"><span class="eyebrow">{outcome==='success'?'ГОТОВО':'ОПЕРАЦИЯ ОСТАНОВЛЕНА'}</span><h1>{outcome==='success'?(action==='remove'?'VoyahTune удалён':'VoyahTune готов к работе'):outcome==='cancelled'?'Операция отменена':outcome==='paused'?'Требуется ваше решение':'Не удалось завершить операцию'}</h1><p class="subtitle">{outcome==='success'?(action==='remove'?'Команды удаления выполнены. Автомобиль перезагружается.':'Запуск Native проверен после перезагрузки автомобиля.'):['cancelled','paused'].includes(outcome)?stopMessage:'Завершённые шаги могли изменить автомобиль. После устранения причины выполните новую проверку.'}</p></div>
        {#if outcome==='success'}<section class="status-panel success"><h2>{action==='remove'?'Приложения и компоненты удалены':'VoyahTune установлен'}</h2><p>{action==='remove'?'Резервные копии и журнал сохранены на компьютере.':'Откройте VoyahTune на автомобиле, чтобы настроить доступные функции.'}</p></section>{/if}
      {/if}
      {#if closeNotice && busy}<p class="info-note" role="status">Сейчас выполняется проверка или установка. Дождитесь завершения; установку можно остановить кнопкой «Остановить после текущего шага».</p>{/if}
      {#if eventError}<section class="status-panel danger error-box" role="alert"><h2>{eventError.message}</h2><p>{eventError.nextAction}</p><pre>{eventError.detail}</pre></section>{/if}
      {#if error}<section class="status-panel danger error-box" role="alert"><h2>{error.message}</h2>{#if screen===4 && current}<p><strong>{error.code==='CANCELLED'?'Последний шаг':'Ошибка на шаге'}:</strong> {plan?.steps.find(s=>s.id===current)?.title||current}</p>{/if}<p>{error.nextAction}</p><details open={screen===4}><summary>Технические подробности · {error.code}</summary><pre>{error.detail}</pre></details></section>{/if}
      {#if reportPath}<p class="report-path">Отчёт: {reportPath}</p>{/if}
      </div>
      <footer class="footer">
        {#if screen===0}<span class="footer-note next-reason" id="next-reason" role="status">{nextBlocked||'На следующем шаге проверим подключение автомобиля'}</span><button class="button primary" disabled={!!nextBlocked} aria-describedby="next-reason" onclick={next}>Далее</button>
        {:else if screen===1}<button class="button secondary" disabled={busy} onclick={()=>{screen=0;error=null;}}>Назад</button><button class="button primary" disabled={busy||!connected||!confirmed} onclick={()=>review()}>{busy?'Проверяем…':'Проверить автомобиль'}</button>
        {:else if screen===2}<button class="button secondary" onclick={()=>{screen=1;confirmed=false;}}>Назад</button><button class="button" class:primary={action!=='remove'} class:danger={action==='remove'} disabled={busy||!eventsReady||(action==='remove'&&!removeConfirmed)} onclick={start}>{action==='remove'?'Удалить VoyahTune':'Начать установку'}</button>
        {:else if screen===3}<button class="button secondary" onclick={()=>logOpen=true}>Журнал</button><button class="button secondary" disabled={stopRequested} onclick={stop}>Остановить после текущего шага</button>
        {:else}<div class="footer-actions"><button class="button secondary" onclick={()=>logOpen=true}>Журнал</button><button class="button secondary" onclick={saveReport}>Сохранить отчёт</button></div><button class="button primary" onclick={outcome==='success'?()=>{screen=0;error=null;}:retry}>{outcome==='success'?'В начало':'Проверить заново'}</button>{/if}
      </footer>
    </main>
  </div>
</div>
{#if logOpen}<dialog use:showLog onclose={()=>logOpen=false} class="log-modal" aria-label="Журнал операции"><header><h2>Журнал операции</h2><button class="button secondary" onclick={()=>logOpen=false}>Закрыть</button></header><p>Последние события. Полный журнал автоматически сохраняется на компьютере.</p><pre>{events.map(e=>`${e.timestamp} [${e.stepId||e.type}] ${e.message}${e.data.script?'\n'+e.data.script:''}`).join('\n')||(screen===4?'События не получены. Ошибка выше содержит подробности; сохраните отчёт.':'Операция ещё не запускалась.')}</pre><button class="button primary" onclick={saveReport}>Сохранить отчёт</button></dialog>{/if}
