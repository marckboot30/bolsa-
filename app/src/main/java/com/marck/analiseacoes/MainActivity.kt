package com.marck.analiseacoes

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Mostra a análise (HTML) e faz por ela as buscas de cotação e os avisos no celular. */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private var notifId = 100

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0b0e14")
        window.navigationBarColor = Color.parseColor("#0b0e14")
        createChannel()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0b0e14"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) { loaded = true; openFromIntent(intent) }
        }
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    private var loaded = false

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (loaded) openFromIntent(intent)
    }

    /** Veio de uma notificação: abre o gráfico no ativo e no tempo do alerta. */
    private fun openFromIntent(i: Intent?) {
        val a = i?.getStringExtra("asset") ?: return; val tf = i.getStringExtra("tf") ?: return
        i.removeExtra("asset"); i.removeExtra("tf")
        val q = { s: String -> "'" + s.replace("\\", "").replace("'", "") + "'" }
        web.evaluateJavascript("window.openFrom && window.openFrom(${q(a)}, ${q(tf)})", null)
    }

    inner class Bridge {
        @JavascriptInterface
        fun fetch(id: Int, url: String) {
            thread {
                var ok = false
                var payload = ""
                try {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 10000
                    c.readTimeout = 15000
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AnaliseAcoes/1.0")
                    val code = c.responseCode
                    val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                    c.disconnect()
                    ok = code < 400
                    payload = if (ok) Base64.encodeToString(body.toByteArray(), Base64.NO_WRAP) else "HTTP $code"
                } catch (e: Exception) {
                    payload = e.javaClass.simpleName
                }
                val js = "window.onFetch($id, $ok, '${payload.replace("'", "")}')"
                runOnUiThread { web.evaluateJavascript(js, null) }
            }
        }

        @JavascriptInterface
        fun brokerSave(key: String, secret: String, mode: String) = Broker.save(this@MainActivity, key, secret, mode)

        @JavascriptInterface
        fun brokerClear() = Broker.clear(this@MainActivity)

        @JavascriptInterface
        fun brokerInfo(): String = Broker.info(this@MainActivity).toString()

        @JavascriptInterface
        fun robotStatus(id: String, tf: String): String = Broker.status(this@MainActivity, id, tf)

        @JavascriptInterface
        fun brokerLog(): String = Broker.logJson(this@MainActivity)

        /** action: account | buy | sell — responde em window.onBroker(id, ok, base64) */
        @JavascriptInterface
        fun broker(id: Int, action: String, symbol: String, amount: Double) {
            thread {
                var ok = true
                val txt = try {
                    when (action) {
                        "account" -> Broker.account(this@MainActivity).toString()
                        "buy" -> Broker.buy(this@MainActivity, symbol, amount, "compra manual").also { Broker.setPosition(this@MainActivity, symbol, true) }.toString()
                        "sell" -> (Broker.sellAll(this@MainActivity, symbol, "venda manual")?.toString() ?: "{\"none\":true}").also { Broker.setPosition(this@MainActivity, symbol, false) }
                        else -> "{}"
                    }
                } catch (e: Exception) { ok = false; e.message ?: e.javaClass.simpleName }
                val b64 = Base64.encodeToString(txt.toByteArray(), Base64.NO_WRAP)
                runOnUiThread { web.evaluateJavascript("window.onBroker && window.onBroker($id, $ok, '$b64')", null) }
            }
        }

        @JavascriptInterface
        fun setWatch(json: String) {
            Watches.save(this@MainActivity, json)
            runOnUiThread { runCatching { Watches.apply(this@MainActivity) } }
        }

        @JavascriptInterface
        fun batteryExempt() {
            runOnUiThread {
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName")))
                }.onFailure { runCatching { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
            }
        }

        @JavascriptInterface
        fun notify(title: String, text: String) {
            runOnUiThread { showNotification(title, text) }
        }
    }

    private fun createChannel() = MonitorService.createChannels(this)

    private fun showNotification(title: String, text: String) {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(this, "sinais") else @Suppress("DEPRECATION") android.app.Notification.Builder(this)
        val n = b.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(text)
            .setAutoCancel(true).setContentIntent(pi).build()
        runCatching { getSystemService(NotificationManager::class.java).notify(notifId++, n) }
        runCatching {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(400, 200)) else @Suppress("DEPRECATION") v.vibrate(400)
        }
    }

    override fun onResume() {
        super.onResume()
        web.evaluateJavascript("window.app && app.refresh(true)", null)
    }
}
