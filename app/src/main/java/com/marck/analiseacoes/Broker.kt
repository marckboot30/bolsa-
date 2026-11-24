package com.marck.analiseacoes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Conexão com a Binance para o robô.
 * Modos: "demo" (Demo Mode, dinheiro de mentira), "testnet" (Spot Testnet) e "real" (DINHEIRO DE VERDADE).
 * Cada modo guarda a sua própria chave, para a chave da demo nunca ir para a conta real e vice-versa.
 * O modo real só liga depois de o usuário confirmar na tela.
 */
object Broker {
    private const val PREFS = "corretora"
    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun base(mode: String) = when (mode) {
        "testnet" -> "https://testnet.binance.vision"
        "real" -> "https://api.binance.com"
        else -> "https://demo-api.binance.com" // Demo Mode: preços parecidos com os reais, dinheiro de mentira
    }

    private fun norm(m: String) = if (m == "testnet" || m == "real") m else "demo"
    fun mode(ctx: Context) = norm(sp(ctx).getString("mode", "demo") ?: "demo")
    private fun keyOf(ctx: Context, m: String) = sp(ctx).getString("key_$m", "") ?: ""
    private fun secretOf(ctx: Context, m: String) = sp(ctx).getString("secret_$m", "") ?: ""

    /** Salva a chave do modo escolhido e passa a usar esse modo. */
    fun save(ctx: Context, key: String, secret: String, mode: String) {
        val m = norm(mode); val e = sp(ctx).edit().putString("mode", m)
        if (key.isNotBlank()) e.putString("key_$m", key.trim())
        if (secret.isNotBlank()) e.putString("secret_$m", secret.trim())
        e.apply()
    }

    fun clear(ctx: Context) { val m = mode(ctx); sp(ctx).edit().remove("key_$m").remove("secret_$m").apply() }

    fun info(ctx: Context): JSONObject {
        val m = mode(ctx); val res = JSONObject().put("mode", m)
        for (x in listOf("demo", "testnet", "real")) { val k = keyOf(ctx, x)
            res.put(x, JSONObject().put("hasKey", k.isNotEmpty() && secretOf(ctx, x).isNotEmpty()).put("key", if (k.length > 8) k.take(4) + "…" + k.takeLast(4) else "")) }
        return res
    }

    class BinanceError(val code: Int, msg: String) : Exception(msg)

    private var offset = 0L

    private fun http(method: String, url: String, key: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 10000; c.readTimeout = 15000
        if (key != null) c.setRequestProperty("X-MBX-APIKEY", key)
        if (method == "POST" || method == "DELETE") c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (code >= 400) {
            val j = runCatching { JSONObject(body) }.getOrNull()
            throw BinanceError(j?.optInt("code", code) ?: code, traduz(j?.optInt("code", 0) ?: 0, j?.optString("msg") ?: "HTTP $code"))
        }
        return body
    }

    private fun traduz(code: Int, msg: String) = when (code) {
        -2014, -2015 -> "Chave da API inválida, ou sem permissão de negociar. Confira se copiou a chave e o segredo certos para esse modo (demo ou real) e se ligou a permissão de Spot trading."
        -1022 -> "Assinatura inválida: o segredo da API está errado."
        -1021 -> "O relógio do celular está diferente do da Binance."
        -2010 -> "A Binance recusou a ordem: $msg"
        -1013 -> "Valor da ordem pequeno demais para esse ativo."
        -1121 -> "Esse par não existe nessa conta de teste."
        else -> msg
    }

    private fun hmac(secret: String, data: String): String {
        val m = Mac.getInstance("HmacSHA256"); m.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return m.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun syncTime(mode: String) {
        runCatching { offset = JSONObject(http("GET", base(mode) + "/api/v3/time", null)).getLong("serverTime") - System.currentTimeMillis() }
    }

    /** Chamada assinada (ordem, saldo). */
    fun signed(ctx: Context, method: String, path: String, params: Map<String, String>): String {
        val mode = mode(ctx); val key = keyOf(ctx, mode); val secret = secretOf(ctx, mode)
        if (key.isEmpty() || secret.isEmpty()) throw BinanceError(0, if (mode == "real") "Coloque a chave e o segredo da API da conta REAL." else "Coloque a chave e o segredo da API da conta de teste.")
        fun once(): String {
            val q = (params + mapOf("recvWindow" to "10000", "timestamp" to (System.currentTimeMillis() + offset).toString()))
                .entries.joinToString("&") { it.key + "=" + URLEncoder.encode(it.value, "UTF-8") }
            val full = q + "&signature=" + hmac(secret, q)
            return if (method == "GET") http("GET", base(mode) + path + "?" + full, key) else http(method, base(mode) + path + "?" + full, key)
        }
        return try { once() } catch (e: BinanceError) { if (e.code == -1021) { syncTime(mode); once() } else throw e }
    }

    fun account(ctx: Context): JSONObject {
        val a = JSONObject(signed(ctx, "GET", "/api/v3/account", mapOf("omitZeroBalances" to "true")))
        val out = JSONArray()
        val b = a.getJSONArray("balances")
        for (i in 0 until b.length()) { val x = b.getJSONObject(i); val free = x.getString("free").toDouble(); val locked = x.getString("locked").toDouble()
            if (free + locked > 0) out.put(JSONObject().put("asset", x.getString("asset")).put("free", free).put("locked", locked)) }
        return JSONObject().put("canTrade", a.optBoolean("canTrade", true)).put("balances", out).put("mode", mode(ctx))
    }

    private class Sym(val base: String, val quote: String, val step: BigDecimal, val minQty: BigDecimal, val minNotional: Double, val tick: BigDecimal)
    private val syms = HashMap<String, Sym>()

    private fun sym(ctx: Context, symbol: String): Sym {
        syms[symbol]?.let { return it }
        val mode = mode(ctx)
        val s = JSONObject(http("GET", base(mode) + "/api/v3/exchangeInfo?symbol=" + symbol, null)).getJSONArray("symbols").getJSONObject(0)
        var step = BigDecimal("0.00000001"); var minQty = BigDecimal.ZERO; var minN = 0.0; var tick = BigDecimal("0.01")
        val f = s.getJSONArray("filters")
        for (i in 0 until f.length()) { val x = f.getJSONObject(i)
            when (x.getString("filterType")) {
                "LOT_SIZE" -> { step = BigDecimal(x.getString("stepSize")).stripTrailingZeros(); minQty = BigDecimal(x.getString("minQty")) }
                "NOTIONAL", "MIN_NOTIONAL" -> minN = x.optString("minNotional", "0").toDouble()
                "PRICE_FILTER" -> tick = BigDecimal(x.getString("tickSize")).stripTrailingZeros()
            } }
        return Sym(s.getString("baseAsset"), s.getString("quoteAsset"), step, minQty, minN, tick).also { syms[symbol] = it }
    }

    private fun resumo(ctx: Context, o: JSONObject, side: String, symbol: String, why: String): JSONObject {
        val qty = o.optString("executedQty", "0").toDouble(); val quote = o.optString("cummulativeQuoteQty", "0").toDouble()
        return JSONObject().put("t", System.currentTimeMillis()).put("side", side).put("symbol", symbol).put("qty", qty).put("quote", quote)
            .put("price", if (qty > 0) quote / qty else 0.0).put("mode", mode(ctx)).put("status", o.optString("status")).put("why", why).put("id", o.optLong("orderId"))
    }

    /** Compra a mercado gastando [quoteQty] na moeda de cotação (ex.: R$ 100 em BTCBRL). */
    fun buy(ctx: Context, symbol: String, quoteQty: Double, why: String): JSONObject {
        val s = sym(ctx, symbol)
        if (s.minNotional > 0 && quoteQty < s.minNotional) throw BinanceError(-1013, "O mínimo por ordem em $symbol é ${s.minNotional} ${s.quote}.")
        val o = JSONObject(signed(ctx, "POST", "/api/v3/order", mapOf("symbol" to symbol, "side" to "BUY", "type" to "MARKET",
            "quoteOrderQty" to BigDecimal(quoteQty).setScale(2, RoundingMode.DOWN).toPlainString(), "newOrderRespType" to "FULL")))
        return resumo(ctx, o, "BUY", symbol, why).also { log(ctx, it) }
    }

    private fun px(v: Double, tick: BigDecimal) = BigDecimal(v.toString()).divide(tick, 0, RoundingMode.HALF_UP).multiply(tick).stripTrailingZeros().toPlainString()

    private fun baseBal(ctx: Context, s: Sym): Pair<Double, Double> {
        val bal = account(ctx).getJSONArray("balances"); var free = 0.0; var locked = 0.0
        for (i in 0 until bal.length()) { val b = bal.getJSONObject(i); if (b.getString("asset") == s.base) { free = b.getDouble("free"); locked = b.getDouble("locked") } }
        return Pair(free, locked)
    }

    /** Ainda tem o ativo (livre ou preso numa ordem de alvo/stop)? */
    fun holds(ctx: Context, symbol: String): Boolean { val s = sym(ctx, symbol); val (f, l) = baseBal(ctx, s); return BigDecimal((f + l).toString()) >= s.minQty.max(s.step) }

    /** Cancela todas as ordens abertas do ativo (alvo e stop). */
    fun cancelAll(ctx: Context, symbol: String) {
        try { signed(ctx, "DELETE", "/api/v3/openOrders", mapOf("symbol" to symbol)) } catch (e: BinanceError) { if (e.code != -2011) throw e }
    }

    /** Coloca alvo e stop juntos (OCO) na própria Binance: ela fecha a operação mesmo com o celular desligado. */
    fun protect(ctx: Context, symbol: String, tp: Double, sl: Double) {
        val s = sym(ctx, symbol); val (free, _) = baseBal(ctx, s)
        val qty = BigDecimal(free.toString()).divide(s.step, 0, RoundingMode.DOWN).multiply(s.step)
        if (qty <= BigDecimal.ZERO || qty < s.minQty) throw BinanceError(0, "Sem saldo do ativo para colocar o stop.")
        signed(ctx, "POST", "/api/v3/orderList/oco", mapOf("symbol" to symbol, "side" to "SELL", "quantity" to qty.stripTrailingZeros().toPlainString(),
            "aboveType" to "LIMIT_MAKER", "abovePrice" to px(tp, s.tick),
            "belowType" to "STOP_LOSS_LIMIT", "belowStopPrice" to px(sl, s.tick), "belowPrice" to px(sl * 0.997, s.tick), "belowTimeInForce" to "GTC"))
    }

    /** Vendas feitas pela Binance (alvo/stop) depois de [since]: quantidade e valor recebido. */
    fun soldSince(ctx: Context, symbol: String, since: Long): Pair<Double, Double> {
        val a = JSONArray(signed(ctx, "GET", "/api/v3/myTrades", mapOf("symbol" to symbol, "limit" to "20")))
        var q = 0.0; var v = 0.0
        for (i in 0 until a.length()) { val t = a.getJSONObject(i); if (!t.getBoolean("isBuyer") && t.getLong("time") >= since) { q += t.getString("qty").toDouble(); v += t.getString("quoteQty").toDouble() } }
        return Pair(q, v)
    }

    /** Acrescenta dados à última ordem registrada (ex.: alvo e stop). */
    fun patchLast(ctx: Context, extra: JSONObject) {
        val a = runCatching { JSONArray(sp(ctx).getString("log", "[]")) }.getOrDefault(JSONArray()); if (a.length() == 0) return
        val o = a.getJSONObject(0); val k = extra.keys(); while (k.hasNext()) { val key = k.next(); o.put(key, extra.get(key)) }
        sp(ctx).edit().putString("log", a.toString()).apply()
    }

    /** Quantos robôs estão comprados agora neste modo. */
    fun openCount(ctx: Context): Int { val pre = "pos_${mode(ctx)}_"; return sp(ctx).all.count { it.key.startsWith(pre) && it.value == true } }

    /** Resultado de hoje somando todas as criptos, neste modo. */
    fun todayAll(ctx: Context): Double {
        val a = runCatching { JSONArray(sp(ctx).getString("log", "[]")) }.getOrDefault(JSONArray()); val m = mode(ctx)
        val syms = HashSet<String>(); for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.optString("mode", "demo") == m) syms.add(o.optString("symbol")) }
        return syms.sumOf { today(ctx, it).first }
    }

    /** Resultado de hoje (compra → venda) e perdas seguidas de hoje, neste modo e ativo. */
    fun today(ctx: Context, symbol: String): Pair<Double, Int> {
        val a = runCatching { JSONArray(sp(ctx).getString("log", "[]")) }.getOrDefault(JSONArray()); val m = mode(ctx)
        val cal = java.util.Calendar.getInstance(); cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0); cal.set(java.util.Calendar.SECOND, 0); val t0 = cal.timeInMillis
        var buy: JSONObject? = null; var sum = 0.0; val res = ArrayList<Double>()
        for (i in a.length() - 1 downTo 0) { val o = a.getJSONObject(i)
            if (o.optString("symbol") != symbol || o.optString("mode", "demo") != m) continue
            when (o.optString("side")) { "BUY" -> buy = o
                "SELL" -> { val b = buy; if (b != null && o.optLong("t") >= t0) { val r = o.optDouble("quote") - b.optDouble("quote"); sum += r; res.add(r) }; buy = null } } }
        var seq = 0; for (r in res.reversed()) { if (r < 0) seq++ else break }
        return Pair(sum, seq)
    }

    /** Vende tudo o que tiver do ativo (ex.: todo o BTC). */
    fun sellAll(ctx: Context, symbol: String, why: String): JSONObject? {
        val s = sym(ctx, symbol)
        val bal = account(ctx).getJSONArray("balances")
        var free = 0.0; for (i in 0 until bal.length()) if (bal.getJSONObject(i).getString("asset") == s.base) free = bal.getJSONObject(i).getDouble("free")
        val qty = BigDecimal(free.toString()).divide(s.step, 0, RoundingMode.DOWN).multiply(s.step)
        if (qty <= BigDecimal.ZERO || qty < s.minQty) return null
        val o = JSONObject(signed(ctx, "POST", "/api/v3/order", mapOf("symbol" to symbol, "side" to "SELL", "type" to "MARKET",
            "quantity" to qty.stripTrailingZeros().toPlainString(), "newOrderRespType" to "FULL")))
        return resumo(ctx, o, "SELL", symbol, why).also { log(ctx, it) }
    }

    fun log(ctx: Context, o: JSONObject) {
        val a = runCatching { JSONArray(sp(ctx).getString("log", "[]")) }.getOrDefault(JSONArray())
        val n = JSONArray().put(o); for (i in 0 until minOf(a.length(), 49)) n.put(a.get(i))
        sp(ctx).edit().putString("log", n.toString()).apply()
    }

    /** A vigia avisa que conferiu o ativo (para o app mostrar "conferido há X s"). */
    fun beat(ctx: Context, id: String, tf: String) = sp(ctx).edit().putLong("beat_${id}|$tf", System.currentTimeMillis()).apply()

    fun status(ctx: Context, id: String, tf: String): String = JSONObject().put("mode", mode(ctx)).put("inPos", inPosition(ctx, id))
        .put("beat", sp(ctx).getLong("beat_${id}|$tf", 0L)).put("now", System.currentTimeMillis()).toString()

    fun logJson(ctx: Context): String = sp(ctx).getString("log", "[]") ?: "[]"

    /** Lembra se o robô está comprado em cada ativo (para não comprar duas vezes). */
    fun inPosition(ctx: Context, symbol: String) = sp(ctx).getBoolean("pos_${mode(ctx)}_$symbol", false)
    fun setPosition(ctx: Context, symbol: String, on: Boolean) = sp(ctx).edit().putBoolean("pos_${mode(ctx)}_$symbol", on).apply()
}
