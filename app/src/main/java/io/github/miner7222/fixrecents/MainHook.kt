package io.github.miner7222.fixrecents

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

class MainHook : XposedModule() {

    private val systemUiHooksInstalled = AtomicBoolean(false)
    private val launcherHooksInstalled = AtomicBoolean(false)

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(
            Log.INFO,
            TAG,
            "Loaded ZuiRecentsFix in ${param.processName}, framework=$frameworkName($frameworkVersionCode), api=$apiVersion",
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        when (param.packageName) {
            SYSTEMUI_PACKAGE -> {
                if (!systemUiHooksInstalled.compareAndSet(false, true)) return
                installHook("SystemUI GoingToOverview guard") { hookGoingToOverviewGuard(param.classLoader) }
                installHook("SystemUI RecentsController finish guard") { hookRecentsController(param.classLoader) }
            }
            LAUNCHER_PACKAGE -> {
                if (!launcherHooksInstalled.compareAndSet(false, true)) return
                installHook("Launcher RecentsActivity start-home guard") {
                    hookRecentsActivityStartHome(param.classLoader)
                }
            }
        }
    }

    private fun installHook(name: String, block: () -> Unit) {
        runCatching(block)
            .onSuccess { log(Log.INFO, TAG, "Installed $name") }
            .onFailure { log(Log.ERROR, TAG, "Failed to install $name", it) }
    }

    private fun hookRecentsActivityStartHome(classLoader: ClassLoader) {
        hookMethod(classLoader, RECENTS_ACTIVITY_CLASS, "M") { chain ->
            if (shouldSuppressRecentsActivityStartHome(chain.thisObject)) {
                null
            } else {
                chain.proceed()
            }
        }
    }

    private fun hookGoingToOverviewGuard(classLoader: ClassLoader) {
        hookMethod(classLoader, EXTERNAL_BINDER_RUNNER_CLASS, "run") { chain ->
            val runner = chain.thisObject
            val lambda = runner?.fieldValue(FIELD_F0)
            val controller = runner?.fieldValue(FIELD_F1)

            if (!shouldSkipGoingToOverviewHide(lambda, controller)) {
                return@hookMethod chain.proceed()
            }

            handleGoingToOverviewWithoutFreeforms(lambda, controller)
            null
        }
    }

    private fun hookRecentsController(classLoader: ClassLoader) {
        hookMethod(
            classLoader = classLoader,
            className = RECENTS_CONTROLLER_CLASS,
            methodName = "finishInner",
            Boolean::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
            resolveClass(classLoader, IRESULT_RECEIVER_CLASS),
            String::class.java,
        ) { chain ->
            val args = chain.args.toTypedArray()
            val controller = chain.thisObject
            val toHome = args[0] as Boolean
            val reason = args[3] as String

            if (controller != null && shouldSuppressForcedHome(controller, toHome, reason)) {
                Log.i(TAG, "Suppressing RecentsController.finishInner forced home, reason=$reason")
                args[0] = false
            }

            chain.proceed(args)
        }
    }

    private fun hookMethod(
        classLoader: ClassLoader,
        className: String,
        methodName: String,
        vararg parameterTypes: Class<*>,
        block: (XposedInterface.Chain) -> Any?,
    ) {
        val executable = resolveExecutable(classLoader, className, methodName, parameterTypes)
        hook(executable)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept(block)
    }

    private fun resolveExecutable(
        classLoader: ClassLoader,
        className: String,
        methodName: String,
        parameterTypes: Array<out Class<*>>,
    ): Executable {
        val targetClass = resolveClass(classLoader, className)
        val executable = targetClass.declaredMethods.firstOrNull {
            it.name == methodName && it.parameterTypes.contentEquals(parameterTypes)
        } ?: throw NoSuchMethodException("${targetClass.name}#$methodName${describeParameters(parameterTypes)}")
        executable.isAccessible = true
        return executable
    }

    private fun resolveClass(classLoader: ClassLoader, className: String): Class<*> {
        return Class.forName(className, false, classLoader)
    }

    private fun describeParameters(parameterTypes: Array<out Class<*>>): String {
        return parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
    }

    private fun shouldSuppressForcedHome(controller: Any, toHome: Boolean, reason: String): Boolean {
        if (!toHome) return false
        if (reason != REQUEST_REASON) return false

        val willFinishToHome = controller.booleanField("mWillFinishToHome")
        if (willFinishToHome) return false

        val state = controller.intField("mState")
        if (state != STATE_NORMAL) return false

        val pausingTasks = controller.fieldValue("mPausingTasks")
        if (pausingTasks !is Collection<*> || pausingTasks.isEmpty()) return false

        val context = resolveContext(controller) ?: return false
        return isThirdPartyLauncherDefault(context)
    }

    private fun shouldSuppressRecentsActivityStartHome(activity: Any?): Boolean {
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
            topTaskIsLauncherPackage = topTaskIsLauncher,
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

    private fun shouldSkipGoingToOverviewHide(lambda: Any?, controller: Any?): Boolean {
        if (!isGoingToOverviewLambda(lambda, controller)) return false

        val hangingController = resolveHangingModeController(controller!!) ?: return false

        val visibleFreeforms = getOvOrderedVisibleTasks(hangingController)
        return isEmptyTaskList(visibleFreeforms)
    }

    private fun handleGoingToOverviewWithoutFreeforms(lambda: Any?, controller: Any?) {
        val hangingController = resolveHangingModeController(controller!!) ?: return

        cancelHangingAnimator(hangingController)
        hangingController.setFieldValue("prevVisibleFreeforms", getOvOrderedVisibleTasks(hangingController))
        hangingController.setFieldValue("mLauncherState", getLambdaState(lambda!!))
    }

    private fun isGoingToOverviewLambda(lambda: Any?, controller: Any?): Boolean {
        if (lambda == null || controller == null) return false
        if (lambda.javaClass.name != RECENT_TASKS_LAMBDA_CLASS) return false
        if (controller.javaClass.name != RECENT_TASKS_CONTROLLER_CLASS) return false
        return GOING_TO_OVERVIEW == getLambdaState(lambda)
    }

    private fun getLambdaState(lambda: Any): String? {
        return lambda.stringField(FIELD_F0)
    }

    private fun resolveHangingModeController(controller: Any): Any? {
        val listener = controller.fieldValue("onLauncherStateChangedListener") ?: return null
        return listener.fieldValue(FIELD_THIS0)
    }

    private fun getOvOrderedVisibleTasks(hangingController: Any): List<*>? {
        val hideAndRestore = hangingController.fieldValue("mFreeformsHideAndRestore") ?: return null
        return hideAndRestore.callMethod(
            "getOvOrderedVisibleTasks",
            arrayOf(Int::class.javaPrimitiveType!!),
            arrayOf(0),
        ) as? List<*>
    }

    private fun cancelHangingAnimator(hangingController: Any) {
        val positioner = hangingController.fieldValue("mHangingModePositioner") ?: return
        val animator = positioner.fieldValue("mAnimator") ?: return
        val taskAnimSet = animator.fieldValue("mTaskAnimSet") ?: return

        if (!taskAnimSet.booleanField("isAnimating")) return

        val valueAnimator = taskAnimSet.fieldValue("animator")
        if (valueAnimator is ValueAnimator) {
            valueAnimator.cancel()
        }
    }

    private fun resolveContext(controller: Any): Context? {
        val application = currentApplication()
        if (application != null) return application

        val outer = controller.fieldValue(FIELD_THIS0) ?: return null

        val field = findFieldAssignableTo(outer.javaClass, Context::class.java) ?: return null
        return try {
            field.get(outer) as Context
        } catch (ignored: Exception) {
            null
        }
    }

    private fun currentApplication(): Application? {
        return try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val method = activityThread.getDeclaredMethod("currentApplication")
            method.isAccessible = true
            method.invoke(null) as? Application
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

    private fun Any.fieldValue(name: String): Any? {
        val field = findField(javaClass, name) ?: return null
        return try {
            field.get(this)
        } catch (ignored: Exception) {
            null
        }
    }

    private fun Any.setFieldValue(name: String, value: Any?) {
        val field = findField(javaClass, name) ?: return
        runCatching { field.set(this, value) }
    }

    private fun Any.booleanField(name: String): Boolean {
        return fieldValue(name) as? Boolean ?: false
    }

    private fun Any.intField(name: String): Int {
        return fieldValue(name) as? Int ?: 0
    }

    private fun Any.stringField(name: String): String? {
        return fieldValue(name) as? String
    }

    private fun Any.callMethod(name: String, parameterTypes: Array<Class<*>>, args: Array<Any?>): Any? {
        val method = findMethod(javaClass, name, parameterTypes) ?: return null
        return try {
            method.invoke(this, *args)
        } catch (ignored: Exception) {
            null
        }
    }

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            try {
                val field = current.getDeclaredField(name)
                field.isAccessible = true
                return field
            } catch (ignored: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
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

    private fun findMethod(type: Class<*>, name: String, parameterTypes: Array<Class<*>>): Method? {
        var current: Class<*>? = type
        while (current != null) {
            val method = current.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.contentEquals(parameterTypes)
            }
            if (method != null) {
                method.isAccessible = true
                return method
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
        private const val IRESULT_RECEIVER_CLASS = "com.android.internal.os.IResultReceiver"
        private const val GOING_TO_OVERVIEW = "GoingToOverview"
        private const val REQUEST_REASON = "requested"
        private const val STATE_NORMAL = 0
        private const val ACTIVITY_TYPE_HOME = 2
        private const val MAX_RUNNING_TASKS = 32
        private const val FIELD_F0 = "f\$0"
        private const val FIELD_F1 = "f\$1"
        private const val FIELD_THIS0 = "this\$0"
    }
}
