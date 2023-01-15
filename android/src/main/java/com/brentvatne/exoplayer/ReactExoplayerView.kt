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
import android.view.accessibility.CaptioningManager
import android.widget.FrameLayout
import com.brentvatne.react.R
import com.brentvatne.receiver.AudioBecomingNoisyReceiver
import com.brentvatne.receiver.BecomingNoisyListener
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Dynamic
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableArray
import com.facebook.react.uimanager.ThemedReactContext
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.MediaItem.SubtitleConfiguration
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.Timeline
import com.google.android.exoplayer2.Tracks
import com.google.android.exoplayer2.drm.DefaultDrmSessionManager
import com.google.android.exoplayer2.drm.DefaultDrmSessionManagerProvider
import com.google.android.exoplayer2.drm.DrmSessionEventListener
import com.google.android.exoplayer2.drm.DrmSessionManager
import com.google.android.exoplayer2.drm.DrmSessionManagerProvider
import com.google.android.exoplayer2.drm.FrameworkMediaDrm
import com.google.android.exoplayer2.drm.HttpMediaDrmCallback
import com.google.android.exoplayer2.drm.UnsupportedDrmException
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil
import com.google.android.exoplayer2.metadata.Metadata
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.MergingMediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.SingleSampleMediaSource
import com.google.android.exoplayer2.source.TrackGroupArray
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.dash.DashUtil
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.trackselection.ExoTrackSelection
import com.google.android.exoplayer2.trackselection.TrackSelectionOverride
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.exoplayer2.upstream.BandwidthMeter
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultAllocator
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter
import com.google.android.exoplayer2.upstream.HttpDataSource
import com.google.android.exoplayer2.util.Util
import okhttp3.internal.wait
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SuppressLint("ViewConstructor")
class ReactExoplayerView(private val themedReactContext: ThemedReactContext,
                         config: ReactExoplayerConfig
) : FrameLayout(
    themedReactContext
), Player.Listener, BandwidthMeter.EventListener, BecomingNoisyListener,
    OnAudioFocusChangeListener, DrmSessionEventListener {
    private val eventEmitter = VideoEventEmitter(themedReactContext)
    private val config: ReactExoplayerConfig
    private val bandwidthMeter: DefaultBandwidthMeter?
    private var exoPlayerView: StyledPlayerView? = null
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
    private var hasDrmFailed = false
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
    private var mainHandler: Handler? = null

    // Props from React
    private var backBufferDurationMs = DefaultLoadControl.DEFAULT_BACK_BUFFER_DURATION_MS
    private var srcUri: Uri? = null
    private var extension: String? = null
    private var repeat = false
    private var audioTrackType: String? = null
    private var audioTrackValue: Dynamic? = null
    private var videoTrackType: String? = null
    private var videoTrackValue: Dynamic? = null
    private var textTrackType: String? = null
    private var textTrackValue: Dynamic? = null
    private var textTracks: ReadableArray? = null
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
    private var drmUUID: UUID? = null
    private var drmLicenseUrl: String? = null
    private var drmLicenseHeader: Array<String?>? = null
    private val audioManager: AudioManager
    private val audioBecomingNoisyReceiver: AudioBecomingNoisyReceiver

    // store last progress event values to avoid sending unnecessary messages
    private var lastPos: Long = -1
    private var lastBufferDuration: Long = -1
    private var lastDuration: Long = -1
    private val progressHandler: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            var msg = msg
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
                    msg = obtainMessage(SHOW_PROGRESS)
                    sendMessageDelayed(msg, Math.round(mProgressUpdateInterval).toLong())
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
        //themedReactContext.addLifecycleEventListener(this@ReactExoplayerView)
        audioBecomingNoisyReceiver = AudioBecomingNoisyReceiver(themedReactContext)

        themedReactContext.addLifecycleEventListener(object : LifecycleEventListener {
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
        })
    }

    override fun setId(id: Int) {
        super.setId(id)
        eventEmitter.setViewId(id)
    }

    private fun createViews() {
        clearResumePosition()
        mediaDataSourceFactory = buildDataSourceFactory(true)
        if (CookieHandler.getDefault() !== DEFAULT_COOKIE_MANAGER) {
            CookieHandler.setDefault(DEFAULT_COOKIE_MANAGER)
        }
        val layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
        )
        exoPlayerView = StyledPlayerView(context)
        exoPlayerView!!.layoutParams = layoutParams
        exoPlayerView!!.setKeepContentOnPlayerReset(true)
        exoPlayerView!!.useController = false
        exoPlayerView!!.hideController()
        exoPlayerView!!.setShutterBackgroundColor(0)
        //exoPlayerView.setShowShuffleButton(true);
        //exoPlayerView.setShowBuffering(0);
        addView(exoPlayerView, 0, layoutParams)
        exoPlayerView!!.isFocusable = focusable
        mainHandler = Handler()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        initializePlayer()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()/* We want to be able to continue playing audio when switching tabs.
         * Leave this here in case it causes issues.
         */
        // stopPlayback();
    }

    fun cleanUpResources() {
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

    private inner class RNVLoadControl(allocator: DefaultAllocator?,
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
                Math.floor(activityManager.memoryClass * maxHeapAllocationPercent * 1024 * 1024)
                    .toInt()
        }

        override fun shouldContinueLoading(playbackPositionUs: Long,
                                           bufferedDurationUs: Long,
                                           playbackSpeed: Float
        ): Boolean {
            if (disableBuffering) {
                return false
            }
            val loadedBytes = allocator.totalBytesAllocated
            val isHeapReached = availableHeapInBytes > 0 && loadedBytes >= availableHeapInBytes
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
        Handler().postDelayed(Runnable {
            try {
                if (player == null) {
                    // Initialize core configuration and listeners
                    initializePlayerCore(self)
                }
                if (playerNeedsSource && srcUri != null) {
                    //exoPlayerView!!.invalidateAspectRatio()
                    // DRM session manager creation must be done on a different thread to prevent crashes so we start a new thread
                    val es = Executors.newSingleThreadExecutor()
                    es.execute {
                        // DRM initialization must run on a different thread
                        val drmSessionManager = initializePlayerDrm(self)
                        if (drmSessionManager == null && self.drmUUID != null) {
                            // Failed to intialize DRM session manager - cannot continue
                            Log.e(
                                "ExoPlayer Exception",
                                "Failed to initialize DRM Session Manager Framework!"
                            )
                            eventEmitter.error(
                                "Failed to initialize DRM Session Manager Framework!",
                                Exception("DRM Session Manager Framework failure!"),
                                "3003"
                            )
                            return@execute
                        }
                        if (activity == null) {
                            Log.e("ExoPlayer Exception", "Failed to initialize Player!")
                            eventEmitter.error(
                                "Failed to initialize Player!",
                                Exception("Current Activity is null!"),
                                "1001"
                            )
                            return@execute
                        }

                        // Initialize handler to run on the main thread
                        activity.runOnUiThread(Runnable {
                            try {
                                // Source initialization must run on the main thread
                                initializePlayerSource(self, drmSessionManager)
                            } catch (ex: Exception) {
                                self.playerNeedsSource = true
                                Log.e("ExoPlayer Exception", "Failed to initialize Player!")
                                Log.e("ExoPlayer Exception", ex.toString())
                                self.eventEmitter.error(ex.toString(), ex, "1001")
                            }
                        })
                    }
                } else if (srcUri != null) {
                    initializePlayerSource(self, null)
                }
            } catch (ex: Exception) {
                self.playerNeedsSource = true
                Log.e("ExoPlayer Exception", "Failed to initialize Player!")
                Log.e("ExoPlayer Exception", ex.toString())
                eventEmitter.error(ex.toString(), ex, "1001")
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
            DefaultRenderersFactory(context).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                .setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(context, renderersFactory)
            //.setTrackSelector(self.trackSelector!!)
            .setBandwidthMeter(bandwidthMeter!!)
            .setLoadControl(loadControl)
            .build()
        player!!.addListener(self)
        exoPlayerView!!.player = player
        audioBecomingNoisyReceiver.setListener(self)
        bandwidthMeter.addEventListener(Handler(), self)
        setPlayWhenReady(!isPaused)
        playerNeedsSource = true
        val params = PlaybackParameters(rate, 1f)
        player!!.playbackParameters = params
    }

    private fun initializePlayerDrm(self: ReactExoplayerView): DrmSessionManager? {
        var drmSessionManager: DrmSessionManager? = null
        if (self.drmUUID != null) {
            drmSessionManager = try {
                self.buildDrmSessionManager(
                    self.drmUUID, self.drmLicenseUrl, self.drmLicenseHeader
                )
            } catch (e: UnsupportedDrmException) {
                val errorStringId =
                    if (Util.SDK_INT < 18) R.string.error_drm_not_supported else if (e.reason == UnsupportedDrmException.REASON_UNSUPPORTED_SCHEME) R.string.error_drm_unsupported_scheme else R.string.error_drm_unknown
                eventEmitter.error(resources.getString(errorStringId), e, "3003")
                return null
            }
        }
        return drmSessionManager
    }

    private fun initializePlayerSource(self: ReactExoplayerView,
                                       drmSessionManager: DrmSessionManager?
    ) {
        val mediaSourceList = buildTextSources()
        val videoSource = buildMediaSource(self.srcUri, self.extension, drmSessionManager)
        val mediaSource: MediaSource = if (mediaSourceList.size == 0) {
            videoSource
        } else {
            mediaSourceList.add(0, videoSource)
            val textSourceArray = mediaSourceList.toTypedArray()
            MergingMediaSource(*textSourceArray)
        }

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
        //player!!.prepare(mediaSource, !haveResumePosition, false)
        player!!.setMediaSource(mediaSource, !haveResumePosition)
        player!!.prepare()
        playerNeedsSource = false
        reLayout(exoPlayerView)
        eventEmitter.loadStart()
        loadVideoStarted = true
        finishPlayerInitialization()
    }

    private fun finishPlayerInitialization() {
        applyModifiers()
    }

    @Throws(UnsupportedDrmException::class)
    private fun buildDrmSessionManager(uuid: UUID?,
                                       licenseUrl: String?,
                                       keyRequestPropertiesArray: Array<String?>?,
                                       retryCount: Int = 0
    ): DrmSessionManager? {
        var retryCount = retryCount
        return if (Util.SDK_INT < 18) {
            null
        } else try {
            val drmCallback = HttpMediaDrmCallback(
                licenseUrl, buildHttpDataSourceFactory(false)!!
            )
            if (keyRequestPropertiesArray != null) {
                var i = 0
                while (i < keyRequestPropertiesArray.size - 1) {
                    drmCallback.setKeyRequestProperty(
                        keyRequestPropertiesArray[i]!!, keyRequestPropertiesArray[i + 1]!!
                    )
                    i += 2
                }
            }
            val mediaDrm = FrameworkMediaDrm.newInstance(uuid!!)
            if (hasDrmFailed) {
                // When DRM fails using L1 we want to switch to L3
                mediaDrm.setPropertyString("securityLevel", "L3")
            }
            DefaultDrmSessionManager(uuid, mediaDrm, drmCallback, null, false, 3)
        } catch (ex: UnsupportedDrmException) {
            // Unsupported DRM exceptions are handled by the calling method
            throw ex
        } catch (ex: Exception) {
            if (retryCount < 3) {
                // Attempt retry 3 times in case where the OS Media DRM Framework fails for whatever reason
                return buildDrmSessionManager(
                    uuid, licenseUrl, keyRequestPropertiesArray, ++retryCount
                )
            }
            // Handle the unknow exception and emit to JS
            eventEmitter.error(ex.toString(), ex, "3006")
            null
        }
    }

    private fun buildMediaSource(uri: Uri?,
                                 overrideExtension: String?,
                                 drmSessionManager: DrmSessionManager?
    ): MediaSource {
        checkNotNull(uri) { "Invalid video uri" }
        val type =
            Util.inferContentType((if (!TextUtils.isEmpty(overrideExtension)) ".$overrideExtension" else uri.lastPathSegment)!!)
        config.disableDisconnectError = disableDisconnectError
        val mediaItem = MediaItem.Builder().setUri(uri).build()
        var drmProvider: DrmSessionManagerProvider? = null
        drmProvider = if (drmSessionManager != null) {
            DrmSessionManagerProvider { drmSessionManager }
        } else {
            DefaultDrmSessionManagerProvider()
        }
        return when (type) {
            C.TYPE_SS -> SsMediaSource.Factory(
                DefaultSsChunkSource.Factory(mediaDataSourceFactory!!),
                buildDataSourceFactory(false)
            ).setDrmSessionManagerProvider(drmProvider).setLoadErrorHandlingPolicy(
                config.buildLoadErrorHandlingPolicy(minLoadRetryCount)
            ).createMediaSource(mediaItem)

            C.TYPE_DASH -> DashMediaSource.Factory(
                DefaultDashChunkSource.Factory(mediaDataSourceFactory!!),
                buildDataSourceFactory(false)
            ).setDrmSessionManagerProvider(drmProvider).setLoadErrorHandlingPolicy(
                config.buildLoadErrorHandlingPolicy(minLoadRetryCount)
            ).createMediaSource(mediaItem)

            C.TYPE_HLS -> HlsMediaSource.Factory(
                mediaDataSourceFactory!!
            ).setDrmSessionManagerProvider(drmProvider).setLoadErrorHandlingPolicy(
                config.buildLoadErrorHandlingPolicy(minLoadRetryCount)
            ).createMediaSource(mediaItem)

            C.TYPE_OTHER -> ProgressiveMediaSource.Factory(
                mediaDataSourceFactory!!
            ).setDrmSessionManagerProvider(drmProvider).setLoadErrorHandlingPolicy(
                config.buildLoadErrorHandlingPolicy(minLoadRetryCount)
            ).createMediaSource(mediaItem)

            else -> {
                throw IllegalStateException("Unsupported type: $type")
            }
        }
    }

    private fun buildTextSources(): ArrayList<MediaSource> {
        val textSources = ArrayList<MediaSource>()
        if (textTracks == null) {
            return textSources
        }
        for (i in 0 until textTracks!!.size()) {
            val textTrack = textTracks!!.getMap(i)
            val language = textTrack.getString("language")
            val title =
                if (textTrack.hasKey("title")) textTrack.getString("title") else "$language $i"
            val uri = Uri.parse(textTrack.getString("uri"))
            val textSource = buildTextSource(
                title, uri, textTrack.getString("type"), language
            )
            if (textSource != null) {
                textSources.add(textSource)
            }
        }
        return textSources
    }

    private fun buildTextSource(title: String?, uri: Uri, mimeType: String?, language: String?
    ): MediaSource {
        val subtitleConfiguration = SubtitleConfiguration.Builder(uri)
            .setMimeType(mimeType)
            .setLanguage(language)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
            .setLabel(title)
            .build()
        return SingleSampleMediaSource.Factory(mediaDataSourceFactory!!)
            .createMediaSource(subtitleConfiguration, C.TIME_UNSET)
    }

    private fun releasePlayer() {
        if (player != null) {
            updateResumePosition()
            player!!.release()
            player!!.removeListener(this)
            //trackSelector = null
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
                        setSelectedTrack(C.TRACK_TYPE_VIDEO, videoTrackType, videoTrackValue)
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
            if (audioTrackType != null) {
                setSelectedAudioTrack(audioTrackType, audioTrackValue)
            }
            if (videoTrackType != null) {
                setSelectedVideoTrack(videoTrackType, videoTrackValue)
            }
            if (textTrackType != null) {
                setSelectedTextTrack(textTrackType, textTrackValue)
            }
            val videoFormat = player!!.videoFormat
            val width = videoFormat?.width ?: 0
            val height = videoFormat?.height ?: 0
            val trackId = if (videoFormat != null) videoFormat.id else "-1"

            // Properties that must be accessed on the main thread
            val duration = player!!.duration
            val currentPosition = player!!.currentPosition
            val textTrackInfo = textTrackInfo
            val trackRendererIndex = getTrackRendererIndex(C.TRACK_TYPE_VIDEO)
            val es = Executors.newSingleThreadExecutor()
            es.execute { // To prevent ANRs caused by getVideoTrackInfo we run this on a different thread and notify the player only when we're done
                eventEmitter.load(
                    duration.toDouble(),
                    currentPosition.toDouble(),
                    width,
                    height,
                    textTrackInfo,
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

    // We need retry count to in case where minefest request fails from poor network conditions
    private fun getVideoTrackInfoFromManifest(retryCount: Int): WritableArray? {
        var retryCount = retryCount
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
                } catch (e: Exception) {
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
        } catch (e: Exception) {
        }
        return null
    }

    private val textTrackInfo: WritableArray
        private get() {
            val textTracks = Arguments.createArray()
            val info = trackSelector!!.currentMappedTrackInfo
            val index = getTrackRendererIndex(C.TRACK_TYPE_TEXT)
            if (info == null || index == C.INDEX_UNSET) {
                return textTracks
            }
            val groups = info.getTrackGroups(index)
            for (i in 0 until groups.length) {
                val format = groups[i].getFormat(0)
                val textTrack = Arguments.createMap()
                textTrack.putInt("index", i)
                textTrack.putString("title", if (format.id != null) format.id else "")
                textTrack.putString("type", format.sampleMimeType)
                textTrack.putString(
                    "language", if (format.language != null) format.language else ""
                )
                textTracks.pushMap(textTrack)
            }
            return textTracks
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

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,
                                         newPosition: Player.PositionInfo,
                                         reason: Int
    ) {
        if (playerNeedsSource) {
            // This will only occur if the user has performed a seek whilst in the error state. Update the
            // resume position so that if the user then retries, playback will resume from the position to
            // which they seeked.
            updateResumePosition()
        }
        if (isUsingContentResolution) {
            // Discontinuity events might have a different track list so we update the selected track
            setSelectedTrack(C.TRACK_TYPE_VIDEO, videoTrackType, videoTrackValue)
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
            if (isUsingContentResolution) {
                // We need to update the selected track to make sure that it still matches user selection if track list has changed in this period
                setSelectedTrack(C.TRACK_TYPE_VIDEO, videoTrackType, videoTrackValue)
            }
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
        when (e.errorCode) {
            PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED, PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED, PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED, PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR, PlaybackException.ERROR_CODE_DRM_UNSPECIFIED -> if (!hasDrmFailed) {
                // When DRM fails to reach the app level certificate server it will fail with a source error so we assume that it is DRM related and try one more time
                hasDrmFailed = true
                playerNeedsSource = true
                updateResumePosition()
                initializePlayer()
                setPlayWhenReady(true)
                return
            }

            else -> {}
        }
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
            hasDrmFailed = false
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

    fun setTextTracks(textTracks: ReadableArray?) {
        this.textTracks = textTracks
        reloadSource()
    }

    private fun reloadSource() {
        playerNeedsSource = true
        initializePlayer()
    }

    fun setResizeModeModifier(@ResizeMode.Mode resizeMode: Int) {
        exoPlayerView!!.resizeMode = resizeMode
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

    fun setSelectedTrack(trackType: Int, type: String?, value: Dynamic?) {
        var type = type
        if (player == null) return
        val rendererIndex = getTrackRendererIndex(trackType)
        if (rendererIndex == C.INDEX_UNSET) {
            return
        }
        val info = trackSelector!!.currentMappedTrackInfo ?: return
        val groups = info.getTrackGroups(rendererIndex)
        var groupIndex = C.INDEX_UNSET
        var tracks: MutableList<Int?> = ArrayList()
        tracks.add(0)
        if (TextUtils.isEmpty(type)) {
            type = "default"
        }
        val disableParameters =
            trackSelector!!.parameters.buildUpon().setRendererDisabled(rendererIndex, true).build()
        if (type == "disabled") {
            trackSelector!!.setParameters(disableParameters)
            return
        } else if (type == "language") {
            for (i in 0 until groups.length) {
                val format = groups[i].getFormat(0)
                if (format.language != null && format.language == value!!.asString()) {
                    groupIndex = i
                    break
                }
            }
        } else if (type == "title") {
            for (i in 0 until groups.length) {
                val format = groups[i].getFormat(0)
                if (format.id != null && format.id == value!!.asString()) {
                    groupIndex = i
                    break
                }
            }
        } else if (type == "index") {
            if (value!!.asInt() < groups.length) {
                groupIndex = value.asInt()
            }
        } else if (type == "resolution") {
            val height = value!!.asInt()
            for (i in 0 until groups.length) { // Search for the exact height
                val group = groups[i]
                var closestFormat: Format? = null
                var closestTrackIndex = -1
                var usingExactMatch = false
                for (j in 0 until group.length) {
                    val format = group.getFormat(j)
                    if (format.height == height) {
                        groupIndex = i
                        tracks[0] = j
                        closestFormat = null
                        closestTrackIndex = -1
                        usingExactMatch = true
                        break
                    } else if (isUsingContentResolution) {
                        // When using content resolution rather than ads, we need to try and find the closest match if there is no exact match
                        if (closestFormat != null) {
                            if ((format.bitrate > closestFormat.bitrate || format.height > closestFormat.height) && format.height < height) {
                                // Higher quality match
                                closestFormat = format
                                closestTrackIndex = j
                            }
                        } else if (format.height < height) {
                            closestFormat = format
                            closestTrackIndex = j
                        }
                    }
                }
                // This is a fallback if the new period contains only higher resolutions than the user has selected
                if (closestFormat == null && isUsingContentResolution && !usingExactMatch) {
                    // No close match found - so we pick the lowest quality
                    var minHeight = Int.MAX_VALUE
                    for (j in 0 until group.length) {
                        val format = group.getFormat(j)
                        if (format.height < minHeight) {
                            minHeight = format.height
                            groupIndex = i
                            tracks[0] = j
                        }
                    }
                }
                // Selecting the closest match found
                if (closestFormat != null && closestTrackIndex != -1) {
                    // We found the closest match instead of an exact one
                    groupIndex = i
                    tracks[0] = closestTrackIndex
                }
            }
        } else if (trackType == C.TRACK_TYPE_TEXT && Util.SDK_INT > 18) { // Text default
            // Use system settings if possible
            val captioningManager =
                themedReactContext.getSystemService(Context.CAPTIONING_SERVICE) as CaptioningManager
            if (captioningManager != null && captioningManager.isEnabled) {
                groupIndex = getGroupIndexForDefaultLocale(groups)
            }
        } else if (rendererIndex == C.TRACK_TYPE_AUDIO) { // Audio default
            groupIndex = getGroupIndexForDefaultLocale(groups)
        }
        if (groupIndex == C.INDEX_UNSET && trackType == C.TRACK_TYPE_VIDEO && groups.length != 0) { // Video auto
            // Add all tracks as valid options for ABR to choose from
            val group = groups[0]
            tracks = ArrayList(group.length)
            val allTracks = ArrayList<Int?>(group.length)
            groupIndex = 0
            for (j in 0 until group.length) {
                allTracks.add(j)
            }

            // Valiate list of all tracks and add only supported formats
            var supportedFormatLength = 0
            val supportedTrackList = ArrayList<Int?>()
            for (g in allTracks.indices) {
                val format = group.getFormat(g)
                if (isFormatSupported(format)) {
                    supportedFormatLength++
                }
            }
            if (allTracks.size == 1) {
                // With only one tracks we can't remove any tracks so attempt to play it anyway
                tracks = allTracks
            } else {
                tracks = ArrayList(supportedFormatLength + 1)
                for (k in allTracks.indices) {
                    val format = group.getFormat(k)
                    if (isFormatSupported(format)) {
                        tracks.add(allTracks[k])
                        supportedTrackList.add(allTracks[k])
                    }
                }
            }
        }
        if (groupIndex == C.INDEX_UNSET) {
            trackSelector!!.setParameters(disableParameters)
            return
        }

        if (tracks.contains(null)) {
            return
        }

        val selectionOverride = TrackSelectionOverride(groups[groupIndex], tracks as List<Int>)
        val selectionParameters = trackSelector!!.parameters.buildUpon()
            .setRendererDisabled(rendererIndex, false)
            .addOverride(selectionOverride)
            .build()
        trackSelector!!.setParameters(selectionParameters)
    }

    private fun isFormatSupported(format: Format): Boolean {
        val width = if (format.width == Format.NO_VALUE) 0 else format.width
        val height = if (format.height == Format.NO_VALUE) 0 else format.height
        val frameRate: Float =
            if (format.frameRate == Format.NO_VALUE.toFloat()) 0f else format.frameRate
        val mimeType = format.sampleMimeType ?: return true
        var isSupported = false
        isSupported = try {
            val codecInfo = MediaCodecUtil.getDecoderInfo(mimeType, false, false)
            codecInfo!!.isVideoSizeAndRateSupportedV21(width, height, frameRate.toDouble())
        } catch (e: Exception) {
            // Failed to get decoder info - assume it is supported
            true
        }
        return isSupported
    }

    private fun getGroupIndexForDefaultLocale(groups: TrackGroupArray): Int {
        if (groups.length == 0) {
            return C.INDEX_UNSET
        }
        var groupIndex = 0 // default if no match
        val locale2 = Locale.getDefault().language // 2 letter code
        val locale3 = Locale.getDefault().isO3Language // 3 letter code
        for (i in 0 until groups.length) {
            val format = groups[i].getFormat(0)
            val language = format.language
            if (language != null && language == locale2 || language == locale3) {
                groupIndex = i
                break
            }
        }
        return groupIndex
    }

    fun setSelectedVideoTrack(type: String?, value: Dynamic?) {
        videoTrackType = type
        videoTrackValue = value
        setSelectedTrack(C.TRACK_TYPE_VIDEO, videoTrackType, videoTrackValue)
    }

    fun setSelectedAudioTrack(type: String?, value: Dynamic?) {
        audioTrackType = type
        audioTrackValue = value
        setSelectedTrack(C.TRACK_TYPE_AUDIO, audioTrackType, audioTrackValue)
    }

    fun setSelectedTextTrack(type: String?, value: Dynamic?) {
        textTrackType = type
        textTrackValue = value
        setSelectedTrack(C.TRACK_TYPE_TEXT, textTrackType, textTrackValue)
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
        val finallyUseTextureView = useTextureView && drmUUID == null
        //exoPlayerView!!.setUseTextureView(finallyUseTextureView)
    }

    fun useSecureView(useSecureView: Boolean) {
        //exoPlayerView!!.useSecureView(useSecureView)
    }

    fun setHideShutterView(hideShutterView: Boolean) {
        //exoPlayerView!!.setHideShutterView(hideShutterView)
        exoPlayerView!!.setShutterBackgroundColor(0)
    }

    fun setBufferConfig(newMinBufferMs: Int,
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

    fun setDrmType(drmType: UUID?) {
        drmUUID = drmType
    }

    fun setDrmLicenseUrl(licenseUrl: String?) {
        drmLicenseUrl = licenseUrl
    }

    fun setDrmLicenseHeader(header: Array<String?>?) {
        drmLicenseHeader = header
    }

    override fun onDrmKeysLoaded(windowIndex: Int, mediaPeriodId: MediaSource.MediaPeriodId?) {
        Log.d("DRM Info", "onDrmKeysLoaded")
    }

    override fun onDrmSessionManagerError(windowIndex: Int,
                                          mediaPeriodId: MediaSource.MediaPeriodId?,
                                          e: Exception
    ) {
        Log.d("DRM Info", "onDrmSessionManagerError")
        eventEmitter.error("onDrmSessionManagerError", e, "3002")
    }

    override fun onDrmKeysRestored(windowIndex: Int, mediaPeriodId: MediaSource.MediaPeriodId?) {
        Log.d("DRM Info", "onDrmKeysRestored")
    }

    override fun onDrmKeysRemoved(windowIndex: Int, mediaPeriodId: MediaSource.MediaPeriodId?) {
        Log.d("DRM Info", "onDrmKeysRemoved")
    }

    fun setSubtitleStyle(style: SubtitleStyle) {
        //exoPlayerView!!.setSubtitleStyle(style)
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
