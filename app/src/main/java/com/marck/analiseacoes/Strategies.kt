package com.marck.analiseacoes

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class Candle(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double)

/** Mesmas estratégias do app (index.html), para a vigia no fundo. Retorna 1 compra, -1 venda, 0 nada no último candle. */
object Strategies {
    private fun ema(v: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(v.size) { Double.NaN }; if (v.size < n) return out
        val k = 2.0 / (n + 1); var e = 0.0; for (i in 0 until n) e += v[i]; e /= n; out[n - 1] = e
        for (i in n until v.size) { e = v[i] * k + e * (1 - k); out[i] = e }
        return out
    }

    private fun rsi(c: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(c.size) { Double.NaN }; if (c.size <= n) return out
        var g = 0.0; var l = 0.0
        for (i in 1..n) { val d = c[i] - c[i - 1]; if (d > 0) g += d else l -= d }
        g /= n; l /= n; out[n] = if (l == 0.0) 100.0 else 100 - 100 / (1 + g / l)
        for (i in n + 1 until c.size) { val d = c[i] - c[i - 1]; g = (g * (n - 1) + max(d, 0.0)) / n; l = (l * (n - 1) + max(-d, 0.0)) / n; out[i] = if (l == 0.0) 100.0 else 100 - 100 / (1 + g / l) }
        return out
    }

    /** Padrão do candle i: "EA", "EB", "M", "EC" ou "". */
    fun pattern(cs: List<Candle>, i: Int): String {
        val c = cs[i]; val p = if (i > 0) cs[i - 1] else null
        val body = Math.abs(c.c - c.o); val range = max(c.h - c.l, 1e-9); val up = c.h - max(c.c, c.o); val lo = min(c.c, c.o) - c.l
        var avg = 0.0; var cnt = 0; for (k in max(0, i - 20) until i) { avg += cs[k].h - cs[k].l; cnt++ }; avg = if (cnt > 0) avg / cnt else range
        if (p != null) {
            val pb = Math.abs(p.c - p.o)
            if (p.c < p.o && c.c > c.o && c.c >= p.o && c.o <= p.c && body > pb) return "EA"
            if (p.c > p.o && c.c < c.o && c.o >= p.c && c.c <= p.o && body > pb) return "EB"
        }
        if (body <= range * .1 && range > avg * .3) return "D"
        if (lo >= body * 2 && up <= body * .6 && range > avg * .6) return "M"
        if (up >= body * 2 && lo <= body * .6 && range > avg * .6) return "EC"
        return ""
    }

    fun dirs(cs: List<Candle>, st: JSONObject, dayOf: (Long) -> Long): IntArray {
        val n = cs.size; val d = IntArray(n); val c = DoubleArray(n) { cs[it].c }
        when (st.optString("type", "ma")) {
            "ma" -> {
                val f = ema(c, st.optInt("f", 9)); val s = ema(c, st.optInt("s", 21))
                for (i in 1 until n) { if (f[i - 1].isNaN() || s[i - 1].isNaN()) continue
                    if (f[i - 1] <= s[i - 1] && f[i] > s[i] && cs[i].c > cs[i].o) d[i] = 1 else if (f[i - 1] >= s[i - 1] && f[i] < s[i] && cs[i].c < cs[i].o) d[i] = -1 }
            }
            "macd" -> {
                val e12 = ema(c, 12); val e26 = ema(c, 26); val m = DoubleArray(n) { if (e26[it].isNaN()) 0.0 else e12[it] - e26[it] }
                if (n > 40) { val sg = ema(m.copyOfRange(25, n), 9)
                    for (i in 35 until n) { if (sg[i - 26].isNaN()) continue; val a = m[i - 1] - sg[i - 26]; val b = m[i] - sg[i - 25]
                        if (a <= 0 && b > 0) d[i] = 1 else if (a >= 0 && b < 0) d[i] = -1 } }
            }
            "rsi" -> {
                val r = rsi(c, st.optInt("n", 14)); val lo = st.optDouble("lo", 30.0); val hi = st.optDouble("hi", 70.0)
                for (i in 1 until n) { if (r[i - 1].isNaN()) continue; if (r[i - 1] < lo && r[i] >= lo) d[i] = 1 else if (r[i - 1] > hi && r[i] <= hi) d[i] = -1 }
            }
            "bb" -> {
                val p = st.optInt("n", 20); val k = st.optDouble("k", 2.0)
                fun band(j: Int): Pair<Double, Double> { var m = 0.0; for (q in j - p + 1..j) m += c[q]; m /= p; var v = 0.0; for (q in j - p + 1..j) v += (c[q] - m) * (c[q] - m); val sd = sqrt(v / p); return Pair(m - k * sd, m + k * sd) }
                for (i in p + 1 until n) { val (l0, u0) = band(i - 1); val (l1, u1) = band(i)
                    if (c[i - 1] < l0 && c[i] >= l1) d[i] = 1 else if (c[i - 1] > u0 && c[i] <= u1) d[i] = -1 }
            }
            "don" -> {
                val p = st.optInt("n", 20)
                for (i in p until n) { var hh = Double.NEGATIVE_INFINITY; var ll = Double.POSITIVE_INFINITY
                    for (q in i - p until i) { hh = max(hh, cs[q].h); ll = min(ll, cs[q].l) }
                    if (c[i] > hh && c[i - 1] <= hh) d[i] = 1 else if (c[i] < ll && c[i - 1] >= ll) d[i] = -1 }
            }
            "pivot", "dow" -> {
                val k = st.optInt("k", 2); val dow = st.optString("type") == "dow"
                var top = Double.NaN; var bot = Double.NaN; var topUsed = true; var botUsed = true
                for (i in 2 * k until n) {
                    val j = i - k; var isH = true; var isL = true
                    for (q in j - k..j + k) { if (q == j) continue; if (cs[q].h >= cs[j].h) isH = false; if (cs[q].l <= cs[j].l) isL = false }
                    if (isH) { val prev = top; top = cs[j].h; topUsed = false; if (dow && !prev.isNaN() && top < prev) d[i] = -1 }
                    if (isL) { val prev = bot; bot = cs[j].l; botUsed = false; if (dow && !prev.isNaN() && bot > prev) d[i] = 1 }
                    if (!dow) {
                        if (!top.isNaN() && !topUsed && c[i] > top) { d[i] = 1; topUsed = true }
                        else if (!bot.isNaN() && !botUsed && c[i] < bot) { d[i] = -1; botUsed = true }
                    }
                }
            }
            "eng", "eng0", "ham" -> {
                val type = st.optString("type"); val e50 = ema(c, 50)
                for (i in 1 until n) { val p = pattern(cs, i)
                    val okUp = type == "eng0" || (!e50[i].isNaN() && c[i] > e50[i]); val okDn = type == "eng0" || (!e50[i].isNaN() && c[i] < e50[i])
                    if (type == "ham") { if (p == "M" && okUp) d[i] = 1 else if (p == "EC" && okDn) d[i] = -1 }
                    else { if (p == "EA" && okUp) d[i] = 1 else if (p == "EB" && okDn) d[i] = -1 } }
            }
            "h1" -> {
                val win = st.optInt("win", 60) * 60L; val trendF = st.optBoolean("trend", true); val e21 = ema(c, 21)
                var day = Long.MIN_VALUE; var t0 = 0L; var o = 0.0; var hi = 0.0; var lo = 0.0; var last = 0.0
                var ready = false; var trend = 0; var done = Long.MIN_VALUE
                for (i in 0 until n) { val x = cs[i]; val dd = dayOf(x.t)
                    if (dd != day) { day = dd; t0 = x.t; o = x.o; hi = x.h; lo = x.l; last = x.c; ready = false }
                    else if (!ready && x.t < t0 + win) { hi = max(hi, x.h); lo = min(lo, x.l); last = x.c }
                    if (!ready) {
                        val closes = i == n - 1 || dayOf(cs[i + 1].t) != day || cs[i + 1].t >= t0 + win
                        if (closes && x.t < t0 + win) { ready = true
                            trend = if (last > o && (e21[i].isNaN() || last > e21[i])) 1 else if (last < o && (e21[i].isNaN() || last < e21[i])) -1 else 0 }
                        continue
                    }
                    if (done == day || i == 0) continue
                    val okUp = !trendF || (trend == 1 && !e21[i].isNaN() && c[i] > e21[i]); val okDn = !trendF || (trend == -1 && !e21[i].isNaN() && c[i] < e21[i])
                    if (c[i] > hi && c[i - 1] <= hi && okUp) { d[i] = 1; done = day } else if (c[i] < lo && c[i - 1] >= lo && okDn) { d[i] = -1; done = day }
                }
            }
            "dayhl" -> {
                var hi = 0.0; var lo = 0.0; var day = Long.MIN_VALUE; var upDone = false; var dnDone = false
                for (i in 0 until n) { val x = cs[i]; val dd = dayOf(x.t)
                    if (dd != day) { day = dd; hi = x.h; lo = x.l; upDone = false; dnDone = false; continue }
                    if (!upDone && x.c > hi) { d[i] = 1; upDone = true } else if (!dnDone && x.c < lo) { d[i] = -1; dnDone = true }
                    hi = max(hi, x.h); lo = min(lo, x.l) }
            }
        }
        return d
    }

    // ---------------- saída da operação ----------------
    class Event(val i: Int, val entry: Boolean, val dir: Int, val price: Double, val entryPrice: Double, val why: String)

    private fun emaPub(v: DoubleArray, n: Int) = ema(v, n)

    private fun exitReason(cs: List<Candle>, i: Int, pos: Int, e9: DoubleArray, pats: Array<String>, top: DoubleArray, bot: DoubleArray, mode: String): String? {
        val c = cs[i]; val p = pats[i]
        if (mode == "auto") {
            if (pos > 0 && p == "EB") return "apareceu engolfo de baixa"
            if (pos > 0 && p == "EC") return "apareceu estrela cadente"
            if (pos < 0 && p == "EA") return "apareceu engolfo de alta"
            if (pos < 0 && p == "M") return "apareceu martelo"
        }
        if ((mode == "auto" || mode == "media") && !e9[i].isNaN() && !e9[i - 1].isNaN()) {
            if (pos > 0 && c.c < e9[i] && cs[i - 1].c >= e9[i - 1]) return "fechou abaixo da média 9"
            if (pos < 0 && c.c > e9[i] && cs[i - 1].c <= e9[i - 1]) return "fechou acima da média 9"
        }
        if (mode == "topo") {
            if (pos > 0 && !bot[i - 1].isNaN() && c.c < bot[i - 1]) return "perdeu o último fundo"
            if (pos < 0 && !top[i - 1].isNaN() && c.c > top[i - 1]) return "rompeu o último topo"
        }
        return null
    }

    /** Mesma simulação do app: entra no sinal, sai na virada. Devolve os eventos. */
    fun simulate(cs: List<Candle>, d: IntArray, mode: String, short: Boolean, daily: Boolean, target: Double = 0.0, dayOf: (Long) -> Long): List<Event> {
        val n = cs.size; val c = DoubleArray(n) { cs[it].c }; val e9 = emaPub(c, 9)
        val pats = Array(n) { pattern(cs, it) }
        val top = DoubleArray(n) { Double.NaN }; val bot = DoubleArray(n) { Double.NaN }
        var t = Double.NaN; var b = Double.NaN; val k = 2
        for (i in 0 until n) {
            if (i >= 2 * k) { val j = i - k; var isH = true; var isL = true
                for (q in j - k..j + k) { if (q == j) continue; if (cs[q].h >= cs[j].h) isH = false; if (cs[q].l <= cs[j].l) isL = false }
                if (isH) t = cs[j].h; if (isL) b = cs[j].l }
            top[i] = t; bot[i] = b
        }
        val ev = ArrayList<Event>(); var pos = 0; var entry = 0.0; var ei = -1
        fun close(i: Int, why: String, px: Double = cs[i].c) { ev.add(Event(i, false, pos, px, entry, why)); pos = 0 }
        for (i in 1 until n) {
            var s = d[i]
            if (daily && dayOf(cs[i].t) != dayOf(cs[i - 1].t)) s = 0
            var exitedNow = false
            if (pos != 0 && i > ei && target > 0) { val tp = entry + pos * target
                if ((pos > 0 && cs[i].h >= tp) || (pos < 0 && cs[i].l <= tp)) { close(i, "bateu a meta de ${target.toInt()} pontos", tp); exitedNow = true } }
            if (pos != 0 && s == -pos) close(i, "sinal contrário")
            else if (pos != 0 && i > ei) { val why = exitReason(cs, i, pos, e9, pats, top, bot, mode); if (why != null) { close(i, why); exitedNow = true } }
            if (pos == 0 && s != 0 && (s == 1 || short) && !exitedNow) { pos = s; entry = cs[i].c; ei = i; ev.add(Event(i, true, s, cs[i].c, cs[i].c, "")) }
            if (daily && pos != 0 && i < n - 1 && dayOf(cs[i + 1].t) != dayOf(cs[i].t)) close(i, "fim do pregão")
        }
        return ev
    }
}
