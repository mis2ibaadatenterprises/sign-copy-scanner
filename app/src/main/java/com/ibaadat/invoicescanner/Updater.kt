package com.ibaadat.invoicescanner

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Same auto-update pattern as the other internal apps: the CI build publishes
 *   version.json  ->  { "versionCode": 7, "versionName": "1.6", "apk": "https://…/app.apk" }
 * The app checks it on launch and offers the install. No store involved.
 */
object Updater {

    data class Available(val versionName: String, val apkUrl: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(300, TimeUnit.SECONDS).build()

    fun check(ctx: Context, prefs: Prefs): Available? {
        if (prefs.updateUrl.isBlank()) return null
        return try {
            val res = client.newCall(Request.Builder().url(prefs.updateUrl).build()).execute()
            val json = JSONObject(res.body?.string().orEmpty())
            val mine = ctx.packageManager.getPackageInfo(ctx.packageName, 0).let {
                @Suppress("DEPRECATION") it.versionCode
            }
            if (json.optInt("versionCode") > mine)
                Available(json.optString("versionName"), json.optString("apk"))
            else null
        } catch (e: Exception) { null }
    }

    /** Downloads the APK and hands it to the system installer. */
    fun install(ctx: Context, apkUrl: String) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "update.apk")
        client.newCall(Request.Builder().url(apkUrl).build()).execute().use { res ->
            apk.outputStream().use { out -> res.body?.byteStream()?.copyTo(out) }
        }
        val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
