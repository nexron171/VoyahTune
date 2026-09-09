/* Browser view/controller. All automobile operations go through the mock adapter. */
(function () {
  'use strict';
  const M = window.VoyahModel;
  const adapter = new window.VoyahDemoAdapter();
  const main = document.getElementById('main');
  const nav = document.getElementById('navigation');
  const dialog = document.getElementById('log-dialog');
  const state = {
    screen: 'choice', action: null, scan: null, scanning: false, confirmed: null,
    dns: 'keep', deleteConfirmed: false, step: 0, progress: 0, completed: [],
    error: null, paused: false, stopPrompt: false, events: [], started: null,
    manualDate: null, dateEditor: false, connectionNote: '', checkingStart: false
  };
  let scanGeneration = 0, toastTimer;
  const paths = {
    check: '<path d="m5 12 4 4L19 6"/>',
    full: '<path d="M3 6h5m4 0h9M3 12h11m4 0h3M3 18h2m4 0h12"/><circle cx="10" cy="6" r="2"/><circle cx="16" cy="12" r="2"/><circle cx="7" cy="18" r="2"/>',
    light: '<path d="M3 8h5m4 0h9M3 16h11m4 0h3"/><circle cx="10" cy="8" r="2"/><circle cx="16" cy="16" r="2"/>',
    remove: '<path d="M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7m4-7v7"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6m0-10v.1"/>',
    usb: '<path d="M12 21V3m-3 3 3-3 3 3M12 16l-5-4V8m5 4 5-4V5"/><circle cx="7" cy="6" r="2"/><path d="M15 2h4v4h-4z"/>',
    refresh: '<path d="M20 7v5h-5M4 17v-5h5M6 6a8 8 0 0 1 13 3M18 18a8 8 0 0 1-13-3"/>',
    lock: '<rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3m-4 5v2"/>',
    devices: '<rect x="2" y="5" width="13" height="11" rx="2"/><path d="M6 20h5m-2-4v4"/><rect x="17" y="8" width="5" height="13" rx="1"/>',
    alert: '<path d="m12 3 10 18H2L12 3zM12 9v5m0 3v.1"/>',
    arrow: '<path d="M4 12h15m-6-6 6 6-6 6"/>',
    back: '<path d="M20 12H5m6-6-6 6 6 6"/>',
    log: '<path d="M6 3h9l4 4v14H6zM14 3v5h5M9 12h7m-7 4h7"/>',
    close: '<path d="m6 6 12 12M6 18 18 6"/>',
    download: '<path d="M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5"/>'
  };
  function icon(name) { return `<svg aria-hidden="true" viewBox="0 0 24 24">${paths[name] || paths.info}</svg>`; }
  function esc(value) { return String(value).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])); }
  function button(action, text, cls = 'secondary', opts = {}) {
    return `<button type="button" class="button ${cls}" data-action="${action}"${opts.disabled ? ' disabled' : ''}${opts.value ? ` data-value="${opts.value}"` : ''}>${opts.icon ? icon(opts.icon) : ''}${text}</button>`;
  }
  function heading(title, subtitle, eyebrow = '') {
    return `<div class="screen-heading">${eyebrow ? `<span class="eyebrow">${eyebrow}</span>` : ''}<h1 tabindex="-1">${title}</h1>${subtitle ? `<p class="subtitle">${subtitle}</p>` : ''}</div>`;
  }
  function actionLabel() { return state.action === 'remove' ? 'УДАЛЕНИЕ VOYAHTUNE' : `${M.variants[state.action].name.toUpperCase()} · ${M.variants[state.action].subtitle.toUpperCase()}`; }
  function info(text) { return `<p class="info-note">${icon('info')}<span>${text}</span></p>`; }
  function shell(content, footer) { return `<div class="screen">${content}</div><footer class="footer">${footer}</footer>`; }
  function announce(text) { document.getElementById('announcement').textContent = text; }
  function toast(text) { const el = document.getElementById('toast'); el.textContent = text; el.hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => { el.hidden = true; }, 3000); }
  function log(type, text) {
    state.events.push({ timestamp: new Date().toISOString(), type, message: text, demo: true });
    if (dialog.open) updateLog();
  }
  function renderNavigation() {
    const current = ['choice', 'connection', 'review', 'running', 'result'].indexOf(state.screen);
    nav.innerHTML = ['Выбор действия', 'Подключение', 'Проверка', 'Выполнение', 'Результат'].map((name, i) => `<li class="nav-item ${i === current ? 'current' : i < current ? 'done' : ''}"${i === current ? ' aria-current="step"' : ''}><span class="nav-number">${i < current ? icon('check') : i + 1}</span><span class="nav-label">${name}</span><span class="sr-only">${i + 1}. ${name}</span></li>`).join('');
    document.querySelectorAll('.demo-fields select').forEach(el => { el.disabled = state.checkingStart || state.screen === 'running' && !state.error && !state.paused; });
  }
  function choiceScreen() {
    const cards = Object.entries(M.variants).map(([key, variant]) => `<article class="choice ${key === 'remove' ? 'danger' : ''}"><div class="choice-icon">${icon(key)}</div><h2>${variant.name}</h2><p class="choice-subtitle">${variant.subtitle}</p><ul>${variant.features.map(f => `<li>${icon('check')}<span>${f}</span></li>`).join('')}</ul><details><summary>Что входит в набор${key === 'remove' ? ' удаления' : ''}</summary><p>${variant.details}</p></details>${button('choose', key === 'remove' ? 'Перейти к удалению' : `Выбрать ${variant.name}`, key === 'full' ? 'primary' : key === 'light' ? 'outline-teal' : 'danger', { value: key })}</article>`).join('');
    return shell(heading('Что вы хотите сделать?', 'Выберите набор функций для автомобиля или удалите VoyahTune.', 'НАЧАЛО РАБОТЫ') + `<div class="choices">${cards}</div>` + info('На следующем шаге проверим подключение автомобиля.'), '<span class="footer-note">Выбор не запускает установку или удаление</span>');
  }
  function codeCard() {
    const date = state.manualDate || M.beijingDate();
    const displayDate = date.split('-').reverse().join('.');
    return `<aside class="code-panel" aria-label="Код инженерного меню"><h2>Инженерное меню</h2><p class="code-label">Код для входа</p><div class="code-line"><output class="engineering-code" id="engineering-code">${M.engineeringCode(date)}</output></div><div class="code-meta"><p class="code-date" id="code-date">Дата расчёта: ${displayDate} · Пекин</p><p class="code-source">${state.manualDate ? 'Дата задана вручную. Убедитесь, что она соответствует пекинской дате на часах автомобиля.' : 'По часам компьютера. Часы автомобиля пока не проверены.'}</p><button class="text-button" data-action="edit-date" aria-expanded="${state.dateEditor}">${state.dateEditor ? 'Скрыть выбор даты' : 'Изменить дату расчёта'}</button>${state.dateEditor ? `<form id="date-form" class="date-editor"><label for="engineering-date">Дата по Пекину (UTC+08:00)</label><input id="engineering-date" name="date" type="date" min="0001-01-01" max="9999-12-31" value="${date}" required><div class="footer-actions"><button class="button primary compact" type="submit">Рассчитать</button>${button('current-date', 'По часам компьютера', 'secondary compact')}</div><p class="inline-error" id="date-error" role="alert"></p></form>` : ''}<p class="code-footnote">Ориентировочный код по пекинской дате. Время его смены на автомобиле может отличаться.</p></div><details class="help-details"><summary>Если код не подходит</summary><p>Попробуйте вручную увеличить число на 1: например, вместо 2921 введите 2922. Или выберите следующий день в календаре выше. Небольшая разница во времени может влиять на код; точный алгоритм его смены в автомобиле неизвестен.</p></details></aside>`;
  }
  function connectionStatus() {
    if (state.scanning) return '<div class="checking-panel" role="status"><span class="busy-dot"></span> Проверяем подключение…<p class="fade-note">Одна проверка. После неё вы сможете обновить результат вручную.</p></div>';
    if (!state.scan) return `<div class="status-panel"><div class="status-icon">${icon('usb')}</div><h2>Проверьте подключение</h2><p>Нажмите «Обновить», чтобы найти подключённый автомобиль.</p><div style="margin-top:20px">${button('refresh', 'Обновить', 'primary', { icon: 'refresh' })}</div></div>`;
    const s = state.scan;
    const devices = s.devices.length ? `<div class="device-list">${s.devices.map(d => `<div class="device"><span class="device-status">${esc(d.status)}</span><strong>${esc(d.model)}</strong><small><code>${esc(d.serial)}</code>${d.firmware ? `<br>${esc(d.firmware)}` : ''}</small></div>`).join('')}</div>` : '';
    return `<section class="status-panel ${s.tone}" aria-label="Результат проверки"><div class="status-icon">${icon(s.icon)}</div><h2>${s.title}</h2><p>${s.text}</p>${devices}${adapter.connection === 'none' ? '<ol class="checklist"><li>Подключите кабель к диагностическому USB-порту</li><li>Включите отладку по USB</li><li>Нажмите «Обновить»</li></ol>' : '<div style="height:18px"></div>'}${s.detail ? `<p class="inline-error">${esc(s.detail)}</p>` : ''}${button('refresh', 'Обновить', M.canConfirm(s) ? 'secondary' : 'primary', { icon: 'refresh' })}<div class="check-caption">Проверено сейчас · устройств: ${s.devices.length}</div></section>`;
  }
  function connectionScreen() {
    return shell(heading('Подключите автомобиль', 'Проверьте подключение и подтвердите найденное устройство.', actionLabel()) + (state.connectionNote ? `<div class="connection-note" role="alert">${esc(state.connectionNote)}</div>` : '') + `<div class="connection-grid"><div>${connectionStatus()}<details class="help-details"><summary>Подсказка по кабелю и драйверу</summary><p>Используйте диагностический USB-порт и подходящий кабель для вашего ГУ. Для Windows может потребоваться ADB-драйвер устройства. ADB уже входит в установщик — отдельно устанавливать его не нужно.</p></details></div>${codeCard()}</div>`, button('back', 'Назад', 'secondary', { icon: 'back' }) + button('confirm-car', 'Подтвердить автомобиль', 'primary', { disabled: state.scanning || !M.canConfirm(state.scan), icon: 'arrow' }));
  }
  function reviewScreen() {
    const removing = state.action === 'remove';
    const installed = adapter.installed;
    const items = removing ? ['Проверим компоненты Full, Light и остатки старых установок', 'Остановим дополнительные компоненты и восстановим штатные файлы', 'Удалим приложения, их данные и настройки VoyahTune', 'Очистим остатки и проверим результат после перезагрузки'] : [`${['full', 'mixed'].includes(installed) && state.action === 'light' ? 'Удалим компоненты Full, которые не входят в Light' : 'Сохраним резервные копии перед изменением системных файлов'}`, `Установим ${M.variants[state.action].name} и приложение RestoreMode`, 'Применим выбранную настройку DNS', 'Перезагрузим автомобиль и проверим запуск VoyahTune'];
    const dns = removing ? '' : `<fieldset class="dns-choice"><legend>Яндекс DNS</legend><div class="radio-group">${[['keep', 'Оставить как есть'], ['on', 'Включить'], ['off', 'Выключить']].map(([value, label]) => `<label class="radio-option"><input type="radio" name="dns" value="${value}"${state.dns === value ? ' checked' : ''}>${label}</label>`).join('')}</div><p class="dns-hint">Эта настройка позволяет иметь доступ к сервисам, которые доступны в белых списках.</p></fieldset>`;
    const confirmation = removing ? `<label class="confirm-delete"><input type="checkbox" id="confirm-delete"${state.deleteConfirmed ? ' checked' : ''}><span><strong>Понимаю, что приложения и их данные будут удалены.</strong><br>Будут очищены компоненты обоих наборов. Бэкапы и отчёт на компьютере сохранятся.</span></label>` : info('Автомобиль должен стоять на месте. Не отключайте питание и USB во время выполнения.');
    return shell(heading(removing ? 'Удалить VoyahTune с автомобиля?' : 'Всё готово к установке', removing ? 'Полная очистка независимо от ранее установленного набора.' : 'Проверьте выбранный набор и план действий.', actionLabel()) + `<div class="plan-panel"><div class="plan-summary"><div><span class="meta-label">Подтверждённый автомобиль</span><span class="meta-value">Voyah Free</span><small class="meta-label">${esc(state.confirmed.serial)}</small></div><div><span class="meta-label">Сейчас установлено</span><span class="meta-value">${M.installedLabel(installed)}</span><small class="meta-label">${removing ? 'Поиск остатков Full + Light' : M.planTitle(state.action, installed)}</small></div></div><div class="plan-body"><ul class="plan-list">${items.map(i => `<li>${icon('check')}<span>${i}</span></li>`).join('')}</ul>${dns}</div></div>${confirmation}${state.checkingStart ? '<p class="fade-note" role="status"><span class="busy-dot"></span>Повторно проверяем подтверждённый автомобиль…</p>' : ''}`, button('back', 'Назад', 'secondary', { disabled: state.checkingStart, icon: 'back' }) + button('start', removing ? 'Удалить VoyahTune и его данные' : `Установить ${M.variants[state.action].name}`, removing ? 'danger-fill' : 'primary', { disabled: state.checkingStart || removing && !state.deleteConfirmed, icon: removing ? 'remove' : 'arrow' }));
  }
  function steps() { return state.action === 'remove' ? M.removeSteps : M.installSteps; }
  function stepList() {
    return `<ol class="step-list">${steps().map((label, i) => { const status = state.completed.includes(i) ? 'done' : i === state.step ? state.error ? 'failed' : state.paused ? '' : 'active' : ''; return `<li class="step-row ${status}"><span class="step-indicator">${status === 'done' ? icon('check') : status === 'failed' ? icon('close') : ''}</span><span>${label}</span>${i === state.step && state.paused ? '<small>· остановлено</small>' : ''}</li>`; }).join('')}</ol>`;
  }
  function elapsed() { const seconds = state.started ? Math.floor((Date.now() - state.started) / 1000) : 0; return `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`; }
  function progressText() {
    if (state.action !== 'remove' && state.step === 2) return `Native.apk — ${Math.round(state.progress * 32 / 100)} из 32 МБ`;
    if (state.action !== 'remove' && state.step === 3) return `Передача файлов — ${Math.round(state.progress * 64 / 100)} из 64 МБ`;
    if (state.step === 6) return 'Ожидаем возвращения автомобиля и загрузки Android. Предел: 5 минут.';
    if (state.step === 5 && state.action !== 'remove' && state.dns === 'keep') return 'Сохраняем текущее состояние DNS.';
    return 'Выполняем действие и проверяем результат.';
  }
  function errorBlock() {
    const e = state.error;
    return `<section class="error-panel" role="alert"><div class="error-heading">${icon('alert')}<h2>${e.title}</h2></div><p>${e.reason}</p><p class="error-state">${e.state}</p><p>${e.next}</p><details><summary>Технические подробности · ${e.code}</summary><pre>${esc(e.raw)}</pre></details></section>`;
  }
  function runningScreen() {
    const isRemove = state.action === 'remove';
    let title = isRemove ? 'Удаляем VoyahTune' : 'Устанавливаем VoyahTune';
    if (state.error) title = 'Требуется ваше внимание';
    else if (state.paused) title = 'Выполнение остановлено';
    const measurable = !isRemove && [2, 3].includes(state.step);
    const execution = state.error ? errorBlock() + `<div class="execution-panel error-steps">${stepList()}</div>` : `<section class="execution-panel"><div class="execution-top"><span class="step-label">ШАГ ${state.step + 1} ИЗ ${steps().length}</span><span class="elapsed" id="elapsed">Прошло ${elapsed()}</span></div><h2>${steps()[state.step]}</h2><div class="progress-track ${!measurable && !state.paused ? 'indeterminate' : ''}"${measurable ? ` role="progressbar" aria-label="Передача файла" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${state.progress}"` : ''}><div class="progress-bar" style="width:${state.progress}%"></div></div><p class="progress-text" id="progress-text">${state.paused ? 'Продолжение начнётся с проверки текущего состояния.' : progressText()}</p>${stepList()}</section>`;
    const stopPrompt = state.stopPrompt ? `<div class="cancel-panel"><strong>Остановить выполнение?</strong><p>В прототипе остановка произойдёт сразу. В установщике — после завершения текущей безопасной операции.</p><div class="footer-actions">${button('cancel-stop', 'Продолжать', 'primary compact')}${button('confirm-stop', 'Да, остановить', 'secondary compact')}</div></div>` : '';
    const right = state.error || state.paused ? button('retry', state.action === 'remove' && state.step === 7 ? 'Повторить очистку' : state.paused ? 'Продолжить' : 'Повторить шаг', 'primary', { icon: 'refresh', disabled: state.checkingStart }) : button('stop', 'Остановить', 'secondary');
    return shell(heading(title, 'Voyah Free · подключение подтверждено', actionLabel()) + execution + stopPrompt + (state.error || state.paused ? info('Отчёт сохранит причину остановки и все выполненные шаги.') : info('Не отключайте кабель и питание автомобиля.')), button('show-log', 'Показать журнал', 'secondary', { icon: 'log' }) + `<div class="footer-actions">${state.error || state.paused ? button('save-report', 'Сохранить отчёт', 'secondary', { icon: 'download' }) : ''}${right}</div>`);
  }
  function resultScreen() {
    const removing = state.action === 'remove';
    const items = removing ? ['Приложения и их данные удалены', 'Компоненты Full и Light очищены', 'Собственные изменения DNS восстановлены', 'Остаточные служебные файлы удалены', 'Результат проверен после перезагрузки'] : [`Набор ${M.variants[state.action].name} установлен`, 'VoyahTune запущен на автомобиле', 'Установленные компоненты проверены', 'Резервная копия и отчёт сохранены'];
    return shell(`<span class="eyebrow">${actionLabel()}</span><div class="success-hero"><div class="result-icon">${icon('check')}</div><h1 tabindex="-1">${removing ? 'VoyahTune удалён' : 'Установка завершена'}</h1><p>${removing ? 'Компоненты и настройки VoyahTune очищены с автомобиля.' : 'Можно отключить кабель и настроить функции на автомобиле.'}</p></div><ul class="result-list">${items.map(i => `<li>${icon('check')}<span>${i}</span></li>`).join('')}</ul><p class="result-note">${removing ? 'Резервные копии и журнал на компьютере сохранены.' : 'Дополнительные функции включаются в приложении VoyahTune.'}<br>В прототипе показан демонстрационный результат.</p>`, button('save-report', 'Сохранить отчёт', 'secondary', { icon: 'download' }) + button('finish', 'Готово', 'primary', { icon: 'check' }));
  }
  function render(focus = false) {
    renderNavigation();
    main.innerHTML = ({ choice: choiceScreen, connection: connectionScreen, review: reviewScreen, running: runningScreen, result: resultScreen })[state.screen]();
    if (focus) main.querySelector('h1')?.focus({ preventScroll: true });
  }
  function updateProgress() {
    const track = main.querySelector('.progress-track');
    if (track) { track.querySelector('.progress-bar').style.width = `${state.progress}%`; if (track.hasAttribute('aria-valuenow')) track.setAttribute('aria-valuenow', state.progress); }
    const text = document.getElementById('progress-text'); if (text) text.textContent = progressText();
    const time = document.getElementById('elapsed'); if (time) time.textContent = `Прошло ${elapsed()}`;
  }
  async function scan() {
    const generation = ++scanGeneration;
    state.scanning = true; state.scan = null; state.confirmed = null;
    render();
    const result = await adapter.scan();
    if (generation !== scanGeneration || state.screen !== 'connection') return;
    state.scanning = false; state.scan = result;
    log('connection-check', `${result.title}; devices=${result.devices.length}`);
    render(); announce(result.title);
  }
  function choose(action) {
    state.action = action; state.screen = 'connection'; state.connectionNote = '';
    state.deleteConfirmed = false; state.scan = null; state.confirmed = null;
    state.error = null; state.paused = false; state.completed = []; state.events = [];
    state.dns = 'keep'; state.started = null; adapter.failureConsumed = false;
    render(true); scan();
  }
  async function verifyTarget(generation) {
    const result = await adapter.scan();
    if (generation !== scanGeneration) return false;
    if (M.canConfirm(result) && state.confirmed && result.devices[0].serial === state.confirmed.serial) return true;
    state.screen = 'connection'; state.scan = result; state.confirmed = null; state.scanning = false;
    state.connectionNote = 'Подключение изменилось. Обновите проверку и заново подтвердите автомобиль.';
    state.deleteConfirmed = false;
    log('target-changed', 'Выполнение не началось: подтверждённый автомобиль недоступен.');
    return false;
  }
  async function start(retry = false) {
    if (state.checkingStart || !state.confirmed || state.action === 'remove' && !state.deleteConfirmed) return;
    state.checkingStart = true; render();
    const generation = scanGeneration;
    const valid = await verifyTarget(generation);
    if (generation !== scanGeneration) { state.checkingStart = false; return; }
    state.checkingStart = false;
    if (!valid) { render(true); return; }
    state.screen = 'running'; state.error = null; state.paused = false; state.stopPrompt = false;
    if (!retry) { state.step = 0; state.completed = []; state.started = Date.now(); }
    state.progress = 0;
    log(retry ? 'retry' : 'operation-started', `${M.planTitle(state.action, adapter.installed)}${retry ? `; повтор шага ${state.step + 1}` : ''}`);
    render(true);
    adapter.run({ action: state.action, from: retry ? state.step : 0, emit: event => {
      if (event.type === 'progress') { state.progress = event.progress; updateProgress(); return; }
      if (event.type === 'step-started') { state.step = event.step; state.progress = 0; log(event.type, event.label); render(); announce(event.label); }
      if (event.type === 'step-succeeded') { if (!state.completed.includes(event.step)) state.completed.push(event.step); log(event.type, event.label); }
      if (event.type === 'failed') { state.error = event.error; state.stopPrompt = false; log(event.type, `${event.error.code}: ${event.error.raw}`); render(true); announce(event.error.title); }
      if (event.type === 'completed') { state.screen = 'result'; log(event.type, 'Операция завершена и проверена (демонстрация).'); document.getElementById('demo-installed').value = adapter.installed; render(true); announce('Операция завершена'); }
    } });
  }
  function reset() {
    adapter.stop(); scanGeneration++; adapter.failureConsumed = false;
    state.screen = 'choice'; state.action = null; state.confirmed = null; state.scan = null;
    state.scanning = false; state.checkingStart = false; state.error = null; state.paused = false;
    state.stopPrompt = false; state.connectionNote = ''; state.events = []; state.started = null;
    state.manualDate = null; state.dateEditor = false; state.deleteConfirmed = false;
    render(true);
  }
  function updateLog() {
    document.getElementById('log-content').textContent = state.events.length ? state.events.map(e => `${e.timestamp.slice(11, 19)}  ${e.type}\n${e.message}`).join('\n\n') : 'Пока нет событий.';
  }
  function saveReport() {
    const report = { prototype: true, product: 'VoyahTune', operation: state.action, confirmedDevice: state.confirmed, error: state.error, completedSteps: state.completed.map(i => steps()[i]), events: state.events };
    const url = URL.createObjectURL(new Blob([JSON.stringify(report, null, 2)], { type: 'application/json;charset=utf-8' }));
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = 'voyahtune-demo-report.json'; document.body.append(anchor); anchor.click(); anchor.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000); toast('Демонстрационный отчёт сохранён');
  }
  main.addEventListener('click', async event => {
    const target = event.target.closest('[data-action]'); if (!target || target.disabled) return;
    switch (target.dataset.action) {
      case 'choose': choose(target.dataset.value); break;
      case 'refresh': state.connectionNote = ''; scan(); break;
      case 'confirm-car':
        if (!M.canConfirm(state.scan) || state.scanning) break;
        state.confirmed = { ...state.scan.devices[0] }; state.screen = 'review'; log('confirmed', `Пользователь подтвердил ${state.confirmed.serial}`); render(true); break;
      case 'back':
        if (state.screen === 'connection') { scanGeneration++; state.screen = 'choice'; state.scanning = false; state.confirmed = null; }
        else if (state.screen === 'review') { state.screen = 'connection'; state.confirmed = null; state.deleteConfirmed = false; }
        render(true); break;
      case 'edit-date': state.dateEditor = !state.dateEditor; render(); if (state.dateEditor) document.getElementById('engineering-date').focus(); break;
      case 'current-date': state.manualDate = null; render(); break;
      case 'start': start(false); break;
      case 'retry': start(true); break;
      case 'stop': state.stopPrompt = true; render(); break;
      case 'cancel-stop': state.stopPrompt = false; render(); break;
      case 'confirm-stop': adapter.stop(); state.paused = true; state.stopPrompt = false; log('paused', 'Пользователь остановил демонстрацию.'); render(true); break;
      case 'show-log': updateLog(); dialog.showModal(); break;
      case 'save-report': saveReport(); break;
      case 'finish': reset(); break;
    }
  });
  main.addEventListener('change', event => {
    if (event.target.name === 'dns') state.dns = event.target.value;
    if (event.target.id === 'confirm-delete') { state.deleteConfirmed = event.target.checked; main.querySelector('[data-action="start"]').disabled = !state.deleteConfirmed; }
  });
  main.addEventListener('submit', event => {
    if (event.target.id !== 'date-form') return;
    event.preventDefault(); const date = new FormData(event.target).get('date');
    try { M.engineeringCode(date); state.manualDate = date; render(); announce('Код пересчитан по выбранной дате'); }
    catch (error) { document.getElementById('date-error').textContent = error.message; }
  });
  document.getElementById('demo-connection').addEventListener('change', event => {
    adapter.connection = event.target.value; scanGeneration++;
    if (state.screen === 'connection' || state.screen === 'review') {
      state.screen = 'connection'; state.scan = null; state.scanning = false; state.confirmed = null;
      state.deleteConfirmed = false; state.connectionNote = 'Условия подключения изменены. Нажмите «Обновить», чтобы проверить автомобиль.'; render();
    }
  });
  document.getElementById('demo-installed').addEventListener('change', event => { adapter.installed = event.target.value; state.deleteConfirmed = false; if (state.screen === 'review') render(); });
  document.getElementById('demo-fault').addEventListener('change', event => { adapter.fault = event.target.value; adapter.failureConsumed = false; });
  document.getElementById('demo-reset').addEventListener('click', reset);
  document.getElementById('close-log').addEventListener('click', () => dialog.close());
  document.getElementById('download-log').addEventListener('click', saveReport);
  window.addEventListener('beforeunload', event => { if (adapter.running) { event.preventDefault(); event.returnValue = ''; } });
  // Date display can roll over at midnight in Beijing without polling ADB or re-rendering form controls.
  setInterval(() => {
    if (state.screen !== 'connection' || state.manualDate || state.dateEditor) return;
    const date = M.beijingDate(); const code = document.getElementById('engineering-code');
    if (code) code.textContent = M.engineeringCode(date);
    const label = document.getElementById('code-date'); if (label) label.textContent = `Дата расчёта: ${date.split('-').reverse().join('.')} · Пекин`;
  }, 30000);
  render();
})();
