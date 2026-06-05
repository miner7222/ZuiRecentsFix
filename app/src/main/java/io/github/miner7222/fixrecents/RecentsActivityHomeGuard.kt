package io.github.miner7222.fixrecents

internal object RecentsActivityHomeGuard {

    fun shouldSuppressStartHome(
        hasThirdPartyDefaultLauncher: Boolean,
        topTaskPackageName: String?,
        topTaskIsHome: Boolean,
        topTaskIsLauncherPackage: Boolean
    ): Boolean {
        return hasThirdPartyDefaultLauncher &&
            topTaskPackageName != null &&
            !topTaskIsHome &&
            !topTaskIsLauncherPackage
    }
}
