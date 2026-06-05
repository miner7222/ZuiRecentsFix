package io.github.miner7222.fixrecents

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.configs
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.factory.field
import com.highcapable.yukihookapi.hook.factory.current
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.highcapable.yukihookapi.hook.type.java.BooleanType
import com.highcapable.yukihookapi.hook.type.java.IntType
import com.highcapable.yukihookapi.hook.type.java.StringClass
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import java.lang.reflect.Field

@InjectYukiHookWithXposed
class MainHook : IYukiHookXposedInit {

    override fun onInit() = configs {
        isDebug = false
    }

    override fun onHook() = encase {
        loadApp(SYSTEMUI_PACKAGE) {
            hookGoingToOverviewGuard()
            hookRecentsController()
        }
        loadApp(LAUNCHER_PACKAGE) {
            hookRecentsActivityStartHome()
        }
    }

    private fun PackageParam.hookRecentsActivityStartHome() {
        RECENTS_ACTIVITY_CLASS.toClass().hook {
            injectMember {
                method {
                    name = "M"
                    emptyParam()
                }
                replaceUnit {
                    if (shouldSuppressRecentsActivityStartHome(instance)) {
                        return@replaceUnit
                    }

                    callOriginal()
                }
            }
        }
    }

    private fun PackageParam.hookGoingToOverviewGuard() {
        EXTERNAL_BINDER_RUNNER_CLASS.toClass().hook {
            injectMember {
                method {
                    name = "run"
                    emptyParam()
                }
                replaceUnit {
                    val runner = instance
                    val lambda = runner.current().field { name = "f$0" }.any()
                    val controller = runner.current().field { name = "f$1" }.any()

                    if (!shouldSkipGoingToOverviewHide(lambda, controller)) {
                        callOriginal()
                        return@replaceUnit
                    }

                    handleGoingToOverviewWithoutFreeforms(lambda, controller)
                }
            }
        }
    }

    private fun PackageParam.hookRecentsController() {
        RECENTS_CONTROLLER_CLASS.toClass().hook {
            injectMember {
                method {
                    name = "finishInner"
                    param(BooleanType, BooleanType, "com.android.internal.os.IResultReceiver".toClass(), StringClass)
                }
                beforeHook {
                    val toHome = args[0] as Boolean
                    val reason = args[3] as String

                    if (shouldSuppressForcedHome(instance, toHome, reason)) {
                        Log.i(TAG, "Suppressing RecentsController.finishInner forced home, reason=$reason")
                        args[0] = false
                    }
                }
            }
        }
    }

    private fun PackageParam.shouldSuppressForcedHome(controller: Any, toHome: Boolean, reason: String): Boolean {
        if (!toHome) return false
        if (reason != REQUEST_REASON) return false

        val willFinishToHome = controller.current().field { name = "mWillFinishToHome" }.boolean()
        if (willFinishToHome) return false

        val state = controller.current().field { name = "mState" }.int()
        if (state != STATE_NORMAL) return false

        val pausingTasks = controller.current().field { name = "mPausingTasks" }.any()
        if (pausingTasks !is Collection<*> || pausingTasks.isEmpty()) return false

        val context = resolveContext(controller) ?: return false
        return isThirdPartyLauncherDefault(context)
    }

    private fun PackageParam.shouldSuppressRecentsActivityStartHome(activity: Any?): Boolean {
        val context = (activity as? Context) ?: currentApplication() ?: return false
        val defaultHomePackage = resolveDefaultHomePackage(context) ?: return false
        val hasThirdPartyDefaultLauncher = isThirdPartyLauncherPackage(defaultHomePackage)
        val topTask = findTopRunningTask(context)
        val topPackageName = topTask?.let { getTaskPackageName(it) }
        val topTaskIsHome = topTask?.let { isHomeTask(it) } ?: false
        val topTaskIsLauncher = topPackageName == LAUNCHER_PACKAGE || topPackageName == defaultHomePackage

        val shouldSuppress = RecentsActivityHomeGuard.shouldSuppressStartHome(
            hasThirdPartyDefaultLauncher = hasThirdPartyDefaultLauncher,
            topTaskPackageName = topPackageName,
            topTaskIsHome = topTaskIsHome,
            topTaskIsLauncherPackage = topTaskIsLauncher
        )

        val logMessage = "RecentsActivity.StartHomeFromRecents " +
            "defaultHome=$defaultHomePackage topPackage=$topPackageName " +
            "topHome=$topTaskIsHome topLauncher=$topTaskIsLauncher"
        if (shouldSuppress) {
            Log.i(TAG, "Suppressing $logMessage")
        } else {
            Log.i(TAG, "Allowing $logMessage")
        }

        return shouldSuppress
    }

    private fun PackageParam.shouldSkipGoingToOverviewHide(lambda: Any?, controller: Any?): Boolean {
        if (!isGoingToOverviewLambda(lambda, controller)) return false

        val hangingController = resolveHangingModeController(controller!!) ?: return false

        val visibleFreeforms = getOvOrderedVisibleTasks(hangingController)
        return isEmptyTaskList(visibleFreeforms)
    }

    private fun PackageParam.handleGoingToOverviewWithoutFreeforms(lambda: Any?, controller: Any?) {
        val hangingController = resolveHangingModeController(controller!!) ?: return

        cancelHangingAnimator(hangingController)
        hangingController.current().field { name = "prevVisibleFreeforms" }.set(getOvOrderedVisibleTasks(hangingController))
        hangingController.current().field { name = "mLauncherState" }.set(getLambdaState(lambda!!))
    }

    private fun isGoingToOverviewLambda(lambda: Any?, controller: Any?): Boolean {
        if (lambda == null || controller == null) return false
        if (lambda.javaClass.name != RECENT_TASKS_LAMBDA_CLASS) return false
        if (controller.javaClass.name != RECENT_TASKS_CONTROLLER_CLASS) return false
        return GOING_TO_OVERVIEW == getLambdaState(lambda)
    }

    private fun getLambdaState(lambda: Any): String? {
        return lambda.current().field { name = "f$0" }.string()
    }

    private fun resolveHangingModeController(controller: Any): Any? {
        val listener = controller.current().field { name = "onLauncherStateChangedListener" }.any() ?: return null
        return listener.current().field { name = "this$0" }.any()
    }

    private fun PackageParam.getOvOrderedVisibleTasks(hangingController: Any): List<*>? {
        val hideAndRestore = hangingController.current().field { name = "mFreeformsHideAndRestore" }.any() ?: return null
        return hideAndRestore.current().method {
            name = "getOvOrderedVisibleTasks"
            param(IntType)
        }.call(0) as? List<*>
    }

    private fun cancelHangingAnimator(hangingController: Any) {
        val positioner = hangingController.current().field { name = "mHangingModePositioner" }.any() ?: return
        val animator = positioner.current().field { name = "mAnimator" }.any() ?: return
        val taskAnimSet = animator.current().field { name = "mTaskAnimSet" }.any() ?: return

        if (!taskAnimSet.current().field { name = "isAnimating" }.boolean()) return

        val valueAnimator = taskAnimSet.current().field { name = "animator" }.any()
        if (valueAnimator is ValueAnimator) {
            valueAnimator.cancel()
        }
    }

    private fun PackageParam.resolveContext(controller: Any): Context? {
        val application = currentApplication()
        if (application != null) return application

        val outer = controller.current().field { name = "this$0" }.any() ?: return null

        val field = findFieldAssignableTo(outer.javaClass, Context::class.java) ?: return null
        return try {
            field.get(outer) as Context
        } catch (ignored: Exception) {
            null
        }
    }

    private fun PackageParam.currentApplication(): Application? {
        return try {
            "android.app.ActivityThread".toClass().method { name = "currentApplication" }.get()?.invoke(null) as? Application
        } catch (ignored: Exception) {
            null
        }
    }

    private fun isThirdPartyLauncherDefault(context: Context): Boolean {
        val packageName = resolveDefaultHomePackage(context) ?: return false
        return isThirdPartyLauncherPackage(packageName)
    }

    private fun resolveDefaultHomePackage(context: Context): String? {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = context.packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName
    }

    private fun isThirdPartyLauncherPackage(packageName: String): Boolean {
        return packageName != "android" && packageName != LAUNCHER_PACKAGE
    }

    private fun findTopRunningTask(context: Context): ActivityManager.RunningTaskInfo? {
        return findRunningTasks(context).firstOrNull()
    }

    private fun findRunningTasks(context: Context): List<ActivityManager.RunningTaskInfo> {
        return try {
            val activityManager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
            activityManager.getRunningTasks(MAX_RUNNING_TASKS)
        } catch (ignored: Exception) {
            emptyList()
        }
    }

    private fun isHomeTask(task: ActivityManager.RunningTaskInfo): Boolean {
        return getActivityType(task) == ACTIVITY_TYPE_HOME ||
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

    private fun isEmptyTaskList(value: Any?): Boolean {
        return value == null || (value is Collection<*> && value.isEmpty())
    }

    private fun findFieldAssignableTo(type: Class<*>, fieldType: Class<*>): Field? {
        var current: Class<*>? = type
        while (current != null) {
            for (field in current.declaredFields) {
                if (fieldType.isAssignableFrom(field.type)) {
                    field.isAccessible = true
                    return field
                }
            }
            current = current.superclass
        }
        return null
    }

    companion object {
        private const val TAG = "ZuiRecentsFix"
        private const val SYSTEMUI_PACKAGE = "com.android.systemui"
        private const val LAUNCHER_PACKAGE = "com.zui.launcher"
        private const val EXTERNAL_BINDER_RUNNER_CLASS =
            "com.android.wm.shell.common.ExternalInterfaceBinder\$\$ExternalSyntheticLambda0"
        private const val RECENT_TASKS_CONTROLLER_CLASS =
            "com.android.wm.shell.recents.RecentTasksController"
        private const val RECENT_TASKS_LAMBDA_CLASS =
            "com.android.wm.shell.recents.RecentTasksController\$\$ExternalSyntheticLambda6"
        private const val RECENTS_CONTROLLER_CLASS =
            "com.android.wm.shell.recents.RecentsTransitionHandler\$RecentsController"
        private const val RECENTS_ACTIVITY_CLASS = "com.android.quickstep.RecentsActivity"
        private const val GOING_TO_OVERVIEW = "GoingToOverview"
        private const val REQUEST_REASON = "requested"
        private const val STATE_NORMAL = 0
        private const val ACTIVITY_TYPE_HOME = 2
        private const val MAX_RUNNING_TASKS = 32
    }
}
