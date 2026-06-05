package io.github.miner7222.fixrecents

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.concurrent.atomic.AtomicBoolean

class MainHook : XposedModule() {

    private val systemUiHooksInstalled = AtomicBoolean(false)
    private val launcherHooksInstalled = AtomicBoolean(false)

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(
            Log.INFO,
            HookConstants.TAG,
            "onModuleLoaded process=${param.processName} systemServer=${param.isSystemServer} " +
                "framework=$frameworkName($frameworkVersionCode) api=$apiVersion",
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        log(
            Log.INFO,
            HookConstants.TAG,
            "onPackageReady package=${param.packageName} first=${param.isFirstPackage} loader=${param.classLoader}",
        )

        when (param.packageName) {
            HookConstants.SYSTEMUI_PACKAGE -> installOnce("SystemUI hooks", systemUiHooksInstalled, param) { scope ->
                SystemUiRecentsHooks(scope).install()
            }
            HookConstants.LAUNCHER_PACKAGE -> installOnce("Launcher hooks", launcherHooksInstalled, param) { scope ->
                LauncherRecentsHooks(scope).install()
            }
        }
    }

    private fun installOnce(
        name: String,
        installed: AtomicBoolean,
        param: PackageReadyParam,
        block: (ModernHookScope) -> Unit,
    ) {
        if (!installed.compareAndSet(false, true)) {
            log(Log.INFO, HookConstants.TAG, "Skipping duplicate $name for ${param.packageName}")
            return
        }

        runCatching { block(ModernHookScope(this, param.classLoader)) }
            .onSuccess { log(Log.INFO, HookConstants.TAG, "Installed $name for ${param.packageName}") }
            .onFailure { log(Log.ERROR, HookConstants.TAG, "Failed to install $name for ${param.packageName}", it) }
    }
}
