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
    val strat: JSONObject, val stratName: String, val exit: String, val short: Boolean, val target: Double = 0.0,
    val robot: Boolean = false, val robotQuote: Double = 0.0,
)

object Watches {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("vigia", Context.MODE_PRIVATE)
    fun save(ctx: Context, json: String) = sp(ctx).edit().putString("list", json).apply()
    fun load(ctx: Context): List<Watch> = runCatching {
        val a = JSONArray(sp(ctx).getString("list", "[]"))
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Watch(o.getString("id"), o.getString("tf"), o.optString("name", o.getString("id")), o.optString("src", "yahoo"),
                o.optInt("fast", 9), o.optInt("slow", 21), o.optBoolean("cross", true), o.optBoolean("brk", true), o.optInt("dec", 2), o.optBoolean("b3", true),
                o.optJSONObject("strat") ?: JSONObject().put("type", "ma").put("f", o.optInt("fast", 9)).put("s", o.optInt("slow", 21)),
                o.optString("stratName", "Cruzamento de médias"), o.optString("exit", "auto"), o.optBoolean("short", false), o.optDouble("target", 0.0),
                o.optBoolean("robot", false), o.optDouble("robotQuote", 0.0))
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

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AnaliseAcoes/1.0")
        val body = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        return body
    }

    /** tempo escolhido -> (minutos, intervalo do Yahoo, intervalo da Binance, período do Yahoo) */
    private fun tfInfo(tf: String): Array<Any> = when (tf) {
        "1m" -> arrayOf(1, "1m", "1m", "5d"); "2m" -> arrayOf(2, "2m", "1m", "5d"); "3m" -> arrayOf(3, "1m", "3m", "5d")
        "5m" -> arrayOf(5, "5m", "5m", "5d"); "10m" -> arrayOf(10, "5m", "5m", "10d"); "15m" -> arrayOf(15, "15m", "15m", "1mo")
        "20m" -> arrayOf(20, "5m", "5m", "1mo"); "30m" -> arrayOf(30, "30m", "30m", "1mo"); "40m" -> arrayOf(40, "5m", "5m", "1mo")
        "60m" -> arrayOf(60, "60m", "1h", "3mo"); else -> arrayOf(1440, "1d", "1d", "1y")
    }
    private fun baseMin(iv: String) = when (iv) { "1m" -> 1; "2m" -> 2; "3m" -> 3; "5m" -> 5; "15m" -> 15; "30m" -> 30; "60m", "1h" -> 60; else -> 1440 }

    private fun bucket(t: Long, min: Int, crypto: Boolean): Long {
        val p = min * 60L
        if (crypto) return t / p * p
        val loc = t - 10800; val day0 = Math.floorDiv(loc, 86400L) * 86400L; val s0 = day0 + 9 * 3600
        return s0 + Math.floorDiv(loc - s0, p) * p + 10800
    }

    private fun candles(w: Watch): List<Candle> {
        val info = tfInfo(w.tf); val min = info[0] as Int; val yIv = info[1] as String; val bIv = info[2] as String; val yRange = info[3] as String
        val crypto = w.src == "binance"; val bMin = baseMin(if (crypto) bIv else yIv)
        var raw = ArrayList<Candle>()
        if (crypto) {
            val pages = maxOf(1, minOf(5, Math.ceil(300.0 * min / bMin / 1000).toInt()))
            var end: Long? = null
            for (pg in 0 until pages) {
                val a = JSONArray(get("https://api.binance.com/api/v3/klines?symbol=${w.id}&interval=$bIv&limit=1000" + (end?.let { "&endTime=$it" } ?: "")))
                if (a.length() == 0) break
                val part = ArrayList<Candle>()
                for (i in 0 until a.length()) { val k = a.getJSONArray(i); part.add(Candle(k.getLong(0) / 1000, k.getString(1).toDouble(), k.getString(2).toDouble(), k.getString(3).toDouble(), k.getString(4).toDouble())) }
                part.addAll(raw); raw = part; end = a.getJSONArray(0).getLong(0) - 1
                if (a.length() < 1000) break
            }
        } else {
            val r = JSONObject(get("https://query1.finance.yahoo.com/v8/finance/chart/" + java.net.URLEncoder.encode(w.id, "UTF-8") + "?interval=$yIv&range=$yRange&includePrePost=false"))
                .getJSONObject("chart").getJSONArray("result").getJSONObject(0)
            val ts = r.getJSONArray("timestamp"); val q = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
            val o = q.getJSONArray("open"); val h = q.getJSONArray("high"); val l = q.getJSONArray("low"); val c = q.getJSONArray("close")
            for (i in 0 until ts.length()) { if (o.isNull(i) || h.isNull(i) || l.isNull(i) || c.isNull(i)) continue; raw.add(Candle(ts.getLong(i), o.getDouble(i), h.getDouble(i), l.getDouble(i), c.getDouble(i))) }
        }
        // junta os candles menores no tempo escolhido
        val out = ArrayList<Candle>()
        if (bMin == min) out.addAll(raw) else {
            var bt = Long.MIN_VALUE; var o = 0.0; var h = 0.0; var l = 0.0; var c = 0.0
            for (x in raw) {
                val b = bucket(x.t, min, crypto)
                if (b != bt) { if (bt != Long.MIN_VALUE) out.add(Candle(bt, o, h, l, c)); bt = b; o = x.o; h = x.h; l = x.l; c = x.c }
                else { h = maxOf(h, x.h); l = minOf(l, x.l); c = x.c }
            }
            if (bt != Long.MIN_VALUE) out.add(Candle(bt, o, h, l, c))
        }
        // só candles já fechados
        val now = System.currentTimeMillis() / 1000
        return out.filter { it.t + min * 60L <= now - 5 }
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
        val tfName = when (w.tf) { "60m" -> "1 hora"; "1d" -> "diário"; else -> w.tf.removeSuffix("m") + " min" }
        if (w.cross) {
            val dirs = Strategies.dirs(cs, w.strat) { day(it) }
            val evs = Strategies.simulate(cs, dirs, w.exit, w.short || w.dec == 0, w.b3 && w.tf != "1d", w.target) { day(it) }
            for (e in evs) {
                if (e.i != k) continue
                if (w.robot && w.src == "binance") robot(w, e, c.t)
                if (e.entry) send("${w.id}|${w.tf}|in|${c.t}",
                    (if (e.dir > 0) "🟢 ENTRADA DE COMPRA" else "🔴 ENTRADA DE VENDA") + " · ${w.name} ($tfName)",
                    "${w.stratName}. Preço: ${fmt(e.price, w)}. Vou avisar quando for hora de sair.")
                else {
                    val r = if (w.dec == 0) (e.price - e.entryPrice) * e.dir else (e.price - e.entryPrice) / e.entryPrice * e.dir * 100
                    val rs = (if (r >= 0) "+" else "") + if (w.dec == 0) "${Math.round(r)} pts" else String.format(Locale("pt", "BR"), "%.2f%%", r)
                    send("${w.id}|${w.tf}|out|${c.t}",
                        "🏁 HORA DE SAIR da sua ${if (e.dir > 0) "compra" else "venda"} · ${w.name}",
                        "Motivo: ${e.why}. Resultado: $rs (entrou a ${fmt(e.entryPrice, w)}, agora ${fmt(e.price, w)}). Aguarde o próximo sinal.")
                }
            }
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

    // ---------------- robô (conta de TESTE da Binance) ----------------
    private fun robot(w: Watch, e: Strategies.Event, t: Long) {
        val key = "robo|${w.id}|${w.tf}|${if (e.entry) "in" else "out"}|$t"
        val sent = sentPrefs.getStringSet("k", emptySet())!!
        if (key in sent) return
        sentPrefs.edit().putStringSet("k", sent + key).apply() // marca antes, para nunca mandar a mesma ordem duas vezes
        try {
            if (e.entry && e.dir > 0 && !Broker.inPosition(this, w.id)) {
                val o = Broker.buy(this, w.id, w.robotQuote, w.stratName)
                Broker.setPosition(this, w.id, true)
                send("$key|ok", "🤖 Robô COMPROU · ${w.name}", (if (Broker.mode(this) == "real") "CONTA REAL" else "Conta de teste") + ": comprou ${o.getDouble("qty")} por ${fmtQ(o.getDouble("quote"), w)} (${w.stratName}).")
            } else if (!e.entry && e.dir > 0 && Broker.inPosition(this, w.id)) {
                val o = Broker.sellAll(this, w.id, e.why)
                Broker.setPosition(this, w.id, false)
                if (o != null) send("$key|ok", "🤖 Robô VENDEU · ${w.name}", (if (Broker.mode(this) == "real") "CONTA REAL" else "Conta de teste") + ": vendeu ${o.getDouble("qty")} por ${fmtQ(o.getDouble("quote"), w)}. Motivo: ${e.why}.")
            }
        } catch (x: Exception) {
            Broker.log(this, JSONObject().put("t", System.currentTimeMillis()).put("side", "ERRO").put("symbol", w.id).put("why", x.message ?: "falha"))
            send("$key|erro", "⚠️ Robô não conseguiu operar · ${w.name}", x.message ?: "Falha ao falar com a Binance.")
        }
    }

    private fun fmtQ(v: Double, w: Watch) = (if (w.id.endsWith("BRL")) "R$ " else "") +
        java.text.NumberFormat.getNumberInstance(Locale("pt", "BR")).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(v) + (if (w.id.endsWith("USDT")) " USDT" else "")

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
