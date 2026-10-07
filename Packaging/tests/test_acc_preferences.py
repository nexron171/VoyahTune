#!/usr/bin/env python3
"""Execute the real provider policy with in-memory Android preference/Bundle adapters."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
BUNDLE = r"""
package android.os;
import java.util.HashMap;
public class Bundle {
    private final HashMap<String,Object> values = new HashMap<>();
    public void putInt(String k,int v){values.put(k,v);}
    public void putLong(String k,long v){values.put(k,v);}
    public void putBoolean(String k,boolean v){values.put(k,v);}
    public void putString(String k,String v){values.put(k,v);}
    public int getInt(String k,int d){return (Integer)values.getOrDefault(k,d);}
    public long getLong(String k,long d){return (Long)values.getOrDefault(k,d);}
    public boolean getBoolean(String k){return (Boolean)values.getOrDefault(k,false);}
    public String getString(String k){return (String)values.get(k);}
    public boolean containsKey(String k){return values.containsKey(k);}
}
"""
PREFERENCES = r"""
package android.content;
public interface SharedPreferences {
    String getString(String k,String d);
    boolean getBoolean(String k,boolean d);
    int getInt(String k,int d);
    long getLong(String k,long d);
    Editor edit();
    interface Editor {
        Editor putString(String k,String v);
        Editor putBoolean(String k,boolean v);
        Editor putInt(String k,int v);
        Editor putLong(String k,long v);
        Editor remove(String k);
        boolean commit();
    }
}
"""
HARNESS = r"""
package ru.big.town.restoremode.vehicle;
import android.os.Bundle;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import ru.big.town.common.DriveSelectionPolicy;
public final class AccPreferencesHarness {
    static final class Memory implements SharedPreferences {
        final Map<String,Object> disk;
        boolean commitFails;
        Memory(){this(new HashMap<>());}
        Memory(Map<String,Object> disk){this.disk=disk;}
        public String getString(String k,String d){return (String)disk.getOrDefault(k,d);}
        public boolean getBoolean(String k,boolean d){return (Boolean)disk.getOrDefault(k,d);}
        public int getInt(String k,int d){return (Integer)disk.getOrDefault(k,d);}
        public long getLong(String k,long d){return (Long)disk.getOrDefault(k,d);}
        public Editor edit(){return new Editor(){
            final Map<String,Object> pending=new HashMap<>();
            public Editor remove(String k){pending.put(k,null);return this;}
            public Editor putString(String k,String v){pending.put(k,v);return this;}
            public Editor putBoolean(String k,boolean v){pending.put(k,v);return this;}
            public Editor putInt(String k,int v){pending.put(k,v);return this;}
            public Editor putLong(String k,long v){pending.put(k,v);return this;}
            public boolean commit(){if(commitFails)return false;disk.putAll(pending);disk.values().removeIf(java.util.Objects::isNull);return true;}
        };}
    }
    static void equal(Object expected,Object actual){
        if(!Objects.equals(expected,actual))throw new AssertionError(expected+" != "+actual);
    }
    static Bundle action(Memory p,String name,Bundle args,int boot){
        return DriveSelectionPreferences.hook(p,name,args,boot);
    }
    static Bundle acc(Memory p,int value,int boot){
        Bundle b=new Bundle();b.putInt("acc",value);return action(p,"acc",b,boot);
    }
    static Bundle claim(Memory p){return action(p,"claimSettings",null,1);}
    static void complete(Memory p,long cycle,boolean accepted){
        Bundle b=new Bundle();b.putLong("cycle",cycle);b.putBoolean("accepted",accepted);
        action(p,"completeSettings",b,1);
    }
    static void next(Memory p){acc(p,0,1);acc(p,2,1);}
    static void scenario(String name,Runnable test){test.run();System.out.println("PASS: "+name);}
    public static void main(String[] args){
        scenario("one settings claim per ACC survives provider recreation",()->{
            Memory p=new Memory();Bundle first=acc(p,2,1);
            equal(1L,first.getLong("cycle",-1));equal("pending",first.getString("settingsStartup"));
            equal(true,claim(p).getBoolean("claimed"));
            Memory restarted=new Memory(new HashMap<>(p.disk));
            equal(false,claim(restarted).getBoolean("claimed"));
            complete(restarted,1,true);
            equal("submitted",acc(restarted,2,1).getString("settingsStartup"));
            equal(false,claim(restarted).getBoolean("claimed"));
            next(restarted);equal(2L,claim(restarted).getLong("cycle",-1));
        });
        scenario("uncertain submission waits for next ACC",()->{
            Memory p=new Memory();acc(p,2,1);claim(p);complete(p,1,false);
            equal("uncertain",claim(p).getString("settingsStartup"));
            equal(false,claim(p).getBoolean("claimed"));next(p);
            equal(true,claim(p).getBoolean("claimed"));
        });
        scenario("old completion cannot overwrite new cycle",()->{
            Memory p=new Memory();acc(p,2,1);claim(p);next(p);claim(p);
            complete(p,1,true);equal("claimed",claim(p).getString("settingsStartup"));
            complete(p,2,true);equal("submitted",claim(p).getString("settingsStartup"));
        });
        scenario("OFF and snapshots do not claim or create another cycle",()->{
            Memory p=new Memory();acc(p,0,1);equal(false,claim(p).getBoolean("claimed"));
            acc(p,2,1);long cycle=action(p,"snapshot",null,1).getLong("cycle",-1);
            for(int i=0;i<5;i++)equal(cycle,action(p,"snapshot",null,1).getLong("cycle",-1));
            equal(cycle,acc(p,2,1).getLong("cycle",-1));
        });
        scenario("claim storage failure does not mark operation claimed",()->{
            Memory p=new Memory();acc(p,2,1);p.commitFails=true;
            try{claim(p);throw new AssertionError("failed commit accepted");}
            catch(IllegalStateException expected){}
            equal("pending",p.disk.get("settingsStartup"));p.commitFails=false;
            equal(true,claim(p).getBoolean("claimed"));
        });
        scenario("cold boot clears current trip but keeps pinned opt-out targets",()->{
            Memory p=new Memory();p.disk.put("driveMode","COMFORT");p.disk.put("driveRememberLast",false);
            p.disk.put("energy","SREV");p.disk.put("energyRememberLast",false);acc(p,2,1);
            DriveSelectionPreferences.select(p,"SPORT",DriveSelectionPolicy.EXPLICIT);
            DriveSelectionPreferences.selectEnergy(p,"EV",false);
            equal("SPORT",action(p,"snapshot",null,1).getString("mode"));
            equal("EV",action(p,"snapshot",null,1).getString("energy"));
            Bundle boot=acc(p,2,2);equal("COMFORT",boot.getString("mode"));
            equal("SREV",boot.getString("energy"));equal("pending",boot.getString("settingsStartup"));
        });
        scenario("manual intent cancels old startup completion",()->{
            Memory p=new Memory();p.disk.put("driveEnabled",true);acc(p,2,1);
            long rev=action(p,"snapshot",null,1).getLong("revision",-1);
            Bundle b=new Bundle();b.putLong("revision",rev);
            equal(true,action(p,"claim",b,1).getBoolean("claimed"));
            DriveSelectionPreferences.select(p,"SPORT",DriveSelectionPolicy.EXPLICIT);
            b.putBoolean("accepted",true);action(p,"complete",b,1);
            equal("selected",action(p,"snapshot",null,1).getString("startup"));
            equal(false,action(p,"claim",b,1).getBoolean("claimed"));
        });
        scenario("recuperation remembers explicit intent and respects pinned opt-out",()->{
            for(boolean remember:new boolean[]{true,false}){
                Memory p=new Memory();p.disk.put("recycle","LOW");p.disk.put("recycleRememberLast",remember);
                p.disk.put("recycleEnabled",true);acc(p,2,1);
                Bundle chosen=new Bundle();chosen.putString("recycle","HIGH");action(p,"user",chosen,1);
                equal("HIGH",DriveSelectionPreferences.recycle(new Memory(new HashMap<>(p.disk))));
                next(p);equal(remember ? "HIGH" : "LOW",DriveSelectionPreferences.recycle(p));
                equal(false,DriveSelectionPreferences.selectRecycle(p,"INVALID"));
            }
        });
        scenario("forced EV preserves ordinary engine target across ACC",()->{
            Memory p=new Memory();p.disk.put("energy","SREV");acc(p,2,1);
            DriveSelectionPreferences.selectEnergy(p,"FORCE_EV",false);next(p);
            Bundle snap=action(p,"snapshot",null,1);equal("SREV",snap.getString("configuredEnergy"));
            equal(true,p.disk.get("forcedEv"));equal("SREV",snap.getString("energy"));
        });
    }
}
"""

def main():
    java_home = Path(os.environ["JAVA_HOME"]) / "bin" if os.environ.get("JAVA_HOME") else None
    javac = str(java_home / "javac") if java_home else shutil.which("javac")
    java = str(java_home / "java") if java_home else shutil.which("java")
    if not javac or not java:
        raise SystemExit("JDK required: set JAVA_HOME or put javac/java on PATH")
    with tempfile.TemporaryDirectory(prefix="voyahtune-acc-policy-") as tmp:
        temp = Path(tmp)
        sources = []
        for name, contents in {"android/os/Bundle.java": BUNDLE,
                               "android/content/SharedPreferences.java": PREFERENCES,
                               "ru/big/town/restoremode/vehicle/AccPreferencesHarness.java": HARNESS}.items():
            path = temp / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(contents)
            sources.append(str(path))
        sources += [str(ROOT / "RestoreMode/app/src/main/java/ru/big/town/restoremode/vehicle/DriveSelectionPreferences.java"),
                    str(ROOT / "SharedAndroid/src/main/java/ru/big/town/common/DriveSelectionPolicy.java")]
        subprocess.run([javac, "--release", "11", "-d", str(temp), *sources], check=True)
        subprocess.run([java, "-cp", str(temp), "ru.big.town.restoremode.vehicle.AccPreferencesHarness"], check=True)

if __name__ == "__main__":
    main()
