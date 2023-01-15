package com.brentvatne.exoplayer

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.Timeline
import com.google.android.exoplayer2.Tracks
import com.google.android.exoplayer2.text.Cue
import com.google.android.exoplayer2.ui.SubtitleView
import com.google.android.exoplayer2.video.VideoSize

class ExoPlayerView @JvmOverloads constructor(private val context: Context,
                                              attrs: AttributeSet? = null,
                                              defStyleAttr: Int = 0
) : FrameLayout(
    context, attrs, defStyleAttr
) {
    /**
     * Get the view onto which video is rendered. This is either a [SurfaceView] (default)
     * or a [TextureView] if the `use_texture_view` view attribute has been set to true.
     *
     * @return either a [SurfaceView] or a [TextureView].
     */
    var videoSurfaceView: View? = null
        private set
    private val shutterView: View
    private val subtitleLayout: SubtitleView
    private val layout: AspectRatioFrameLayout
    private val componentListener: ComponentListener
    private var player: ExoPlayer? = null
    private val layoutParams: ViewGroup.LayoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    )
    private var useTextureView = true
    private var useSecureView = false
    private var hideShutterView = false
    private fun clearVideoView() {
        if (videoSurfaceView is TextureView) {
            player!!.clearVideoTextureView(videoSurfaceView as TextureView?)
        } else if (videoSurfaceView is SurfaceView) {
            player!!.clearVideoSurfaceView(videoSurfaceView as SurfaceView?)
        }
    }

    private fun setVideoView() {
        if (videoSurfaceView is TextureView) {
            player!!.setVideoTextureView(videoSurfaceView as TextureView?)
        } else if (videoSurfaceView is SurfaceView) {
            player!!.setVideoSurfaceView(videoSurfaceView as SurfaceView?)
        }
    }

    fun setSubtitleStyle(style: SubtitleStyle) {
        // ensure we reset subtile style before reapplying it
        subtitleLayout.setUserDefaultStyle()
        subtitleLayout.setUserDefaultTextSize()
        if (style.fontSize > 0) {
            subtitleLayout.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSize.toFloat())
        }
        subtitleLayout.setPadding(
            style.paddingLeft, style.paddingTop, style.paddingRight, style.paddingBottom
        )
    }

    private fun updateSurfaceView() {
        val view: View
        if (!useTextureView || useSecureView) {
            view = SurfaceView(context)
            if (useSecureView) {
                view.setSecure(true)
            }
        } else {
            view = TextureView(context)
        }
        view.layoutParams = layoutParams
        videoSurfaceView = view
        if (layout.getChildAt(0) != null) {
            layout.removeViewAt(0)
        }
        layout.addView(videoSurfaceView, 0, layoutParams)
        if (player != null) {
            setVideoView()
        }
    }

    private fun updateShutterViewVisibility() {
        shutterView.visibility = if (hideShutterView) INVISIBLE else VISIBLE
    }

    /**
     * Set the [ExoPlayer] to use. The [ExoPlayer.addListener] method of the
     * player will be called and previous
     * assignments are overridden.
     *
     * @param player The [ExoPlayer] to use.
     */
    fun setPlayer(player: ExoPlayer?) {
        if (this.player === player) {
            return
        }
        if (this.player != null) {
            this.player!!.removeListener(componentListener)
            clearVideoView()
        }
        this.player = player
        shutterView.visibility = if (hideShutterView) INVISIBLE else VISIBLE
        if (player != null) {
            setVideoView()
            player.addListener(componentListener)
        }
    }

    /**
     * Sets the resize mode which can be of value [ResizeMode.Mode]
     *
     * @param resizeMode The resize mode.
     */
    fun setResizeMode(@ResizeMode.Mode resizeMode: Int) {
        if (layout.resizeMode != resizeMode) {
            layout.resizeMode = resizeMode
            post(measureAndLayout)
        }
    }

    fun setUseTextureView(useTextureView: Boolean) {
        if (useTextureView != this.useTextureView) {
            this.useTextureView = useTextureView
            updateSurfaceView()
        }
    }

    fun useSecureView(useSecureView: Boolean) {
        if (useSecureView != this.useSecureView) {
            this.useSecureView = useSecureView
            updateSurfaceView()
        }
    }

    fun setHideShutterView(hideShutterView: Boolean) {
        this.hideShutterView = hideShutterView
        updateShutterViewVisibility()
    }

    private val measureAndLayout = Runnable {
        measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        layout(left, top, right, bottom)
    }

    init {
        componentListener = ComponentListener()
        val aspectRatioParams = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
        )
        aspectRatioParams.gravity = Gravity.CENTER
        layout = AspectRatioFrameLayout(context)
        layout.layoutParams = aspectRatioParams
        shutterView = View(getContext())
        shutterView.layoutParams = layoutParams
        shutterView.setBackgroundColor(ContextCompat.getColor(context, android.R.color.black))
        subtitleLayout = SubtitleView(context)
        subtitleLayout.layoutParams = layoutParams
        subtitleLayout.setUserDefaultStyle()
        subtitleLayout.setUserDefaultTextSize()
        updateSurfaceView()
        layout.addView(shutterView, 1, layoutParams)
        layout.addView(subtitleLayout, 2, layoutParams)
        addViewInLayout(layout, 0, aspectRatioParams)
    }

    private fun updateForCurrentTrackSelections() {
        if (player == null) {
            return
        }
        val selections = player!!.currentTrackSelections
        for (i in 0 until selections.length) {
            if (player!!.getRendererType(i) == C.TRACK_TYPE_VIDEO && selections[i] != null) {
                // Video enabled so artwork must be hidden. If the shutter is closed, it will be opened in
                // onRenderedFirstFrame().
                return
            }
        }
        // Video disabled so the shutter must be closed.
        shutterView.visibility = if (hideShutterView) INVISIBLE else VISIBLE
    }

    fun invalidateAspectRatio() {
        // Resetting aspect ratio will force layout refresh on next video size changed
        layout.invalidateAspectRatio()
    }

    private inner class ComponentListener : Player.Listener {
        // TextRenderer.Output implementation
        override fun onCues(cues: List<Cue>) {
            subtitleLayout.setCues(cues)
        }

        // ExoPlayer.VideoListener implementation
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            val isInitialRatio = layout.aspectRatio == 0f
            layout.aspectRatio =
                if (videoSize.height == 0) 1f else videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height

            // React native workaround for measuring and layout on initial load.
            if (isInitialRatio) {
                post(measureAndLayout)
            }
        }

        override fun onRenderedFirstFrame() {
            shutterView.visibility = INVISIBLE
        }

        // ExoPlayer.EventListener implementation
        override fun onIsLoadingChanged(isLoading: Boolean) {
            // Do nothing.
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // Do nothing.
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Do nothing.
        }

        override fun onPlayerError(e: PlaybackException) {
            // Do nothing.
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,
                                             newPosition: Player.PositionInfo,
                                             reason: Int
        ) {
            // Do nothing.
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            // Do nothing.
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateForCurrentTrackSelections()
        }

        override fun onPlaybackParametersChanged(params: PlaybackParameters) {
            // Do nothing
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            // Do nothing.
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            // Do nothing.
        }
    }
}
