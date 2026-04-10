package io.github.miner7222.fixrecents;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

import io.github.libxposed.api.XposedModule;

public class RecentsFixModule extends XposedModule {
    private static final String TAG = "ZuiRecentsFix";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    private static final String LAUNCHER_PACKAGE = "com.zui.launcher";
    private static final String EXTERNAL_BINDER_RUNNER_CLASS =
            "com.android.wm.shell.common.ExternalInterfaceBinder$$ExternalSyntheticLambda0";
    private static final String RECENT_TASKS_CONTROLLER_CLASS =
            "com.android.wm.shell.recents.RecentTasksController";
    private static final String RECENT_TASKS_LAMBDA_CLASS =
            "com.android.wm.shell.recents.RecentTasksController$$ExternalSyntheticLambda6";
    private static final String RECENTS_CONTROLLER_CLASS =
            "com.android.wm.shell.recents.RecentsTransitionHandler$RecentsController";
    private static final String GOING_TO_OVERVIEW = "GoingToOverview";
    private static final String REQUEST_REASON = "requested";
    private static final int STATE_NORMAL = 0;
    private static final int DEFAULT_DISPLAY_ID = 0;

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        log(Log.INFO, TAG, "Loaded in process " + param.getProcessName());
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.isFirstPackage()) return;
        if (!SYSTEMUI_PACKAGE.equals(param.getPackageName())) return;

        try {
            hookGoingToOverviewGuard(param.getClassLoader());
            hookRecentsController(param.getClassLoader());
            log(Log.INFO, TAG, "Installed SystemUI recents hooks for " + param.getPackageName());
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to install SystemUI recents hooks", t);
        }
    }

    private void hookGoingToOverviewGuard(@NonNull ClassLoader classLoader)
            throws ReflectiveOperationException {
        var runnerClass = classLoader.loadClass(EXTERNAL_BINDER_RUNNER_CLASS);
        var runMethod = runnerClass.getDeclaredMethod("run");
        deoptimize(runMethod);

        hook(runMethod).intercept(chain -> {
            var runner = chain.getThisObject();
            var lambda = getFieldValue(runner, "f$0");
            var controller = getFieldValue(runner, "f$1");
            if (!shouldSkipGoingToOverviewHide(lambda, controller)) {
                return chain.proceed();
            }

            handleGoingToOverviewWithoutFreeforms(lambda, controller);
            log(Log.INFO, TAG, "Skipped empty GoingToOverview hide that forces home");
            return null;
        });
    }

    private void hookRecentsController(@NonNull ClassLoader classLoader)
            throws ReflectiveOperationException {
        var controllerClass = classLoader.loadClass(RECENTS_CONTROLLER_CLASS);
        var finishInner = controllerClass.getDeclaredMethod(
                "finishInner",
                boolean.class,
                boolean.class,
                classLoader.loadClass("com.android.internal.os.IResultReceiver"),
                String.class
        );

        hook(finishInner).intercept(chain -> {
            var toHome = (Boolean) chain.getArg(0);
            var reason = (String) chain.getArg(3);
            if (!shouldSuppressForcedHome(chain.getThisObject(), toHome, reason)) {
                return chain.proceed();
            }

            var args = chain.getArgs().toArray();
            args[0] = false;
            log(Log.INFO, TAG, "Suppressed forced home finish for third-party launcher recents toggle");
            return chain.proceed(args);
        });
    }

    private boolean shouldSuppressForcedHome(Object controller, boolean toHome, String reason) {
        if (!toHome) return false;
        if (!REQUEST_REASON.equals(reason)) return false;

        var willFinishToHome = getBooleanField(controller, "mWillFinishToHome");
        if (willFinishToHome == null || willFinishToHome) return false;

        var state = getIntField(controller, "mState");
        if (state == null || state != STATE_NORMAL) return false;

        var pausingTasks = getFieldValue(controller, "mPausingTasks");
        if (!(pausingTasks instanceof Collection<?> collection) || collection.isEmpty()) return false;

        var context = resolveContext(controller);
        if (context == null) return false;
        return isThirdPartyLauncherDefault(context);
    }

    private boolean shouldSkipGoingToOverviewHide(Object lambda, Object controller) {
        if (!isGoingToOverviewLambda(lambda, controller)) return false;

        var hangingController = resolveHangingModeController(controller);
        if (hangingController == null) return false;

        var visibleFreeforms = getOvOrderedVisibleTasks(hangingController);
        return isEmptyTaskList(visibleFreeforms);
    }

    private void handleGoingToOverviewWithoutFreeforms(Object lambda, Object controller) {
        var hangingController = resolveHangingModeController(controller);
        if (hangingController == null) return;

        cancelHangingAnimator(hangingController);
        setFieldValue(hangingController, "prevVisibleFreeforms", getOvOrderedVisibleTasks(hangingController));
        setFieldValue(hangingController, "mLauncherState", getLambdaState(lambda));
    }

    private boolean isGoingToOverviewLambda(Object lambda, Object controller) {
        if (lambda == null || controller == null) return false;
        if (!RECENT_TASKS_LAMBDA_CLASS.equals(lambda.getClass().getName())) return false;
        if (!RECENT_TASKS_CONTROLLER_CLASS.equals(controller.getClass().getName())) return false;
        return GOING_TO_OVERVIEW.equals(getLambdaState(lambda));
    }

    private String getLambdaState(Object lambda) {
        var value = getFieldValue(lambda, "f$0");
        return value instanceof String ? (String) value : null;
    }

    private Object resolveHangingModeController(Object controller) {
        var listener = getFieldValue(controller, "onLauncherStateChangedListener");
        if (listener == null) return null;
        return getFieldValue(listener, "this$0");
    }

    private List<?> getOvOrderedVisibleTasks(Object hangingController) {
        var hideAndRestore = getFieldValue(hangingController, "mFreeformsHideAndRestore");
        if (hideAndRestore == null) return null;

        try {
            Method method = hideAndRestore.getClass().getDeclaredMethod("getOvOrderedVisibleTasks", int.class);
            method.setAccessible(true);
            var result = method.invoke(hideAndRestore, DEFAULT_DISPLAY_ID);
            return result instanceof List<?> list ? list : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private void cancelHangingAnimator(Object hangingController) {
        var positioner = getFieldValue(hangingController, "mHangingModePositioner");
        if (positioner == null) return;

        var animator = getFieldValue(positioner, "mAnimator");
        if (animator == null) return;

        var taskAnimSet = getFieldValue(animator, "mTaskAnimSet");
        if (taskAnimSet == null) return;

        var animating = getBooleanField(taskAnimSet, "isAnimating");
        if (animating == null || !animating) return;

        var valueAnimator = getFieldValue(taskAnimSet, "animator");
        if (valueAnimator instanceof android.animation.ValueAnimator animation) {
            animation.cancel();
        }
    }

    private Context resolveContext(Object controller) {
        var application = currentApplication();
        if (application != null) return application;

        var outer = getFieldValue(controller, "this$0");
        if (outer == null) return null;

        var field = findFieldAssignableTo(outer.getClass(), Context.class);
        if (field == null) return null;
        try {
            return (Context) field.get(outer);
        } catch (IllegalAccessException ignored) {
            return null;
        }
    }

    private Application currentApplication() {
        try {
            var clazz = Class.forName("android.app.ActivityThread");
            var method = clazz.getDeclaredMethod("currentApplication");
            method.setAccessible(true);
            return (Application) method.invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isThirdPartyLauncherDefault(Context context) {
        var homeIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        var resolved = context.getPackageManager().resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
        var packageName = resolved != null && resolved.activityInfo != null ? resolved.activityInfo.packageName : null;
        return packageName != null && !"android".equals(packageName) && !LAUNCHER_PACKAGE.equals(packageName);
    }

    private boolean isEmptyTaskList(Object value) {
        return value == null || (value instanceof Collection<?> collection && collection.isEmpty());
    }

    private Boolean getBooleanField(Object target, String name) {
        var value = getFieldValue(target, name);
        return value instanceof Boolean ? (Boolean) value : null;
    }

    private Integer getIntField(Object target, String name) {
        var value = getFieldValue(target, name);
        return value instanceof Integer ? (Integer) value : null;
    }

    private Object getFieldValue(Object target, String name) {
        if (target == null) return null;
        var field = findField(target.getClass(), name);
        if (field == null) return null;
        try {
            return field.get(target);
        } catch (IllegalAccessException ignored) {
            return null;
        }
    }

    private boolean setFieldValue(Object target, String name, Object value) {
        if (target == null) return false;
        var field = findField(target.getClass(), name);
        if (field == null) return false;
        try {
            field.set(target, value);
            return true;
        } catch (IllegalAccessException ignored) {
            return false;
        }
    }

    private Field findField(Class<?> type, String name) {
        for (var current = type; current != null; current = current.getSuperclass()) {
            try {
                var field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private Field findFieldAssignableTo(Class<?> type, Class<?> fieldType) {
        for (var current = type; current != null; current = current.getSuperclass()) {
            for (var field : current.getDeclaredFields()) {
                if (!fieldType.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }
}
