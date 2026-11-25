package com.marck.analiseacoes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Relatório diário: testa todas as estratégias, saídas e tempos gráficos em várias criptos
 * e mostra onde elas estão funcionando melhor (acerto e lucro, já descontando as taxas).
 */
object Scanner {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("relatorio", Context.MODE_PRIVATE)
    private val brt = TimeZone.getTimeZone("America/Sao_Paulo")
    private fun today() = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = brt }.format(Date())
    private fun hourNow() = java.util.Calendar.getInstance(brt).get(java.util.Calendar.HOUR_OF_DAY)

    fun save(ctx: Context, json: String) = sp(ctx).edit().putString("cfg", json).apply()
    fun cfg(ctx: Context): JSONObject = runCatching { JSONObject(sp(ctx).getString("cfg", "{}")) }.getOrDefault(JSONObject())
    fun enabled(ctx: Context) = cfg(ctx).optBoolean("on", false)
    fun report(ctx: Context): String = sp(ctx).getString("report", "{}") ?: "{}"

    /** Já está na hora do relatório de hoje? */
    fun due(ctx: Context): Boolean {
        val c = cfg(ctx); if (!c.optBoolean("on", false)) return false
        return sp(ctx).getString("day", "") != today() && hourNow() >= c.optInt("hour", 8)
    }

    private fun interval(tf: String) = when (tf) { "60m" -> "1h"; "1d" -> "1d"; else -> tf }

    private fun mins(tf: String) = when (tf) { "1m" -> 1; "3m" -> 3; "5m" -> 5; "15m" -> 15; "30m" -> 30; "60m" -> 60; "1d" -> 1440; else -> tf.removeSuffix("m").toIntOrNull() ?: 15 }

    /** Baixa candles; com [days] > 0 busca páginas até cobrir esses dias (+ aquecimento). */
    private fun klines(get: (String) -> String, symbol: String, tf: String, days: Int = 0): List<Candle> {
        val need = if (days > 0) ((days * 1440) / mins(tf)) + 250 else 1000
        val pages = minOf(8, (need + 999) / 1000); var end = 0L; val out = ArrayList<Candle>()
        for (p in 0 until pages) {
            val a = JSONArray(get("https://api.binance.com/api/v3/klines?symbol=$symbol&interval=${interval(tf)}&limit=1000" + (if (end > 0) "&endTime=$end" else "")))
            if (a.length() == 0) break
            val part = List(a.length()) { i -> val k = a.getJSONArray(i); Candle(k.getLong(0) / 1000, k.getString(1).toDouble(), k.getString(2).toDouble(), k.getString(3).toDouble(), k.getString(4).toDouble()) }
            out.addAll(0, part); end = a.getJSONArray(0).getLong(0) - 1
            if (a.length() < 1000) break
        }
        return out
    }

    /** Roda o relatório. [progress] recebe um texto curto do andamento. */
    fun run(ctx: Context, get: (String) -> String, progress: (String) -> Unit = {}): JSONObject = run(ctx, get, cfg(ctx), 0, progress)

    /** [days] > 0: análise detalhada só dos últimos [days] dias, com histórico completo. */
    fun run(ctx: Context, get: (String) -> String, c: JSONObject, days: Int, progress: (String) -> Unit = {}): JSONObject {
        val coins = c.optJSONArray("coins") ?: JSONArray(); val tfs = c.optJSONArray("tfs") ?: JSONArray(listOf("5m", "15m", "30m", "60m"))
        val strats = c.optJSONArray("strats") ?: JSONArray(); val exits = c.optJSONArray("exits") ?: JSONArray(listOf("auto", "atr", "atr2"))
        val fee = c.optDouble("fee", 0.001); val minOps = c.optInt("minOps", 6); val minHit = c.optDouble("minHit", 70.0)
        val dayOf: (Long) -> Long = { Math.floorDiv(it - 10800, 86400L) }
        val all = ArrayList<JSONObject>(); var tested = 0
        for (ci in 0 until coins.length()) {
            val coin = coins.getJSONObject(ci); val id = coin.getString("id")
            for (ti in 0 until tfs.length()) {
                val tf = tfs.getString(ti); progress("${coin.optString("name", id)} · $tf")
                val cs = runCatching { klines(get, id, tf, days) }.getOrNull() ?: continue
                if (cs.size < 300) continue
                var start = 200
                if (days > 0) { val t0 = cs.last().t - days * 86400L; val i0 = cs.indexOfFirst { it.t >= t0 }; if (i0 > start) start = i0 }
                val span = (cs.last().t - cs[start].t) / 86400.0
                for (si in 0 until strats.length()) {
                    val st = strats.getJSONObject(si)
                    val d = runCatching { Strategies.dirs(cs, st, dayOf) }.getOrNull() ?: continue
                    for (ei in 0 until exits.length()) {
                        val ex = exits.getString(ei); tested++
                        val ev = runCatching { Strategies.simulate(cs, d, ex, false, false, 0.0, dayOf) }.getOrNull() ?: continue
                        var n = 0; var wins = 0; var sum = 0.0
                        for (e in ev) { if (e.entry || e.i < start) continue
                            val r = ((e.price / e.entryPrice - 1) * e.dir - 2 * fee) * 100; n++; if (r > 0) wins++; sum += r }
                        if (n < minOps || sum <= 0) continue
                        all.add(JSONObject().put("id", id).put("name", coin.optString("name", id)).put("tf", tf).put("strat", st.optString("id")).put("stratName", st.optString("name"))
                            .put("exit", ex).put("n", n).put("hit", Math.round(wins * 100.0 / n).toInt()).put("sum", sum).put("days", span).put("perDay", if (span > 0) sum / span else 0.0)
                            .put("opd", if (span > 0) n / span else 0.0).put("st", st))
                    }
                }
            }
        }
        // primeiro as que acertam a meta de acerto, depois pelo lucro por dia
        val top = all.sortedWith(compareByDescending<JSONObject> { it.getInt("hit") >= minHit }.thenByDescending { it.getDouble("perDay") }).take(if (days > 0) 400 else 15)
        val rep = JSONObject().put("t", System.currentTimeMillis()).put("tested", tested).put("found", all.size).put("minHit", minHit).put("items", JSONArray(top))
        if (days > 0) return rep
        sp(ctx).edit().putString("report", rep.toString()).putString("day", today()).apply()
        return rep
    }
}
