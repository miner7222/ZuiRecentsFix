# ZuiRecentsFix

## Description

This LSPosed module is designed to resolve Android 16 based ZUI/ZUXOS Recents screen issues that appear when a third-party launcher is set as default.

The module is built with libxposed API 101 and requires an LSPosed framework that supports API 101 modules.

## Installation and Usage

### 1. Download the APK

Download the latest release from GitHub.

### 2. Enable the Module

Find `ZuiRecentsFix` in module list and enable it.

### 3. Check Scope

Make sure `com.android.systemui` and `com.zui.launcher` are included in module scope.

### 4. Reboot

## Notes

- ZUI 17.5 / ZUXOS 1.5: hooks `com.android.systemui` to keep Recents from failing to open with third-party launchers.
- ZUI 18.0: hooks `com.zui.launcher` fallback Recents so `RecentsActivity` does not start HOME after a non-home task has already launched from Recents.
