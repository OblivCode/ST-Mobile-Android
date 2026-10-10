package app.stmobile

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider

import app.stmobile.logging.DiagnosticsSnapshot

object Utils {

    fun toast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun promptBatteryOptimization(context: Context) {
        val pm = context.getSystemService(PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(context.packageName) == true) {
            toast(context, "Battery optimization is already disabled")
            return
        }
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            // OEM fallback (Xiaomi HyperOS, ColorOS, OriginOS)
            try {
                val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (_: Exception) {
                toast(context, "Please allow background running in App Info / Battery settings")
            }
        }
    }

    fun requestNotificationPermission(activity: Activity, requestCode: Int = 1) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), requestCode)
        }
    }

    fun shareLogs(context: Context) {
        Thread {
            try {
                val bundle = DiagnosticsSnapshot.createLogBundle(context)
                if (!bundle.exists() || bundle.length() == 0L) {
                    toast(context, context.getString(R.string.toast_no_logs))
                    return@Thread
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", bundle)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("logs", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(send, context.getString(R.string.btn_export_logs)).apply {
                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                Handler(Looper.getMainLooper()).post {
                    context.startActivity(chooser)
                }
            } catch (e: Exception) {
                toast(context, context.getString(R.string.toast_export_logs_failed, e.message ?: "Unknown error"))
            }
        }.start()
    }
}
