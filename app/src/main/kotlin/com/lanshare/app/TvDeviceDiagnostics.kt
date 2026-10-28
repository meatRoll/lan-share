package com.lanshare.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** Temporary, opt-in diagnostics. Never request identifiers or launch settings. */
object TvDeviceDiagnostics {
    fun record(context: Context) {
        if (!BuildConfig.LOGGING_ENABLED) return
        // Each read and write is isolated so diagnostics cannot interrupt startup.
        fun field(name: String, read: () -> Any?) {
            val value = try {
                read()?.toString()?.replace('\n', ' ')?.replace('\r', ' ')?.take(2048) ?: "null"
            } catch (error: Throwable) {
                "unavailable (${error.javaClass.simpleName})"
            }
            try { Logcat.d("tv device info", "$name=$value") } catch (_: Throwable) { }
        }
        field("appVersion") { "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" }
        field("xgimiDirectInstall") {
            Build.MANUFACTURER?.trim().equals("XGIMI", ignoreCase = true) ||
                Build.BRAND?.trim().equals("XGIMI", ignoreCase = true)
        }
        field("manufacturer") { Build.MANUFACTURER }
        field("brand") { Build.BRAND }
        field("model") { Build.MODEL }
        field("device") { Build.DEVICE }
        field("product") { Build.PRODUCT }
        field("board") { Build.BOARD }
        field("hardware") { Build.HARDWARE }
        field("sdkInt") { Build.VERSION.SDK_INT }
        field("androidRelease") { Build.VERSION.RELEASE }
        field("securityPatch") { if (Build.VERSION.SDK_INT >= 23) Build.VERSION.SECURITY_PATCH else "not supported" }
        field("buildId") { Build.ID }
        field("buildDisplay") { Build.DISPLAY }
        field("incremental") { Build.VERSION.INCREMENTAL }
        field("fingerprint") { Build.FINGERPRINT }
        field("buildType") { Build.TYPE }
        field("buildTags") { Build.TAGS }
        field("supportedAbis") { Build.SUPPORTED_ABIS.joinToString(",") }
        field("targetSdk") { context.applicationInfo.targetSdkVersion }
        field("canRequestPackageInstalls") {
            if (Build.VERSION.SDK_INT >= 26) context.packageManager.canRequestPackageInstalls() else "not supported"
        }
        field("requestInstallPackagesAppOp") {
            if (Build.VERSION.SDK_INT < 26) "not supported"
            else {
                val ops = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
                val uid = android.os.Process.myUid()
                val mode = if (Build.VERSION.SDK_INT >= 29) {
                    ops.unsafeCheckOpNoThrow("android:request_install_packages", uid, context.packageName)
                } else {
                    @Suppress("DEPRECATION")
                    ops.checkOpNoThrow("android:request_install_packages", uid, context.packageName)
                }
                when (mode) {
                    android.app.AppOpsManager.MODE_ALLOWED -> "MODE_ALLOWED"
                    android.app.AppOpsManager.MODE_IGNORED -> "MODE_IGNORED"
                    android.app.AppOpsManager.MODE_ERRORED -> "MODE_ERRORED"
                    android.app.AppOpsManager.MODE_DEFAULT -> "MODE_DEFAULT"
                    else -> "mode=$mode"
                }
            }
        }
        field("unknownAppSourcesHandler") {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            context.packageManager.resolveActivity(intent, 0)?.activityInfo?.let { "${it.packageName}/${it.name}" }
                ?: "none (may also be filtered by package visibility)"
        }
        field("securitySettingsHandler") {
            context.packageManager.resolveActivity(Intent(Settings.ACTION_SECURITY_SETTINGS), 0)?.activityInfo?.let {
                "${it.packageName}/${it.name}"
            } ?: "none (may also be filtered by package visibility)"
        }
    }
}
