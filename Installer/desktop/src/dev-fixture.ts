// Excluded from production by import.meta.env.DEV. Browser-only UI verification.
import type {Event,Plan,Action,Dns} from './api';
const listeners=new Set<(event:Event)=>void>();let sequence=0,cancel=false;let journal:Event[]=[];let canbusAnswer:((approved:boolean)=>void)|undefined;
const scenario=new URLSearchParams(location.search).get('fixture');
export function subscribe(callback:(event:Event)=>void){if(scenario==='events-error')throw Error('core:event:allow-listen denied');listeners.add(callback);return ()=>{listeners.delete(callback);};}
const emit=(type:string,stepId:string|undefined,message:string,data={})=>{const event={operationId:'browser-fixture',sequence:++sequence,timestamp:new Date().toISOString(),type,stepId,message,data};journal.push(event);for(const listener of listeners)listener(event);};
export async function command(name:string,args:Record<string,unknown>):Promise<unknown>{
  if(name==='engineering_code'){const date=String(args.date||new Date(Date.now()+8*3600000).toISOString().slice(0,10));return {date,code:[...date.slice(0,4)].map((d,i)=>Number(d)+Number((date.slice(5,7)+date.slice(8,10))[i])).join('')};}
  if(name==='devices')return {devices:scenario==='none'?[]:scenario==='multiple'?[{serial:'CAR-001',state:'device',model:'Voyah Free'},{serial:'PHONE',state:'unauthorized'}]:[{serial:'CAR-001',state:scenario==='unauthorized'?'unauthorized':'device',model:'Voyah Free'}]};
  if(name==='release_info')return {manifest:{releaseVersion:'0.0.0-dev'},payloadRoot:'/demo/payload'};
  if(name==='plan')return {request:{action:args.action as Action,dns:args.dns as Dns,serial:String(args.serial),inventoryToken:'fixture-token',confirmed:false},operation:args.action==='remove'?'remove':'install',warnings:['Проверка интерфейса: реальный автомобиль не используется.'],inventory:{serial:'CAR-001',model:'Voyah Free',fingerprint:'fixture',state:'absent',sdk:30,abi:'arm64-v8a',files:{},packages:{}},steps:[{id:'preflight',title:'Повторная проверка автомобиля и файлов'},{id:'root',title:'Получение системного доступа'},...(args.action==='remove'?[]:[{id:'permission',title:'Проверка владельца CAN-разрешения'}]),{id:'files',title:'Установка компонентов'},{id:'reboot',title:'Перезагрузка автомобиля'},{id:'verify',title:'Проверка результата'}]} satisfies Plan;
  if(name==='operation_events')return journal;
  if(name==='cancel'){cancel=true;canbusAnswer?.(false);return;}
  if(name==='resolve_canbus_conflict'){canbusAnswer?.(args.approved===true);return;}
  if(name==='apply'){
    cancel=false;journal=[];const plan=await command('plan',args.request as unknown as Record<string,unknown>) as Plan;
    for(const step of plan.steps){
      emit('step-started',step.id,step.title);await new Promise(resolve=>setTimeout(resolve,350));
      if(scenario==='root-error'&&step.id==='root'){const error={code:'ROOT_UNAVAILABLE',message:'Прошивка не разрешает системный доступ через ADB',detail:'adbd cannot run as root in production builds',nextAction:'Проверьте настройки отладки и повторите проверку.',retryable:true};emit('operation-failed',step.id,error.message,{report:{error}});return;}
      if(scenario==='canbus-conflict'&&step.id==='permission'&&plan.request.action!=='remove'){
        const approved=await new Promise<boolean>(resolve=>{canbusAnswer=resolve;emit('canbus-conflict',step.id,'Обнаружено конфликтующее приложение com.voyah.hl.service (VoyahHlCTRL): оно установлено для пользователя 0 или владеет разрешением WRITE_CANBUS. Установщик сохранит копию системной папки на компьютере, удалит приложение для пользователя 0 и системную папку, очистит кэш пакетов и перезагрузит автомобиль. Функции приложения и его пользовательские данные будут удалены.',{awaitingConfirmation:true});});
        canbusAnswer=undefined;
        if(!approved){emit('operation-cancelled',step.id,'Установка отменена',{report:{error:{code:'CANCELLED',message:'Удаление com.voyah.hl.service отменено. Установка не продолжена.'}}});return;}
        emit('canbus-removal-started',step.id,'Сохраняем VoyahHlCTRL перед удалением');
        emit('canbus-removal-reboot',step.id,'Перезагрузка для освобождения WRITE_CANBUS');
        await new Promise(resolve=>setTimeout(resolve,350));
        emit('canbus-conflict-resolved',step.id,'Конфликт устранён. Продолжаем установку.');
      }
      emit('step-completed',step.id,step.title);
      if(cancel){emit('operation-cancelled',step.id,'Остановлено',{report:{error:{code:'CANCELLED',message:'Операция остановлена между шагами',detail:'',nextAction:'Выполните новую проверку.',retryable:true}}});return;}
    }
    emit('operation-completed',undefined,'Результат проверен');return;
  }
  if(name==='save_report')return 'Тестовый отчёт · браузерная проверка';
  throw Error(`Unknown fixture command: ${name}`);
}
