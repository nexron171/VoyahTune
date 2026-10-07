#!/bin/bash
VER=SCENARIOS
DIR=./Releases/build/$VER
echo "Release dir: $DIR"
mkdir -p $DIR
cd ./RunYN && ./gradlew assembleRelease && cp ./app/build/outputs/apk/release/app-release.apk .${DIR}/RunYN.apk && cd ..
cd ./Native && ./gradlew assembleRelease && cp ./app/build/outputs/apk/release/app-release.apk .${DIR}/Native.apk && cd ..
cd ./RestoreMode && ./gradlew assembleRelease && cp ./app/build/outputs/apk/release/app-release.apk .${DIR}/restore-mode.apk && cd ..


./Packaging/inject/build.sh ./Packaging/inject  &&  cp ./Packaging/inject/bin/{\
apollo_tech.js,\
clusternavi.js,\
keyboard_lock_en.js,\
keyboard_ru.js,\
launcherdock.js,\
multidisplay.js,\
steeringwheelkeys.js,\
vd_bypass.js} ./Packaging/inject/*.json  ${DIR}

cp  ./Packaging/system/init.logcat.sh $DIR
cp  ./Packaging/system/init.logcat.original.sh $DIR
cp  ./Packaging/system/privapp-permissions-ru.big.town.anative.xml $DIR
cp  ./loaderFrida/loaderFrida ./loaderFrida/init.voyah_tune.rc ./loaderFrida/*.json $DIR


for f in `ls -1 ./Scripts/*.sh`
do
  echo $f
  cp $f $DIR
  on=$(basename $f)
  cat $f | sed -r 's/sleep/pause/'| unix2dos  > $DIR/${on%%.sh}"-win11.bat"
  cat $f | sed -r 's/sleep/pause/'| unix2dos | iconv -futf8 -tcp1251  > $DIR/${on%%.sh}"-win10.bat"
  echo "$f $on"
done
ls -all $DIR

zip -r ./Releases/dist/release-CN-$VER.zip $DIR