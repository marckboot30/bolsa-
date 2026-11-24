package com.marck.analiseacoes

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
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
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
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
        fun notify(title: String, text: String) {
            runOnUiThread { showNotification(title, text) }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel("sinais", "Sinais de compra e venda", NotificationManager.IMPORTANCE_HIGH)
            ch.enableVibration(true)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

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
