package com.ibaadat.invoicescanner

import android.content.Context
import androidx.work.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Scans never block on signal. Each one is written to disk first, then a
 * WorkManager job drains the folder whenever the phone is online — so a
 * counter with no bars still works, and nothing is lost if the app is killed.
 */
object Queue {

    private fun dir(ctx: Context) = File(ctx.filesDir, "queue").apply { mkdirs() }

    fun add(ctx: Context, job: JSONObject): File {
        val f = File(dir(ctx), "${System.currentTimeMillis()}-${(1000..9999).random()}.json")
        f.writeText(job.toString())
        return f
    }

    fun pending(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name } ?: emptyList()

    fun count(ctx: Context) = pending(ctx).size

    fun kick(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("drain", ExistingWorkPolicy.KEEP, req)
    }
}

class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (!prefs.configured) return Result.failure()

        for (f in Queue.pending(applicationContext)) {
            val job = try { JSONObject(f.readText()) } catch (e: Exception) { f.delete(); continue }
            when (Net.upload(prefs, job)) {
                is Net.Outcome.Ok -> f.delete()
                is Net.Outcome.Rejected -> f.delete()   // bad login or malformed: retrying won't help
                is Net.Outcome.Offline -> return Result.retry()
            }
        }
        return Result.success()
    }
}

/**
 * Talks to the Godown Board web app. Sign copies go in through the same
 * `upload` action the board itself uses — same folders, same log sheet,
 * same permissions.
 */
object Net {

    sealed class Outcome {
        data class Ok(val id: String) : Outcome()
        data class Rejected(val reason: String) : Outcome()
        object Offline : Outcome()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)   // a PDF over a weak counter connection
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)                 // Apps Script answers via a redirect
        .build()

    /** Raw POST. Returns the parsed reply, or null when the network is the problem. */
    private fun post(url: String, body: JSONObject): JSONObject? = try {
        val req = Request.Builder().url(url)
            .post(body.toString().toRequestBody("text/plain; charset=utf-8".toMediaType()))
            .build()
        client.newCall(req).execute().use { res ->
            JSONObject(res.body?.string().orEmpty())
        }
    } catch (e: Exception) { null }

    private fun looksLikeExpiredSession(err: String): Boolean {
        val e = err.lowercase()
        return e.contains("session") || e.contains("sign in") || e.contains("log in") ||
               e.contains("login") || e.contains("expired")
    }

    /** Signs in and caches the token plus the godowns this login may open. */
    fun login(prefs: Prefs): Outcome {
        val r = post(prefs.endpoint, JSONObject()
            .put("action", "login").put("id", prefs.loginId).put("password", prefs.password))
            ?: return Outcome.Offline
        if (!r.optBoolean("ok")) return Outcome.Rejected(r.optString("error", "login refused"))

        val d = r.optJSONObject("data") ?: return Outcome.Rejected("unexpected reply")
        val token = d.optString("token")
        if (token.isBlank()) return Outcome.Rejected(d.optString("msg", "no token returned"))
        prefs.token = token

        // Ask which godowns this login may open, for the picker in Settings.
        val c = post(prefs.endpoint, JSONObject().put("action", "config").put("token", token))
        val cd = c?.optJSONObject("data")
        val arr = cd?.optJSONArray("godowns")
        if (cd != null) prefs.name = cd.optString("name", prefs.loginId)
        if (arr != null) {
            val list = (0 until arr.length()).map {
                val g = arr.getJSONObject(it)
                g.optString("key") to (g.optString("name") + " — " + g.optString("division"))
            }
            prefs.godowns = list
            if (prefs.godown.isBlank() && list.size == 1) prefs.godown = list[0].first
        }
        return Outcome.Ok(token)
    }

    /**
     * Sends one sign copy. `job` carries filename, bill, party and the base64 PDF.
     * A stale token is refreshed once and the upload retried.
     */
    fun upload(prefs: Prefs, job: JSONObject, retry: Boolean = true): Outcome {
        if (prefs.token.isBlank()) {
            when (val l = login(prefs)) {
                is Outcome.Ok -> {}
                else -> return l
            }
        }
        val body = JSONObject()
            .put("action", "upload")
            .put("token", prefs.token)
            .put("godown", prefs.godown)
            .put("date", job.optString("date_folder"))
            .put("section", "signcopy")
            .put("name", job.optString("filename"))
            .put("mime", "application/pdf")
            .put("data", job.optString("pdf"))
            .put("bill", job.optString("invoice"))
            .put("note", job.optString("party"))
            .put("by", prefs.name)

        val r = post(prefs.endpoint, body) ?: return Outcome.Offline
        if (r.optBoolean("ok")) {
            return Outcome.Ok(r.optJSONObject("data")?.optString("id").orEmpty())
        }
        val err = r.optString("error", "rejected")
        if (retry && looksLikeExpiredSession(err)) {
            prefs.token = ""
            return upload(prefs, job, retry = false)
        }
        return Outcome.Rejected(err)
    }
}
