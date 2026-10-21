package com.fadil.apkfixer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
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
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val action = "com.fadil.apkfixer.INSTALL_RESULT"
    private val io = Executors.newSingleThreadExecutor()
    private val fixer by lazy { Fixer(this, this::log) }
    private var picked: File? = null
    private var lastPkg = ""

    private lateinit var scroll: ScrollView
    private lateinit var tvLog: TextView
    private lateinit var tvInfo: TextView
    private lateinit var swMin: MaterialSwitch
    private lateinit var swTarget: MaterialSwitch
    private lateinit var swAbi: MaterialSwitch
    private lateinit var swSplit: MaterialSwitch
    private lateinit var btnPick: MaterialButton
    private lateinit var btnInstall: MaterialButton
    private lateinit var btnSave: MaterialButton
    private lateinit var progress: LinearProgressIndicator

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) load(uri)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
            val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            setBusy(false)
            when (st) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val next = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    next?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (next != null) startActivity(next)
                }
                PackageInstaller.STATUS_SUCCESS -> log("✔ Aplikasi terpasang.")
                PackageInstaller.STATUS_FAILURE_ABORTED -> log("Instalasi dibatalkan.")
                else -> {
                    val h = hint(msg)
                    log("✘ Install gagal [kode $st]: $msg" + if (h.isNotEmpty()) "\n💡 $h" else "")
                    if (st == PackageInstaller.STATUS_FAILURE_CONFLICT ||
                        st == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ||
                        (msg ?: "").contains("DOWNGRADE")
                    ) showUninstall()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        scroll = findViewById(R.id.scroll)
        tvLog = findViewById(R.id.tvLog)
        tvInfo = findViewById(R.id.tvInfo)
        swMin = findViewById(R.id.swMin)
        swTarget = findViewById(R.id.swTarget)
        swAbi = findViewById(R.id.swAbi)
        swSplit = findViewById(R.id.swSplit)
        btnPick = findViewById(R.id.btnPick)
        btnInstall = findViewById(R.id.btnInstall)
        btnSave = findViewById(R.id.btnSave)
        progress = findViewById(R.id.progress)
        findViewById<Chip>(R.id.chipAndroid).text = "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}"
        findViewById<Chip>(R.id.chipAbi).text = "ABI ${Build.SUPPORTED_ABIS[0]}"

        ContextCompat.registerReceiver(this, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)

        btnPick.setOnClickListener { picker.launch(arrayOf("*/*")) }
        btnInstall.setOnClickListener { run(true) }
        btnSave.setOnClickListener { run(false) }
        findViewById<MaterialButton>(R.id.btnCopy).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("log", tvLog.text))
            Toast.makeText(this, "Log disalin", Toast.LENGTH_SHORT).show()
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    private fun handleIntent(i: Intent?) {
        val uri: Uri? = when (i?.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            else -> null
        }
        if (uri != null) load(uri)
    }

    private fun log(s: String) = runOnUiThread {
        tvLog.text = if (tvLog.text.toString() == "—") s else tvLog.text.toString() + "\n" + s
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setBusy(b: Boolean) = runOnUiThread {
        progress.visibility = if (b) View.VISIBLE else View.GONE
        btnPick.isEnabled = !b
        btnInstall.isEnabled = !b && picked != null
        btnSave.isEnabled = !b && picked != null
    }

    private fun hint(m: String?): String {
        val s = m ?: return ""
        return when {
            "NO_MATCHING_ABIS" in s -> "ABI aplikasi tidak cocok dengan CPU HP."
            "DOWNGRADE" in s -> "Versi yang terpasang lebih baru. Uninstall dulu."
            "UPDATE_INCOMPATIBLE" in s || "signatures" in s || "SIGNATURES" in s ->
                "Tanda tangan beda dengan versi terpasang. Uninstall dulu."
            "OLDER_SDK" in s -> "minSdk terlalu tinggi. Aktifkan 'Turunkan minSdk'."
            "MISSING_SPLIT" in s -> "Split yang dibutuhkan tidak lengkap. Pakai file XAPK/APKS utuh."
            "INSUFFICIENT_STORAGE" in s -> "Penyimpanan HP penuh."
            "NO_CERTIFICATES" in s || "PARSE" in s -> "APK rusak atau tanda tangan tidak valid."
            else -> ""
        }
    }

    private fun showUninstall() {
        if (lastPkg.isEmpty()) return
        Snackbar.make(findViewById(android.R.id.content), "Versi lama terpasang. Uninstall dulu?", Snackbar.LENGTH_INDEFINITE)
            .setAction("Uninstall") {
                startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$lastPkg")))
            }.show()
    }

    private fun load(uri: Uri) {
        picked = null
        setBusy(true)
        io.execute {
            try {
                val f = File(cacheDir, "input.bin")
                contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
                picked = f
                val text = fixer.describe(f)
                runOnUiThread { tvInfo.text = text }
            } catch (e: Throwable) { log("Error: $e") }
            setBusy(false)
        }
    }

    private fun run(install: Boolean) {
        val f = picked ?: return
        val opts = Opts(swMin.isChecked, swTarget.isChecked, swAbi.isChecked, swSplit.isChecked)
        setBusy(true)
        log("— mulai —")
        io.execute {
            try {
                val out = fixer.fix(f, opts)
                lastPkg = fixer.lastPkg
                if (install) installApks(out) else { saveAll(out); setBusy(false) }
            } catch (e: Throwable) {
                log("Error: $e")
                setBusy(false)
            }
        }
    }

    private fun installApks(files: List<File>) {
        if (!packageManager.canRequestPackageInstalls()) {
            log("Aktifkan izin 'Install aplikasi tidak dikenal' untuk ApkFixer, lalu tekan tombol lagi.")
            setBusy(false)
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
