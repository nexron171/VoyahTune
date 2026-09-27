import { invoke, isTauri } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';
export type Action = 'full'|'light'|'remove';
export type Dns = 'keep'|'on'|'off';
export interface Failure { code:string; message:string; detail:string; nextAction:string; retryable:boolean }
export interface Device {serial:string; state:string; model?:string; product?:string}
export interface Request {action:Action; dns:Dns; serial:string; inventoryToken:string; confirmed:boolean}
export interface Plan {currentMode?:'absent'|'light'|'full'|'unknown';releaseVersion?:string;manifestSha256?:string;request:Request; operation:string; warnings:string[]; steps:{id:string;title:string}[]; inventory:{serial:string;model:string;fingerprint:string;state:string;variant?:'full'|'light';version?:string;sdk:number;abi:string;files:Record<string,string>;packages:Record<string,{path:string;sha256:string;build:unknown}>}}
export interface Event {operationId:string;sequence:number;timestamp:string;type:string;stepId?:string;message:string;data:Record<string,any>}
export function failure(error:unknown):Failure {if(typeof error==='object' && error && 'message' in error)return error as Failure;return {code:'DESKTOP_ERROR',message:String(error),detail:'',nextAction:'Сохраните отчёт и повторите проверку.',retryable:true}}
export async function command<T>(command:string,args:Record<string,unknown>={}):Promise<T>{
  if(import.meta.env.DEV && new URLSearchParams(location.search).has('fixture'))return (await import('./dev-fixture')).command(command,args) as Promise<T>;
  if(!isTauri())throw {code:'NATIVE_REQUIRED',message:'Откройте нативное приложение VoyahTune',detail:'Браузерная сборка не имеет доступа к USB. Для согласования интерфейса используйте Installer/prototype.',nextAction:'Запустите собранный установщик или npm run tauri dev.',retryable:false};
  return invoke<T>(command,args);
}
export async function subscribe(callback:(event:Event)=>void){if(import.meta.env.DEV && new URLSearchParams(location.search).has('fixture'))return (await import('./dev-fixture')).subscribe(callback);return isTauri()?listen<Event>('installer-event',e=>callback(e.payload)):()=>{};}

export async function onCloseBlocked(callback:()=>void){return isTauri()?listen('installer-close-blocked',callback):()=>{};}

export interface PayloadProgress {stage:string;bytes:number;total:number}
export interface Release {installerUpdates?:{version:string;platform:string;url:string}[];version:string;publishedAt:string;channel:string;notesUrl:string;compatible:boolean;incompatibility?:string;payload:{size:number;sha256:string};requirements:{minInstallerVersion:string}}
export interface CachedPayload {version:string;path:string;deletable?:boolean}
export interface CatalogState {catalog:{generatedAt:string;releases:Release[];installerDownloads:{version:string;platform:string;url:string}[]};cached:CachedPayload[];warning?:string;installerVersion:string}
export async function onPayloadProgress(callback:(event:PayloadProgress)=>void){if(import.meta.env.DEV && new URLSearchParams(location.search).has('fixture'))return (await import('./dev-fixture')).onPayloadProgress(callback);return isTauri()?listen<PayloadProgress>('payload-progress',e=>callback(e.payload)):()=>{};}
