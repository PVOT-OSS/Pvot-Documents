package org.prauga.documents.extensions

import android.content.Intent
import android.os.Build
import android.os.Parcelable

/** [Intent.getParcelableExtra], without the deprecation warning on API 33+. */
inline fun <reified T : Parcelable> Intent.parcelableExtraCompat(name: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name)
    }
