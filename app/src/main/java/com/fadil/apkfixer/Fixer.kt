package com.fadil.apkfixer

import android.content.Context
import android.os.Build
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class Opts(
    val lowerMin: Boolean,
    val lowerTarget: Boolean,
    val stripAbi: Boolean,
    val dropSplits: Boolean
)

class Fixer(internal val ctx: Context, internal val log: (String) -> Unit) {
    private val sdk = Build.VERSION.SDK_INT
    internal val deviceAbis: Set<String> = Build.SUPPORTED_ABIS.toSet()
    private val deviceAbisNorm: Set<String> = deviceAbis.map { it.replace('-', '_') }.toSet()
    private val knownAbis = setOf("arm64_v8a", "armeabi_v7a", "armeabi", "x86", "x86_64", "mips", "mips64", "riscv64")
    private val splitRe = Regex("""(?:split_)?config\.([a-z0-9_]+)\.apk""", RegexOption.IGNORE_CASE)

    /** Paket aplikasi utama dari fix() terakhir (untuk tombol uninstall). */
    var lastPkg: String = ""
        private set
    var lastVer: String = ""
        private set

    internal fun workDir(): File = File(ctx.cacheDir, "work").apply { deleteRecursively(); mkdirs() }

    /** APK polos -> dirinya sendiri; XAPK/APKS/APKM -> semua .apk di dalamnya diekstrak. */
    internal fun listApks(input: File, work: File, dropSplits: Boolean): List<File> {
        ZipFile(input).use { z ->
            if (z.getEntry("AndroidManifest.xml") != null) return listOf(input)
            val out = ArrayList<File>()
            for (e in z.entries()) {
                if (e.isDirectory || !e.name.endsWith(".apk", true)) continue
                val fname = File(e.name).name
                if (dropSplits) {
                    val tok = splitRe.matchEntire(fname)?.groupValues?.get(1)?.lowercase()
                    if (tok != null && tok in knownAbis && tok !in deviceAbisNorm) {
                        log("↷ Split dilewati (ABI tidak cocok): $fname")
                        continue
                    }
                }
                val f = File(work, "in_${out.size}_$fname")
                z.getInputStream(e).use { i -> f.outputStream().use { i.copyTo(it) } }
                out += f
            }
            require(out.isNotEmpty()) { "Tidak ada file .apk di dalam arsip ini." }
            return out
        }
    }

    internal fun manifestOf(apk: File): ByteArray =
        ZipFile(apk).use { z ->
            val e = z.getEntry("AndroidManifest.xml") ?: error("${apk.name}: tidak ada AndroidManifest.xml")
            z.getInputStream(e).readBytes()
        }

    internal fun abisOf(apk: File): Set<String> =
        ZipFile(apk).use { z ->
            z.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") }
                .mapNotNull { it.split('/').getOrNull(1) }.toSortedSet()
        }

    fun describe(input: File): String {
        val work = workDir()
        val sb = StringBuilder()
        val apks = listApks(input, work, false)
        sb.append("${apks.size} APK di dalam file\n")
        val sha = Native.sha256(input.path)
        if (sha.isNotEmpty()) sb.append("SHA-256 ${sha.take(16)}…\n")
        for (a in apks) {
            val p = Native.inspect(manifestOf(a)).split('\t')
            if (p.size < 7) { sb.append("• ${a.name}: manifest tidak terbaca\n"); continue }
            val min = p[1].toIntOrNull() ?: -1
            val tgt = p[2].toIntOrNull() ?: -1
            if (min == -1 && tgt == -1) { sb.append("• split: ${a.name}\n"); continue }
            val abis = abisOf(a)
            sb.append("\n📦 ${p[0]}\n   versi ${p[5]} (kode ${p[4]}) · ${p[6]} izin\n")
            sb.append("   minSdk $min · targetSdk $tgt · split wajib: ${if (p[3] == "1") "ya" else "tidak"}\n")
            if (abis.isNotEmpty()) sb.append("   ABI: ${abis.joinToString()}\n")
            if (min > sdk) sb.append("   ⚠ minSdk $min lebih tinggi dari HP (API $sdk)\n")
            if (abis.isNotEmpty() && abis.none { it in deviceAbis }) sb.append("   ⚠ ABI tidak cocok dengan HP\n")
        }
        return sb.toString().trim()
    }

    /** Patch manifest, align, tanda tangan V1+V2+V3, lalu verifikasi. Mengembalikan APK siap install. */
    fun fix(input: File, opts: Opts): List<File> {
        val work = workDir()
        val apks = listApks(input, work, opts.dropSplits)
        val outDir = File(work, "out").apply { mkdirs() }
        val (key, cert) = loadKey()
        lastPkg = ""
        lastVer = ""
        return apks.mapIndexed { idx, apk ->
            val manifest = manifestOf(apk)
            val p = Native.inspect(manifest).split('\t')
            require(p.size >= 4) { "${apk.name}: manifest tidak terbaca" }
            if (lastPkg.isEmpty() && p[0].isNotEmpty()) { lastPkg = p[0]; lastVer = p.getOrElse(5) { "" } }
            val min = p[1].toInt()
            val tgt = p[2].toInt()
            val newMin = if (opts.lowerMin && min > sdk) sdk else -1
            val newTgt = if (opts.lowerTarget && tgt > sdk) sdk else -1
            if (min > sdk && newMin < 0) log("⚠ minSdk $min > API HP $sdk dan tidak diturunkan; instalasi akan ditolak.")
            if (newMin >= 0) log("minSdk $min → $newMin (app bisa crash bila memakai API yang belum ada di HP)")
            if (newTgt >= 0) log("targetSdk $tgt → $newTgt")
            val patched = Native.patch(manifest, newMin, newTgt, true) ?: error("Gagal mem-patch manifest")

            val libAbis = abisOf(apk)
            val keep = if (opts.stripAbi && libAbis.any { it in deviceAbis }) deviceAbis else null
            val aligned = File(work, "al_$idx.apk")
            alignRewrite(apk, aligned, patched, keep)

            val out = File(outDir, if (apks.size == 1) "fixed.apk" else "fixed_${idx}_${apk.name}")
            val cfg = ApkSigner.SignerConfig.Builder("ApkFixer", key, listOf(cert)).build()
            ApkSigner.Builder(listOf(cfg))
                .setInputApk(aligned).setOutputApk(out)
                .setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(true)
                .setCreatedBy("ApkFixer").build().sign()
            aligned.delete()
            log("✔ ${out.name} (${out.length() / 1024} KB) ditandatangani")
            verify(out)
            out
        }
    }

    private fun verify(f: File) {
        try {
            val r = ApkVerifier.Builder(f).build().verify()
            if (r.isVerified) log("   verifikasi OK (v1=${r.isVerifiedUsingV1Scheme} v2=${r.isVerifiedUsingV2Scheme} v3=${r.isVerifiedUsingV3Scheme})")
            else log("   ⚠ verifikasi gagal: " + r.errors.joinToString { it.toString() })
        } catch (t: Throwable) {
            log("   (verifikasi dilewati: ${t.javaClass.simpleName})")
        }
    }

    internal fun loadKey(): Pair<PrivateKey, X509Certificate> {
        val k = ctx.assets.open("key.pk8").use { it.readBytes() }
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(k))
        val cert = ctx.assets.open("cert.der").use { CertificateFactory.getInstance("X.509").generateCertificate(it) } as X509Certificate
        return key to cert
    }

    internal fun isSig(n: String) = n.startsWith("META-INF/") &&
        (n == "META-INF/MANIFEST.MF" || n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"))

    internal class Counter(o: OutputStream) : FilterOutputStream(o) {
        var n = 0L
        override fun write(b: Int) { out.write(b); n++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); n += len }
    }

    /** Tulis ulang ZIP: manifest baru + override lain, tanda tangan lama dibuang,
     *  resources.arsc STORED + align 4, .so STORED align 4096. */
    internal fun alignRewrite(
        src: File, dst: File, manifest: ByteArray, keepAbis: Set<String>?,
        overrides: Map<String, ByteArray> = emptyMap()
    ) {
        var dropped = 0
        ZipFile(src).use { zf ->
            val cnt = Counter(BufferedOutputStream(FileOutputStream(dst), 1 shl 16))
            ZipOutputStream(cnt).use { zos ->
                val written = HashSet<String>()
                for (e in zf.entries()) {
                    val name = e.name
                    if (e.isDirectory || isSig(name)) continue
                    if (keepAbis != null && name.startsWith("lib/")) {
                        val abi = name.split('/').getOrNull(1)
                        if (abi != null && abi !in keepAbis) { dropped++; continue }
                    }
                    written += name
                    val ne = ZipEntry(name)
                    if (e.time != -1L) ne.time = e.time
                    when {
                        name == "AndroidManifest.xml" -> {
                            ne.method = ZipEntry.DEFLATED
                            zos.putNextEntry(ne); zos.write(manifest)
                        }
                        overrides.containsKey(name) -> {
                            ne.method = ZipEntry.DEFLATED
                            zos.putNextEntry(ne); zos.write(overrides.getValue(name))
                        }
                        e.method == ZipEntry.STORED || name == "resources.arsc" -> {
                            var data: ByteArray? = null
                            val size: Long
                            val crc: Long
                            if (e.method == ZipEntry.STORED) { size = e.size; crc = e.crc }
                            else {
                                data = zf.getInputStream(e).readBytes()
                                size = data.size.toLong()
                                crc = CRC32().apply { update(data) }.value
                            }
                            ne.method = ZipEntry.STORED; ne.size = size; ne.compressedSize = size; ne.crc = crc
                            val a = if (name.endsWith(".so")) 4096 else 4
                            val hdr = 30 + name.toByteArray().size
                            var pad = ((a - (cnt.n + hdr) % a) % a).toInt()
                            if (pad in 1..3) pad += a
                            if (pad > 0) {
                                val x = ByteArray(pad)
                                x[0] = 0x35; x[1] = 0xD9.toByte()
                                x[2] = ((pad - 4) and 0xff).toByte(); x[3] = (((pad - 4) shr 8) and 0xff).toByte()
                                ne.extra = x
                            }
                            zos.putNextEntry(ne)
                            if (data != null) zos.write(data) else zf.getInputStream(e).use { it.copyTo(zos) }
                        }
                        else -> {
                            ne.method = ZipEntry.DEFLATED
                            zos.putNextEntry(ne)
                            zf.getInputStream(e).use { it.copyTo(zos) }
                        }
                    }
                    zos.closeEntry()
                }
                for ((name, bytes) in overrides) {
                    if (name in written) continue
                    val ne = ZipEntry(name); ne.method = ZipEntry.DEFLATED
                    zos.putNextEntry(ne); zos.write(bytes); zos.closeEntry()
                }
            }
        }
        if (dropped > 0) log("↷ $dropped file library ABI lain dibuang")
    }
}
