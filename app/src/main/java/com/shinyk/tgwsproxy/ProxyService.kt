package com.shinyk.tgwsproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.util.concurrent.Executors

class ProxyService : Service() {
    private val executor = Executors.newSingleThreadExecutor()

    companion object {
        const val ACTION_STATUS = "com.shinyk.tgwsproxy.STATUS"
        const val EXTRA_OK = "ok"
        const val EXTRA_ERROR = "error"
        const val EXTRA_PORT = "port"
        const val EXTRA_STAGE = "stage"
    }

    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        createChannel()
    }

    private fun sendStatus(ok: Boolean, stage: String, error: String? = null, port: Int = 1443) {
        val i = Intent(ACTION_STATUS).setPackage(packageName)
            .putExtra(EXTRA_OK, ok)
            .putExtra(EXTRA_STAGE, stage)
            .putExtra(EXTRA_PORT, port)
        if (error != null) i.putExtra(EXTRA_ERROR, error)
        sendBroadcast(i)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val secret = intent?.getStringExtra("secret") ?: run {
            sendStatus(false, "missing_secret", "Secret не передан сервису.")
            stopSelf()
            return START_NOT_STICKY
        }
        val port = intent.getIntExtra("port", 1443)
        val logPath = File(filesDir, "tg-ws-proxy-debug.log").absolutePath

        startForeground(1, buildNotification("Диагностика / запуск…"))
        sendStatus(false, "starting", port = port)

        executor.execute {
            try {
                val python = Python.getInstance()
                val module = python.getModule("android_proxy")

                val diagJson = module.callAttr("diagnostics_json", secret, port, logPath)
                    .toJava(String::class.java)
                val diag = org.json.JSONObject(diagJson)
                val diagOk = diag.optBoolean("ok", false)
                if (!diagOk) {
                    val err = if (diag.isNull("error")) "Диагностика не пройдена." else diag.optString("error")
                    updateNotification("Ошибка диагностики")
                    sendStatus(false, "diagnostics_failed", err, port)
                    return@execute
                }

                updateNotification("Запуск прокси…")
                sendStatus(false, "starting_proxy", port = port)

                val resultJson = module.callAttr("start_proxy_json", secret, port, 15, logPath)
                    .toJava(String::class.java)
                val result = org.json.JSONObject(resultJson)

                val ok = result.optBoolean("ok", false)
                val error = if (result.isNull("error")) null else result.optString("error")
                val stage = result.optString("stage", if (ok) "running" else "startup_failed")

                updateNotification(if (ok) "Прокси работает: 127.0.0.1:$port" else "Ошибка запуска")
                sendStatus(ok, stage, error, port)

                if (!ok) {
                    // Keep service alive so the notification and log remain available.
                    return@execute
                }
            } catch (e: Throwable) {
                val message = e.stackTraceToString()
                updateNotification("Исключение Android/Python")
                sendStatus(false, "service_exception", message, port)
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        executor.execute {
            try {
                if (Python.isStarted()) {
                    Python.getInstance().getModule("android_proxy").callAttr("stop_proxy")
                }
            } catch (_: Throwable) {}
        }
        executor.shutdown()
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("proxy", "TG WS Proxy", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, "proxy")
            .setContentTitle("TG WS Proxy")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(1, buildNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
