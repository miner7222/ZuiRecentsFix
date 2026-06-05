package io.github.miner7222.fixrecents

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentsActivityHomeGuardTest {

    @Test
    fun suppressesStartHomeAfterNonHomeTaskLaunchWithThirdPartyLauncher() {
        assertTrue(
            RecentsActivityHomeGuard.shouldSuppressStartHome(
                hasThirdPartyDefaultLauncher = true,
                topTaskPackageName = "com.android.settings",
                topTaskIsHome = false,
                topTaskIsLauncherPackage = false
            )
        )
    }

    @Test
    fun keepsStartHomeWhenDefaultLauncherIsStockZui() {
        assertFalse(
            RecentsActivityHomeGuard.shouldSuppressStartHome(
                hasThirdPartyDefaultLauncher = false,
                topTaskPackageName = "com.android.settings",
                topTaskIsHome = false,
                topTaskIsLauncherPackage = false
            )
        )
    }

    @Test
    fun keepsStartHomeWhenTopTaskIsHome() {
        assertFalse(
            RecentsActivityHomeGuard.shouldSuppressStartHome(
                hasThirdPartyDefaultLauncher = true,
                topTaskPackageName = "com.teslacoilsw.launcher",
                topTaskIsHome = true,
                topTaskIsLauncherPackage = true
            )
        )
    }

    @Test
    fun keepsStartHomeWhenTopTaskIsRecentsOrLauncher() {
        assertFalse(
            RecentsActivityHomeGuard.shouldSuppressStartHome(
                hasThirdPartyDefaultLauncher = true,
                topTaskPackageName = "com.zui.launcher",
                topTaskIsHome = false,
                topTaskIsLauncherPackage = true
            )
        )
    }

    @Test
    fun keepsStartHomeWhenTopTaskCannotBeRead() {
        assertFalse(
            RecentsActivityHomeGuard.shouldSuppressStartHome(
                hasThirdPartyDefaultLauncher = true,
                topTaskPackageName = null,
                topTaskIsHome = false,
                topTaskIsLauncherPackage = false
            )
        )
    }
}
