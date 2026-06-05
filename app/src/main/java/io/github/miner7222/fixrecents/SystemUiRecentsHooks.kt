package io.github.miner7222.fixrecents

import android.animation.ValueAnimator
import android.content.Context
import android.util.Log

internal class SystemUiRecentsHooks(
    private val scope: ModernHookScope,
) {
    fun install() {
        hookGoingToOverviewGuard()
        hookRecentsController()
    }

    private fun hookGoingToOverviewGuard() {
        scope.interceptMethod(HookConstants.EXTERNAL_BINDER_RUNNER_CLASS, "run") { chain ->
            val runner = chain.thisObject
            val lambda = runner?.fieldValue(HookConstants.FIELD_F0)
            val controller = runner?.fieldValue(HookConstants.FIELD_F1)

            if (!shouldSkipGoingToOverviewHide(lambda, controller)) {
                return@interceptMethod chain.proceed()
            }

            handleGoingToOverviewWithoutFreeforms(lambda, controller)
            null
        }
    }

    private fun hookRecentsController() {
        scope.interceptMethod(
            HookConstants.RECENTS_CONTROLLER_CLASS,
            "finishInner",
            Boolean::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
            scope.loadClass(HookConstants.IRESULT_RECEIVER_CLASS),
            String::class.java,
        ) { chain ->
            val args = chain.args.toTypedArray()
            val controller = chain.thisObject
            val toHome = args[0] as Boolean
            val reason = args[3] as String

            if (controller != null && shouldSuppressForcedHome(controller, toHome, reason)) {
                Log.i(HookConstants.TAG, "Suppressing RecentsController.finishInner forced home, reason=$reason")
                args[0] = false
            }

            chain.proceed(args)
        }
    }

    private fun shouldSuppressForcedHome(controller: Any, toHome: Boolean, reason: String): Boolean {
        if (!toHome) return false
        if (reason != HookConstants.REQUEST_REASON) return false

        val willFinishToHome = controller.booleanField("mWillFinishToHome")
        if (willFinishToHome) return false

        val state = controller.intField("mState")
        if (state != HookConstants.STATE_NORMAL) return false

        val pausingTasks = controller.fieldValue("mPausingTasks")
        if (pausingTasks !is Collection<*> || pausingTasks.isEmpty()) return false

        val context = resolveContext(controller) ?: return false
        return isThirdPartyLauncherDefault(context)
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
        if (lambda.javaClass.name != HookConstants.RECENT_TASKS_LAMBDA_CLASS) return false
        if (controller.javaClass.name != HookConstants.RECENT_TASKS_CONTROLLER_CLASS) return false
        return HookConstants.GOING_TO_OVERVIEW == getLambdaState(lambda)
    }

    private fun getLambdaState(lambda: Any): String? {
        return lambda.stringField(HookConstants.FIELD_F0)
    }

    private fun resolveHangingModeController(controller: Any): Any? {
        val listener = controller.fieldValue("onLauncherStateChangedListener") ?: return null
        return listener.fieldValue(HookConstants.FIELD_THIS0)
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

        val outer = controller.fieldValue(HookConstants.FIELD_THIS0) ?: return null

        val field = findFieldAssignableTo(outer.javaClass, Context::class.java) ?: return null
        return try {
            field.get(outer) as Context
        } catch (ignored: Exception) {
            null
        }
    }

    private fun isEmptyTaskList(value: Any?): Boolean {
        return value == null || (value is Collection<*> && value.isEmpty())
    }
}
