package dev.superdrop.service.radio;

// Shizuku user-service interface for the IN-APP privileged radio path (Path B).
//
// WHAT: the AIDL the main app binds via Shizuku.bindUserService. Its impl
//   (RadioShellService) runs in a shell-UID process spawned by Shizuku, so it
//   can flip Wi-Fi/Bluetooth via `svc`/`cmd` shell commands that a normal app
//   (targetSdk 36) cannot.
// WHY: when Shizuku is present the app does everything the radio-helper APK did,
//   in-app, so the user needs only one app (Super Drop + Shizuku, not + helper).
// DIFFERENCE FROM THE HELPER'S IRadioShell: this one ALSO exposes Bluetooth
//   (setBluetoothEnabled / getBluetoothState) — the helper's is Wi-Fi only,
//   because the helper toggles BT with BluetoothAdapter.enable() (targetSdk<=32),
//   an option the modern-targetSdk main app does not have.
interface IRadioShell {
    // true if the radio was set (shell command exited 0).
    boolean setWifiEnabled(boolean enabled) = 1;

    // current Wi-Fi state per `settings get global wifi_on`, or -1 if unknown.
    int getWifiState() = 2;

    // true if Bluetooth was set (a shell command exited 0).
    boolean setBluetoothEnabled(boolean enabled) = 3;

    // current Bluetooth state per `settings get global bluetooth_on`, or -1 if unknown.
    int getBluetoothState() = 4;

    void destroy() = 16777114; // Shizuku reserves this transaction id for teardown
}
