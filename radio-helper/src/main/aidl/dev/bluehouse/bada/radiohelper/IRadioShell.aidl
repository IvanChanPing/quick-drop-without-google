package dev.bluehouse.bada.radiohelper;

// Shizuku user-service interface. The implementation runs in a shell-UID
// process (spawned by Shizuku), so it can flip Wi-Fi via `svc wifi`.
interface IRadioShell {
    // true if the radio was set (shell command exited 0).
    boolean setWifiEnabled(boolean enabled) = 1;

    // current Wi-Fi state per `settings get global wifi_on`, or -1 if unknown.
    int getWifiState() = 2;

    void destroy() = 16777114; // Shizuku reserves this transaction id for teardown
}
