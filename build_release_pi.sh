#!/bin/bash
VER=TEST
DIR=./Releases/build/$VER
echo "Release dir: $DIR"
mkdir -p $DIR

cd ./RunYN && ./gradlew assembleRelease && cp ./app/build/outputs/apk/release/app-release.apk .${DIR}/RunYN.apk && cd ..
cd ./Native && ./gradlew assembleRelease && cp ./app/build/outputs/apk/release/app-release.apk .${DIR}/Native.apk && cd ..
cd ./RestoreMode && ./gradlew assembleRelease && cp ./app/build/outputs/apk/release/app-release.apk .${DIR}/restore-mode.apk && cd ..

./Packaging/pi/inject/build.sh ./Packaging/pi/inject  &&  cp ./Packaging/pi/inject/bin/{\
apollo_tech.js,\
clusternavi.js,\
keyboard_lock_en.js,\
keyboard_ru.js,\
launcherdock.js,\
multidisplay.js,\
steeringwheelkeys.js,\
vd_bypass.js} ./Packaging/pi/inject/*.json  ${DIR}

cp  ./Packaging/pi/system/init.logcat.sh $DIR
cp  ./Packaging/pi/tools/frida-inject-16.2.1-android-arm64 $DIR/frida-inject
cp  ./Packaging/pi/system/init.logcat.original.sh $DIR
cp  ./Packaging/pi/system/privapp-permissions-ru.big.town.anative.xml $DIR
cp  ./Packaging/pi/loaderFrida/loaderFrida ./Packaging/pi/loaderFrida/init.voyah_tune.rc ./Packaging/pi/loaderFrida/*.json $DIR
cp ./Packaging/pi/tools/*.exe ./Packaging/pi/tools/*.dll $DIR

for f in `ls -1 ./Packaging/pi/Scripts/*.sh`
do
  echo $f
  cp $f $DIR
  on=$(basename $f)
  cat $f | sed -r 's/sleep/pause/'| sed -r 's/#!\/bin\/.*sh//' | sed -r 's/#/rem/' | sed -r 's/%/%%/g' | unix2dos  > $DIR/${on%%.sh}"-win11.bat"
  cat $f | sed -r 's/sleep/pause/'| sed -r 's/#!\/bin\/.*sh//' | sed -r 's/#/rem/' | sed -r 's/%/%%/g' | unix2dos | iconv -futf8 -tcp1251  > $DIR/${on%%.sh}"-win10.bat"
  echo "$f $on"
done
ls -all $DIR

zip -r ./Releases/dist/release-CN-$VER.zip $DIR
