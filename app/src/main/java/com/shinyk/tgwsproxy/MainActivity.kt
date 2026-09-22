package com.shinyk.tgwsproxy

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.security.SecureRandom

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("proxy", MODE_PRIVATE) }

    private lateinit var status: TextView
    private lateinit var statusDot: TextView
    private lateinit var toggleButton: Button
    private lateinit var secretView: TextView
    private lateinit var stageView: TextView
    private lateinit var errorView: TextView

    private val port = 1443
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ProxyService.ACTION_STATUS) return
            val ok = intent.getBooleanExtra(ProxyService.EXTRA_OK, false)
            val stage = intent.getStringExtra(ProxyService.EXTRA_STAGE) ?: "—"
            val error = intent.getStringExtra(ProxyService.EXTRA_ERROR)
            updateStatus(ok, stage, error)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        statusDot = findViewById(R.id.statusDot)
        toggleButton = findViewById(R.id.toggleButton)
        secretView = findViewById(R.id.secret)
        stageView = findViewById(R.id.stage)
        errorView = findViewById(R.id.error)

        val secret = getOrCreateSecret()
        secretView.text = secret
        findViewById<TextView>(R.id.localEndpoint).text = "127.0.0.1:$port"

        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        toggleButton.setOnClickListener {
            if (prefs.getBoolean("running", false)) stopProxy() else startProxy(secret)
        }

        findViewById<View>(R.id.copyLinkButton).setOnClickListener {
            copyTelegramLink(secret)
        }

        findViewById<View>(R.id.openTelegramButton).setOnClickListener {
            openTelegramLink(secret)
        }

        secretView.setOnLongClickListener {
            copyText("Secret", secretView.text.toString(), "Secret скопирован")
            true
        }

        requestNotificationsIfNeeded()
        refreshUi()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter(ProxyService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        try { unregisterReceiver(statusReceiver) } catch (_: IllegalArgumentException) {}
        super.onStop()
    }

    private fun startProxy(secret: String) {
        prefs.edit().putBoolean("running", true).apply()
        updateStatus(false, "Запуск…", null)

        val intent = Intent(this, ProxyService::class.java)
            .putExtra("secret", secret)
            .putExtra("port", port)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopProxy() {
        stopService(Intent(this, ProxyService::class.java))
        prefs.edit().putBoolean("running", false).apply()
        updateStatus(false, "Остановлен", null)
    }

    private fun updateStatus(ok: Boolean, stage: String, error: String?) {
        if (ok) {
            prefs.edit().putBoolean("running", true).apply()
            status.text = "Работает"
            statusDot.setTextColor(ContextCompat.getColor(this, R.color.success))
            toggleButton.text = "Остановить"
            stageView.text = ""
            errorView.visibility = View.GONE
            return
        }

        val starting = stage == "starting" || stage == "starting_proxy" || stage == "android_start_service"
        if (starting) {
            status.text = "Запуск…"
            statusDot.setTextColor(ContextCompat.getColor(this, R.color.warning))
            toggleButton.text = "Остановить"
            stageView.text = ""
            errorView.visibility = View.GONE
            return
        }

        prefs.edit().putBoolean("running", false).apply()
        status.text = "Выключен"
        statusDot.setTextColor(ContextCompat.getColor(this, R.color.danger))
        toggleButton.text = "Запустить"
        stageView.text = ""

        if (!error.isNullOrBlank()) {
            errorView.text = error
            errorView.visibility = View.VISIBLE
        } else {
            errorView.visibility = View.GONE
        }
    }

    private fun refreshUi() {
        val running = prefs.getBoolean("running", false)
        if (running) {
            status.text = "Работает"
            statusDot.setTextColor(ContextCompat.getColor(this, R.color.success))
            toggleButton.text = "Остановить"
            stageView.text = ""
        } else {
            status.text = "Выключен"
            statusDot.setTextColor(ContextCompat.getColor(this, R.color.danger))
            toggleButton.text = "Запустить"
            stageView.text = ""
        }
        errorView.visibility = View.GONE
    }

    private fun buildTelegramLink(secret: String): String =
        "tg://proxy?server=127.0.0.1&port=$port&secret=dd$secret"

    private fun copyTelegramLink(secret: String) {
        copyText("Telegram proxy", buildTelegramLink(secret), "Ссылка Telegram скопирована")
    }

    private fun openTelegramLink(secret: String) {
        val link = buildTelegramLink(secret)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        } catch (_: Throwable) {
            copyTelegramLink(secret)
            Toast.makeText(this, "Ссылка скопирована — открой Telegram вручную.", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyText(label: String, text: String, message: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun getOrCreateSecret(): String {
        prefs.getString("secret", null)?.let { return it }
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val secret = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("secret", secret).apply()
        return secret
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                10
            )
        }
    }
}
