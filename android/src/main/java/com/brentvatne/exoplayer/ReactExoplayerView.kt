
package com.brentvatne.exoplayer

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.media.AudioManager
import android.media.AudioManager.OnAudioFocusChangeListener
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import com.brentvatne.receiver.AudioBecomingNoisyReceiver
import com.brentvatne.receiver.BecomingNoisyListener
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.WritableArray
import com.facebook.react.uimanager.ThemedReactContext
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.Timeline
import com.google.android.exoplayer2.Tracks
import com.google.android.exoplayer2.database.StandaloneDatabaseProvider
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil
import com.google.android.exoplayer2.metadata.Metadata
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.dash.DashUtil
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.trackselection.ExoTrackSelection
import com.google.android.exoplayer2.upstream.BandwidthMeter
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.DefaultAllocator
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.HttpDataSource
import com.google.android.exoplayer2.upstream.cache.CacheDataSource
import com.google.android.exoplayer2.upstream.cache.NoOpCacheEvictor
import com.google.android.exoplayer2.upstream.cache.SimpleCache
import com.google.android.exoplayer2.util.Util
import okhttp3.internal.wait
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.floor
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class ReactExoplayerView(
    private val themedReactContext: ThemedReactContext,
    config: ReactExoplayerConfig,
    private val cachePool: MutableList<CacheInPool>
) : FrameLayout(
    themedReactContext
), Player.Listener, BandwidthMeter.EventListener, BecomingNoisyListener,
    OnAudioFocusChangeListener {
    private val eventEmitter = VideoEventEmitter(themedReactContext)
    private val config: ReactExoplayerConfig
    private val bandwidthMeter: DefaultBandwidthMeter?
    private var exoPlayerView: ExoPlayerView? = null
    private var mediaDataSourceFactory: DataSource.Factory? = null
    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var playerNeedsSource = false
    private var resumeWindow = 0
    private var resumePosition: Long = 0
    private var loadVideoStarted = false
    private var isInBackground = false
    private var isPaused = false
    private var isBuffering = false
    private var muted = false
    private var hasAudioFocus = false
    private var rate = 1f
    private var audioVolume = 1f
    private var minLoadRetryCount = 3
    private var maxBitRate = 0
    private var seekTime = C.TIME_UNSET
    private var isUsingContentResolution = false
    private var selectTrackWhenReady = false
    private var minBufferMs = DefaultLoadControl.DEFAULT_MIN_BUFFER_MS
    private var maxBufferMs = DefaultLoadControl.DEFAULT_MAX_BUFFER_MS
    private var bufferForPlaybackMs = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS
    private var bufferForPlaybackAfterRebufferMs =
        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
    private var maxHeapAllocationPercent = DEFAULT_MAX_HEAP_ALLOCATION_PERCENT
    private var minBackBufferMemoryReservePercent = DEFAULT_MIN_BACK_BUFFER_MEMORY_RESERVE
    private var minBufferMemoryReservePercent = DEFAULT_MIN_BUFFER_MEMORY_RESERVE

    private var lifecycleEventListener: LifecycleEventListener? = null

    // Props from React
    private var backBufferDurationMs = DefaultLoadControl.DEFAULT_BACK_BUFFER_DURATION_MS
    private var srcUri: Uri? = null
    private var extension: String? = null
    private var repeat = false
    private var disableFocus = false
    private var focusable = true
    private var disableBuffering = false
    private var contentStartTime = -1L
    private var disableDisconnectError = false
    private var preventsDisplaySleepDuringVideoPlayback = true
    private var mProgressUpdateInterval = 250.0f
    private var playInBackground = false
    private var requestHeaders: Map<String?, String?>? = null
    private var mReportBandwidth = false
    private val audioManager: AudioManager
    private val audioBecomingNoisyReceiver: AudioBecomingNoisyReceiver

    // store last progress event values to avoid sending unnecessary messages
    private var lastPos: Long = -1
    private var lastBufferDuration: Long = -1
    private var lastDuration: Long = -1
    private val progressHandler: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                SHOW_PROGRESS -> if (player != null) {
                    val pos = player!!.currentPosition
                    val bufferedDuration = player!!.bufferedPercentage * player!!.duration / 100
                    val duration = player!!.duration
                    if (lastPos != pos || lastBufferDuration != bufferedDuration || lastDuration != duration) {
                        lastPos = pos
                        lastBufferDuration = bufferedDuration
                        lastDuration = duration
                        eventEmitter.progressChanged(
                            pos.toDouble(),
                            bufferedDuration.toDouble(),
                            player!!.duration.toDouble(),
                            getPositionInFirstPeriodMsForCurrentWindow(pos)
                        )
                    }
                    sendMessageDelayed(
                        obtainMessage(SHOW_PROGRESS), mProgressUpdateInterval.roundToInt().toLong()
                    )
                }
            }
        }
    }

    fun getPositionInFirstPeriodMsForCurrentWindow(currentPosition: Long): Double {
        val window = Timeline.Window()
        if (!player!!.currentTimeline.isEmpty) {
            player!!.currentTimeline.getWindow(player!!.currentMediaItemIndex, window)
        }
        return (window.windowStartTimeMs + currentPosition).toDouble()
    }

    init {
        this.config = config
        bandwidthMeter = config.bandwidthMeter
        createViews()
        audioManager = themedReactContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioBecomingNoisyReceiver = AudioBecomingNoisyReceiver(themedReactContext)

        lifecycleEventListener = object : LifecycleEventListener {
            override fun onHostResume() {
                initializePlayer()

                if (!playInBackground || !isInBackground) {
                    setPlayWhenReady(!isPaused)
                }
                isInBackground = false
            }

            override fun onHostPause() {
                isInBackground = true
                if (playInBackground) {
                    return
                }
                stopPlayback()
                //setPlayWhenReady(false)
            }

            override fun onHostDestroy() {
                stopPlayback()
                themedReactContext.removeLifecycleEventListener(this)
            }
        }

        themedReactContext.addLifecycleEventListener(lifecycleEventListener)
    }

    override fun setId(id: Int) {
        super.setId(id)
        eventEmitter.setViewId(id)
    }

    @SuppressLint("InflateParams")
    private fun createViews() {
        clearResumePosition()
        mediaDataSourceFactory = buildDataSourceFactory(true)
        if (CookieHandler.getDefault() !== DEFAULT_COOKIE_MANAGER) {
            CookieHandler.setDefault(DEFAULT_COOKIE_MANAGER)
        }

        val layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

        //aspectRatioLayout = AspectRatioFrameLayout(themedReactContext)
        //aspectRatioLayout!!.layoutParams = layoutParams
        //exoPlayerView = StyledPlayerView(context)
        //exoPlayerView = LayoutInflater.from(themedReactContext)
        //    .inflate(R.layout.styled_player_view, null) as StyledPlayerView
        //exoPlayerView!!.layoutParams = layoutParams
        //exoPlayerView!!.setKeepContentOnPlayerReset(true)
        //exoPlayerView!!.useController = false
        //exoPlayerView!!.hideController()
        //exoPlayerView!!.isFocusable = focusable
        //exoPlayerView!!.setShutterBackgroundColor(0)
        //exoPlayerView!!.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
        //exoPlayerView.setShowShuffleButton(true);
        //exoPlayerView.setShowBuffering(0);

        exoPlayerView = ExoPlayerView(themedReactContext)
        exoPlayerView!!.layoutParams = layoutParams

        addView(exoPlayerView, 0, layoutParams)

        exoPlayerView!!.isFocusable = focusable
        //aspectRatioLayout!!.addView(exoPlayerView, 0, layoutParams)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        initializePlayer()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()/* We want to be able to continue playing audio when switching tabs.
         * Leave this here in case it causes issues.
         */
        //stopPlayback()
    }

    fun cleanUpResources() {
        lifecycleEventListener?.let {
            themedReactContext.removeLifecycleEventListener(it)
        }
        stopPlayback()
    }

    //BandwidthMeter.EventListener implementation
    override fun onBandwidthSample(elapsedMs: Int, bytes: Long, bitrate: Long) {
        if (mReportBandwidth) {
            if (player == null) {
                eventEmitter.bandwidthReport(bitrate.toDouble(), 0, 0, "-1")
            } else {
                val videoFormat = player!!.videoFormat
                val width = videoFormat?.width ?: 0
                val height = videoFormat?.height ?: 0
                val trackId = if (videoFormat != null) videoFormat.id else "-1"
                eventEmitter.bandwidthReport(bitrate.toDouble(), height, width, trackId)
            }
        }
    }

    /**
     * Update the layout
     * @param view  view needs to update layout
     *
     * This is a workaround for the open bug in react-native: https://github.com/facebook/react-native/issues/17968
     */
    private fun reLayout(view: View?) {
        if (view == null) return
        view.measure(
            MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        )
        view.layout(view.left, view.top, view.measuredWidth, view.measuredHeight)
    }

    private inner class RNVLoadControl(
        allocator: DefaultAllocator?,
        minBufferMs: Int,
        maxBufferMs: Int,
        bufferForPlaybackMs: Int,
        bufferForPlaybackAfterRebufferMs: Int,
        targetBufferBytes: Int,
        prioritizeTimeOverSizeThresholds: Boolean,
        backBufferDurationMs: Int,
        retainBackBufferFromKeyframe: Boolean
    ) : DefaultLoadControl(
        allocator!!,
        minBufferMs,
        maxBufferMs,
        bufferForPlaybackMs,
        bufferForPlaybackAfterRebufferMs,
        targetBufferBytes,
        prioritizeTimeOverSizeThresholds,
        backBufferDurationMs,
        retainBackBufferFromKeyframe
    ) {
        private var availableHeapInBytes = 0
        private val runtime: Runtime

        init {
            runtime = Runtime.getRuntime()
            val activityManager =
                themedReactContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            availableHeapInBytes =
                floor(activityManager.memoryClass * maxHeapAllocationPercent * 1024 * 1024).toInt()
        }

        override fun shouldContinueLoading(
            playbackPositionUs: Long, bufferedDurationUs: Long, playbackSpeed: Float
        ): Boolean {
            if (disableBuffering) {
                return false
            }
            val loadedBytes = allocator.totalBytesAllocated
            val isHeapReached = availableHeapInBytes in 1..loadedBytes
            if (isHeapReached) {
                return false
            }
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val freeMemory = runtime.maxMemory() - usedMemory
            val reserveMemory = minBufferMemoryReservePercent.toLong() * runtime.maxMemory()
            val bufferedMs = bufferedDurationUs / 1000L
            if (reserveMemory > freeMemory && bufferedMs > 2000) {
                // We don't have enough memory in reserve so we stop buffering to allow other components to use it instead
                return false
            }
            if (runtime.freeMemory() == 0L) {
                Log.w("ExoPlayer Warning", "Free memory reached 0, forcing garbage collection")
                runtime.gc()
                return false
            }
            return super.shouldContinueLoading(
                playbackPositionUs, bufferedDurationUs, playbackSpeed
            )
        }
    }

    private fun initializePlayer() {
        val self = this
        val activity = themedReactContext.currentActivity
        // This ensures all props have been settled, to avoid async racing conditions.
        Handler(Looper.getMainLooper()).postDelayed({
                                                        try {
                                                            if (player == null) {
                                                                // Initialize core configuration and listeners
                                                                initializePlayerCore(self)
                                                            }

                                                            if (playerNeedsSource && srcUri != null) {
                                                                //exoPlayerView!!.invalidateAspectRatio()
                                                                if (activity != null) {
                                                                    try {
                                                                        // Source initialization must run on the main thread
                                                                        initializePlayerSource(self)
                                                                    } catch (ex: Exception) {
                                                                        self.playerNeedsSource =
                                                                            true
                                                                        Log.e(
                                                                            "ExoPlayer Exception",
                                                                            "Failed to initialize Player!"
                                                                        )
                                                                        Log.e(
                                                                            "ExoPlayer Exception",
                                                                            ex.toString()
                                                                        )
                                                                        self.eventEmitter.error(
                                                                            ex.toString(),
                                                                            ex,
                                                                            "1001"
                                                                        )
                                                                    }
                                                                } else {
                                                                    Log.e(
                                                                        "ExoPlayer Exception",
                                                                        "Failed to initialize Player!"
                                                                    )
                                                                    eventEmitter.error(
                                                                        "Failed to initialize Player!",
                                                                        Exception("Current Activity is null!"),
                                                                        "1001"
                                                                    )
                                                                }
                                                            } else if (srcUri != null) {
                                                                initializePlayerSource(self)
                                                            }
                                                        } catch (ex: Exception) {
                                                            self.playerNeedsSource = true
                                                            Log.e(
                                                                "ExoPlayer Exception",
                                                                "Failed to initialize Player!"
                                                            )
                                                            Log.e(
                                                                "ExoPlayer Exception", ex.toString()
                                                            )
                                                            eventEmitter.error(
                                                                ex.toString(), ex, "1001"
                                                            )
                                                        }
                                                    }, 1)
    }

    private fun initializePlayerCore(self: ReactExoplayerView) {
        val videoTrackSelectionFactory: ExoTrackSelection.Factory = AdaptiveTrackSelection.Factory()
        self.trackSelector = DefaultTrackSelector(context, videoTrackSelectionFactory)
        self.trackSelector!!.setParameters(
            trackSelector!!.buildUponParameters()
                .setMaxVideoBitrate(if (maxBitRate == 0) Int.MAX_VALUE else maxBitRate)
        )
        val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        val loadControl = RNVLoadControl(
            allocator,
            minBufferMs,
            maxBufferMs,
            bufferForPlaybackMs,
            bufferForPlaybackAfterRebufferMs,
            -1,
            true,
            backBufferDurationMs,
            DefaultLoadControl.DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME
        )
        val renderersFactory =
            DefaultRenderersFactory(context).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(context, renderersFactory).setBandwidthMeter(bandwidthMeter!!)
            .setLoadControl(loadControl).build()
        player!!.addListener(self)
        exoPlayerView!!.setPlayer(player)
        audioBecomingNoisyReceiver.setListener(self)
        bandwidthMeter.addEventListener(Handler(), self)
        setPlayWhenReady(!isPaused)
        playerNeedsSource = true
        val params = PlaybackParameters(rate, 1f)
        player!!.playbackParameters = params
    }

    private fun initializePlayerSource(self: ReactExoplayerView) {
        //val mediaSource = buildMediaSource(self.srcUri, self.extension)

        // wait for player to be set
        while (player == null) {
            try {
                wait()
            } catch (ex: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.e("ExoPlayer Exception", ex.toString())
            }
        }
        val haveResumePosition = resumeWindow != C.INDEX_UNSET
        if (haveResumePosition) {
            player!!.seekTo(resumeWindow, resumePosition)
        }

        val databaseProvider = StandaloneDatabaseProvider(context)
        context.getExternalFilesDir("video_cache/${srcUri?.lastPathSegment}")
            ?.let { videoCacheFile ->
                var cacheInPool = cachePool.find { it.path == videoCacheFile.absolutePath }
                if (cacheInPool == null) {
                    val simpleCache =
                        SimpleCache(videoCacheFile, NoOpCacheEvictor(), databaseProvider)
                    cacheInPool = CacheInPool(videoCacheFile.absolutePath, simpleCache)
                    cachePool.add(cacheInPool)
                }

                val defaultDataSourceFactory = DefaultDataSource.Factory(context)
                val cacheDataSourceFactory = CacheDataSource.Factory().setCache(cacheInPool.cache)
                    .setUpstreamDataSourceFactory(defaultDataSourceFactory)

                val cacheDataSource = cacheDataSourceFactory.createDataSource()
                val dataSpec = DataSpec.Builder().setUri(srcUri!!).build()

                //val cacheWriter = CacheWriter(cacheDataSource, dataSpec, null, null)
                //val cacheKey = cacheDataSource.cacheKeyFactory.buildCacheKey(dataSpec)

                //val cachedBytes = cacheDataSource.cache.getCachedBytes(cacheKey, 0, Long.MAX_VALUE)
                //Log.d("mine", "cachedBytes ${cachedBytes.toString()}")

                //val lifecycle = findViewTreeLifecycleOwner()
                //lifecycle?.let {
                //    it.lifecycleScope.launch(Dispatchers.IO) {
                //        cacheWriter.cache()
                //    }
                //}

                val mediaItem = MediaItem.fromUri(srcUri!!)
                val mediaSource = ProgressiveMediaSource.Factory(cacheDataSourceFactory)
                    .setLoadErrorHandlingPolicy(
                        config.buildLoadErrorHandlingPolicy(minLoadRetryCount)
                    ).createMediaSource(mediaItem)

                player!!.setMediaSource(mediaSource, !haveResumePosition)
                player!!.prepare()
            }


        //player!!.setMediaSource(mediaSource, !haveResumePosition)
        //player!!.prepare()
        playerNeedsSource = false
        reLayout(exoPlayerView)
        eventEmitter.loadStart()
        loadVideoStarted = true
        finishPlayerInitialization()
    }

    private fun finishPlayerInitialization() {
        applyModifiers()
    }

    private fun buildMediaSource(uri: Uri?, overrideExtension: String?): MediaSource {
        checkNotNull(uri) { "Invalid video uri" }
        val type =
            Util.inferContentType((if (!TextUtils.isEmpty(overrideExtension)) ".$overrideExtension" else uri.lastPathSegment)!!)
        config.disableDisconnectError = disableDisconnectError
        val mediaItem = MediaItem.Builder().setUri(uri).build()
        return when (type) {
            C.TYPE_SS -> SsMediaSource.Factory(
                DefaultSsChunkSource.Factory(mediaDataSourceFactory!!),
                buildDataSourceFactory(false)
            ).setLoadErrorHandlingPolicy(
                config.buildLoadErrorHandlingPolicy(minLoadRetryCount)
            ).createMediaSource(mediaItem)

            C.TYPE_DASH -> DashMediaSource.Factory(
                DefaultDashChunkSource.Factory(mediaDataSourceFactory!!),
                buildDataSourceFactory(false)
            ).setLoadErrorHandlingPolicy(
                config.buildLoadErrorHandlingPolicy(minLoadRetryCount)
            ).createMediaSource(mediaItem)

            C.TYPE_HLS -> HlsMediaSource.Factory(
                mediaDataSourceFactory!!
            ).setLoadErrorHandlingPolicy(
                config.buildLoadErrorHandlingPolicy(minLoadRetryCount)
            ).createMediaSource(mediaItem)

            C.TYPE_OTHER -> ProgressiveMediaSource.Factory(
                mediaDataSourceFactory!!
            ).setLoadErrorHandlingPolicy(
                config.buildLoadErrorHandlingPolicy(minLoadRetryCount)
            ).createMediaSource(mediaItem)

            else -> {
                throw IllegalStateException("Unsupported type: $type")
            }
        }
    }

    private fun releasePlayer() {
        if (player != null) {
            //updateResumePosition()
            clearResumePosition()
            player!!.release()
            player!!.removeListener(this)
            player = null
        }
        progressHandler.removeMessages(SHOW_PROGRESS)
        audioBecomingNoisyReceiver.removeListener()
        bandwidthMeter!!.removeEventListener(this)
    }

    private fun requestAudioFocus(): Boolean {
        if (disableFocus || srcUri == null || hasAudioFocus) {
            return true
        }
        val result = audioManager.requestAudioFocus(
            this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
        )
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun setPlayWhenReady(playWhenReady: Boolean) {
        if (player == null) {
            return
        }
        if (playWhenReady) {
            hasAudioFocus = requestAudioFocus()
            if (hasAudioFocus) {
                player!!.playWhenReady = true
            }
        } else {
            // ensure playback is not ENDED, else it will trigger another ended event
            if (player!!.playbackState != Player.STATE_ENDED) {
                player!!.playWhenReady = false
            }
        }
    }

    private fun startPlayback() {
        if (player != null) {
            when (player!!.playbackState) {
                Player.STATE_IDLE, Player.STATE_ENDED -> initializePlayer()
                Player.STATE_BUFFERING, Player.STATE_READY -> if (!player!!.playWhenReady) {
                    setPlayWhenReady(true)
                }

                else -> {}
            }
        } else {
            initializePlayer()
        }
        if (!disableFocus) {
            keepScreenOn = preventsDisplaySleepDuringVideoPlayback
        }
    }

    private fun pausePlayback() {
        if (player != null) {
            if (player!!.playWhenReady) {
                setPlayWhenReady(false)
            }
        }
        keepScreenOn = false
    }

    private fun stopPlayback() {
        onStopPlayback()
        releasePlayer()
    }

    private fun onStopPlayback() {
        audioManager.abandonAudioFocus(this)
    }

    private fun updateResumePosition() {
        resumeWindow = player!!.currentMediaItemIndex
        resumePosition = if (player!!.isCurrentMediaItemSeekable) Math.max(
            0, player!!.currentPosition
        ) else C.TIME_UNSET
    }

    private fun clearResumePosition() {
        resumeWindow = C.INDEX_UNSET
        resumePosition = C.TIME_UNSET
    }

    /**
     * Returns a new DataSource factory.
     *
     * @param useBandwidthMeter Whether to set [.bandwidthMeter] as a listener to the new
     * DataSource factory.
     * @return A new DataSource factory.
     */
    private fun buildDataSourceFactory(useBandwidthMeter: Boolean): DataSource.Factory? {
        return DataSourceUtil.getDefaultDataSourceFactory(
            themedReactContext, if (useBandwidthMeter) bandwidthMeter else null, requestHeaders
        )
    }

    /**
     * Returns a new HttpDataSource factory.
     *
     * @param useBandwidthMeter Whether to set [.bandwidthMeter] as a listener to the new
     * DataSource factory.
     * @return A new HttpDataSource factory.
     */
    private fun buildHttpDataSourceFactory(useBandwidthMeter: Boolean): HttpDataSource.Factory? {
        return DataSourceUtil.getDefaultHttpDataSourceFactory(
            themedReactContext, if (useBandwidthMeter) bandwidthMeter else null, requestHeaders
        )
    }

    // AudioManager.OnAudioFocusChangeListener implementation
    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                eventEmitter.audioFocusChanged(false)
                pausePlayback()
                audioManager.abandonAudioFocus(this)
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> eventEmitter.audioFocusChanged(false)
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                eventEmitter.audioFocusChanged(true)
            }

            else -> {}
        }
        if (player != null) {
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                // Lower the volume
                if (!muted) {
                    player!!.volume = audioVolume * 0.8f
                }
            } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                // Raise it back to normal
                if (!muted) {
                    player!!.volume = audioVolume * 1
                }
            }
        }
    }

    // AudioBecomingNoisyListener implementation
    override fun onAudioBecomingNoisy() {
        eventEmitter.audioBecomingNoisy()
    }

    // Player.Listener implementation
    override fun onIsLoadingChanged(isLoading: Boolean) {
        // Do nothing.
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) || events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)) {
            val playbackState = player.playbackState
            val playWhenReady = player.playWhenReady
            var text = "onStateChanged: playWhenReady=$playWhenReady, playbackState="
            eventEmitter.playbackRateChange(if (playWhenReady && playbackState == ExoPlayer.STATE_READY) 1.0f else 0.0f)
            when (playbackState) {
                Player.STATE_IDLE -> {
                    text += "idle"
                    eventEmitter.idle()
                    clearProgressMessageHandler()
                    if (!player.playWhenReady) {
                        keepScreenOn = false
                    }
                }

                Player.STATE_BUFFERING -> {
                    text += "buffering"
                    onBuffering(true)
                    clearProgressMessageHandler()
                    keepScreenOn = preventsDisplaySleepDuringVideoPlayback
                }

                Player.STATE_READY -> {
                    text += "ready"
                    eventEmitter.ready()
                    onBuffering(false)
                    startProgressHandler()
                    videoLoaded()
                    if (selectTrackWhenReady && isUsingContentResolution) {
                        selectTrackWhenReady = false
                    }
                    keepScreenOn = preventsDisplaySleepDuringVideoPlayback
                }

                Player.STATE_ENDED -> {
                    text += "ended"
                    eventEmitter.end()
                    onStopPlayback()
                    keepScreenOn = false
                }

                else -> text += "unknown"
            }
        }
    }

    private fun startProgressHandler() {
        progressHandler.sendEmptyMessage(SHOW_PROGRESS)
    }

    /*
        The progress message handler will duplicate recursions of the onProgressMessage handler
        on change of player state from any state to STATE_READY with playWhenReady is true (when
        the video is not paused). This clears all existing messages.
     */
    private fun clearProgressMessageHandler() {
        progressHandler.removeMessages(SHOW_PROGRESS)
    }

    private fun videoLoaded() {
        if (loadVideoStarted) {
            loadVideoStarted = false
            val videoFormat = player!!.videoFormat
            val width = videoFormat?.width ?: 0
            val height = videoFormat?.height ?: 0
            val trackId = if (videoFormat != null) videoFormat.id else "-1"

            // Properties that must be accessed on the main thread
            val duration = player!!.duration
            val currentPosition = player!!.currentPosition
            val trackRendererIndex = getTrackRendererIndex(C.TRACK_TYPE_VIDEO)
            val es = Executors.newSingleThreadExecutor()
            es.execute { // To prevent ANRs caused by getVideoTrackInfo we run this on a different thread and notify the player only when we're done
                eventEmitter.load(
                    duration.toDouble(),
                    currentPosition.toDouble(),
                    width,
                    height,
                    getVideoTrackInfo(trackRendererIndex),
                    trackId
                )
            }
        }
    }

    private fun getVideoTrackInfo(trackRendererIndex: Int): WritableArray {
        if (contentStartTime != -1L) {
            val contentVideoTracks = videoTrackInfoFromManifest
            if (contentVideoTracks != null) {
                isUsingContentResolution = true
                return contentVideoTracks
            }
        }
        val videoTracks = Arguments.createArray()
        val info = trackSelector!!.currentMappedTrackInfo
        if (info == null || trackRendererIndex == C.INDEX_UNSET) {
            return videoTracks
        }
        val groups = info.getTrackGroups(trackRendererIndex)
        for (i in 0 until groups.length) {
            val group = groups[i]
            for (trackIndex in 0 until group.length) {
                val format = group.getFormat(trackIndex)
                if (isFormatSupported(format)) {
                    val videoTrack = Arguments.createMap()
                    videoTrack.putInt(
                        "width", if (format.width == Format.NO_VALUE) 0 else format.width
                    )
                    videoTrack.putInt(
                        "height", if (format.height == Format.NO_VALUE) 0 else format.height
                    )
                    videoTrack.putInt(
                        "bitrate", if (format.bitrate == Format.NO_VALUE) 0 else format.bitrate
                    )
                    videoTrack.putString("codecs", if (format.codecs != null) format.codecs else "")
                    videoTrack.putString(
                        "trackId", if (format.id == null) trackIndex.toString() else format.id
                    )
                    videoTracks.pushMap(videoTrack)
                }
            }
        }
        return videoTracks
    }

    private val videoTrackInfoFromManifest: WritableArray?
        private get() = getVideoTrackInfoFromManifest(0)

    // We need retry count to in case where manifest request fails from poor network conditions
    private fun getVideoTrackInfoFromManifest(maxRetryCount: Int): WritableArray? {
        var retryCount = maxRetryCount
        val es = Executors.newSingleThreadExecutor()
        val dataSource = mediaDataSourceFactory!!.createDataSource()
        val sourceUri = srcUri
        val startTime = contentStartTime * 1000 - 100 // s -> ms with 100ms offset
        val result = es.submit(object : Callable<WritableArray?> {
            var ds = dataSource
            var uri = sourceUri
            var startTimeUs = startTime * 1000 // ms -> us

            @Throws(Exception::class)
            override fun call(): WritableArray? {
                val videoTracks = Arguments.createArray()
                try {
                    val manifest = DashUtil.loadManifest(ds, uri!!)
                    val periodCount = manifest.periodCount
                    for (i in 0 until periodCount) {
                        val period = manifest.getPeriod(i)
                        for (adaptationIndex in period.adaptationSets.indices) {
                            val adaptation = period.adaptationSets[adaptationIndex]
                            if (adaptation.type != C.TRACK_TYPE_VIDEO) {
                                continue
                            }
                            var hasFoundContentPeriod = false
                            for (representationIndex in adaptation.representations.indices) {
                                val representation = adaptation.representations[representationIndex]
                                val format = representation.format
                                if (representation.presentationTimeOffsetUs <= startTimeUs) {
                                    break
                                }
                                hasFoundContentPeriod = true
                                val videoTrack = Arguments.createMap()
                                videoTrack.putInt(
                                    "width",
                                    if (format.width == Format.NO_VALUE) 0 else format.width
                                )
                                videoTrack.putInt(
                                    "height",
                                    if (format.height == Format.NO_VALUE) 0 else format.height
                                )
                                videoTrack.putInt(
                                    "bitrate",
                                    if (format.bitrate == Format.NO_VALUE) 0 else format.bitrate
                                )
                                videoTrack.putString(
                                    "codecs", if (format.codecs != null) format.codecs else ""
                                )
                                videoTrack.putString(
                                    "trackId",
                                    if (format.id == null) representationIndex.toString() else format.id
                                )
                                if (isFormatSupported(format)) {
                                    videoTracks.pushMap(videoTrack)
                                }
                            }
                            if (hasFoundContentPeriod) {
                                return videoTracks
                            }
                        }
                    }
                } catch (_: Exception) {
                }
                return null
            }
        })
        try {
            val results = result[3000, TimeUnit.MILLISECONDS]
            if (results == null && retryCount < 1) {
                return getVideoTrackInfoFromManifest(++retryCount)
            }
            es.shutdown()
            return results
        } catch (_: Exception) {
        }
        return null
    }

    private fun onBuffering(buffering: Boolean) {
        if (isBuffering == buffering) {
            return
        }
        isBuffering = buffering
        if (buffering) {
            eventEmitter.buffering(true)
        } else {
            eventEmitter.buffering(false)
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int
    ) {
        if (playerNeedsSource) {
            // This will only occur if the user has performed a seek whilst in the error state. Update the
            // resume position so that if the user then retries, playback will resume from the position to
            // which they seeked.
            updateResumePosition()
        }
        if (isUsingContentResolution) {
            // Discontinuity events might have a different track list so we update the selected track
            selectTrackWhenReady = true
        }
        // When repeat is turned on, reaching the end of the video will not cause a state change
        // so we need to explicitly detect it.
        if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION && player!!.repeatMode == Player.REPEAT_MODE_ONE) {
            eventEmitter.end()
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        // Do nothing.
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY && seekTime != C.TIME_UNSET) {
            eventEmitter.seek(player!!.currentPosition, seekTime)
            seekTime = C.TIME_UNSET
        }
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        // Do nothing.
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        // Do nothing.
    }

    override fun onTracksChanged(tracks: Tracks) {
        // Do nothing.
    }

    override fun onPlaybackParametersChanged(params: PlaybackParameters) {
        eventEmitter.playbackRateChange(params.speed)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        eventEmitter.playbackStateChanged(isPlaying)
    }

    override fun onPlayerError(e: PlaybackException) {
        if (e == null) {
            return
        }

        val errorString = "ExoPlaybackException: " + PlaybackException.getErrorCodeName(e.errorCode)
        val errorCode = "2" + e.errorCode.toString()
        val needsReInitialization = false
        eventEmitter.error(errorString, e, errorCode)
        playerNeedsSource = true
        if (isBehindLiveWindow(e)) {
            clearResumePosition()
            initializePlayer()
        } else {
            updateResumePosition()
            if (needsReInitialization) {
                initializePlayer()
            }
        }
    }

    fun getTrackRendererIndex(trackType: Int): Int {
        if (player != null) {
            val rendererCount = player!!.rendererCount
            for (rendererIndex in 0 until rendererCount) {
                if (player!!.getRendererType(rendererIndex) == trackType) {
                    return rendererIndex
                }
            }
        }
        return C.INDEX_UNSET
    }

    override fun onMetadata(metadata: Metadata) {
        eventEmitter.timedMetadata(metadata)
    }

    // ReactExoplayerViewManager public api
    fun setSrc(uri: Uri?, extension: String?, headers: Map<String?, String?>?) {
        if (uri != null) {
            val isSourceEqual = uri == srcUri
            srcUri = uri
            this.extension = extension
            requestHeaders = headers
            mediaDataSourceFactory = DataSourceUtil.getDefaultDataSourceFactory(
                themedReactContext, bandwidthMeter, requestHeaders
            )
            if (!isSourceEqual) {
                reloadSource()
            }
        }
    }

    fun clearSrc() {
        if (srcUri != null) {
            player!!.stop()
            player!!.clearMediaItems()
            srcUri = null
            this.extension = null
            requestHeaders = null
            mediaDataSourceFactory = null
            clearResumePosition()
        }
    }

    fun setProgressUpdateInterval(progressUpdateInterval: Float) {
        mProgressUpdateInterval = progressUpdateInterval
    }

    fun setReportBandwidth(reportBandwidth: Boolean) {
        mReportBandwidth = reportBandwidth
    }

    fun setRawSrc(uri: Uri?, extension: String?) {
        if (uri != null) {
            val isSourceEqual = uri == srcUri
            srcUri = uri
            this.extension = extension
            mediaDataSourceFactory = buildDataSourceFactory(true)
            if (!isSourceEqual) {
                reloadSource()
            }
        }
    }

    private fun reloadSource() {
        playerNeedsSource = true
        initializePlayer()
    }

    fun setResizeModeModifier(@ResizeMode.Mode resizeMode: Int) {
        exoPlayerView!!.setResizeMode(resizeMode)
    }

    private fun applyModifiers() {
        setRepeatModifier(repeat)
        setMutedModifier(muted)
    }

    fun setRepeatModifier(repeat: Boolean) {
        if (player != null) {
            if (repeat) {
                player!!.repeatMode = Player.REPEAT_MODE_ONE
            } else {
                player!!.repeatMode = Player.REPEAT_MODE_OFF
            }
        }
        this.repeat = repeat
    }

    fun setPreventsDisplaySleepDuringVideoPlayback(preventsDisplaySleepDuringVideoPlayback: Boolean) {
        this.preventsDisplaySleepDuringVideoPlayback = preventsDisplaySleepDuringVideoPlayback
    }

    private fun isFormatSupported(format: Format): Boolean {
        val width = if (format.width == Format.NO_VALUE) 0 else format.width
        val height = if (format.height == Format.NO_VALUE) 0 else format.height
        val frameRate: Float =
            if (format.frameRate == Format.NO_VALUE.toFloat()) 0f else format.frameRate
        val mimeType = format.sampleMimeType ?: return true
        val isSupported: Boolean = try {
            val codecInfo = MediaCodecUtil.getDecoderInfo(mimeType, false, false)
            codecInfo!!.isVideoSizeAndRateSupportedV21(width, height, frameRate.toDouble())
        } catch (e: Exception) {
            // Failed to get decoder info - assume it is supported
            true
        }

        return isSupported
    }

    fun setPausedModifier(paused: Boolean) {
        isPaused = paused
        if (player != null) {
            if (!paused) {
                startPlayback()
            } else {
                pausePlayback()
            }
        }
    }

    fun setMutedModifier(muted: Boolean) {
        this.muted = muted
        if (player != null) {
            player!!.volume = if (muted) 0f else audioVolume
        }
    }

    fun setVolumeModifier(volume: Float) {
        audioVolume = volume
        if (player != null) {
            player!!.volume = audioVolume
        }
    }

    fun seekTo(positionMs: Long) {
        if (player != null) {
            player!!.seekTo(positionMs)
            eventEmitter.seek(player!!.currentPosition, positionMs)
        }
    }

    fun setRateModifier(newRate: Float) {
        rate = newRate
        if (player != null) {
            val params = PlaybackParameters(rate, 1f)
            player!!.playbackParameters = params
        }
    }

    fun setMaxBitRateModifier(newMaxBitRate: Int) {
        maxBitRate = newMaxBitRate
        if (player != null) {
            trackSelector!!.setParameters(
                trackSelector!!.buildUponParameters()
                    .setMaxVideoBitrate(if (maxBitRate == 0) Int.MAX_VALUE else maxBitRate)
            )
        }
    }

    fun setMinLoadRetryCountModifier(newMinLoadRetryCount: Int) {
        minLoadRetryCount = newMinLoadRetryCount
        releasePlayer()
        initializePlayer()
    }

    fun setPlayInBackground(playInBackground: Boolean) {
        this.playInBackground = playInBackground
    }

    fun setDisableFocus(disableFocus: Boolean) {
        this.disableFocus = disableFocus
    }

    override fun setFocusable(focusable: Boolean) {
        this.focusable = focusable
        exoPlayerView!!.isFocusable = this.focusable
    }

    fun setBackBufferDurationMs(backBufferDurationMs: Int) {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val freeMemory = runtime.maxMemory() - usedMemory
        val reserveMemory = minBackBufferMemoryReservePercent.toLong() * runtime.maxMemory()
        if (reserveMemory > freeMemory) {
            // We don't have enough memory in reserve so we will
            Log.w(
                "ExoPlayer Warning",
                "Not enough reserve memory, setting back buffer to 0ms to reduce memory pressure!"
            )
            this.backBufferDurationMs = 0
            return
        }
        this.backBufferDurationMs = backBufferDurationMs
    }

    fun setContentStartTime(contentStartTime: Int) {
        this.contentStartTime = contentStartTime.toLong()
    }

    fun setDisableBuffering(disableBuffering: Boolean) {
        this.disableBuffering = disableBuffering
    }

    fun setDisableDisconnectError(disableDisconnectError: Boolean) {
        this.disableDisconnectError = disableDisconnectError
    }

    fun setUseTextureView(useTextureView: Boolean) {
        exoPlayerView!!.setUseTextureView(useTextureView)
    }

    fun useSecureView(useSecureView: Boolean) {
        exoPlayerView!!.useSecureView(useSecureView)
    }

    fun setHideShutterView(hideShutterView: Boolean) {
        exoPlayerView!!.setHideShutterView(hideShutterView)
    }

    fun setBufferConfig(
        newMinBufferMs: Int,
        newMaxBufferMs: Int,
        newBufferForPlaybackMs: Int,
        newBufferForPlaybackAfterRebufferMs: Int,
        newMaxHeapAllocationPercent: Double,
        newMinBackBufferMemoryReservePercent: Double,
        newMinBufferMemoryReservePercent: Double
    ) {
        minBufferMs = newMinBufferMs
        maxBufferMs = newMaxBufferMs
        bufferForPlaybackMs = newBufferForPlaybackMs
        bufferForPlaybackAfterRebufferMs = newBufferForPlaybackAfterRebufferMs
        maxHeapAllocationPercent = newMaxHeapAllocationPercent
        minBackBufferMemoryReservePercent = newMinBackBufferMemoryReservePercent
        minBufferMemoryReservePercent = newMinBufferMemoryReservePercent
        releasePlayer()
        initializePlayer()
    }

    companion object {
        const val DEFAULT_MAX_HEAP_ALLOCATION_PERCENT = 1.0
        const val DEFAULT_MIN_BACK_BUFFER_MEMORY_RESERVE = 0.0
        const val DEFAULT_MIN_BUFFER_MEMORY_RESERVE = 0.0
        private const val TAG = "ReactExoplayerView"
        private var DEFAULT_COOKIE_MANAGER: CookieManager? = null
        private const val SHOW_PROGRESS = 1

        init {
            DEFAULT_COOKIE_MANAGER = CookieManager()
            DEFAULT_COOKIE_MANAGER!!.setCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        }

        private fun isBehindLiveWindow(e: PlaybackException): Boolean {
            return e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
        }
    }
}
