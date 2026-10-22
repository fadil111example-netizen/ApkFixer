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
     * Mengganti package, label aplikasi, dan/atau authorities provider di manifest biner.
     * newPkg/newLabel boleh null (tidak diubah). authSuffix bila tidak null ditambahkan
     * ke setiap authorities provider ("com.app.provider" -> "com.app.provider.<suffix>").
     * Mengembalikan manifest baru, atau null bila gagal dipatch.
     */
    external fun clonePatch(manifest: ByteArray, newPkg: String?, newLabel: String?, authSuffix: String?): ByteArray?
}
