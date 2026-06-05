# ZuiRecentsFix

## Description

This LSPosed module is designed to resolve ZUI(ZUXOS) A16 Recents issues that appear when a third-party launcher is set as default.

While Lenovo has officially fixed this bug in certain builds released since March 2026 (e.g., `TB321FU_ROW_OPEN_USER_Q00002.0_W_ZUI_17.5.10.170_ST_260321`), this module serves as a workaround for users on other models awaiting the update or those who prefer to remain on older builds.

The module is built with libxposed API 101 and requires an LSPosed/modern Xposed framework that supports API 101 modules.

It is recommended to uninstall this module once you receive an official OTA update that includes the fix.

## Installation and Usage

### 1. Download the APK

Download the latest release from GitHub.

### 2. Enable the Module

Find `ZuiRecentsFix` in module list and enable it.

### 3. Check Scope

Make sure `com.android.systemui` and `com.zui.launcher` are included in module scope.

### 4. Reboot

## Notes

- Modern Xposed metadata is packaged under `META-INF/xposed/`; static scope includes `com.android.systemui` and `com.zui.launcher`.
- TB322/ZUXOS 1.5.10: hooks `com.android.systemui` to keep Recents from failing to open with third-party launchers.
- TB323/ZUI 18.0.10.084: hooks `com.zui.launcher` fallback Recents so `RecentsActivity` does not start HOME after a non-home task has already launched from Recents.
- Runtime diagnostics use the `ZuiRecentsFix` log tag.
