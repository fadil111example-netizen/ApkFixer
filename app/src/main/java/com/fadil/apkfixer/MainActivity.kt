package com.fadil.apkfixer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
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
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {
    private val action = "com.fadil.apkfixer.INSTALL_RESULT"
    private val io = Executors.newSingleThreadExecutor()
    private val fixer by lazy { Fixer(this, this::log) }
    private val prefs by lazy { getSharedPreferences("apkfixer", MODE_PRIVATE) }
    private var picked: File? = null
    private var lastPkg = ""
    private var lastVer = ""

    private lateinit var scroll: ScrollView
    private lateinit var tvLog: TextView
    private lateinit var tvInfo: TextView
    private lateinit var tvHistory: TextView
    private lateinit var swMin: MaterialSwitch
    private lateinit var swTarget: MaterialSwitch
    private lateinit var swAbi: MaterialSwitch
    private lateinit var swSplit: MaterialSwitch
    private lateinit var btnPick: MaterialButton
    private lateinit var btnInstalled: MaterialButton
    private lateinit var btnClone: MaterialButton
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
                PackageInstaller.STATUS_SUCCESS -> { log("✔ Aplikasi terpasang."); addHistory("Terpasang ✔") }
                PackageInstaller.STATUS_FAILURE_ABORTED -> { log("Instalasi dibatalkan."); addHistory("Dibatalkan") }
                else -> {
                    val h = hint(msg)
                    log("✘ Install gagal [kode $st]: $msg" + if (h.isNotEmpty()) "\n💡 $h" else "")
                    addHistory("Gagal (kode $st)")
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
        tvHistory = findViewById(R.id.tvHistory)
        swMin = findViewById(R.id.swMin)
        swTarget = findViewById(R.id.swTarget)
        swAbi = findViewById(R.id.swAbi)
        swSplit = findViewById(R.id.swSplit)
        btnPick = findViewById(R.id.btnPick)
        btnInstalled = findViewById(R.id.btnInstalled)
        btnClone = findViewById(R.id.btnClone)
        btnInstall = findViewById(R.id.btnInstall)
        btnSave = findViewById(R.id.btnSave)
        progress = findViewById(R.id.progress)
        findViewById<Chip>(R.id.chipAndroid).text = "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}"
        findViewById<Chip>(R.id.chipAbi).text = "ABI ${Build.SUPPORTED_ABIS[0]}"

        bindPref(swMin, "min", true)
        bindPref(swTarget, "target", false)
        bindPref(swAbi, "abi", true)
        bindPref(swSplit, "split", true)

        ContextCompat.registerReceiver(this, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)

        btnPick.setOnClickListener { picker.launch(arrayOf("*/*")) }
        btnInstalled.setOnClickListener { pickInstalled() }
        btnClone.setOnClickListener { pickForClone() }
        btnInstall.setOnClickListener { run(true) }
        btnSave.setOnClickListener { run(false) }
        findViewById<MaterialButton>(R.id.btnCopy).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("log", tvLog.text))
            Toast.makeText(this, "Log disalin", Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.btnClearLog).setOnClickListener { tvLog.text = "—" }
        findViewById<MaterialButton>(R.id.btnClearHist).setOnClickListener {
            prefs.edit().remove("hist").apply()
            renderHistory()
        }
        renderHistory()
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

    private fun bindPref(sw: MaterialSwitch, key: String, def: Boolean) {
        sw.isChecked = prefs.getBoolean(key, def)
        sw.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(key, v).apply() }
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
        btnInstalled.isEnabled = !b
        btnClone.isEnabled = !b
        btnInstall.isEnabled = !b && picked != null
        btnSave.isEnabled = !b && picked != null
    }

    // ---------- riwayat ----------
    private fun readHistory(): JSONArray =
        try { JSONArray(prefs.getString("hist", "[]")) } catch (e: Exception) { JSONArray() }

    private fun addHistory(status: String) {
        val old = readHistory()
        val n = JSONArray()
        n.put(JSONObject().put("t", System.currentTimeMillis()).put("p", lastPkg).put("v", lastVer).put("s", status))
        for (i in 0 until minOf(old.length(), 19)) n.put(old.get(i))
        prefs.edit().putString("hist", n.toString()).apply()
        renderHistory()
    }

    private fun renderHistory() {
        val arr = readHistory()
        val fmt = SimpleDateFormat("dd MMM HH:mm", Locale("id", "ID"))
        val sb = StringBuilder()
        for (i in 0 until minOf(arr.length(), 8)) {
            val o = arr.getJSONObject(i)
            val pkg = o.optString("p").ifEmpty { "(tidak diketahui)" }
            val ver = o.optString("v")
            sb.append(fmt.format(Date(o.optLong("t")))).append(" · ").append(pkg)
            if (ver.isNotEmpty()) sb.append(" v").append(ver)
            sb.append(" · ").append(o.optString("s")).append('\n')
        }
        runOnUiThread { tvHistory.text = if (sb.isEmpty()) "Belum ada riwayat." else sb.toString().trim() }
    }

    // ---------- bantuan error ----------
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

    // ---------- muat file ----------
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

    // ---------- ambil APK dari aplikasi terpasang ----------
    @Suppress("DEPRECATION")
    private fun pickInstalled() {
        setBusy(true)
        io.execute {
            val pm = packageManager
            val apps = pm.getInstalledApplications(0)
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != packageName }
                .map { it to pm.getApplicationLabel(it).toString() }
                .sortedBy { it.second.lowercase() }
            setBusy(false)
            runOnUiThread {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Pilih aplikasi (${apps.size})")
                    .setItems(apps.map { "${it.second}\n${it.first.packageName}" }.toTypedArray()) { _, i ->
                        extractApp(apps[i].first)
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun extractApp(ai: ApplicationInfo) {
        setBusy(true)
        log("— mengambil APK ${ai.packageName} —")
        io.execute {
            try {
                val files = listOf(File(ai.sourceDir)) + (ai.splitSourceDirs?.map { File(it) } ?: emptyList())
                val ver = (packageManager.getPackageInfo(ai.packageName, 0).versionName ?: "x")
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                val dir = File(cacheDir, "extract").apply { deleteRecursively(); mkdirs() }
                val single = files.size == 1
                val name = ai.packageName + "_" + ver + if (single) ".apk" else ".xapk"
                val target = File(dir, name)
                if (single) files[0].copyTo(target, true)
                else ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { z ->
                    z.setLevel(0)
                    for (f in files) {
                        z.putNextEntry(ZipEntry(f.name))
                        f.inputStream().use { it.copyTo(z) }
                        z.closeEntry()
                    }
                }
                saveToDownloads(target, name, if (single) "application/vnd.android.package-archive" else "application/octet-stream")
                val inp = File(cacheDir, "input.bin")
                target.copyTo(inp, true)
                picked = inp
                lastPkg = ai.packageName
                lastVer = ver
                val text = fixer.describe(inp)
                runOnUiThread { tvInfo.text = text }
            } catch (e: Throwable) { log("Error: $e") }
            setBusy(false)
        }
    }

    // ---------- perbaiki ----------
    private fun run(install: Boolean) {
        val f = picked ?: return
        val opts = Opts(swMin.isChecked, swTarget.isChecked, swAbi.isChecked, swSplit.isChecked)
        setBusy(true)
        log("— mulai —")
        io.execute {
            try {
                val out = fixer.fix(f, opts)
                lastPkg = fixer.lastPkg
                lastVer = fixer.lastVer
                if (install) installApks(out) else {
                    out.forEach { saveToDownloads(it, it.name, "application/vnd.android.package-archive") }
                    addHistory("Disimpan")
                    setBusy(false)
                }
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

    @Suppress("DEPRECATION")
    private fun pickForClone() {
        setBusy(true)
        io.execute {
            val pm = packageManager
            val apps = pm.getInstalledApplications(0)
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != packageName }
                .map { it to pm.getApplicationLabel(it).toString() }
                .sortedBy { it.second.lowercase() }
            setBusy(false)
            runOnUiThread {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Clone aplikasi mana? (${apps.size})")
                    .setItems(apps.map { "${it.second}\n${it.first.packageName}" }.toTypedArray()) { _, i ->
                        confirmClone(apps[i].first)
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            }
        }
    }

    private fun confirmClone(ai: ApplicationInfo) {
        val cloner = Cloner(this, this::log)
        val sug = cloner.suggest(ai)
        val view = layoutInflater.inflate(R.layout.dialog_clone, null)
        val etPkg = view.findViewById<TextInputEditText>(R.id.etPkg).apply { setText(sug.pkg) }
        val etLabel = view.findViewById<TextInputEditText>(R.id.etLabel).apply { setText(sug.label) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Clone " + pm_label(ai))
            .setView(view)
            .setPositiveButton("Clone & Install") { _, _ ->
                val pkg = etPkg.text?.toString()?.trim().orEmpty()
                val label = etLabel.text?.toString()?.trim().orEmpty()
                runClone(cloner, ai, pkg, label)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun pm_label(ai: ApplicationInfo): String = packageManager.getApplicationLabel(ai).toString()

    private fun runClone(cloner: Cloner, ai: ApplicationInfo, pkg: String, label: String) {
        setBusy(true)
        log("— kloning ${ai.packageName} → $pkg —")
        io.execute {
            try {
                val src = cloner.extractInstalled(ai)
                val res = cloner.clone(src, pkg, label)
                lastPkg = res.newPackage
                lastVer = "clone"
                installApks(res.apks)
                addHistory("Clone dari ${ai.packageName}")
            } catch (e: Throwable) {
                log("Error kloning: ${e.message ?: e.javaClass.simpleName}")
                setBusy(false)
            }
        }
    }

    private fun saveToDownloads(f: File, name: String, mime: String) {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/ApkFixer")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: error("Gagal membuat file")
        contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
        log("Tersimpan: Download/ApkFixer/$name")
    }
}
