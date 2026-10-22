package com.fadil.apkfixer

object Native {
    init { System.loadLibrary("apkfixer") }

    /** "pkg\tmin\ttarget\tsplitWajib\tversionCode\tversionName\tjumlahIzin" atau "" bila manifest tidak terbaca. */
    external fun inspect(manifest: ByteArray): String

    /** Salinan manifest yang sudah dipatch; nilai < 0 berarti tidak diubah. */
    external fun patch(manifest: ByteArray, minSdk: Int, targetSdk: Int, clearSplit: Boolean): ByteArray?

    /** SHA-256 (hex) sebuah file, dihitung di C++. "" bila file tidak terbaca. */
    external fun sha256(path: String): String

    /**
     * Mengganti package, label, versi, authorities provider, dan setiap <permission>/
     * <permission-group>/android:permission yang namanya diawali origPkg (mencegah
     * INSTALL_FAILED_DUPLICATE_PERMISSION saat klon dipasang berdampingan dengan aslinya).
     * Field yang di-null-kan / newVerCode < 0 berarti tidak diubah.
     * Mengembalikan manifest baru, atau null bila gagal dipatch.
     */
    external fun clonePatch(
        manifest: ByteArray, origPkg: String, newPkg: String?, newLabel: String?,
        newVerName: String?, newVerCode: Int, authSuffix: String?
    ): ByteArray?
}
