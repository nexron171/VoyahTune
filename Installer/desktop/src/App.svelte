<script lang="ts">
  import { onMount } from 'svelte';
  import { command, subscribe, onCloseBlocked, failure, type Action, type Dns, type Device, type Plan, type Event, type Failure } from './api';
  let screen = $state(0), action = $state<Action>('full'), dns = $state<Dns>('keep');
  let devices = $state<Device[]>([]), busy = $state(false), confirmed = $state(false), removeConfirmed = $state(false);
  let plan = $state<Plan|null>(null), error = $state<Failure|null>(null), events = $state<Event[]>([]);
  let date = $state(''), code = $state(''), manualDate = $state(false), logOpen = $state(false), stopRequested = $state(false);
  let outcome = $state<'running'|'success'|'failed'|'cancelled'|'paused'>('running'), reportPath = $state('');
  let closeNotice = $state(false), eventsReady = $state(false), eventError = $state<Failure|null>(null);
  let completed = $state<string[]>([]), current = $state<string|undefined>();
  let canbusNotice = $state(''), canbusAnswerPending = $state(false), stopMessage = $state('');
  let releasePath = $state(''), releaseVersion = $state('');
  function showLog(node: HTMLDialogElement) { node.showModal(); return {destroy(){node.close();}}; }
  const stages = ['Выбор действия','Подключение','Проверка','Выполнение','Результат'];
  const variants:{id:Action;name:string;subtitle:string;features:string[];details:string}[] = [
    {id:'full',name:'Full',subtitle:'Полный набор',features:['Сохранение настроек автомобиля','Кнопки руля и два приложения рядом','Окна и штатная клавиатура'],details:'Включает функции Light, настройку кнопок руля, разделение экрана, управление окнами и дополнительные варианты штатной клавиатуры.'},
    {id:'light',name:'Light',subtitle:'Базовый набор',features:['Сохранение настроек автомобиля','Восстановление настроек при запуске','Без изменения окон и клавиатуры'],details:'Сохранение и восстановление настроек автомобиля. Изменение кнопок руля, окон и клавиатуры в этот набор не входит.'},
    {id:'remove',name:'Удалить',subtitle:'Полная очистка VoyahTune',features:['Приложения и их настройки','Компоненты Full и Light','Остатки предыдущих установок'],details:'Проверим оба набора и известные старые компоненты, восстановим собственные изменения DNS. Журнал и резервные копии на компьютере сохранятся.'}
  ];
  const connected = $derived(devices.length === 1 && devices[0].state === 'device');
  const operationName = $derived(({install:'Установка',update:'Обновление',switch:'Переход на',repair:'Восстановление',remove:'Удаление'}[plan?.operation || 'install'] || 'Установка') + (action==='remove'?' VoyahTune':` ${action==='full'?'Full':'Light'}`));
  const inventoryName = $derived(plan ? ({absent:'VoyahTune не установлен',complete:`${plan.inventory.variant?.toUpperCase()} · ${plan.inventory.version}`,unknown:'Старая сборка: набор не определён',partial:'Неполная установка',mixed:'Компоненты разных наборов или версий',remnants:'Остатки предыдущей установки'}[plan.inventory.state] || plan.inventory.state) : '');
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
    let disposed=false, unsubscribe=()=>{}, unsubscribeClose=()=>{};
    onCloseBlocked(()=>closeNotice=true).then(off=>{if(disposed)off();else unsubscribeClose=off;}).catch(e=>eventError=failure(e));
    subscribe(acceptEvent).then(off=>{if(disposed)off();else {unsubscribe=off;eventsReady=true;}}).catch(e=>{
      eventError={code:'EVENTS_UNAVAILABLE',message:'Не удалось подключить журнал и прогресс установки',detail:String(e),nextAction:'Перезапустите установщик. Установка не начнётся без подключения журнала.',retryable:false};
    });
    updateCode(); loadRelease(); return ()=>{disposed=true;unsubscribe();unsubscribeClose();};
  });
  async function loadRelease(){busy=true;releaseVersion='';error=null;plan=null;confirmed=false;try{const info=await command<{manifest:{releaseVersion:string};payloadRoot:string}>('release_info',{path:releasePath||null});releaseVersion=info.manifest.releaseVersion;releasePath=info.payloadRoot;}catch(e){error=failure(e);}finally{busy=false;}}
  async function updateCode(){try{const c=await command<{code:string;date:string}>('engineering_code',{date:manualDate?date:null});code=c.code;date=c.date;}catch(e){error=failure(e);}}
  async function choose(value:Action){action=value;screen=1;plan=null;confirmed=false;error=null;await refresh();}
  async function refresh(){busy=true;confirmed=false;plan=null;error=null;devices=[];try{devices=(await command<{devices:Device[]}>('devices')).devices;}catch(e){error=failure(e);}finally{busy=false;}}
  async function review(){if(!connected||!confirmed)return;busy=true;error=null;try{plan=await command<Plan>('plan',{serial:devices[0].serial,action,dns});screen=2;removeConfirmed=false;}catch(e){error=failure(e);}finally{busy=false;}}
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
      <div class="sidebar-bottom"><span class="offline-dot"></span> Всё необходимое в комплекте<small>macOS · Windows · Linux</small></div>
    </aside>
    <main id="main" aria-busy={busy&&!canbusNotice}>
      <div class="screen">
      {#if screen===0}
        <div class="screen-heading"><span class="eyebrow">НАЧАЛО РАБОТЫ</span><h1>Что вы хотите сделать?</h1><p class="subtitle">Выберите набор функций для автомобиля или удалите VoyahTune.</p></div>
        <section class="plan-panel release-picker"><h2>{releaseVersion ? `Комплект VoyahTune ${releaseVersion}` : 'Комплект релиза'}</h2><p>Распакуйте весь ZIP. При необходимости укажите папку payload или файл manifest.json.</p><label>Путь к комплекту<input aria-label="Путь к комплекту" type="text" bind:value={releasePath} disabled={busy}></label><button class="button secondary" onclick={loadRelease} disabled={busy}>{busy?'Проверяем файлы…':'Открыть комплект'}</button></section>
        <div class="choices">{#each variants as v}<article class="choice" class:danger={v.id==='remove'}><div class="choice-icon" aria-hidden="true"><svg viewBox="0 0 24 24" aria-hidden="true">{#if v.id==='remove'}<path d="M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7m4-7v7"/>{:else if v.id==='full'}<path d="M3 6h5m4 0h9M3 12h11m4 0h3M3 18h2m4 0h12"/><circle cx="10" cy="6" r="2"/><circle cx="16" cy="12" r="2"/><circle cx="7" cy="18" r="2"/>{:else}<path d="M3 8h5m4 0h9M3 16h11m4 0h3"/><circle cx="10" cy="8" r="2"/><circle cx="16" cy="16" r="2"/>{/if}</svg></div><h2>{v.name}</h2><p class="choice-subtitle">{v.subtitle}</p><ul>{#each v.features as feature}<li><span class="teal">✓</span><span>{feature}</span></li>{/each}</ul><details><summary>Что входит в набор</summary><p>{v.details}</p></details><button class="button" class:primary={v.id==='full'} class:outline-teal={v.id==='light'} class:danger={v.id==='remove'} disabled={busy||!releaseVersion||!eventsReady} onclick={()=>choose(v.id)}>{v.id==='remove'?'Перейти к удалению':`Выбрать ${v.name}`}</button></article>{/each}</div>
        <p class="info-note">ⓘ На следующем шаге проверим подключение автомобиля.</p>
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
        <section class="plan-panel"><div class="plan-summary"><div><h2>{plan.inventory.model}</h2><p>{plan.inventory.serial} · Android API {plan.inventory.sdk}</p></div><span class="badge">{action==='remove'?'Full + Light':action.toUpperCase()}</span></div><div class="plan-body"><p><strong>Релиз комплекта:</strong> {releaseVersion}</p><p><strong>Сейчас в автомобиле:</strong> {inventoryName}</p><details><summary>По каким признакам определено состояние</summary><p>{action==='remove'?'Проверены наличие и пути приложений, системного Native и файлов VoyahTune. Подписи установленных APK при удалении не проверяются.':'Проверены активные APK, системный Native, подписанные метаданные набора и версии, наличие загрузочных файлов и их SHA-256. Для старых APK без метаданных набор не угадывается.'}</p><pre>{JSON.stringify({packages:plan.inventory.packages,files:plan.inventory.files},null,2)}</pre></details><ol class="plan-list">{#each plan.steps as step}<li>{step.title}</li>{/each}</ol></div></section>
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
        {#if outcome==='success'}<section class="status-panel success"><h2>{action==='remove'?'Приложения и компоненты удалены':`Установлен ${action==='full'?'Full':'Light'}`}</h2><p>{action==='remove'?'Резервные копии и журнал сохранены на компьютере.':'Откройте VoyahTune на автомобиле, чтобы настроить доступные функции.'}</p></section>{/if}
      {/if}
      {#if closeNotice && busy}<p class="info-note" role="status">Сейчас выполняется проверка или установка. Дождитесь завершения; установку можно остановить кнопкой «Остановить после текущего шага».</p>{/if}
      {#if eventError}<section class="status-panel danger error-box" role="alert"><h2>{eventError.message}</h2><p>{eventError.nextAction}</p><pre>{eventError.detail}</pre></section>{/if}
      {#if error}<section class="status-panel danger error-box" role="alert"><h2>{error.message}</h2>{#if screen===4 && current}<p><strong>{error.code==='CANCELLED'?'Последний шаг':'Ошибка на шаге'}:</strong> {plan?.steps.find(s=>s.id===current)?.title||current}</p>{/if}<p>{error.nextAction}</p><details open={screen===4}><summary>Технические подробности · {error.code}</summary><pre>{error.detail}</pre></details></section>{/if}
      {#if reportPath}<p class="report-path">Отчёт: {reportPath}</p>{/if}
      </div>
      <footer class="footer">
        {#if screen===0}<span class="footer-note">Выбор не запускает установку или удаление</span>
        {:else if screen===1}<button class="button secondary" disabled={busy} onclick={()=>{screen=0;error=null;}}>Назад</button><button class="button primary" disabled={busy||!connected||!confirmed} onclick={review}>{busy?'Проверяем…':'Проверить автомобиль'}</button>
        {:else if screen===2}<button class="button secondary" onclick={()=>{screen=1;confirmed=false;}}>Назад</button><button class="button" class:primary={action!=='remove'} class:danger={action==='remove'} disabled={busy||!eventsReady||(action==='remove'&&!removeConfirmed)} onclick={start}>{action==='remove'?'Удалить VoyahTune':'Начать установку'}</button>
        {:else if screen===3}<button class="button secondary" onclick={()=>logOpen=true}>Журнал</button><button class="button secondary" disabled={stopRequested} onclick={stop}>Остановить после текущего шага</button>
        {:else}<div class="footer-actions"><button class="button secondary" onclick={()=>logOpen=true}>Журнал</button><button class="button secondary" onclick={saveReport}>Сохранить отчёт</button></div><button class="button primary" onclick={outcome==='success'?()=>{screen=0;error=null;}:retry}>{outcome==='success'?'В начало':'Проверить заново'}</button>{/if}
      </footer>
    </main>
  </div>
</div>
{#if logOpen}<dialog use:showLog onclose={()=>logOpen=false} class="log-modal" aria-label="Журнал операции"><header><h2>Журнал операции</h2><button class="button secondary" onclick={()=>logOpen=false}>Закрыть</button></header><p>Последние события. Полный журнал автоматически сохраняется на компьютере.</p><pre>{events.map(e=>`${e.timestamp} [${e.stepId||e.type}] ${e.message}${e.data.script?'\n'+e.data.script:''}`).join('\n')||(screen===4?'События не получены. Ошибка выше содержит подробности; сохраните отчёт.':'Операция ещё не запускалась.')}</pre><button class="button primary" onclick={saveReport}>Сохранить отчёт</button></dialog>{/if}
