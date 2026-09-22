package com.shinyk.tgwsproxy

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.util.concurrent.Executors

class DiagnosticsActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val port = 1443
    private val logFile by lazy { File(filesDir, "tg-ws-proxy-debug.log") }

    private lateinit var summary: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var runButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)

        summary = findViewById(R.id.diagSummary)
        logView = findViewById(R.id.logView)
        logScroll = findViewById(R.id.logScroll)
        runButton = findViewById(R.id.runDiagButton)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.refreshLogButton).setOnClickListener { loadLog() }
        findViewById<Button>(R.id.copyLogButton).setOnClickListener { copyLog() }
        findViewById<Button>(R.id.clearLogButton).setOnClickListener { clearLog() }
        runButton.setOnClickListener { runDiagnostics() }

        logView.setOnLongClickListener {
            copyLog()
            true
        }

        loadLog()
    }

    override fun onResume() {
        super.onResume()
        loadLog()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun runDiagnostics() {
        val secret = getSharedPreferences("proxy", MODE_PRIVATE).getString("secret", null)
        if (secret.isNullOrBlank()) {
            summary.text = "Secret ещё не создан. Вернись на главный экран."
            return
        }

        runButton.isEnabled = false
        runButton.text = "Проверяем…"
        summary.text = "Проверяем Python, crypto, настройки и локальный порт…"

        executor.execute {
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(this))
                val resultJson = Python.getInstance()
                    .getModule("android_proxy")
                    .callAttr("diagnostics_json", secret, port, logFile.absolutePath)
                    .toJava(String::class.java)
                val result = org.json.JSONObject(resultJson)
                val ok = result.optBoolean("ok", false)
                val checks = result.optJSONArray("checks")?.length() ?: 0
                val failed = (0 until checks).count { !result.optJSONArray("checks")!!.getJSONObject(it).optBoolean("ok", false) }
                val error = if (result.isNull("error")) null else result.optString("error")

                runOnUiThread {
                    runButton.isEnabled = true
                    runButton.text = "Запустить диагностику"
                    if (ok) {
                        summary.text = "Готово • все $checks проверки пройдены"
                        summary.setTextColor(ContextCompat.getColor(this, R.color.success))
                    } else {
                        summary.text = "Есть проблема • ошибок: $failed\n${error ?: "Смотри подробности в логе."}"
                        summary.setTextColor(ContextCompat.getColor(this, R.color.danger))
                    }
                    loadLog()
                    Toast.makeText(this, if (ok) "Диагностика OK" else "Диагностика не пройдена", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    runButton.isEnabled = true
                    runButton.text = "Запустить диагностику"
                    summary.text = "Ошибка запуска диагностики:\n${e.message ?: e.javaClass.simpleName}"
                    summary.setTextColor(ContextCompat.getColor(this, R.color.danger))
                    loadLog()
                }
            }
        }
    }

    private fun loadLog() {
        executor.execute {
            val text = try {
                if (!logFile.exists()) "Лог пока пуст." else logFile.readText(Charsets.UTF_8).takeLast(50000)
            } catch (e: Throwable) {
                "Ошибка чтения лога:\n${e.stackTraceToString()}"
            }
            runOnUiThread {
                logView.text = text
                logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    private fun copyLog() {
        val text = logView.text.toString()
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("TG WS Proxy debug log", text))
        Toast.makeText(this, "Лог скопирован", Toast.LENGTH_SHORT).show()
    }

    private fun clearLog() {
        try {
            if (logFile.exists()) logFile.writeText("")
            logView.text = "Лог очищен."
            summary.text = "Лог очищен. Диагностику можно запустить заново."
            summary.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        } catch (e: Throwable) {
            Toast.makeText(this, "Не удалось очистить лог: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
