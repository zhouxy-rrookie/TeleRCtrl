package com.ek.blectr

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UpdateManager(private val context: Context) {

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val releaseNotes: String,
    )

    private val manifestUrl =
        "https://raw.githubusercontent.com/zhouxy-rrookie/TeleRCtrl/main/version.json"

    fun checkForUpdate(callback: (UpdateInfo?) -> Unit) {
        Thread {
            try {
                val currentVc = getCurrentVersionCode()
                val json = fetchUrl(manifestUrl) ?: run {
                    callback(null)
                    return@Thread
                }
                val obj = JSONObject(json)
                val info = UpdateInfo(
                    versionCode = obj.getInt("versionCode"),
                    versionName = obj.getString("versionName"),
                    apkUrl = obj.getString("apkUrl"),
                    releaseNotes = obj.getString("releaseNotes"),
                )
                callback(if (info.versionCode > currentVc) info else null)
            } catch (_: Exception) {
                callback(null)
            }
        }.start()
    }

    fun downloadAndInstall(info: UpdateInfo) {
        val fileName = "blectr_update_${info.versionName}.apk"
        val uri = Uri.parse(info.apkUrl)

        val request = DownloadManager.Request(uri)
            .setTitle(context.getString(R.string.app_name) + " 更新")
            .setDescription("正在下载 ${info.versionName} ...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                fileName,
            )
            .setMimeType("application/vnd.android.package-archive")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            request.setRequiresCharging(false)
        }

        val downloadManager =
            context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return
                val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                if (downloadsDir == null) {
                    Toast.makeText(context, "存储不可用", Toast.LENGTH_SHORT).show()
                    ctx.unregisterReceiver(this)
                    return
                }
                val file = File(downloadsDir, fileName)
                if (!file.exists()) {
                    Toast.makeText(context, "下载文件未找到", Toast.LENGTH_SHORT).show()
                    ctx.unregisterReceiver(this)
                    return
                }
                val apkUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                installApk(apkUri)
                ctx.unregisterReceiver(this)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    private fun installApk(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun getCurrentVersionCode(): Int {
        val pm = context.packageManager
        val pkg = pm.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkg.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            pkg.versionCode
        }
    }

    private fun fetchUrl(urlString: String): String? {
        return try {
            val conn = URL(urlString).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }
}
