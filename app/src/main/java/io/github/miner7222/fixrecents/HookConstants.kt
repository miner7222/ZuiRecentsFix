package io.github.miner7222.fixrecents

internal object HookConstants {
    const val TAG = "ZuiRecentsFix"
    const val SYSTEMUI_PACKAGE = "com.android.systemui"
    const val LAUNCHER_PACKAGE = "com.zui.launcher"
    const val EXTERNAL_BINDER_RUNNER_CLASS =
        "com.android.wm.shell.common.ExternalInterfaceBinder\$\$ExternalSyntheticLambda0"
    const val RECENT_TASKS_CONTROLLER_CLASS =
        "com.android.wm.shell.recents.RecentTasksController"
    const val RECENT_TASKS_LAMBDA_CLASS =
        "com.android.wm.shell.recents.RecentTasksController\$\$ExternalSyntheticLambda6"
    const val RECENTS_CONTROLLER_CLASS =
        "com.android.wm.shell.recents.RecentsTransitionHandler\$RecentsController"
    const val RECENTS_ACTIVITY_CLASS = "com.android.quickstep.RecentsActivity"
    const val IRESULT_RECEIVER_CLASS = "com.android.internal.os.IResultReceiver"
    const val GOING_TO_OVERVIEW = "GoingToOverview"
    const val REQUEST_REASON = "requested"
    const val STATE_NORMAL = 0
    const val ACTIVITY_TYPE_HOME = 2
    const val MAX_RUNNING_TASKS = 32
    const val FIELD_F0 = "f\$0"
    const val FIELD_F1 = "f\$1"
    const val FIELD_THIS0 = "this\$0"
}
