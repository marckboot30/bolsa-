package com.marck.analiseacoes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Turnos automáticos dos robôs (horário de Brasília):
 *  01h–11h e 13h–23h: robôs operando.
 *  11h–13h e 23h–01h: pausa. No começo da pausa fecha todas as operações,
 *  faz a análise detalhada de todas as criptos e liga os robôs nas melhores.
 */
object Turnos {
    private val brt = TimeZone.getTimeZone("America/Sao_Paulo")
    private fun sp(ctx: Context) = ctx.getSharedPreferences("turnos", Context.MODE_PRIVATE)

    fun save(ctx: Context, json: String) = sp(ctx).edit().putString("cfg", json).apply()
    fun cfg(ctx: Context): JSONObject = runCatching { JSONObject(sp(ctx).getString("cfg", "{}")) }.getOrDefault(JSONObject())
    fun enabled(ctx: Context) = cfg(ctx).optBoolean("on", false)

    private fun hour() = Calendar.getInstance(brt).get(Calendar.HOUR_OF_DAY)

    /** Está na pausa (11h–13h ou 23h–01h)? */
    fun paused(ctx: Context): Boolean { if (!enabled(ctx)) return false; val h = hour(); return h == 11 || h == 12 || h == 23 || h == 0 }

    /** Identifica a pausa atual (ex.: "20261124-11"). */
    private fun pauseId(): String {
        val c = Calendar.getInstance(brt); val h = c.get(Calendar.HOUR_OF_DAY)
        if (h == 0) c.add(Calendar.DAY_OF_MONTH, -1)
        val f = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = brt }
        return f.format(c.time) + "-" + (if (h == 11 || h == 12) "11" else "23")
    }

    fun status(ctx: Context): String {
        val h = hour(); val on = enabled(ctx)
        return JSONObject().put("on", on).put("paused", on && (h == 11 || h == 12 || h == 23 || h == 0)).put("hour", h)
            .put("last", sp(ctx).getString("lastMsg", "")).put("lastT", sp(ctx).getLong("lastT", 0)).toString()
    }

    /** Chamado pela vigia a cada volta. Devolve true se fez algo pesado. */
    fun tick(ctx: Context, get: (String) -> String, notify: (String, String, String) -> Unit, progress: (String) -> Unit): Boolean {
        if (!paused(ctx)) return false
        val id = pauseId(); val p = sp(ctx)
        if (!p.getBoolean("closed_$id", false)) {
            p.edit().putBoolean("closed_$id", true).apply()
            closeAll(ctx, notify, id)
        }
        if (!p.getBoolean("done_$id", false)) {
            p.edit().putBoolean("done_$id", true).apply()
            runCatching { analyzeAndApply(ctx, get, notify, progress, id) }
                .onFailure { notify("turno|erro|$id", "⚠️ Análise do turno falhou", (it.message ?: "sem internet?") + " Os robôs continuam com a seleção anterior.") }
            return true
        }
        return false
    }

    private fun closeAll(ctx: Context, notify: (String, String, String) -> Unit, id: String) {
        val syms = Broker.openSymbols(ctx); val sl = Broker.slots(ctx); if (syms.isEmpty() && sl.isEmpty()) return
        var ok = 0; var tot = 0.0
        for (k in sl.keys) { val (sym, tf) = k.split("|").let { it[0] to it.getOrElse(1) { "" } }
            runCatching { Broker.robotClose(ctx, sym, tf, "fim do turno (fechamento automático)")?.let { ok++; tot += it.optDouble("quote") } } }
        for (s in syms) {
            runCatching {
                Broker.cancelAll(ctx, s)
                val o = Broker.sellAll(ctx, s, "fim do turno (fechamento automático)")
                Broker.setPosition(ctx, s, false)
                if (o != null) { ok++; tot += o.optDouble("quote") }
            }
        }
        notify("turno|fecha|$id", "🔒 Fim do turno: $ok operações fechadas", "Fechei tudo para a pausa de análise. Volto a operar às ${if (id.endsWith("-11")) "13h" else "01h"}.")
    }

    private fun analyzeAndApply(ctx: Context, get: (String) -> String, notify: (String, String, String) -> Unit, progress: (String) -> Unit, id: String) {
        val c = cfg(ctx)
        val scan = JSONObject().put("coins", c.optJSONArray("coins")).put("tfs", c.optJSONArray("tfs")).put("strats", c.optJSONArray("strats"))
            .put("exits", JSONArray(listOf("auto", "atr", "atr2"))).put("fee", .001).put("minOps", 5).put("minHit", c.optDouble("minHit", 75.0))
        val rep = Scanner.run(ctx, get, scan, c.optInt("days", 15), progress)
        val items = rep.optJSONArray("items") ?: JSONArray()
        val minHit = c.optDouble("minHit", 75.0); val minFreq = c.optDouble("minFreq", 1.0); val topN = c.optInt("topN", 10); val quote = c.optString("quote", "USDT")
        val all = (0 until items.length()).map { items.getJSONObject(it) }
            .filter { it.optInt("hit") >= minHit && it.optDouble("sum") > 0 && it.optInt("n") >= 5 && it.optDouble("opd") >= minFreq && it.optString("id").endsWith(quote) }
        val big = all.filter { it.optInt("n") >= 8 }
        val seen = HashSet<String>()
        val pick = (if (big.size >= topN) big else all).sortedWith(compareByDescending<JSONObject> { it.optInt("hit") }.thenByDescending { it.optDouble("sum") })
            .filter { seen.add(it.optString("id")) }.take(topN)
        val turno = if (id.endsWith("-11")) "13h" else "01h"
        if (pick.isEmpty()) {
            setLast(ctx, "Nenhuma cripto passou nos filtros. Robôs mantidos.")
            notify("turno|vazio|$id", "📊 Análise do turno: nenhuma passou", "Nenhuma cripto teve ${minHit.toInt()}%+ de acerto, lucro e ${minFreq} op/dia. Os robôs continuam com a seleção anterior.")
            return
        }
        applyPick(ctx, pick, c.optJSONObject("tpl") ?: JSONObject())
        val b = pick[0]
        val msg = "${pick.size} criptos para o turno das $turno · melhor ${b.optString("name")} ${b.optInt("hit")}%"
        setLast(ctx, msg)
        notify("turno|ok|$id", "🤖 Robôs atualizados", msg + ". Toque para ver.")
    }

    private fun setLast(ctx: Context, m: String) = sp(ctx).edit().putString("lastMsg", m).putLong("lastT", System.currentTimeMillis()).apply()

    /** Troca os robôs na lista da vigia: liga nos escolhidos, desliga os outros (menos os comprados). */
    private fun applyPick(ctx: Context, pick: List<JSONObject>, tpl: JSONObject) {
        val vp = ctx.getSharedPreferences("vigia", Context.MODE_PRIVATE)
        val arr = runCatching { JSONArray(vp.getString("list", "[]")) }.getOrDefault(JSONArray())
        val keep = pick.map { it.optString("id") + "|" + it.optString("tf") }.toSet()
        val out = JSONArray()
        for (i in 0 until arr.length()) { val w = arr.getJSONObject(i); val k = w.optString("id") + "|" + w.optString("tf")
            if (w.optString("src") == "binance" && w.optBoolean("robot") && k !in keep && Broker.slot(ctx, k) == null && !Broker.inPosition(ctx, w.optString("id"))) w.put("robot", false)
            if (w.optBoolean("auto") && !w.optBoolean("robot") && k !in keep) continue
            out.put(w) }
        for (p in pick) { val k = p.optString("id") + "|" + p.optString("tf")
            var w: JSONObject? = null; for (i in 0 until out.length()) { val x = out.getJSONObject(i); if (x.optString("id") + "|" + x.optString("tf") == k) w = x }
            if (w == null) { w = JSONObject(tpl.toString()).put("id", p.optString("id")).put("tf", p.optString("tf")).put("name", p.optString("name")).put("src", "binance")
                .put("dec", 2).put("b3", false).put("auto", true).put("cur", if (p.optString("id").endsWith("USDT")) "" else "R$ "); out.put(w) }
            val st = p.optJSONObject("st") ?: JSONObject()
            w.put("robot", true).put("strat", st).put("stratName", st.optString("name")).put("exit", p.optString("exit")).put("short", false).put("hit", p.optInt("hit")) }
        vp.edit().putString("list", out.toString()).putLong("changed", System.currentTimeMillis()).apply()
    }
}
