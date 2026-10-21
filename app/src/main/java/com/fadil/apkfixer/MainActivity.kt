package com.fadil.apkfixer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val action = "com.fadil.apkfixer.INSTALL_RESULT"
    private val io = Executors.newSingleThreadExecutor()
    private var picked: File? = null

    private lateinit var tvLog: TextView
    private lateinit var tvInfo: TextView
    private lateinit var cbMin: CheckBox
    private lateinit var cbTarget: CheckBox
    private lateinit var btnInstall: Button
    private lateinit var btnSave: Button

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) load(uri)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
            val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            when (st) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val next = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    next?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (next != null) startActivity(next)
                }
                PackageInstaller.STATUS_SUCCESS -> log("✔ Aplikasi terpasang.")
                else -> log("✘ Install gagal [kode $st]: $msg")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvLog = findViewById(R.id.tvLog)
        tvInfo = findViewById(R.id.tvInfo)
        cbMin = findViewById(R.id.cbMin)
        cbTarget = findViewById(R.id.cbTarget)
        btnInstall = findViewById(R.id.btnInstall)
        btnSave = findViewById(R.id.btnSave)
        findViewById<TextView>(R.id.tvDevice).text =
            "Android ${Build.VERSION.RELEASE} • API ${Build.VERSION.SDK_INT}\nABI: ${Build.SUPPORTED_ABIS.joinToString()}"

        ContextCompat.registerReceiver(this, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)

        findViewById<Button>(R.id.btnPick).setOnClickListener { picker.launch(arrayOf("*/*")) }
        btnInstall.setOnClickListener { run(true) }
        btnSave.setOnClickListener { run(false) }
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    private fun log(s: String) = runOnUiThread { tvLog.append(s + "\n") }

    private fun load(uri: Uri) = io.execute {
        try {
            val f = File(cacheDir, "input.bin")
            contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
            picked = f
            val text = Fixer(this, this::log).describe(f)
            runOnUiThread { tvInfo.text = text; btnInstall.isEnabled = true; btnSave.isEnabled = true }
        } catch (e: Throwable) { log("Error: $e") }
    }

    private fun run(install: Boolean) {
        val f = picked ?: return
        val opts = Opts(cbMin.isChecked, cbTarget.isChecked)
        io.execute {
            try {
                val out = Fixer(this, this::log).fix(f, opts)
                if (install) installApks(out) else saveAll(out)
            } catch (e: Throwable) { log("Error: $e") }
        }
    }

    private fun installApks(files: List<File>) {
        if (!packageManager.canRequestPackageInstalls()) {
            log("Aktifkan izin 'Install aplikasi tidak dikenal' untuk ApkFixer, lalu tekan tombol lagi.")
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            }
            return
        }
        val pi = packageManager.packageInstaller
        val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        pi.openSession(id).use { s ->
            files.forEachIndexed { i, f ->
                f.inputStream().use { inp ->
                    s.openWrite("part$i.apk", 0, f.length()).use { o -> inp.copyTo(o); s.fsync(o) }
                }
            }
            val pending = PendingIntent.getBroadcast(
                this, id, Intent(action).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            s.commit(pending.intentSender)
        }
        log("Sesi install dikirim, menunggu sistem…")
    }

    private fun saveAll(files: List<File>) {
        for (f in files) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/ApkFixer")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: error("Gagal membuat file")
            contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
            log("Tersimpan: Download/ApkFixer/${f.name}")
        }
    }
}
