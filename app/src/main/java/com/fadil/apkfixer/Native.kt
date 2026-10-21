package com.fadil.apkfixer

object Native {
    init { System.loadLibrary("apkfixer") }

    /** "pkg\tmin\ttarget\tsplitWajib\tversionCode\tversionName\tjumlahIzin" atau "" bila manifest tidak terbaca. */
    external fun inspect(manifest: ByteArray): String

    /** Salinan manifest yang sudah dipatch; nilai < 0 berarti tidak diubah. */
    external fun patch(manifest: ByteArray, minSdk: Int, targetSdk: Int, clearSplit: Boolean): ByteArray?
}
