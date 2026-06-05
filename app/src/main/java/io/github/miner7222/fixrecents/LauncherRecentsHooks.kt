package io.github.miner7222.fixrecents

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log

internal class LauncherRecentsHooks(
    private val scope: ModernHookScope,
) {
    fun install() {
        scope.interceptMethod(HookConstants.RECENTS_ACTIVITY_CLASS, "M") { chain ->
            if (shouldSuppressRecentsActivityStartHome(chain.thisObject)) {
                null
            } else {
                chain.proceed()
            }
        }
    }

    private fun shouldSuppressRecentsActivityStartHome(activity: Any?): Boolean {
        val context = (activity as? Context) ?: currentApplication() ?: return false
        val defaultHomePackage = resolveDefaultHomePackage(context) ?: return false
        val hasThirdPartyDefaultLauncher = isThirdPartyLauncherPackage(defaultHomePackage)
        val topTask = findTopRunningTask(context)
        val topPackageName = topTask?.let { getTaskPackageName(it) }
        val topTaskIsHome = topTask?.let { isHomeTask(it) } ?: false
        val topTaskIsLauncher =
            topPackageName == HookConstants.LAUNCHER_PACKAGE || topPackageName == defaultHomePackage

        val shouldSuppress = RecentsActivityHomeGuard.shouldSuppressStartHome(
            hasThirdPartyDefaultLauncher = hasThirdPartyDefaultLauncher,
            topTaskPackageName = topPackageName,
            topTaskIsHome = topTaskIsHome,
            topTaskIsLauncherPackage = topTaskIsLauncher,
        )

        val logMessage = "RecentsActivity.StartHomeFromRecents " +
            "defaultHome=$defaultHomePackage topPackage=$topPackageName " +
            "topHome=$topTaskIsHome topLauncher=$topTaskIsLauncher"
        if (shouldSuppress) {
            Log.i(HookConstants.TAG, "Suppressing $logMessage")
        } else {
            Log.i(HookConstants.TAG, "Allowing $logMessage")
        }

        return shouldSuppress
    }

    private fun findTopRunningTask(context: Context): ActivityManager.RunningTaskInfo? {
        return findRunningTasks(context).firstOrNull()
    }

    private fun findRunningTasks(context: Context): List<ActivityManager.RunningTaskInfo> {
        return try {
            val activityManager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
            activityManager.getRunningTasks(HookConstants.MAX_RUNNING_TASKS)
        } catch (ignored: Exception) {
            emptyList()
        }
    }

    private fun isHomeTask(task: ActivityManager.RunningTaskInfo): Boolean {
        return getActivityType(task) == HookConstants.ACTIVITY_TYPE_HOME ||
            task.baseIntent?.hasCategory(Intent.CATEGORY_HOME) == true
    }

    private fun getTaskPackageName(task: ActivityManager.RunningTaskInfo): String? {
        return task.topActivity?.packageName ?: task.baseActivity?.packageName ?: task.baseIntent?.component?.packageName
    }

    private fun getActivityType(task: ActivityManager.RunningTaskInfo): Int? {
        return try {
            task.javaClass.getField("topActivityType").getInt(task)
        } catch (ignored: Exception) {
            try {
                task.javaClass.getMethod("getActivityType").invoke(task) as? Int
            } catch (ignored: Exception) {
                null
            }
        }
    }
}
