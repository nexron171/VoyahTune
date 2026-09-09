(function (root) {
  'use strict';
  const variants = {
    full: { name: 'Full', subtitle: 'Полный набор', description: 'Настройки автомобиля и дополнительные возможности экранов.', features: ['Сохранение настроек автомобиля', 'Кнопки руля и два приложения рядом', 'Окна и штатная клавиатура'], details: 'Включает базовые функции Light, настройку кнопок руля, разделение экрана, управление окнами приложений и дополнительные варианты штатной клавиатуры. Доступные функции включаются и настраиваются в автомобиле.' },
    light: { name: 'Light', subtitle: 'Базовый набор', description: 'Основные настройки автомобиля без изменения интерфейса экранов.', features: ['Сохранение настроек автомобиля', 'Восстановление настроек при запуске', 'Без изменения окон и клавиатуры'], details: 'Сохранение и восстановление настроек автомобиля. Изменение кнопок руля, управление окнами, разделение экрана и дополнительные варианты клавиатуры в этот набор не входят.' },
    remove: { name: 'Удалить', subtitle: 'Полная очистка VoyahTune', description: 'Приложения, настройки и компоненты прежних установок.', features: ['Приложения и их настройки', 'Компоненты Full и Light', 'Остатки предыдущих установок'], details: 'Проверим компоненты Full, Light и известных старых версий, даже если приложения уже удалены. Восстановим собственные изменения штатных файлов и DNS. Журнал и резервные копии на компьютере сохранятся.' }
  };
  const installSteps = ['Проверка комплекта', 'Подключение и доступ', 'Резервная копия', 'Установка файлов', 'Настройка компонентов', 'Настройка DNS', 'Перезагрузка автомобиля', 'Проверка запуска'];
  const removeSteps = ['Поиск всех компонентов', 'Подключение и доступ', 'Остановка компонентов', 'Восстановление штатных файлов', 'Удаление приложений и данных', 'Очистка настроек и остатков', 'Перезагрузка и проверка', 'Завершающая очистка'];
  const car = { serial: 'VOYAH-DEMO-001', model: 'Voyah Free', firmware: 'Android 11 · демонстрационное ГУ', status: 'device', compatible: true };
  const connectionStates = {
    none: { title: 'Автомобиль не найден', text: 'Проверьте кабель и включите отладку по USB в инженерном меню автомобиля.', icon: 'usb', tone: 'warning', devices: [] },
    ready: { title: 'Автомобиль найден', text: 'Проверьте сведения об устройстве и подтвердите, что подключён нужный автомобиль.', icon: 'check', tone: 'success', devices: [car] },
    multiple: { title: 'Найдено несколько устройств', text: 'Отключите другие Android-устройства и эмуляторы, затем нажмите «Обновить».', icon: 'devices', tone: 'warning', devices: [car, { serial: 'emulator-5554', model: 'Android Emulator', status: 'device', compatible: false }] },
    unauthorized: { title: 'Разрешите доступ на автомобиле', text: 'На экране автомобиля подтвердите запрос «Разрешить отладку по USB», затем нажмите «Обновить».', icon: 'lock', tone: 'warning', devices: [{ ...car, status: 'unauthorized' }] },
    offline: { title: 'Автомобиль не отвечает', text: 'Устройство обнаружено, но связь недоступна. Переподключите кабель, затем нажмите «Обновить».', icon: 'usb', tone: 'warning', devices: [{ ...car, status: 'offline' }] },
    incompatible: { title: 'Не найден штатный сервис автомобиля', text: 'В списке системных компонентов не найден сервис Qinggan, необходимый для работы VoyahTune. Проверьте, что подключено головное устройство автомобиля.', icon: 'devices', tone: 'danger', detail: 'В демонстрационном ответе PackageManager отсутствует com.qinggan.canbus.service.', devices: [{ serial: 'PHONE-DEMO-002', model: 'Android Phone', status: 'device', compatible: false }] },
    toolError: { title: 'Не удалось выполнить проверку', text: 'Комплектный ADB не запустился. Проверьте, не заблокирован ли файл средствами защиты, и повторите проверку.', icon: 'alert', tone: 'danger', detail: 'ADB_START_FAILED · Не удалось запустить host-tools/adb', devices: [] }
  };
  function engineeringCode(date) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) throw new Error('Укажите дату в формате ГГГГ-ММ-ДД.');
    const d = new Date(date + 'T12:00:00Z');
    if (Number.isNaN(d.getTime()) || d.toISOString().slice(0, 10) !== date || +date.slice(0, 4) < 1) throw new Error('Укажите существующую дату.');
    const year = date.slice(0, 4), monthDay = date.slice(5, 7) + date.slice(8, 10);
    return [...year].map((digit, i) => +digit + +monthDay[i]).join('');
  }
  function beijingDate(instant = new Date()) {
    const parts = new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(instant);
    const part = type => parts.find(p => p.type === type).value;
    return `${part('year')}-${part('month')}-${part('day')}`;
  }
  function canConfirm(result) {
    return !!result && result.devices.length === 1 && result.devices[0].status === 'device' && result.devices[0].compatible === true;
  }
  function installedLabel(value) {
    return { none: 'VoyahTune не установлен', full: 'Full', light: 'Light', mixed: 'Light и остатки Full', remnants: 'Остатки предыдущей установки' }[value];
  }
  function planTitle(action, installed) {
    if (action === 'remove') return 'Удаление VoyahTune';
    if (installed === action) return `Обновление ${variants[action].name}`;
    if (['full', 'light', 'mixed'].includes(installed)) return `Переход на ${variants[action].name}`;
    return `Установка ${variants[action].name}`;
  }
  function faultStep(fault, action) {
    if (fault === 'root') return 1;
    if (fault === 'backup' && action !== 'remove') return 2;
    if (fault === 'boot') return 6;
    if (fault === 'cleanup' && action === 'remove') return 7;
    return -1;
  }
  function errorInfo(fault, action) {
    const errors = {
      root: { code: 'ADB_ROOT_DENIED', title: 'Не удалось получить необходимые права', reason: 'Головное устройство не разрешило доступ для изменения системных файлов.', state: 'Файлы автомобиля ещё не изменялись.', next: 'Проверьте настройки отладки в инженерном меню и доступность root для этой прошивки.', raw: 'adbd cannot run as root in production builds' },
      backup: { code: 'ADB_CONNECTION_LOST', title: 'Не удалось создать резервную копию', reason: 'Во время копирования Native.apk потеряно соединение с автомобилем.', state: 'Системные файлы ещё не заменялись.', next: 'Проверьте USB-кабель и подключение автомобиля, затем повторите шаг.', raw: "adb: error: failed to copy '/system/priv-app/Native/Native.apk': device offline" },
      boot: { code: 'ANDROID_BOOT_TIMEOUT', title: 'Автомобиль не завершил загрузку', reason: 'Android не подтвердил завершение загрузки за 5 минут.', state: action === 'remove' ? 'Компоненты удалены, но результат пока не подтверждён.' : 'Файлы установлены, но запуск VoyahTune пока не подтверждён.', next: 'Проверьте экран автомобиля и соединение. Когда загрузка завершится, повторите проверку.', raw: 'Timeout: sys.boot_completed != 1; deadline=300s' },
      cleanup: { code: 'REMOVE_RESIDUE', title: 'Очистка завершена не полностью', reason: 'Не удалось удалить служебный каталог установщика на автомобиле.', state: 'Приложения удалены. Остался один служебный каталог.', next: 'Проверьте подключение и повторите завершающую очистку.', raw: '/data/local/voyahtune-installer/: Permission denied' }
    };
    return errors[fault];
  }
  const api = { variants, installSteps, removeSteps, connectionStates, engineeringCode, beijingDate, canConfirm, installedLabel, planTitle, faultStep, errorInfo };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.VoyahModel = api;
})(typeof window === 'undefined' ? {} : window);
