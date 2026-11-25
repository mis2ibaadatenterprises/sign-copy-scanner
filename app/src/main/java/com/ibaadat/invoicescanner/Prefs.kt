package com.ibaadat.invoicescanner

import android.content.Context
import org.json.JSONArray

/**
 * Per-phone settings. The phone holds a godown login — the same ID and password
 * used on the board — so every sign copy is attributed to a real person and
 * lands in that godown's day folder.
 */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("scanner", Context.MODE_PRIVATE)
    private fun s(k: String) = p.getString(k, "") ?: ""
    private fun put(k: String, v: String) = p.edit().putString(k, v.trim()).apply()

    var endpoint: String get() = s("endpoint"); set(v) = put("endpoint", v)
    var loginId:  String get() = s("loginId");  set(v) = put("loginId", v)
    var password: String get() = s("password"); set(v) = put("password", v)
    var godown:   String get() = s("godown");   set(v) = put("godown", v)
    var name:     String get() = s("name");     set(v) = put("name", v)
    var token:    String get() = s("token");    set(v) = put("token", v)
    var updateUrl: String get() = s("updateUrl"); set(v) = put("updateUrl", v)

    /** Godowns this login may open, cached from the last successful sign-in. */
    var godowns: List<Pair<String, String>>
        get() = try {
            val a = JSONArray(s("godowns").ifBlank { "[]" })
            (0 until a.length()).map {
                val o = a.getJSONObject(it); o.getString("key") to o.getString("label")
            }
        } catch (e: Exception) { emptyList() }
        set(v) {
            val a = JSONArray()
            v.forEach { a.put(org.json.JSONObject().put("key", it.first).put("label", it.second)) }
            p.edit().putString("godowns", a.toString()).apply()
        }

    val configured get() = endpoint.isNotBlank() && loginId.isNotBlank() &&
            password.isNotBlank() && godown.isNotBlank()
}
