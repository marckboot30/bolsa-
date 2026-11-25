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
    val robot: Boolean = false, val robotQuote: Double = 0.0, val cur: String = "R$ ",
    val maxLoss: Double = 0.0, val maxLosses: Int = 0, val oco: Boolean = true, val maxLossAll: Double = 0.0, val maxOpen: Int = 0,
    val tpMoney: Double = 0.0, val slMoney: Double = 0.0, val prot: JSONObject = JSONObject(),
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
                o.optBoolean("robot", false), o.optDouble("robotQuote", 0.0), o.optString("cur", if (o.optString("id").endsWith("USDT")) "" else "R$ "),
                o.optDouble("maxLoss", 0.0), o.optInt("maxLosses", 0), o.optBoolean("oco", true), o.optDouble("maxLossAll", 0.0), o.optInt("maxOpen", 0),
                o.optDouble("tpMoney", 0.0), o.optDouble("slMoney", 0.0), o.optJSONObject("prot") ?: JSONObject())
        }
    }.getOrDefault(emptyList())

    fun apply(ctx: Context) {
        val i = Intent(ctx, MonitorService::class.java)
        if (load(ctx).isEmpty() && !Scanner.enabled(ctx) && !Turnos.enabled(ctx)) ctx.stopService(i)
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
            if (list.isEmpty() && !Scanner.enabled(this) && !Turnos.enabled(this)) { stopSelf(); return }
            runCatching { Turnos.tick(this, { get(it) }, { k, t, x -> send(k, t, x) }) { getSystemService(NotificationManager::class.java).notify(1, status("📊 Análise do turno: $it")) } }
            if (Scanner.due(this)) runCatching { dailyReport() }
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
        if (w.robot) { Broker.beat(this, w.id, w.tf); if (w.src == "binance") { robotWatchOco(w); runCatching { trail(w, cs) } } }
        val tfName = when (w.tf) { "60m" -> "1 hora"; "1d" -> "diário"; else -> w.tf.removeSuffix("m") + " min" }
        if (w.cross) {
            val dirs = Strategies.dirs(cs, w.strat) { day(it) }
            val q = if (w.robotQuote > 0) w.robotQuote else 50.0; val fees = q * 0.002
            val tpP = if (w.tpMoney > 0) (w.tpMoney + fees) / q else 0.0; val slP = if (w.slMoney > 0) maxOf(0.0005, (w.slMoney - fees) / q) else 0.0
            val evs = Strategies.simulate(cs, dirs, w.exit, w.short || w.dec == 0, w.b3 && w.tf != "1d", w.target, tpP, slP) { day(it) }
            val stKey = "st|${w.id}|${w.tf}"; val wasIn = sentPrefs.getBoolean(stKey, false)
            sentPrefs.edit().putBoolean(stKey, evs.lastOrNull()?.entry == true).apply()
            if (w.robot && w.src == "binance") {
                // entrar junto: a análise deu compra há até 2 candles e o robô ainda está fora
                val last = evs.lastOrNull()
                if (last != null && last.entry && last.dir > 0 && last.i < k && last.i >= k - 2 && Broker.slot(this, "${w.id}|${w.tf}") == null) robot(w, last, cs[last.i].t, cs)
            }
            for (e in evs) {
                if (e.i != k) continue
                if (w.robot && w.src == "binance") robot(w, e, c.t, cs)
                if (e.entry == wasIn) continue // já avisou essa entrada (ou essa saída) antes
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
                // um aviso por cripto por dia (mesmo vigiando vários tempos gráficos)
                val sent = sentPrefs.getStringSet("k", emptySet())!!
                if (c.c > hi && "brk|${w.id}|bh|$d" !in sent) { send("brk|${w.id}|bh|$d", "", ""); send("${w.id}|${w.tf}|bh|$d", "⬆️ Rompeu a MÁXIMA do dia · ${w.name}", "Fechou em ${fmt(c.c, w)}, acima de ${fmt(hi, w)}") }
                if (c.c < lo && "brk|${w.id}|bl|$d" !in sent) { send("brk|${w.id}|bl|$d", "", ""); send("${w.id}|${w.tf}|bl|$d", "⬇️ Perdeu a MÍNIMA do dia · ${w.name}", "Fechou em ${fmt(c.c, w)}, abaixo de ${fmt(lo, w)}") }
            }
        }
    }

    // ---------------- relatório diário ----------------
    private fun dailyReport() {
        val rep = Scanner.run(this, { get(it) }) { getSystemService(NotificationManager::class.java).notify(1, status("📊 Montando o relatório: $it")) }
        val items = rep.optJSONArray("items")
        if (items == null || items.length() == 0) { send("relatorio|" + rep.optLong("t"), "📊 Relatório do dia", "Nenhuma estratégia deu lucro nas criptos testadas. O mais seguro hoje é não operar."); return }
        val b = items.getJSONObject(0)
        val txt = "${b.optString("name")} · ${b.optString("stratName")} · ${tfLabel(b.optString("tf"))}: acerto ${b.optInt("hit")}%, " +
            String.format(Locale("pt", "BR"), "%+.1f%% em %.0f dias", b.optDouble("sum"), b.optDouble("days")) + ". Toque para abrir já configurado."
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("asset", b.optString("id")).putExtra("tf", b.optString("tf")).putExtra("strat", b.optString("strat")).putExtra("exit", b.optString("exit")).putExtra("report", true)
        val pi = PendingIntent.getActivity(this, 777, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        getSystemService(NotificationManager::class.java).notify(777, builder("sinais").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("📊 Relatório do dia: melhor em ${b.optString("name")}").setContentText(txt).setStyle(Notification.BigTextStyle().bigText(txt))
            .setAutoCancel(true).setContentIntent(pi).build())
    }

    private fun tfLabel(tf: String) = when (tf) { "60m" -> "1 hora"; "1d" -> "diário"; else -> tf.removeSuffix("m") + " min" }

    // ---------------- robô (Binance) ----------------
    /** Se o alvo ou o stop foi executado pela própria Binance, registra a venda e avisa. */
    private fun robotWatchOco(w: Watch) {
        if (Broker.slot(this, "${w.id}|${w.tf}") == null) return
        try {
            val o = Broker.robotOcoDone(this, w.id, w.tf) ?: return
            val alvo = o.optBoolean("alvo")
            send("robo|${w.id}|${w.tf}|oco|${o.optLong("t")}", if (alvo) "🎯 Robô bateu o ALVO · ${w.name} · ${tfName(w.tf)}" else "🛑 Robô saiu no STOP · ${w.name} · ${tfName(w.tf)}",
                "A Binance vendeu ${o.getDouble("qty")} por ${fmtQ(o.getDouble("quote"), w)}.")
        } catch (_: Exception) { }
    }

    /** Stop móvel: trava parte do lucro e, com volume forte, deixa o lucro correr. */
    private fun trail(w: Watch, cs: List<Candle>) {
        val p = w.prot; val lockOn = p.optBoolean("lockOn", true); val runOn = p.optBoolean("runOn", true)
        if (!lockOn && !runOn) return
        val sl0 = Broker.slot(this, "${w.id}|${w.tf}") ?: return; if (!sl0.has("oco")) return
        val q = sl0.optDouble("quote"); val qty = sl0.optDouble("qty"); val pr = sl0.optDouble("price"); if (q <= 0 || qty <= 0 || pr <= 0) return
        val fees = q * 0.002
        val px = { m: Double -> pr * (1 + (m + fees) / q) }
        val money = { price: Double -> (price / pr - 1) * q - fees }
        val now = JSONObject(get("https://api.binance.com/api/v3/ticker/price?symbol=${w.id}")).getString("price").toDouble()
        val pnl = qty * now - q - fees; val peak = maxOf(sl0.optDouble("peak", 0.0), pnl)
        var ph = sl0.optInt("ph", 0); val tpM = money(sl0.optDouble("tp")); val slM = money(sl0.optDouble("sl"))
        val lockAt = p.optDouble("lockAt", 0.6); val lockKeep = p.optDouble("lockKeep", 0.3)
        val goal = if (w.tpMoney > 0) w.tpMoney else tpM
        var newTp = Double.NaN; var newSl = Double.NaN; var why = ""
        // fase 2/3: perto do alvo com volume forte e preço acima da média 9 -> sobe o alvo e deixa correr
        if (runOn && pnl >= maxOf(lockAt, tpM - 0.25 * goal) && strong(w, cs, now)) {
            newTp = tpM + p.optDouble("runAdd", 1.0)
            newSl = maxOf(slM, if (pnl >= goal) p.optDouble("runSl", 0.7) else maxOf(lockKeep, 0.5 * goal)); ph = 2; why = "🚀 Volume forte: deixando correr"
        }
        // fase 3: seguindo o preço, guarda 70% do maior lucro
        if (ph == 2 && peak >= goal) { val keep = p.optDouble("trailKeep", 0.7) * peak; if (keep > maxOf(slM, if (newSl.isNaN()) -1e9 else newSl) + 0.1 * goal) { newSl = keep; if (why.isEmpty()) why = "📈 Stop subiu junto" } }
        // fase 1: trava parte do lucro
        if (lockOn && ph < 1 && pnl >= lockAt && slM < lockKeep) { newSl = maxOf(lockKeep, if (newSl.isNaN()) lockKeep else newSl); if (ph < 1) ph = 1; if (why.isEmpty()) why = "🔒 Lucro travado" }
        if (newSl.isNaN() && newTp.isNaN()) { if (peak > sl0.optDouble("peak", 0.0)) Broker.slotPut(this, w.id, w.tf, JSONObject().put("peak", peak)); return }
        var tp = if (newTp.isNaN()) sl0.optDouble("tp") else px(newTp); var sl = if (newSl.isNaN()) sl0.optDouble("sl") else px(newSl)
        // a Binance recusa stop acima do preço ou alvo abaixo dele
        if (sl >= now * 0.9985) sl = now * 0.9985; if (tp <= now * 1.0015) tp = now * 1.0015
        sl = maxOf(sl, minOf(sl0.optDouble("sl"), now * 0.9985))
        if (sl <= sl0.optDouble("sl") && tp == sl0.optDouble("tp")) return
        if (Broker.robotMove(this, w.id, w.tf, tp, sl, JSONObject().put("ph", ph).put("peak", peak)))
            send("robo|${w.id}|${w.tf}|mv|${System.currentTimeMillis() / 60000}", "$why · ${w.name} · ${tfName(w.tf)}",
                "Lucro agora ${fmtQ(pnl, w)}. Stop garante ${fmtQ(money(sl), w)}, alvo em ${fmtQ(money(tp), w)}.")
    }

    /** Volume do candle atual (ou do último fechado) ≥ N× a média dos 20 anteriores e preço acima da média 9. */
    private fun strong(w: Watch, cs: List<Candle>, now: Double): Boolean {
        val closes = cs.map { it.c } + now; val e = ema(closes, 9); if (e.last().isNaN() || now <= e.last()) return false
        val iv = tfInfo(w.tf)[2] as String; val a = JSONArray(get("https://api.binance.com/api/v3/klines?symbol=${w.id}&interval=$iv&limit=22"))
        if (a.length() < 22) return false
        val v = List(a.length()) { a.getJSONArray(it).getString(5).toDouble() }; val avg = v.subList(0, 20).average(); if (avg <= 0) return false
        val cur = a.getJSONArray(21); val frac = ((System.currentTimeMillis() - cur.getLong(0)).toDouble() / (cur.getLong(6) - cur.getLong(0) + 1)).coerceIn(0.25, 1.0)
        return maxOf(v[20], v[21] / frac) >= w.prot.optDouble("runVol", 1.5) * avg
    }

    private fun tfName(tf: String) = when (tf) { "60m" -> "1 hora"; "1d" -> "diário"; else -> tf.removeSuffix("m") + " min" }

    private fun robot(w: Watch, e: Strategies.Event, t: Long, cs: List<Candle>) {
        val key = "robo|${w.id}|${w.tf}|${if (e.entry) "in" else "out"}|$t"
        val sent = sentPrefs.getStringSet("k", emptySet())!!
        if (key in sent) return
        sentPrefs.edit().putStringSet("k", sent + key).apply() // marca antes, para nunca mandar a mesma ordem duas vezes
        try {
            val slotKey = "${w.id}|${w.tf}"
            if (e.entry && e.dir > 0 && Broker.slot(this, slotKey) == null) {
                if (Turnos.paused(this)) return // pausa para análise: sem novas entradas
                val (pnl, seq) = Broker.today(this, w.id); val day = java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(java.util.Date())
                if (w.maxLoss > 0 && -pnl >= w.maxLoss) { send("robo|pausa|${w.id}|$day", "⏸ Robô pausado hoje · ${w.name}", "Chegou no limite de perda do dia (${fmtQ(-pnl, w)}). Volta a operar amanhã."); return }
                if (w.maxLossAll > 0 && -Broker.todayAll(this) >= w.maxLossAll) { send("robo|pausaTodos|$day", "⏸ Todos os robôs pausados hoje", "A soma das perdas de hoje chegou no limite geral (${fmtQ(w.maxLossAll, w)}). Voltam a operar amanhã."); return }
                if (w.maxOpen > 0 && Broker.openCount(this) >= w.maxOpen) { send("$key|cheio", "⏳ Robô não entrou · ${w.name}", "Já há ${w.maxOpen} robôs comprados ao mesmo tempo (o máximo que você escolheu)."); return }
                if (w.maxLosses > 0 && seq >= w.maxLosses) { send("robo|pausa2|${w.id}|$day", "⏸ Robô pausado hoje · ${w.name}", "Foram $seq perdas seguidas. Volta a operar amanhã."); return }
                val o = Broker.robotBuy(this, w.id, w.tf, w.robotQuote, w.stratName)
                if (w.oco) {
                    val a = Strategies.atr(cs, 14); val k = cs.size - 1; val at = if (a[k].isNaN()) cs[k].h - cs[k].l else a[k]
                    val (tk, sk) = when (w.exit) { "atr" -> Pair(1.0, 2.0); "atr2" -> Pair(2.0, 1.0); else -> Pair(4.0, 2.0) }
                    val pr = o.getDouble("price"); var tp = pr + tk * at; var sl = pr - sk * at
                    // alvo/stop em dinheiro: já contando ~0,2% de taxas (ida e volta)
                    val q = o.getDouble("quote"); val fees = q * 0.002
                    if (w.tpMoney > 0 && q > 0) tp = pr * (1 + (w.tpMoney + fees) / q)
                    if (w.slMoney > 0 && q > 0) sl = pr * (1 - maxOf(0.0005, (w.slMoney - fees) / q))
                    try { Broker.robotProtect(this, w.id, w.tf, tp, sl); Broker.patchLast(this, JSONObject().put("tp", tp).put("sl", sl)) }
                    catch (x: Exception) { send("$key|semstop", "⚠️ Robô comprou sem stop na Binance · ${w.name}", (x.message ?: "falha") + " O app continua vigiando a saída.") }
                }
                send("$key|ok", "🤖 Robô COMPROU · ${w.name} · ${tfName(w.tf)}", (if (Broker.mode(this) == "real") "CONTA REAL" else "Conta de teste") + ": comprou ${o.getDouble("qty")} por ${fmtQ(o.getDouble("quote"), w)} (${w.stratName}).")
            } else if (!e.entry && e.dir > 0 && Broker.slot(this, slotKey) != null) {
                val o = Broker.robotClose(this, w.id, w.tf, e.why)
                if (o != null) send("$key|ok", "🤖 Robô VENDEU · ${w.name} · ${tfName(w.tf)}", (if (Broker.mode(this) == "real") "CONTA REAL" else "Conta de teste") + ": vendeu ${o.getDouble("qty")} por ${fmtQ(o.getDouble("quote"), w)}. Motivo: ${e.why}.")
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
        return (if (w.dec == 0) "" else w.cur) + nf.format(v)
    }

    private fun send(key: String, title: String, text: String) {
        val sent = sentPrefs.getStringSet("k", emptySet())!!.toMutableSet()
        if (key in sent) return
        sent.add(key); if (sent.size > 400) { val drop = sent.take(200); sent.removeAll(drop.toSet()) }
        sentPrefs.edit().putStringSet("k", sent).apply()
        if (title.isEmpty()) return // só marca, sem notificação
        val n = builder("sinais").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true).setContentIntent(openAt(this, key)).build()
        getSystemService(NotificationManager::class.java).notify(key.hashCode(), n)
    }

    private fun builder(ch: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, ch) else @Suppress("DEPRECATION") Notification.Builder(this)

    private fun status(text: String): Notification = builder("vigia").setSmallIcon(android.R.drawable.ic_menu_view)
        .setContentTitle("Análise de Ações").setContentText(text).setOngoing(true).setContentIntent(openApp(this)).build()

    companion object {
        /** Abre o app já no ativo e no tempo gráfico do alerta (a chave começa com "ativo|tempo|..."). */
        fun openAt(ctx: Context, key: String): PendingIntent {
            val p = key.removePrefix("robo|").split("|")
            val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (p.size >= 2) i.putExtra("asset", p[0]).putExtra("tf", p[1])
            return PendingIntent.getActivity(ctx, key.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

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
