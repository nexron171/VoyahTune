#!/usr/bin/env python3
"""Process-level ADB fixture. It executes installer shell logic in a private tree.
Never point it at a vehicle. Root locations in scripts are rewritten before sh.
Android commands have explicit stateful implementations; unknown commands fail.
"""
import hashlib,json,os,re,shutil,subprocess,sys,time
from pathlib import Path
base=Path(os.environ['VOYAH_FAKE_ROOT']).resolve()
root=base/'device'
state_file=base/'state.json'
def load():return json.loads(state_file.read_text())
def save(s):state_file.write_text(json.dumps(s))
def remote(path):return root/path.lstrip('/')
def record(args,script=None):
 with (base/'calls.jsonl').open('a') as f:f.write(json.dumps({'args':args,'script':script})+'\n')
def put(path,text=''):p=remote(path);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text);return p
def infrastructure():
 p=remote('/system/etc/voyahtune-ota-bootstrap.json')
 return json.loads(p.read_text()).get('infrastructure','od') if p.exists() else os.environ.get('VOYAH_INFRASTRUCTURE','od')
def package_data(package):
 for parent in ['/data/user/0/','/data/user_de/0/']:remote(parent+package).mkdir(parents=True,exist_ok=True)
def main():
 args=sys.argv[1:]; name=Path(sys.argv[0]).name;s=load()
 if name in ['fake-adb','adb']:
  if args[:1]==['-s']:
   if args[1]!='CAR-001':print('device not found',file=sys.stderr);return 1
   args=args[2:]
  record(args)
  if args[0]=='devices':
   print('List of devices attached')
   for serial,status in s.get('devices',[['CAR-001','device']]):print(serial,status,'product:qinggan model:Voyah_Free')
  elif args[0]=='root':
   if s.get('rootDenied'):print('adbd cannot run as root in production builds');return 0
   s['root']=True;save(s);print('restarting adbd as root')
   if s.get('changeOnRoot'):put('/data/local/bin/keyboard_ru.js','changed during root')
  elif args[0]=='wait-for-device':pass
  elif args[0] in ['disable-verity','remount']:
   print('Success');return 1 if s.get('failPrepareCommand') else 0
  elif args[0]=='reboot':
   if s.get('failReboot'):print('injected reboot failure',file=sys.stderr);return 1
   s.setdefault('locksAtReboot',[]).append(remote('/data/local/voyahtune-install.lock').exists())
   s['reboots']=s.get('reboots',0)+1;s['boot']=str(s['reboots']);s['root']=False
   put('/proc/sys/kernel/random/boot_id',s['boot']+'\n')
   package='ru.big.town.anative';path='/system/priv-app/Native/Native.apk'
   if remote(path).exists():
    if s.get('nativeOldHash') and hashlib.sha256(remote(path).read_bytes()).hexdigest()!=s['nativeOldHash']:
     s['packages'].pop(package,None)  # old registration rejects the changed system key
    elif not s['packages'].get(package,'').startswith('/data/app/'):
     s['packages'][package]=path;package_data(package)
   elif not s.get('retainNativeRegistration'):
    s['packages'].pop(package,None);s.pop('nativeOldHash',None)
   else:s['packages'][package]=path

   if not remote('/system/priv-app/VoyahHlCTRL').exists() and not any(remote('/data/system/package_cache').glob('*')):
    if s.get('canbusOwner')=='com.voyah.hl.service' and not s.get('retainCanbusOwner'):
     s.pop('canbusOwner',None)
    if not s.get('retainCanbusPackage'):
     s['packages'].pop('com.voyah.hl.service',None)
   if remote('/system/etc/init/voyahtune.updater.rc').exists() and remote('/data/local/bin/voyahtune-updater').exists():
    s['updater']='running';s['packages']['ru.big.town.updater']='/system/priv-app/VoyahTuneUpdater/VoyahTuneUpdater.apk';package_data('ru.big.town.updater')
   if remote('/data/local/bin/loaderFrida').exists() and remote('/system/etc/init/voyahtune.load.rc').exists():
    s['loader']='running'
    put('/proc/uptime','100.00 100.00\n')
    put('/proc/42/stat','42 (loaderFrida) S '+'0 '*18+'300 0\n')
    put('/proc/42/cmdline','/data/local/bin/loaderFrida\0')
    put('/data/local/tmp/voyahtune-pi-loader-status.json',json.dumps(dict(schema=1,infrastructure='pi',loaderPid=42,loaderStartTicks='300',bootId=s['boot'],updatedUptimeSeconds=100,state='running')))
   save(s)
  elif args[0]=='push':
   dest=remote(args[2]);dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(args[1],dest)
   if s.get('corruptPush') and args[2].endswith('.voyahtune.new'):dest.write_bytes(dest.read_bytes()+b'corrupted')
   print('1 file pushed')
  elif args[0]=='pull':
   if s.get('failPull'):print('device offline',file=sys.stderr);return 1
   if remote(args[1]).is_dir():shutil.copytree(remote(args[1]),args[2])
   else:shutil.copyfile(remote(args[1]),args[2])
   print('1 file pulled')
  elif args[0]=='install':
   if s.get('installError'):
    print('Failure ['+s['installError']+']',file=sys.stderr);return 1
   if s.get('rejectRestoreUpdate') and 'ru.big.town.restoremode' in s['packages']:
    print('Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package ru.big.town.restoremode signatures do not match previously installed version; ignoring!]',file=sys.stderr);return 1
   path='/data/app/ru.big.town.restoremode/base.apk';target=remote(path);target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(args[-1],target)
   s['packages']['ru.big.town.restoremode']=path;package_data('ru.big.town.restoremode');save(s);print('Success')
  elif args[0]=='shell':
   script=sys.stdin.read() if args[1:]==['sh','-s'] else ' '.join(args[1:]);record(args,script)
   if s.get('failShell') and s['failShell'] in script:print('injected shell failure',file=sys.stderr);return 1
   if script.strip()=='/data/local/bin/voyahtune-updater --version':
    print(json.dumps({'version':'0.2.0','ipcSchema':1,'infrastructure':infrastructure()}));return 0
   script=script.replace('/data/local/bin/voyahtune-updater --version', "printf '%s\\n' '"+json.dumps({'version':'0.2.0','ipcSchema':1,'infrastructure':infrastructure()})+"'")
   if 'sh /data/local/tmp/open_voyah_dns_overlay.sh' in script:
    # The DNS helper has separate repository tests; emulate only its host protocol here.
    if 'restore' in script:shutil.rmtree(remote('/data/local/open_voyah/qgdns'),ignore_errors=True)
    print('off');return 0
   if s.get('readOnly') and 'touch /system/' in script:print('RO');return 0
   script=re.sub(r'(?<![\w/])/(system|data|vendor|sdcard|proc)(?=/|[\s\'\";]|$)',lambda m:str(root)+'/'+m[1],script)
   env={**os.environ,'PATH':str(base/'bin')+':/usr/bin:/bin'}
   result=subprocess.run(['/bin/sh','-s'],input=script,text=True,env=env,capture_output=True)
   sys.stdout.write(result.stdout.replace(str(root),''));sys.stderr.write(result.stderr.replace(str(root),''));return result.returncode
  else:raise RuntimeError(f'Unknown ADB command: {args}')
 elif name=='grep':
  # Shell path remapping must not change a grep pattern that inspects bytes of a
  # pushed script/RC. Keep file arguments mapped into the fake car, but restore
  # the first non-option argument (the pattern) to its original Android paths.
  for i,arg in enumerate(args):
   if not arg.startswith('-'):
    args[i]=arg.replace(str(root),'');break
  return subprocess.run(['/usr/bin/grep',*args]).returncode
 elif name=='getprop':
  print({'ro.build.fingerprint':'qinggan/voyah/free:11/test','ro.product.model':'Voyah Free','ro.build.version.sdk':s.get('sdk','30'),'ro.product.cpu.abilist':s.get('abi','arm64-v8a,armeabi-v7a'),'sys.boot_completed':'1','init.svc.voyahtune_load':s.get('loader',''),'init.svc.voyahtune_updater':s.get('updater','')}.get(args[0],''))
 elif name=='setprop':
  if args[:2]==['ctl.stop','voyahtune_updater']:s['updater']='stopped';save(s)
  if args[:2]==['ctl.start','voyahtune_updater']:s['updater']='running';save(s)
  if args[:2]==['ctl.stop','voyahtune_load']:s['loader']='stopped';save(s)
 elif name=='id':print('0' if s.get('root') else '2000')
 elif name=='pm':
  if args[:2]==['list','packages']:
   for package in s['packages']:print('package:'+package)
  elif args[0]=='path':
   path=s['packages'].get(args[-1]);
   if path:print('package:'+path)
   else:return 1
  elif args[0]=='install':
   if s.get('nativeInstallFailure'):print('Failure [INSTALL_FAILED_TEST]');return 1
   source=Path(args[-1]);package='ru.big.town.updater' if 'VoyahTuneUpdater' in source.name else 'ru.big.town.anative'
   if not s.get('nativeInstallFalseSuccess'):
    path='/data/app/'+package+'/base.apk';target=remote(path);target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target)
    s['packages'][package]=path;package_data(package);save(s)
   print('Success')
  elif args[0]=='uninstall':
   package=args[-1]
   if s.get('failUninstall')==package:print('Failure [DELETE_FAILED_INTERNAL_ERROR]');return 1
   path=s['packages'].pop(package,None)
   if path and path.startswith('/data/app/'):shutil.rmtree(remote(path).parent,ignore_errors=True)
   if '-k' not in args:
    for parent in ['/data/user/0/','/data/user_de/0/']:shutil.rmtree(remote(parent+package),ignore_errors=True)
   save(s);print('Success')
  else:raise RuntimeError(args)
 elif name=='cmd':
  if args[:2]!=['package','install-existing']:raise RuntimeError(args)
  package=args[-1];s['packages'][package]='/system/priv-app/VoyahTuneUpdater/VoyahTuneUpdater.apk' if package=='ru.big.town.updater' else '/system/priv-app/Native/Native.apk';save(s);package_data(package);print('Package installed for user: 0')
 elif name=='timeout':
  if s.get('postflightTimeout'):return 124
  try:return subprocess.run(args[1:],timeout=float(args[0])).returncode
  except subprocess.TimeoutExpired:return 124
 elif name=='dumpsys':
  print('Permissions:')
  if 'canbusOwner' in s:
   print(' Permission [com.qinggan.permission.WRITE_CANBUS]')
   if s['canbusOwner'] is not None:print(' sourcePackage='+s['canbusOwner'])
  print(' Permission [android.permission.INTERNET]\n sourcePackage=android')
 elif name=='settings':
  key=args[2] if len(args)>2 else None;settings=s.setdefault('settings',{})
  if args[0]=='list':
   for k,v in settings.items():print(k+'='+v)
  elif args[0]=='get':
   print(settings.get(key,'null'))
  elif args[0]=='put':
   settings[key]=args[3];save(s)
  elif args[0]=='delete':
   settings.pop(key,None);save(s)
  else:raise RuntimeError(args)
 elif name=='chown':
  # Emulate Android ownership without requiring host root. Modes remain real filesystem modes.
  owners=s.setdefault('owners',{})
  for path in args[1:]:
   if not Path(path).exists():return 1
   owners[str(Path(path).relative_to(root))]=args[0]
  save(s)
 elif name=='stat':
  if args[:2]!=['-c','%a:%u:%g']:raise RuntimeError(args)
  p=Path(args[2]);owner=s.get('owners',{}).get(str(p.relative_to(root)),'0:0')
  print(f'{p.stat().st_mode & 0o7777:o}:{owner}')
 elif name=='am':
  if args[:2]==['force-stop',s.get('failForceStop')]:return 1
  if args[:1]==['broadcast'] and s.pop('restartSystemServerOnBroadcast',False):
   s['systemServerPid']='202';save(s);return 224
 elif name in ['restorecon','mount','pkill','ps']:pass
 elif name=='pidof':print(s.get('systemServerPid','101') if args[0]=='system_server' else '101')
 elif name=='sha256sum':
  for path in args:
   actual=remote(path) if path.startswith(('/system/','/data/')) else Path(path)
   print(hashlib.sha256(actual.read_bytes()).hexdigest()+'  '+path)
 else:raise RuntimeError(f'Unknown device command {name}')
 return 0
if __name__=='__main__':
 try:sys.exit(main())
 except Exception as e:print(str(e),file=sys.stderr);sys.exit(1)
