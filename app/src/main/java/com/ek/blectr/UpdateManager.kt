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

        val downloadManager =
            context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        try {
            downloadManager.query(DownloadManager.Query().setFilterById(-1))
        } catch (_: SecurityException) {
            Toast.makeText(context, "下载管理器不可用", Toast.LENGTH_SHORT).show()
            return
        }

        val request = DownloadManager.Request(uri)
            .setTitle(context.getString(R.string.app_name) + " 更新")
            .setDescription("正在下载 ${info.versionName} ...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                fileName,
            )
            .setMimeType("application/vnd.android.package-archive")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            request.setRequiresCharging(false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        }

        val downloadId = downloadManager.enqueue(request)

        Toast.makeText(context, "开始下载 ${info.versionName} ...", Toast.LENGTH_SHORT).show()

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return
                ctx.unregisterReceiver(this)

                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                var success = false
                cursor?.use {
                    if (it.moveToFirst()) {
                        val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        success = status == DownloadManager.STATUS_SUCCESSFUL
                    }
                }

                if (!success) {
                    Toast.makeText(ctx, "下载失败，请检查网络后重试", Toast.LENGTH_LONG).show()
                    return
                }

                val apkUri = resolveDownloadedFile(downloadManager, downloadId, fileName)
                if (apkUri != null) {
                    installApk(apkUri)
                } else {
                    Toast.makeText(ctx, "下载文件未找到", Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    private fun resolveDownloadedFile(
        downloadManager: DownloadManager,
        downloadId: Long,
        fileName: String,
    ): Uri? {
        try {
            val uri = downloadManager.getUriForDownloadedFile(downloadId)
            if (uri != null) return uri
        } catch (_: Exception) { }

        try {
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = downloadManager.query(query)
            cursor?.use {
                if (it.moveToFirst()) {
                    @Suppress("DEPRECATION")
                    val localUri = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                    if (!localUri.isNullOrEmpty()) {
                        val file = Uri.parse(localUri)?.let { u -> u.path?.let { p -> File(p) } }
                        if (file?.exists() == true) {
                            return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        }
                    }
                }
            }
        } catch (_: Exception) { }

        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (dir != null) {
            val file = File(dir, fileName)
            if (file.exists()) {
                return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }
        }
        return null
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
