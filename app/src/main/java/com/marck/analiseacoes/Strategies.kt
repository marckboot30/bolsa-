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

    private fun sma(v: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(v.size) { Double.NaN }; var sum = 0.0; var cnt = 0
        for (i in v.indices) { if (v[i].isNaN()) { sum = 0.0; cnt = 0; continue }; sum += v[i]; cnt++; if (cnt > n) sum -= v[i - n]; if (cnt >= n) out[i] = sum / n }
        return out
    }

    fun atr(cs: List<Candle>, n: Int): DoubleArray {
        val N = cs.size; val out = DoubleArray(N) { Double.NaN }; if (N <= n) return out
        val tr = DoubleArray(N) { i -> if (i == 0) cs[i].h - cs[i].l else max(cs[i].h - cs[i].l, max(Math.abs(cs[i].h - cs[i - 1].c), Math.abs(cs[i].l - cs[i - 1].c))) }
        var a = 0.0; for (i in 1..n) a += tr[i]; a /= n; out[n] = a
        for (i in n + 1 until N) { a = (a * (n - 1) + tr[i]) / n; out[i] = a }
        return out
    }

    private fun stoch(cs: List<Candle>, n: Int): Pair<DoubleArray, DoubleArray> {
        val raw = DoubleArray(cs.size) { i -> if (i < n - 1) Double.NaN else { var hh = Double.NEGATIVE_INFINITY; var ll = Double.POSITIVE_INFINITY
            for (k in i - n + 1..i) { hh = max(hh, cs[k].h); ll = min(ll, cs[k].l) }; if (hh > ll) (cs[i].c - ll) / (hh - ll) * 100 else 50.0 } }
        val k = sma(raw, 3); return Pair(k, sma(k, 3))
    }

    private fun adx(cs: List<Candle>, n: Int): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val N = cs.size; val pdi = DoubleArray(N) { Double.NaN }; val mdi = DoubleArray(N) { Double.NaN }; val ad = DoubleArray(N) { Double.NaN }
        if (N <= 2 * n) return Triple(pdi, mdi, ad)
        var tr = 0.0; var pm = 0.0; var mm = 0.0; var dxs = 0.0; var ax = 0.0
        for (i in 1 until N) { val c = cs[i]; val p = cs[i - 1]; val up = c.h - p.h; val dn = p.l - c.l
            val t = max(c.h - c.l, max(Math.abs(c.h - p.c), Math.abs(c.l - p.c))); val P = if (up > dn && up > 0) up else 0.0; val M = if (dn > up && dn > 0) dn else 0.0
            if (i <= n) { tr += t; pm += P; mm += M; if (i < n) continue } else { tr = tr - tr / n + t; pm = pm - pm / n + P; mm = mm - mm / n + M }
            val a = if (tr != 0.0) 100 * pm / tr else 0.0; val b = if (tr != 0.0) 100 * mm / tr else 0.0; val dx = if (a + b != 0.0) 100 * Math.abs(a - b) / (a + b) else 0.0
            pdi[i] = a; mdi[i] = b
            if (i < 2 * n) dxs += dx else if (i == 2 * n) { ax = (dxs + dx) / n; ad[i] = ax } else { ax = (ax * (n - 1) + dx) / n; ad[i] = ax } }
        return Triple(pdi, mdi, ad)
    }

    private fun supertrend(cs: List<Candle>, n: Int, m: Double): IntArray {
        val N = cs.size; val A = atr(cs, n); val dir = IntArray(N); var ub = 0.0; var lb = 0.0; var d = 1
        for (i in 0 until N) { if (A[i].isNaN()) continue; val mid = (cs[i].h + cs[i].l) / 2; val u = mid + m * A[i]; val l = mid - m * A[i]
            if (i == 0 || dir[i - 1] == 0) { ub = u; lb = l; d = if (cs[i].c >= mid) 1 else -1; dir[i] = d; continue }
            ub = if (u < ub || cs[i - 1].c > ub) u else ub; lb = if (l > lb || cs[i - 1].c < lb) l else lb
            if (d == 1 && cs[i].c < lb) d = -1 else if (d == -1 && cs[i].c > ub) d = 1; dir[i] = d }
        return dir
    }

    private fun psar(cs: List<Candle>, step: Double, mx: Double): IntArray {
        val N = cs.size; val dir = IntArray(N); if (N < 3) return dir
        var up = cs[1].c >= cs[0].c; var sar = if (up) cs[0].l else cs[0].h; var ep = if (up) cs[1].h else cs[1].l; var af = step; dir[1] = if (up) 1 else -1
        for (i in 2 until N) { sar += af * (ep - sar)
            if (up) { sar = min(sar, min(cs[i - 1].l, cs[i - 2].l)); if (cs[i].l < sar) { up = false; sar = ep; ep = cs[i].l; af = step } else if (cs[i].h > ep) { ep = cs[i].h; af = min(mx, af + step) } }
            else { sar = max(sar, max(cs[i - 1].h, cs[i - 2].h)); if (cs[i].h > sar) { up = true; sar = ep; ep = cs[i].h; af = step } else if (cs[i].l < ep) { ep = cs[i].l; af = min(mx, af + step) } }
            dir[i] = if (up) 1 else -1 }
        return dir
    }

    /** Mínima/máxima do dia até o candle anterior e de ontem (horário de Brasília). */
    class Levels(val dLow: DoubleArray, val dHigh: DoubleArray, val yLow: DoubleArray, val yHigh: DoubleArray, val nIn: IntArray)
    fun levels(cs: List<Candle>, dayOf: (Long) -> Long): Levels {
        val n = cs.size; val dL = DoubleArray(n) { Double.NaN }; val dH = DoubleArray(n) { Double.NaN }; val yL = DoubleArray(n) { Double.NaN }; val yH = DoubleArray(n) { Double.NaN }; val nIn = IntArray(n)
        var day = Long.MIN_VALUE; var lo = Double.NaN; var hi = Double.NaN; var pLo = Double.NaN; var pHi = Double.NaN; var cnt = 0
        for (i in 0 until n) { val d = dayOf(cs[i].t)
            if (d != day) { if (day != Long.MIN_VALUE) { pLo = lo; pHi = hi }; day = d; lo = Double.NaN; hi = Double.NaN; cnt = 0 }
            dL[i] = lo; dH[i] = hi; yL[i] = pLo; yH[i] = pHi; nIn[i] = cnt
            lo = if (lo.isNaN()) cs[i].l else min(lo, cs[i].l); hi = if (hi.isNaN()) cs[i].h else max(hi, cs[i].h); cnt++ }
        return Levels(dL, dH, yL, yH, nIn)
    }

    // ---------- estratégias criadas pelo usuário ----------
    private fun condArr(cs: List<Candle>, c: JSONObject, E: HashMap<Int, DoubleArray>, R: DoubleArray, L: Levels): BooleanArray {
        val n = cs.size; val C = DoubleArray(n) { cs[it].c }; val out = BooleanArray(n)
        fun e(k: Int) = E.getOrPut(k) { ema(C, k) }
        val k = c.optString("k"); val N = c.optInt("n", 9); val M = c.optInt("m", 21); val V = c.optDouble("v", 30.0); val P = c.optString("p", "EA")
        for (i in 1 until n) { val x = cs[i]
            out[i] = when (k) {
                "close_above_ma" -> { val a = e(N); !a[i].isNaN() && !a[i - 1].isNaN() && C[i] > a[i] && C[i - 1] <= a[i - 1] }
                "close_below_ma" -> { val a = e(N); !a[i].isNaN() && !a[i - 1].isNaN() && C[i] < a[i] && C[i - 1] >= a[i - 1] }
                "touch_up" -> { val a = e(N); !a[i].isNaN() && x.l <= a[i] && x.c > a[i] && x.c > x.o }
                "touch_down" -> { val a = e(N); !a[i].isNaN() && x.h >= a[i] && x.c < a[i] && x.c < x.o }
                "above_ma" -> { val a = e(N); !a[i].isNaN() && x.c > a[i] }
                "below_ma" -> { val a = e(N); !a[i].isNaN() && x.c < a[i] }
                "ma_up", "ma_down" -> { val a = e(N); val b = e(M)
                    if (a[i].isNaN() || b[i].isNaN() || a[i - 1].isNaN() || b[i - 1].isNaN()) false
                    else if (k == "ma_up") a[i] > b[i] && a[i - 1] <= b[i - 1] else a[i] < b[i] && a[i - 1] >= b[i - 1] }
                "rsi_lt" -> !R[i].isNaN() && R[i] < V
                "rsi_gt" -> !R[i].isNaN() && R[i] > V
                "bull" -> x.c > x.o
                "bear" -> x.c < x.o
                "pat" -> pattern(cs, i) == P
                "lost_dlow" -> L.nIn[i] >= 2 && !L.dLow[i].isNaN() && x.c < L.dLow[i]
                "reclaim_dlow" -> L.nIn[i] >= 2 && !L.dLow[i].isNaN() && x.l < L.dLow[i] && x.c > L.dLow[i]
                "lost_ylow" -> !L.yLow[i].isNaN() && x.c < L.yLow[i]
                "reclaim_ylow" -> !L.yLow[i].isNaN() && x.l < L.yLow[i] && x.c > L.yLow[i]
                "break_dhigh" -> L.nIn[i] >= 2 && !L.dHigh[i].isNaN() && x.c > L.dHigh[i]
                else -> false
            } }
        return out
    }

    private fun ruleArr(cs: List<Candle>, list: org.json.JSONArray?, E: HashMap<Int, DoubleArray>, R: DoubleArray, L: Levels): BooleanArray {
        val n = cs.size; if (list == null || list.length() == 0) return BooleanArray(n)
        val arrs = (0 until list.length()).map { condArr(cs, list.getJSONObject(it), E, R, L) }
        return BooleanArray(n) { i -> arrs.all { it[i] } }
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
            "sweep" -> { // falso rompimento: passa da mínima/máxima e fecha de volta para dentro
                val L = levels(cs, dayOf); val y = st.optString("lvl") == "y"
                for (i in 1 until n) { val x = cs[i]; val lo = if (y) L.yLow[i] else L.dLow[i]; val hi = if (y) L.yHigh[i] else L.dHigh[i]
                    if (!y && L.nIn[i] < 3) continue
                    if (!lo.isNaN() && x.l < lo && x.c > lo && x.c > x.o) d[i] = 1
                    else if (!hi.isNaN() && x.h > hi && x.c < hi && x.c < x.o) d[i] = -1 }
            }
            "touch" -> { // encostou (ou chegou até 10% de um ATR) na mínima do dia e fechou acima dela — sem exigir cor
                val L = levels(cs, dayOf); val A = atr(cs, 14)
                for (i in 1 until n) { val x = cs[i]; val lo = L.dLow[i]; val hi = L.dHigh[i]; if (L.nIn[i] < 3) continue
                    val tol = 0.1 * (if (A[i].isNaN()) x.h - x.l else A[i])
                    if (!lo.isNaN() && x.l <= lo + tol && x.c > lo && d[i - 1] != 1) d[i] = 1
                    else if (!hi.isNaN() && x.h >= hi - tol && x.c < hi && d[i - 1] != -1) d[i] = -1 }
            }
            "custom" -> {
                val E = HashMap<Int, DoubleArray>(); val R = rsi(c, 14); val L = levels(cs, dayOf)
                val B = ruleArr(cs, st.optJSONArray("buy"), E, R, L); val S = ruleArr(cs, st.optJSONArray("sell"), E, R, L)
                for (i in 1 until n) { val b = B[i] && !B[i - 1]; val v = S[i] && !S[i - 1]; if (b && !v) d[i] = 1 else if (v && !b) d[i] = -1 }
            }
            "fib" -> {
                val e21 = ema(c, 21); val e50 = ema(c, 50); val N = st.optInt("n", 30); val fa = st.optDouble("a", .5); val fb = st.optDouble("b", .618); val pat = st.optBoolean("pat", false)
                for (i in N until n) { if (e50[i].isNaN() || d[i - 1] != 0) continue
                    var hh = Double.NEGATIVE_INFINITY; var hi = -1; var ll = Double.POSITIVE_INFINITY; var li = -1
                    for (k in i - N until i) { if (cs[k].h > hh) { hh = cs[k].h; hi = k }; if (cs[k].l < ll) { ll = cs[k].l; li = k } }
                    val r = hh - ll; val x = cs[i]; if (r <= 0) continue; val p = if (pat) pattern(cs, i) else ""
                    if (e21[i] > e50[i] && li < hi) { val top = hh - fa * r; val bot = hh - fb * r
                        if (x.l <= top && x.l >= bot - .1 * r && x.c > x.o && x.c > (top + bot) / 2 && (!pat || p == "EA" || p == "M")) d[i] = 1 }
                    else if (e21[i] < e50[i] && hi < li) { val bot = ll + fa * r; val top = ll + fb * r
                        if (x.h >= bot && x.h <= top + .1 * r && x.c < x.o && x.c < (top + bot) / 2 && (!pat || p == "EB" || p == "EC")) d[i] = -1 } }
            }
            "pb21" -> {
                val e9 = ema(c, 9); val e21 = ema(c, 21); val e50 = ema(c, 50)
                for (i in 1 until n) { if (e50[i].isNaN() || e50[i - 1].isNaN()) continue; val x = cs[i]
                    if (e9[i] > e21[i] && e21[i] > e50[i] && x.l <= e21[i] && x.c > e21[i] && x.c > x.o && !(cs[i - 1].l <= e21[i - 1])) d[i] = 1
                    else if (e9[i] < e21[i] && e21[i] < e50[i] && x.h >= e21[i] && x.c < e21[i] && x.c < x.o && !(cs[i - 1].h >= e21[i - 1])) d[i] = -1 }
            }
            "adx" -> {
                val (pdi, mdi, ad) = adx(cs, 14)
                for (i in 1 until n) { if (ad[i].isNaN() || pdi[i - 1].isNaN() || ad[i] < 20) continue
                    if (pdi[i - 1] <= mdi[i - 1] && pdi[i] > mdi[i]) d[i] = 1 else if (pdi[i - 1] >= mdi[i - 1] && pdi[i] < mdi[i]) d[i] = -1 }
            }
            "st", "psar" -> {
                val dir = if (st.optString("type") == "st") supertrend(cs, 10, 3.0) else psar(cs, .02, .2)
                for (i in 1 until n) { if (dir[i - 1] == 0) continue; if (dir[i - 1] < 0 && dir[i] > 0) d[i] = 1 else if (dir[i - 1] > 0 && dir[i] < 0) d[i] = -1 }
            }
            "rsi2" -> {
                val r = rsi(c, 2); val s200 = sma(c, 200)
                for (i in 1 until n) { if (r[i - 1].isNaN() || s200[i].isNaN()) continue
                    if (r[i] < 10 && r[i - 1] >= 10 && c[i] > s200[i]) d[i] = 1 else if (r[i] > 90 && r[i - 1] <= 90 && c[i] < s200[i]) d[i] = -1 }
            }
            "stoch" -> {
                val (K, D) = stoch(cs, 14); val e50 = ema(c, 50)
                for (i in 1 until n) { if (D[i - 1].isNaN() || e50[i].isNaN()) continue
                    if (K[i - 1] <= D[i - 1] && K[i] > D[i] && K[i - 1] < 25 && c[i] > e50[i]) d[i] = 1
                    else if (K[i - 1] >= D[i - 1] && K[i] < D[i] && K[i - 1] > 75 && c[i] < e50[i]) d[i] = -1 }
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
    fun simulate(cs: List<Candle>, d: IntArray, mode: String, short: Boolean, daily: Boolean, target: Double = 0.0, dayOf: (Long) -> Long): List<Event> =
        simulate(cs, d, mode, short, daily, target, 0.0, 0.0, dayOf)

    /** [tpPct]/[slPct]: alvo e stop em fração do preço (saída "money", a do alvo/stop em dinheiro). */
    fun simulate(cs: List<Candle>, d: IntArray, mode: String, short: Boolean, daily: Boolean, target: Double, tpPct: Double, slPct: Double, dayOf: (Long) -> Long): List<Event> {
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
        val bra = mode == "atr" || mode == "atr2" || mode == "money"; val A = if (bra) atr(cs, 14) else DoubleArray(0); var tp = 0.0; var sl = 0.0
        fun close(i: Int, why: String, px: Double = cs[i].c) { ev.add(Event(i, false, pos, px, entry, why)); pos = 0 }
        for (i in 1 until n) {
            var s = d[i]
            if (daily && dayOf(cs[i].t) != dayOf(cs[i - 1].t)) s = 0
            var exitedNow = false
            if (pos != 0 && i > ei && target > 0) { val tp = entry + pos * target
                if ((pos > 0 && cs[i].h >= tp) || (pos < 0 && cs[i].l <= tp)) { close(i, "bateu a meta de ${target.toInt()} pontos", tp); exitedNow = true } }
            if (pos != 0 && i > ei && bra) { val x = cs[i]
                if ((pos > 0 && x.l <= sl) || (pos < 0 && x.h >= sl)) { close(i, "bateu o stop", sl); exitedNow = true }
                else if ((pos > 0 && x.h >= tp) || (pos < 0 && x.l <= tp)) { close(i, "bateu o alvo", tp); exitedNow = true } }
            if (pos != 0 && s == -pos) close(i, "sinal contrário")
            else if (pos != 0 && i > ei) { val why = exitReason(cs, i, pos, e9, pats, top, bot, mode); if (why != null) { close(i, why); exitedNow = true } }
            if (pos == 0 && s != 0 && (s == 1 || short) && !exitedNow) { pos = s; entry = cs[i].c; ei = i; ev.add(Event(i, true, s, cs[i].c, cs[i].c, ""))
                if (mode == "money") { tp = entry * (1 + pos * (if (tpPct > 0) tpPct else .01)); sl = entry * (1 - pos * (if (slPct > 0) slPct else .01)) }
                else if (bra) { val a = if (A[i].isNaN()) cs[i].h - cs[i].l else A[i]; val tk = if (mode == "atr") 1.0 else 2.0; val sk = if (mode == "atr") 2.0 else 1.0; tp = entry + pos * tk * a; sl = entry - pos * sk * a } }
            if (daily && pos != 0 && i < n - 1 && dayOf(cs[i + 1].t) != dayOf(cs[i].t)) close(i, "fim do pregão")
        }
        return ev
    }
}
