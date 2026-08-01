package com.trustissue.child

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object TrustIssueDebugLog {
    private const val logFileName = "trustissue_runtime_log.txt"
    private const val maxLogBytes = 768 * 1024
    private val executor = Executors.newSingleThreadExecutor()

    fun append(context: Context, tag: String, level: String, message: String) {
        val appContext = context.applicationContext
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$timestamp $level/$tag $message\n"
        executor.execute {
            runCatching {
                val file = logFile(appContext)
                if (file.length() > maxLogBytes) {
                    file.writeText(
                        "$timestamp I/TrustIssueDebugLog Log rotated after reaching ${maxLogBytes} bytes\n"
                    )
                }
                file.appendText(line)
            }
        }
    }

    fun exportToDownloads(context: Context): String {
        val appContext = context.applicationContext
        val packageInfo = runCatching {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }.getOrNull()
        append(
            appContext,
            "DiagnosticExport",
            "I",
            "EXPORT_SNAPSHOT sdk=${Build.VERSION.SDK_INT} " +
                "device=${Build.MANUFACTURER.take(30)}/${Build.MODEL.take(50)} " +
                "appVersion=${packageInfo?.versionName ?: "unknown"} " +
                "versionCode=${packageInfo?.longVersionCode ?: -1L}"
        )
        append(
            appContext,
            "DiagnosticExport",
            "I",
            "LOG_TAGS shorts_reels=ShortFormDiagnostics " +
                "vpn=VpnDiagnostics vpn_runtime=FocusWebProtection"
        )
        flushPendingWrites()
        val file = logFile(appContext)
        if (!file.exists()) {
            file.writeText("No TrustIssue runtime logs recorded yet.\n")
        }

        val exportedName = "trustissue-log-${
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        }.txt"
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, exportedName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Unable to create debug log file")

        resolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        } ?: throw IllegalStateException("Unable to write debug log file")

        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        append(appContext, "TrustIssueDebugLog", "I", "DEBUG_LOG_EXPORTED file=Downloads/$exportedName")
        return "Downloads/$exportedName"
    }

    fun clear(context: Context) {
        val appContext = context.applicationContext
        executor.execute {
            runCatching { logFile(appContext).writeText("") }
        }
    }

    private fun flushPendingWrites() {
        runCatching {
            executor.submit { Unit }.get(2, TimeUnit.SECONDS)
        }
    }

    private fun logFile(context: Context): File {
        return File(context.filesDir, logFileName)
    }
}
