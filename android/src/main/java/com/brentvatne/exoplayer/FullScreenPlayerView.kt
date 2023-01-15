package com.brentvatne.exoplayer

import android.app.Dialog
import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.activity.OnBackPressedCallback
import com.brentvatne.react.R
import com.google.android.exoplayer2.ui.PlayerControlView
import com.google.android.exoplayer2.ui.StyledPlayerView

class FullScreenPlayerView(context: Context?,
                           private val exoPlayerView: StyledPlayerView?,
                           private val onBackPressedCallback: OnBackPressedCallback
) : Dialog(
    context!!, android.R.style.Theme_Black_NoTitleBar_Fullscreen
) {
    private var parent: ViewGroup? = null
    private val containerView: FrameLayout

    init {
        containerView = FrameLayout(context!!)
        setContentView(containerView, generateDefaultLayoutParams())
    }

    override fun onBackPressed() {
        super.onBackPressed()
        onBackPressedCallback.handleOnBackPressed()
    }

    override fun onStart() {
        parent = exoPlayerView!!.parent as FrameLayout
        parent!!.removeView(exoPlayerView)
        containerView.addView(exoPlayerView, generateDefaultLayoutParams())
        super.onStart()
    }

    override fun onStop() {
        containerView.removeView(exoPlayerView)
        parent!!.addView(exoPlayerView, generateDefaultLayoutParams())
        parent!!.requestLayout()
        parent = null
        super.onStop()
    }

    private fun generateDefaultLayoutParams(): FrameLayout.LayoutParams {
        val layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        layoutParams.setMargins(0, 0, 0, 0)
        return layoutParams
    }
}
