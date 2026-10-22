package com.fadil.apkfixer

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipFile
import javax.security.auth.x500.X500Principal
import com.android.apksig.ApkSigner
import kotlin.random.Random

data class CloneResult(val apks: List<File>, val newPackage: String, val newLabel: String)

/**
 * Mengkloning aplikasi yang sudah terpasang: package name, label, authorities provider,
 * dan ikon dibuat berbeda dari aslinya, lalu ditandatangani dengan kunci baru yang unik
 * (dibuat di Android Keystore, bukan kunci bersama ApkFixer).
 */
class Cloner(private val ctx: Context, private val log: (String) -> Unit) {
    private val fixer = Fixer(ctx, log)
    private val KS = "AndroidKeyStore"

    data class Suggestion(val pkg: String, val label: String)

    fun suggest(ai: ApplicationInfo): Suggestion {
        val pm = ctx.packageManager
        val label = pm.getApplicationLabel(ai).toString()
        val suffix = Random.nextInt(1000, 9999)
        return Suggestion("${ai.packageName}.clone$suffix", "$label Clone")
    }

    /** Menyalin APK (+split) aplikasi terpasang ke satu file kerja (.apk polos atau .xapk gabungan). */
    fun extractInstalled(ai: ApplicationInfo): File {
        val files = listOf(File(ai.sourceDir)) + (ai.splitSourceDirs?.map { File(it) } ?: emptyList())
        val dir = File(ctx.cacheDir, "clone_src").apply { deleteRecursively(); mkdirs() }
        val target = File(dir, "src.bin")
        if (files.size == 1) {
            files[0].copyTo(target, true)
        } else {
            java.util.zip.ZipOutputStream(target.outputStream()).use { z ->
                z.setLevel(0)
                for (f in files) {
                    z.putNextEntry(java.util.zip.ZipEntry(f.name))
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
            }
        }
        return target
    }

    fun clone(input: File, newPkg: String, newLabel: String): CloneResult {
        require(Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$""").matches(newPkg)) {
            "Package name tidak valid: $newPkg"
        }
        val work = fixer.workDir()
        val apks = fixer.listApks(input, work, false)
        val outDir = File(work, "clone_out").apply { mkdirs() }
        val authSuffix = newPkg.substringAfterLast('.').ifEmpty { "clone" }
        val (key, cert) = cloneKey(newPkg)

        val out = apks.mapIndexed { idx, apk ->
            val manifest = fixer.manifestOf(apk)
            val info = Native.inspect(manifest).split('\t')
            val isBase = info.size >= 4 && info[1].toIntOrNull() != -1
            val patched = if (isBase) {
                Native.clonePatch(manifest, newPkg, newLabel, authSuffix)
                    ?: error("Gagal mem-patch manifest untuk kloning (${apk.name})")
            } else {
                // split config (misalnya split per-ABI) biasanya tidak membawa <application>/package utama.
                Native.clonePatch(manifest, null, null, authSuffix) ?: manifest
            }

            val overrides = HashMap<String, ByteArray>()
            if (isBase) overrides.putAll(badgeIcons(apk))

            val aligned = File(work, "cl_$idx.apk")
            fixer.alignRewrite(apk, aligned, patched, null, overrides)

            val f = File(outDir, if (apks.size == 1) "clone.apk" else "clone_${idx}_${apk.name}")
            val cfg = ApkSigner.SignerConfig.Builder("ApkFixerClone", key, listOf(cert)).build()
            ApkSigner.Builder(listOf(cfg))
                .setInputApk(aligned).setOutputApk(f)
                .setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(true)
                .setCreatedBy("ApkFixer").build().sign()
            aligned.delete()
            log("✔ ${f.name} (${f.length() / 1024} KB) — tanda tangan unik dari Android Keystore")
            f
        }
        return CloneResult(out, newPkg, newLabel)
    }

    /** Kunci RSA 2048 khusus klon ini, dibuat & disimpan di Android Keystore (hardware-backed bila tersedia). */
    private fun cloneKey(newPkg: String): Pair<PrivateKey, X509Certificate> {
        val alias = "apkfixer_clone_${newPkg.hashCode().toUInt()}"
        val ks = KeyStore.getInstance(KS).apply { load(null) }
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KS)
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setCertificateSubject(X500Principal("CN=ApkFixer Clone, O=ApkFixer"))
            .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
            .setCertificateNotBefore(java.util.Date(System.currentTimeMillis() - 86_400_000L))
            .setCertificateNotAfter(java.util.Date(System.currentTimeMillis() + 100L * 365 * 86_400_000L))
            .build()
        kpg.initialize(spec)
        kpg.generateKeyPair()
        val entry = ks.getEntry(alias, null) as KeyStore.PrivateKeyEntry
        return entry.privateKey to (entry.certificate as X509Certificate)
    }

    /** Mencari file PNG ikon di dalam APK lewat nama resource ikon, lalu menimpanya dengan versi berlencana. */
    private fun badgeIcons(apk: File): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        try {
            val entryName = iconEntryName(apk) ?: return out
            ZipFile(apk).use { zf ->
                for (e in zf.entries()) {
                    val n = e.name
                    if (e.isDirectory || !n.endsWith(".png")) continue
                    val base = n.substringAfterLast('/').removeSuffix(".png")
                    if (base != entryName && base != "${entryName}_foreground" && base != "${entryName}_background") continue
                    val bytes = zf.getInputStream(e).readBytes()
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                    val badged = addBadge(bmp)
                    val bos = ByteArrayOutputStream()
                    badged.compress(Bitmap.CompressFormat.PNG, 100, bos)
                    out[n] = bos.toByteArray()
                }
            }
            if (out.isEmpty()) log("ℹ Ikon tidak ditemukan sebagai PNG biasa (mungkin vector); lencana klon dilewati.")
            else log("🏷 Lencana 'C' ditambahkan ke ${out.size} ikon")
        } catch (t: Throwable) {
            log("ℹ Lewati lencana ikon: ${t.javaClass.simpleName}")
        }
        return out
    }

    private fun iconEntryName(apk: File): String? = try {
        val pm = ctx.packageManager
        val info = pm.getPackageArchiveInfo(apk.path, 0) ?: return null
        val resId = info.applicationInfo?.icon ?: 0
        if (resId == 0) return null
        info.applicationInfo!!.sourceDir = apk.path
        info.applicationInfo!!.publicSourceDir = apk.path
        val res = pm.getResourcesForApplication(info.applicationInfo!!)
        res.getResourceEntryName(resId)
    } catch (t: Throwable) { null }

    /** Menggambar lingkaran oranye dengan huruf "C" di sudut kanan-bawah ikon, menandai ini salinan klon. */
    private fun addBadge(src: Bitmap): Bitmap {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(bmp)
        val d = minOf(bmp.width, bmp.height) * 0.42f
        val cx = bmp.width - d * 0.55f
        val cy = bmp.height - d * 0.55f
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        c.drawOval(RectF(cx - d / 2 - 3, cy - d / 2 - 3, cx + d / 2 + 3, cy + d / 2 + 3), ring)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF7A00"); style = Paint.Style.FILL }
        c.drawOval(RectF(cx - d / 2, cy - d / 2, cx + d / 2, cy + d / 2), fill)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = d * 0.62f; isFakeBoldText = true
        }
        val fm = text.fontMetrics
        c.drawText("C", cx, cy - (fm.ascent + fm.descent) / 2, text)
        return bmp
    }
}
