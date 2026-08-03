package com.dogu.livekit.core.util

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.dogu.livekit.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

fun Int.dpToPx(context: Context): Int = (this * context.resources.displayMetrics.density).toInt()

fun ImageView.setDefaultAvatar(context: Context) {
    this.setImageResource(R.drawable.ic_person)
    val padding = 10.dpToPx(context)
    this.setPadding(padding, padding, padding, padding)
    this.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.accent_blue))
}

fun TextView.showStatus(message: String, duration: Long = 3000L, scope: CoroutineScope) {
    this.text = message
    this.visibility = View.VISIBLE
    this.animate()
        .alpha(1f)
        .translationYBy(-20f)
        .setDuration(300)
        .withEndAction {
            scope.launch {
                delay(duration.milliseconds)
                this@showStatus.animate()
                    .alpha(0f)
                    .translationYBy(20f)
                    .setDuration(300)
                    .withEndAction {
                        this@showStatus.visibility = View.GONE
                    }
            }
        }
}
