package com.marck.analiseacoes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Um ativo vigiado no fundo. */
data class Watch(
    val id: String, val tf: String, val name: String, val src: String,
    val fast: Int, val slow: Int, val cross: Boolean, val brk: Boolean, val dec: Int, val b3: Boolean,
)

object Watches {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("vigia", Context.MODE_PRIVATE)
    fun save(ctx: Context, json: String) = sp(ctx).edit().putString("list", json).apply()
    fun load(ctx: Context): List<Watch> = runCatching {
        val a = JSONArray(sp(ctx).getString("list", "[]"))
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Watch(o.getString("id"), o.getString("tf"), o.optString("name", o.getString("id")), o.optString("src", "yahoo"),
                o.optInt("fast", 9), o.optInt("slow", 21), o.optBoolean("cross", true), o.optBoolean("brk", true), o.optInt("dec", 2), o.optBoolean("b3", true))
        }
    }.getOrDefault(emptyList())

    fun apply(ctx: Context) {
        val i = Intent(ctx, MonitorService::class.java)
        if (load(ctx).isEmpty()) ctx.stopService(i)
        else if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
    }
}

class MonitorService : Service() {
    @Volatile private var running = false
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null
    private val sentPrefs by lazy { getSharedPreferences("enviados", Context.MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels(this)
        val n = status("Iniciando a vigia…")
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
        if (!running) {
            running = true
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "analise:vigia").apply { acquire() }
            worker = Thread { loop() }.apply { isDaemon = true; start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        runCatching { wake?.release() }
        super.onDestroy()
    }

    private fun loop() {
        while (running) {
            val list = Watches.load(this)
            if (list.isEmpty()) { stopSelf(); return }
            var ok = 0
            for (w in list) if (runCatching { check(w) }.isSuccess) ok++
            val hora = SimpleDateFormat("HH:mm", Locale("pt", "BR")).apply { timeZone = TimeZone.getTimeZone("America/Sao_Paulo") }.format(Date())
            val txt = "Vigiando ${list.size} ativo(s) · conferido às $hora" + if (ok < list.size) " · ${list.size - ok} sem resposta" else ""
            getSystemService(NotificationManager::class.java).notify(1, status(txt))
            try { Thread.sleep(30_000) } catch (_: InterruptedException) { return }
        }
    }

    // ---------------- dados ----------------
    private class Candle(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double)

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AnaliseAcoes/1.0")
        val body = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        return body
    }

    private fun ivSec(tf: String) = when (tf) { "1m" -> 60L; "5m" -> 300L; "15m" -> 900L; "60m" -> 3600L; else -> 86400L }

    private fun candles(w: Watch): List<Candle> {
        val out = ArrayList<Candle>()
        if (w.src == "binance") {
            val iv = if (w.tf == "60m") "1h" else w.tf
            val a = JSONArray(get("https://api.binance.com/api/v3/klines?symbol=${w.id}&interval=$iv&limit=300"))
            for (i in 0 until a.length()) { val k = a.getJSONArray(i); out.add(Candle(k.getLong(0) / 1000, k.getString(1).toDouble(), k.getString(2).toDouble(), k.getString(3).toDouble(), k.getString(4).toDouble())) }
        } else {
            val range = when (w.tf) { "1m", "5m" -> "5d"; "15m" -> "1mo"; "60m" -> "3mo"; else -> "1y" }
            val r = JSONObject(get("https://query1.finance.yahoo.com/v8/finance/chart/" + java.net.URLEncoder.encode(w.id, "UTF-8") + "?interval=${w.tf}&range=$range&includePrePost=false"))
                .getJSONObject("chart").getJSONArray("result").getJSONObject(0)
            val ts = r.getJSONArray("timestamp"); val q = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
            val o = q.getJSONArray("open"); val h = q.getJSONArray("high"); val l = q.getJSONArray("low"); val c = q.getJSONArray("close")
            for (i in 0 until ts.length()) { if (o.isNull(i) || h.isNull(i) || l.isNull(i) || c.isNull(i)) continue; out.add(Candle(ts.getLong(i), o.getDouble(i), h.getDouble(i), l.getDouble(i), c.getDouble(i))) }
        }
        // só candles já fechados
        val now = System.currentTimeMillis() / 1000
        return out.filter { it.t + ivSec(w.tf) <= now - 5 }
    }

    private fun ema(v: List<Double>, n: Int): DoubleArray {
        val out = DoubleArray(v.size) { Double.NaN }; if (v.size < n) return out
        val k = 2.0 / (n + 1); var e = v.subList(0, n).average(); out[n - 1] = e
        for (i in n until v.size) { e = v[i] * k + e * (1 - k); out[i] = e }
        return out
    }

    private fun day(t: Long) = (t - 10800) / 86400 // dia no horário de Brasília

    // ---------------- sinais ----------------
    private fun check(w: Watch) {
        val cs = candles(w); if (cs.size < w.slow + 3) return
        val k = cs.size - 1; val c = cs[k]
        val closes = cs.map { it.c }; val f = ema(closes, w.fast); val s = ema(closes, w.slow)
        val tfName = when (w.tf) { "1m" -> "1 min"; "5m" -> "5 min"; "15m" -> "15 min"; "60m" -> "1 hora"; else -> "diário" }
        val sameDay = day(cs[k - 1].t) == day(c.t)
        if (w.cross && !f[k - 1].isNaN() && !s[k - 1].isNaN() && (!w.b3 || sameDay || w.tf == "1d")) {
            val up = f[k - 1] <= s[k - 1] && f[k] > s[k] && c.c > c.o
            val down = f[k - 1] >= s[k - 1] && f[k] < s[k] && c.c < c.o
            if (up || down) send("${w.id}|${w.tf}|x|${c.t}",
                (if (up) "🟢 COMPRA" else "🔴 VENDA") + " · ${w.name} ($tfName)",
                "Média ${w.fast} cruzou ${if (up) "para cima" else "para baixo"} da ${w.slow}. Preço: ${fmt(c.c, w)}")
        }
        if (w.brk && w.tf != "1d") {
            var hi = Double.NEGATIVE_INFINITY; var lo = Double.POSITIVE_INFINITY
            for (i in 0 until k) if (day(cs[i].t) == day(c.t)) { hi = maxOf(hi, cs[i].h); lo = minOf(lo, cs[i].l) }
            if (hi.isFinite()) {
                val d = day(c.t)
                if (c.c > hi) send("${w.id}|${w.tf}|bh|$d", "⬆️ Rompeu a MÁXIMA do dia · ${w.name}", "Fechou em ${fmt(c.c, w)}, acima de ${fmt(hi, w)}")
                if (c.c < lo) send("${w.id}|${w.tf}|bl|$d", "⬇️ Perdeu a MÍNIMA do dia · ${w.name}", "Fechou em ${fmt(c.c, w)}, abaixo de ${fmt(lo, w)}")
            }
        }
    }

    private fun fmt(v: Double, w: Watch): String {
        val dec = if (w.dec == 0) 0 else if (!w.b3 && v < 10) 4 else 2
        val nf = java.text.NumberFormat.getNumberInstance(Locale("pt", "BR")).apply { minimumFractionDigits = dec; maximumFractionDigits = dec }
        return (if (w.dec == 0) "" else "R$ ") + nf.format(v)
    }

    private fun send(key: String, title: String, text: String) {
        val sent = sentPrefs.getStringSet("k", emptySet())!!.toMutableSet()
        if (key in sent) return
        sent.add(key); if (sent.size > 400) { val drop = sent.take(200); sent.removeAll(drop.toSet()) }
        sentPrefs.edit().putStringSet("k", sent).apply()
        val n = builder("sinais").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true).setContentIntent(openApp(this)).build()
        getSystemService(NotificationManager::class.java).notify(key.hashCode(), n)
    }

    private fun builder(ch: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, ch) else @Suppress("DEPRECATION") Notification.Builder(this)

    private fun status(text: String): Notification = builder("vigia").setSmallIcon(android.R.drawable.ic_menu_view)
        .setContentTitle("Análise de Ações").setContentText(text).setOngoing(true).setContentIntent(openApp(this)).build()

    companion object {
        fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)

        fun createChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("sinais", "Sinais de compra e venda", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
            nm.createNotificationChannel(NotificationChannel("vigia", "Vigia em segundo plano", NotificationManager.IMPORTANCE_LOW))
        }
    }
}

/** Liga a vigia de novo quando o celular reinicia. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) runCatching { Watches.apply(ctx) }
    }
}
