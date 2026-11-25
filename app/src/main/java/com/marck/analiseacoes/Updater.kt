package com.marck.analiseacoes

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Atualização pelo próprio app: confere a última versão no GitHub, baixa e abre o instalador do Android. */
object Updater {
    private const val API = "https://api.github.com/repos/marckboot30/bolsa-/releases/latest"

    fun current(ctx: Context): Long {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
    }

    fun check(ctx: Context): JSONObject {
        val c = URL(API).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "AnaliseAcoes"); c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 10000; c.readTimeout = 15000
        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() }); c.disconnect()
        val tag = j.optString("tag_name"); val n = tag.removePrefix("v").toLongOrNull() ?: 0
        val assets = j.optJSONArray("assets"); var url = ""
        if (assets != null) for (i in 0 until assets.length()) { val a = assets.getJSONObject(i); if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url") }
        val cur = current(ctx)
        return JSONObject().put("available", n > cur && url.isNotEmpty()).put("latest", n).put("current", cur).put("url", url).put("tag", tag)
    }

    /** Pode instalar? Se não, abre a tela do Android para liberar "instalar apps deste app". */
    fun canInstall(act: Activity): Boolean {
        if (act.packageManager.canRequestPackageInstalls()) return true
        act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.packageName)))
        return false
    }

    fun install(ctx: Context, url: String, progress: (Int) -> Unit) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(ctx.packageName) }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true; c.setRequestProperty("User-Agent", "AnaliseAcoes"); c.connectTimeout = 15000; c.readTimeout = 30000
            val total = c.contentLengthLong
            c.inputStream.use { input -> s.openWrite("app.apk", 0, if (total > 0) total else -1).use { out ->
                val buf = ByteArray(64 * 1024); var done = 0L; var last = -1
                while (true) { val r = input.read(buf); if (r < 0) break; out.write(buf, 0, r); done += r
                    if (total > 0) { val p = (done * 100 / total).toInt(); if (p != last) { last = p; progress(p) } } }
                s.fsync(out) } }
            c.disconnect()
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(ctx, 4242, Intent(ctx, UpdateReceiver::class.java), flags)
            s.commit(pending.intentSender)
        }
    }
}

/** O Android pede a confirmação do usuário: abre a tela "Atualizar". */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
            confirm?.let { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(it) }
        }
    }
}
