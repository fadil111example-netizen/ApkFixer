package com.fadil.apkfixer

object Native {
    init { System.loadLibrary("apkfixer") }

    /** "package\tminSdk\ttargetSdk\tsplitRequired(-1/0/1)" atau "" bila manifest tidak terbaca. */
    external fun inspect(manifest: ByteArray): String

    /** Salinan manifest yang sudah dipatch; nilai < 0 berarti tidak diubah. */
    external fun patch(manifest: ByteArray, minSdk: Int, targetSdk: Int, clearSplit: Boolean): ByteArray?
}
