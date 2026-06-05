package io.github.miner7222.fixrecents

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

internal fun currentApplication(): Application? {
    return try {
        val activityThread = Class.forName("android.app.ActivityThread")
        val method = activityThread.getDeclaredMethod("currentApplication")
        method.isAccessible = true
        method.invoke(null) as? Application
    } catch (ignored: Exception) {
        null
    }
}

internal fun isThirdPartyLauncherDefault(context: Context): Boolean {
    val packageName = resolveDefaultHomePackage(context) ?: return false
    return isThirdPartyLauncherPackage(packageName)
}

internal fun resolveDefaultHomePackage(context: Context): String? {
    val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val resolved = context.packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
    return resolved?.activityInfo?.packageName
}

internal fun isThirdPartyLauncherPackage(packageName: String): Boolean {
    return packageName != "android" && packageName != HookConstants.LAUNCHER_PACKAGE
}
