#!/usr/bin/env node
// Build toolchains stay on the build host; end-user packages contain their ADB.
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
import {dirname,resolve,join} from 'node:path';
import {readFile,writeFile,mkdir,rm,cp,chmod,rename,readdir} from 'node:fs/promises';
import {existsSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';

const root=resolve(dirname(fileURLToPath(import.meta.url)),'../..');
const args=process.argv.slice(2);
const flag=name=>args.includes(name);
const option=name=>{const i=args.indexOf(name);return i<0?undefined:args[i+1];};
if(flag('--pi')||flag('--od'))throw Error('The GUI supports both infrastructures; use --pi/--od when building the payload.');
const catalogUrl=option('--catalog-url')||'';
if(catalogUrl){const u=new URL(catalogUrl);if(u.protocol!=='https:'||u.username||u.password||u.hash)throw Error('Catalog must be an HTTPS URL without credentials or fragment');}
if(option('--version'))throw Error('Installer version is defined in Installer/Cargo.toml.');
const payloadPath=option('--payload')?resolve(option('--payload')):undefined;
const payloadManifest=payloadPath?JSON.parse(await readFile(join(payloadPath,'manifest.json'),'utf8')):undefined;
const toolingVersion=(await readFile(join(root,'Installer/Cargo.toml'),'utf8')).match(/version = "([^"]+)"/)[1];
const packageVersion=toolingVersion;
const env={...process.env,VOYAH_INFRASTRUCTURE:'od',VOYAH_CATALOG_URL:catalogUrl};
const cachedCargo=join(root,'Releases/cache/cargo');
if(!env.CARGO_HOME&&existsSync(cachedCargo)){
  env.CARGO_HOME=cachedCargo;
  env.RUSTUP_HOME=join(root,'Releases/cache/rustup');
  env.PATH=join(cachedCargo,'bin')+(process.platform==='win32'?';':':')+env.PATH;
}
function run(command,argv,cwd=root,capture=false){
  if(process.platform==='win32'&&command==='npm'){argv=['/d','/s','/c','npm',...argv];command='cmd.exe';}
  const result=spawnSync(command,argv,{cwd,env,stdio:capture?'pipe':'inherit',encoding:'utf8'});
  if(result.error||result.status!==0)throw Error(`${command} failed: ${result.error||result.stderr||result.status}`);
  return result.stdout?.trim();
}
const toolchain=(await readFile(join(root,'Installer/rust-toolchain.toml'),'utf8')).match(/^channel\s*=\s*"([^"]+)"/m)?.[1];
if(!toolchain)throw Error('Missing pinned Rust toolchain');
env.RUSTUP_TOOLCHAIN=toolchain;
env.CI='true';
const desktop=join(root,'Installer/desktop');
const host=run('rustc',['-vV'],root,true).split('\n').find(line=>line.startsWith('host: ')).slice(6);
const target=option('--target')||(process.platform==='darwin'?'universal-apple-darwin':process.platform==='win32'?'x86_64-pc-windows-msvc':host);
const supported=['universal-apple-darwin','aarch64-apple-darwin','x86_64-apple-darwin',
  'x86_64-unknown-linux-gnu','x86_64-pc-windows-msvc','i686-pc-windows-msvc'];
if(!supported.includes(target))throw Error(`Unsupported target: ${target}`);
const platform=target.includes('windows')?'windows':target.includes('linux')?'linux':'darwin';
if(platform==='darwin'&&process.platform!=='darwin')throw Error('macOS packages require a macOS build host');
if(platform==='linux'&&target!==host)throw Error('Build each Linux architecture in its matching container');
const crossWindows=platform==='windows'&&process.platform!=='win32';
if(crossWindows&&target==='i686-pc-windows-msvc'){
  // cargo-xwin defaults to x64/ARM64; keep its x86 SDK separate from that cache.
  env.XWIN_ARCH='x86';
  env.XWIN_CACHE_DIR=env.XWIN_CACHE_DIR||join(root,'Releases/cache/cargo-xwin-x86');
}
const targetRoot=env.CARGO_TARGET_DIR?resolve(env.CARGO_TARGET_DIR):join(root,'Installer/target');
const hostExe=join(targetRoot,'release',`installer-build${process.platform==='win32'?'.exe':''}`);
const cargoArgs=['--locked','--release','--manifest-path',join(root,'Installer/Cargo.toml')];
run('cargo',['build',...cargoArgs,'-p','installer-build']);
if(payloadPath){
  const verified=JSON.parse(run(hostExe,['verify-payload',payloadPath],root,true));
  if(verified.valid!==true)throw Error('Payload verification failed');
}
run('npm',['ci','--cache',join(root,'Releases/cache/npm')],desktop);
const require=createRequire(join(desktop,'package.json'));
const {unzipSync}=require('fflate');

const resources=join(desktop,'src-tauri/resources');
await mkdir(resources,{recursive:true});
const bundle=join(resources,'bundle.staging');
await rm(bundle,{recursive:true,force:true});
await mkdir(join(bundle,'adb'),{recursive:true});
if(payloadPath)await cp(payloadPath,join(bundle,'payload'),{recursive:true});
run(hostExe,['recovery','--root',root,'--output',join(bundle,'recovery')]);
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const lock=JSON.parse(await readFile(join(root,'Installer/platform-tools.lock.json'),'utf8'));
const adbVersion=lock.version;
{
  const archive=lock.archives[platform];
  const cache=join(root,'Releases/cache/platform-tools');
  await mkdir(cache,{recursive:true});
  const archivePath=join(cache,`${platform}.zip`);
  if(!existsSync(archivePath)){
    const response=await fetch(archive.url);
    if(!response.ok)throw Error(`ADB download failed: ${response.status}`);
    await writeFile(archivePath,new Uint8Array(await response.arrayBuffer()));
  }
  const bytes=await readFile(archivePath);
  if(hash(bytes)!==archive.sha256)throw Error(`ADB archive SHA-256 mismatch: ${archivePath}`);
  for(const [entry,content] of Object.entries(unzipSync(bytes))){
    if(!entry.startsWith('platform-tools/')||entry.endsWith('/'))continue;
    const name=entry.slice('platform-tools/'.length);
    if(name.split('/').some(part=>part==='..')||name.startsWith('/'))throw Error('Unsafe archive entry');
    if(target==='i686-pc-windows-msvc'&&/\.(exe|dll)$/i.test(name)){
      const pe=Buffer.from(content);
      const header=pe.length>=64?pe.readUInt32LE(0x3c):-1;
      if(header<0||header+6>pe.length||pe.toString('ascii',header,header+4)!=='PE\0\0'||pe.readUInt16LE(header+4)!==0x14c)throw Error(`Windows x86 requires 32-bit ADB binaries: ${name}`);
    }
    const destination=join(bundle,'adb',name);
    await mkdir(dirname(destination),{recursive:true});
    await writeFile(destination,content);
    if(platform!=='windows'&&['adb','fastboot','etc1tool','hprof-conv','sqlite3','make_f2fs','make_f2fs_casefold','mke2fs'].includes(name))await chmod(destination,0o755);
  }
}
const hostFiles=[];
async function recordFiles(directory,prefix){
  for(const entry of await readdir(directory,{withFileTypes:true})){
    const file=join(directory,entry.name), relative=`${prefix}/${entry.name}`;
    if(entry.isDirectory())await recordFiles(file,relative);
    else if(entry.isFile())hostFiles.push({path:relative,sha256:hash(await readFile(file))});
    else throw Error(`ADB bundle must not contain symlinks: ${file}`);
  }
}
await recordFiles(join(bundle,'adb'),'adb');
await recordFiles(join(bundle,'recovery'),'recovery');
await writeFile(join(bundle,'host-tools.json'),JSON.stringify({schema:1,platform,target,version:adbVersion,files:hostFiles},null,2)+'\n');
const verified=JSON.parse(run(hostExe,['verify-host',bundle],root,true));
if(verified.valid!==true)throw Error('Bundle verification failed');
console.log(`Prepared GUI ${toolingVersion}; optional payload ${payloadManifest?.releaseVersion||"none"}`);
const finalBundle=join(resources,'bundle');
await rm(finalBundle,{recursive:true,force:true});
await rename(bundle,finalBundle);

// Remove resources left by old builds: the GUI calls installer-core directly.
await rm(join(desktop,'src-tauri/binaries'),{recursive:true,force:true});
if(flag('--prepare-only')){console.log(`Prepared GUI resources: ${finalBundle}`);process.exit(0);}
const releaseConfig={version:packageVersion};
// Platform-independent web assets may be built on the native host to avoid running
// esbuild's Go runtime under x64 emulation. The orchestrator supplies a fresh build.
if(option('--frontend-dist')){
  const frontend=resolve(option('--frontend-dist'));
  if(!existsSync(join(frontend,'index.html')))throw Error('Prebuilt frontend is missing index.html');
  releaseConfig.build={frontendDist:frontend,beforeBuildCommand:'node -e "process.exit(0)"'};
}
if(platform==='linux'){
  const files={'/usr/share/voyahtune-installer/bundle/':'resources/bundle/'};
  releaseConfig.bundle={resources:[],linux:{appimage:{files},deb:{files}}};
}
await writeFile(join(desktop,'src-tauri/tauri.release.conf.json'),JSON.stringify(releaseConfig)+'\n');
const bundles=option('--bundles')||(platform==='windows'?'nsis':platform==='linux'?'appimage,deb':'app,dmg');
if(!/^(app|dmg|nsis|appimage|deb)(,(app|dmg|nsis|appimage|deb))*$/.test(bundles))throw Error('Unsupported --bundles selection');
if(platform==='linux'&&!flag('--no-bundle')){
  await rm(join(targetRoot,'release/bundle/appimage/VoyahTune Installer.AppDir'),{recursive:true,force:true});
  await rm(join(targetRoot,'release/bundle/appimage_deb'),{recursive:true,force:true});
}
run('npm',['run','tauri','--','build','--config','src-tauri/tauri.release.conf.json',
  ...(target!==host?['--target',target]:[]),...(crossWindows?['--runner','cargo-xwin']:[]),
  ...(flag('--no-bundle')?['--no-bundle']:['--bundles',bundles])],desktop);
const output=join(targetRoot,...(target===host?[]:[target]),'release/bundle');
if(platform==='darwin'&&!flag('--no-bundle')){
  const app=join(output,'macos/VoyahTune Installer.app/Contents');
  const packaged=JSON.parse(run(hostExe,['verify-host',join(app,'Resources/bundle')],root,true));
  if(packaged.valid!==true)throw Error('Packaged application verification failed');
  if(payloadPath){
    const embedded=JSON.parse(run(hostExe,['verify-payload',join(app,'Resources/bundle/payload')],root,true));
    if(embedded.valid!==true||embedded.manifest.releaseVersion!==payloadManifest.releaseVersion||!embedded.payloadRoot.startsWith(app))throw Error('Embedded macOS payload verification failed');
  }
  run(join(app,'Resources/bundle/adb/adb'),['version']);
}
console.log(`Native packages: ${output}`);
