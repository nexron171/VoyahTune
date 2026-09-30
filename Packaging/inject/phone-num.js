// phone-num.js — Frida agent for com.qinggan.bluetoothphone (PI firmware, BluetoothPhone-release-signed).
//
// Stock Util.getAmendNumber() on a CN build strips the leading "+" and a "86" prefix, so
// "+79203711943" was dialed as "79203711943" and the operator rejected it. The phone then
// stores that call as "792-037-11943", and redialing from the call log repeats the error.
//
//   * Util.getAmendNumber  — keep "+", drop only formatting (spaces, dashes, parentheses);
//   * HeadSetProfileManager.makeCall(String[, String]) — the single entry to HFP dial():
//     normalize right before dialing, which also repairs numbers that already lost "+"
//     (call log entries, contacts cached before the hook was installed);
//   * PbapProfileManager.startSync — reload contacts through the patched function.

Java.perform(function () {
  "use strict";

  const TAG = "phone-num";
  const Log = Java.use("android.util.Log");

  // 11 digits starting with 7 without "+" is not dialable in Russia/Kazakhstan: that is an
  // international +7 number that lost its "+" (domestic form starts with 8).
  const LOST_PLUS_7 = /^7\d{10}$/;

  function normalizeNumber(number) {
    if (number === null) return "";
    const trimmed = ("" + number).trim();
    if (trimmed === "+") return "+";
    // Keep dial symbols (* # , ; p w), remove only visual formatting.
    const compact = trimmed.replace(/[\s\-()]/g, "");
    return LOST_PLUS_7.test(compact) ? "+" + compact : compact;
  }

  const Util = Java.use("com.qinggan.bluetoothphone.util.Util");
  Util.getAmendNumber.overload("java.lang.String").implementation = normalizeNumber;

  const HeadSet = Java.use("com.qinggan.bluetoothphone.logic.manager.HeadSetProfileManager");
  const makeCall = HeadSet.makeCall.overload("java.lang.String");
  makeCall.implementation = function (number) {
    const fixed = normalizeNumber(number);
    Log.i(TAG, "makeCall " + number + " -> " + fixed);
    return makeCall.call(HeadSet, fixed);
  };
  const makeCallOnDevice = HeadSet.makeCall.overload("java.lang.String", "java.lang.String");
  makeCallOnDevice.implementation = function (number, mac) {
    const fixed = normalizeNumber(number);
    Log.i(TAG, "makeCall " + number + " -> " + fixed + " mac=" + mac);
    return makeCallOnDevice.call(HeadSet, fixed, mac);
  };

  try {
    Java.use("com.qinggan.bluetoothphone.logic.manager.PbapProfileManager").startSync();
  } catch (error) {
    Log.e(TAG, "contact resync failed: " + error);
  }

  Log.i(TAG, "Agent started");
});
