package com.brentvatne.exoplayer

import android.net.Uri
import android.text.TextUtils
import com.facebook.react.bridge.Dynamic
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.common.MapBuilder
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.ViewGroupManager
import com.facebook.react.uimanager.annotations.ReactProp
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.upstream.RawResourceDataSource
import com.google.android.exoplayer2.util.Util
import java.util.Locale

class ReactExoplayerViewManager(private val config: ReactExoplayerConfig) :
    ViewGroupManager<ReactExoplayerView>() {
    override fun getName(): String {
        return REACT_CLASS
    }

    override fun createViewInstance(themedReactContext: ThemedReactContext): ReactExoplayerView {
        return ReactExoplayerView(themedReactContext, config)
    }

    override fun onDropViewInstance(view: ReactExoplayerView) {
        view.cleanUpResources()
    }

    override fun getExportedCustomDirectEventTypeConstants(): Map<String, Any>? {
        val builder = MapBuilder.builder<String, Any>()
        for (event in VideoEventEmitter.Companion.Events) {
            builder.put(event, MapBuilder.of<String, String>("registrationName", event))
        }
        return builder.build()
    }

    override fun getExportedViewConstants(): Map<String, Any>? {
        return MapBuilder.of<String, Any>(
            "ScaleNone",
            Integer.toString(ResizeMode.RESIZE_MODE_FIT),
            "ScaleAspectFit",
            Integer.toString(ResizeMode.RESIZE_MODE_FIT),
            "ScaleToFill",
            Integer.toString(ResizeMode.RESIZE_MODE_FILL),
            "ScaleAspectFill",
            Integer.toString(ResizeMode.RESIZE_MODE_CENTER_CROP)
        )
    }

    @ReactProp(name = PROP_DRM)
    fun setDRM(videoView: ReactExoplayerView, drm: ReadableMap?) {
        if (drm != null && drm.hasKey(PROP_DRM_TYPE)) {
            val drmType = if (drm.hasKey(PROP_DRM_TYPE)) drm.getString(PROP_DRM_TYPE) else null
            val drmLicenseServer = if (drm.hasKey(PROP_DRM_LICENSESERVER)) drm.getString(
                PROP_DRM_LICENSESERVER
            ) else null
            val drmHeaders =
                if (drm.hasKey(PROP_DRM_HEADERS)) drm.getMap(PROP_DRM_HEADERS) else null
            if (drmType != null && drmLicenseServer != null && Util.getDrmUuid(
                    drmType
                ) != null
            ) {
                val drmUUID = Util.getDrmUuid(drmType)
                videoView.setDrmType(drmUUID)
                videoView.setDrmLicenseUrl(drmLicenseServer)
                if (drmHeaders != null) {
                    val drmKeyRequestPropertiesList = ArrayList<String?>()
                    val itr = drmHeaders.keySetIterator()
                    while (itr.hasNextKey()) {
                        val key = itr.nextKey()
                        drmKeyRequestPropertiesList.add(key)
                        drmKeyRequestPropertiesList.add(drmHeaders.getString(key))
                    }
                    videoView.setDrmLicenseHeader(drmKeyRequestPropertiesList.toTypedArray())
                }
                videoView.setUseTextureView(false)
            }
        }
    }

    @ReactProp(name = PROP_SRC)
    fun setSrc(videoView: ReactExoplayerView, src: ReadableMap?) {
        val context = videoView.context.applicationContext
        val uriString = if (src!!.hasKey(PROP_SRC_URI)) src.getString(PROP_SRC_URI) else null
        val extension = if (src.hasKey(PROP_SRC_TYPE)) src.getString(PROP_SRC_TYPE) else null
        val headers: Map<String?, String?>? = if (src.hasKey(PROP_SRC_HEADERS)) toStringMap(
            src.getMap(PROP_SRC_HEADERS)
        ) as Map<String?, String?>? else null
        if (TextUtils.isEmpty(uriString)) {
            videoView.clearSrc()
            return
        }
        if (startsWithValidScheme(uriString)) {
            val srcUri = Uri.parse(uriString)
            if (srcUri != null) {
                videoView.setSrc(srcUri, extension, headers)
            }
        } else {
            var identifier = context.resources.getIdentifier(
                uriString, "drawable", context.packageName
            )
            if (identifier == 0) {
                identifier = context.resources.getIdentifier(
                    uriString, "raw", context.packageName
                )
            }
            if (identifier > 0) {
                val srcUri = RawResourceDataSource.buildRawResourceUri(identifier)
                if (srcUri != null) {
                    videoView.setRawSrc(srcUri, extension)
                }
            } else {
                videoView.clearSrc()
            }
        }
    }

    @ReactProp(name = PROP_RESIZE_MODE)
    fun setResizeMode(videoView: ReactExoplayerView, resizeModeOrdinalString: String) {
        videoView.setResizeModeModifier(convertToIntDef(resizeModeOrdinalString))
    }

    @ReactProp(name = PROP_REPEAT, defaultBoolean = false)
    fun setRepeat(videoView: ReactExoplayerView, repeat: Boolean) {
        videoView.setRepeatModifier(repeat)
    }

    @ReactProp(name = PROP_PREVENTS_DISPLAY_SLEEP_DURING_VIDEO_PLAYBACK, defaultBoolean = false)
    fun setPreventsDisplaySleepDuringVideoPlayback(videoView: ReactExoplayerView,
                                                   preventsSleep: Boolean
    ) {
        videoView.setPreventsDisplaySleepDuringVideoPlayback(preventsSleep)
    }

    @ReactProp(name = PROP_SELECTED_VIDEO_TRACK)
    fun setSelectedVideoTrack(videoView: ReactExoplayerView, selectedVideoTrack: ReadableMap?
    ) {
        var typeString: String? = null
        var value: Dynamic? = null
        if (selectedVideoTrack != null) {
            typeString =
                if (selectedVideoTrack.hasKey(PROP_SELECTED_VIDEO_TRACK_TYPE)) selectedVideoTrack.getString(
                    PROP_SELECTED_VIDEO_TRACK_TYPE
                ) else null
            value =
                if (selectedVideoTrack.hasKey(PROP_SELECTED_VIDEO_TRACK_VALUE)) selectedVideoTrack.getDynamic(
                    PROP_SELECTED_VIDEO_TRACK_VALUE
                ) else null
        }
        videoView.setSelectedVideoTrack(typeString, value)
    }

    @ReactProp(name = PROP_SELECTED_AUDIO_TRACK)
    fun setSelectedAudioTrack(videoView: ReactExoplayerView, selectedAudioTrack: ReadableMap?
    ) {
        var typeString: String? = null
        var value: Dynamic? = null
        if (selectedAudioTrack != null) {
            typeString =
                if (selectedAudioTrack.hasKey(PROP_SELECTED_AUDIO_TRACK_TYPE)) selectedAudioTrack.getString(
                    PROP_SELECTED_AUDIO_TRACK_TYPE
                ) else null
            value =
                if (selectedAudioTrack.hasKey(PROP_SELECTED_AUDIO_TRACK_VALUE)) selectedAudioTrack.getDynamic(
                    PROP_SELECTED_AUDIO_TRACK_VALUE
                ) else null
        }
        videoView.setSelectedAudioTrack(typeString, value)
    }

    @ReactProp(name = PROP_SELECTED_TEXT_TRACK)
    fun setSelectedTextTrack(videoView: ReactExoplayerView, selectedTextTrack: ReadableMap?
    ) {
        var typeString: String? = null
        var value: Dynamic? = null
        if (selectedTextTrack != null) {
            typeString =
                if (selectedTextTrack.hasKey(PROP_SELECTED_TEXT_TRACK_TYPE)) selectedTextTrack.getString(
                    PROP_SELECTED_TEXT_TRACK_TYPE
                ) else null
            value =
                if (selectedTextTrack.hasKey(PROP_SELECTED_TEXT_TRACK_VALUE)) selectedTextTrack.getDynamic(
                    PROP_SELECTED_TEXT_TRACK_VALUE
                ) else null
        }
        videoView.setSelectedTextTrack(typeString, value)
    }

    @ReactProp(name = PROP_TEXT_TRACKS)
    fun setPropTextTracks(videoView: ReactExoplayerView, textTracks: ReadableArray?
    ) {
        videoView.setTextTracks(textTracks)
    }

    @ReactProp(name = PROP_PAUSED, defaultBoolean = false)
    fun setPaused(videoView: ReactExoplayerView, paused: Boolean) {
        videoView.setPausedModifier(paused)
    }

    @ReactProp(name = PROP_MUTED, defaultBoolean = false)
    fun setMuted(videoView: ReactExoplayerView, muted: Boolean) {
        videoView.setMutedModifier(muted)
    }

    @ReactProp(name = PROP_VOLUME, defaultFloat = 1.0f)
    fun setVolume(videoView: ReactExoplayerView, volume: Float) {
        videoView.setVolumeModifier(volume)
    }

    @ReactProp(name = PROP_PROGRESS_UPDATE_INTERVAL, defaultFloat = 250.0f)
    fun setProgressUpdateInterval(videoView: ReactExoplayerView, progressUpdateInterval: Float) {
        videoView.setProgressUpdateInterval(progressUpdateInterval)
    }

    @ReactProp(name = PROP_REPORT_BANDWIDTH, defaultBoolean = false)
    fun setReportBandwidth(videoView: ReactExoplayerView, reportBandwidth: Boolean) {
        videoView.setReportBandwidth(reportBandwidth)
    }

    @ReactProp(name = PROP_SEEK)
    fun setSeek(videoView: ReactExoplayerView, seek: Float) {
        videoView.seekTo(Math.round(seek * 1000f).toLong())
    }

    @ReactProp(name = PROP_RATE)
    fun setRate(videoView: ReactExoplayerView, rate: Float) {
        videoView.setRateModifier(rate)
    }

    @ReactProp(name = PROP_MAXIMUM_BIT_RATE)
    fun setMaxBitRate(videoView: ReactExoplayerView, maxBitRate: Int) {
        videoView.setMaxBitRateModifier(maxBitRate)
    }

    @ReactProp(name = PROP_MIN_LOAD_RETRY_COUNT)
    fun minLoadRetryCount(videoView: ReactExoplayerView, minLoadRetryCount: Int) {
        videoView.setMinLoadRetryCountModifier(minLoadRetryCount)
    }

    @ReactProp(name = PROP_PLAY_IN_BACKGROUND, defaultBoolean = false)
    fun setPlayInBackground(videoView: ReactExoplayerView, playInBackground: Boolean) {
        videoView.setPlayInBackground(playInBackground)
    }

    @ReactProp(name = PROP_DISABLE_FOCUS, defaultBoolean = false)
    fun setDisableFocus(videoView: ReactExoplayerView, disableFocus: Boolean) {
        videoView.setDisableFocus(disableFocus)
    }

    @ReactProp(name = PROP_FOCUSABLE, defaultBoolean = true)
    fun setFocusable(videoView: ReactExoplayerView, focusable: Boolean) {
        videoView.isFocusable = focusable
    }

    @ReactProp(name = PROP_BACK_BUFFER_DURATION_MS, defaultInt = 0)
    fun setBackBufferDurationMs(videoView: ReactExoplayerView, backBufferDurationMs: Int) {
        videoView.setBackBufferDurationMs(backBufferDurationMs)
    }

    @ReactProp(name = PROP_CONTENT_START_TIME, defaultInt = 0)
    fun setContentStartTime(videoView: ReactExoplayerView, contentStartTime: Int) {
        videoView.setContentStartTime(contentStartTime)
    }

    @ReactProp(name = PROP_DISABLE_BUFFERING, defaultBoolean = false)
    fun setDisableBuffering(videoView: ReactExoplayerView, disableBuffering: Boolean) {
        videoView.setDisableBuffering(disableBuffering)
    }

    @ReactProp(name = PROP_DISABLE_DISCONNECT_ERROR, defaultBoolean = false)
    fun setDisableDisconnectError(videoView: ReactExoplayerView, disableDisconnectError: Boolean) {
        videoView.setDisableDisconnectError(disableDisconnectError)
    }

    @ReactProp(name = PROP_FULLSCREEN, defaultBoolean = false)
    fun setFullscreen(videoView: ReactExoplayerView, fullscreen: Boolean) {
    }

    @ReactProp(name = PROP_USE_TEXTURE_VIEW, defaultBoolean = true)
    fun setUseTextureView(videoView: ReactExoplayerView, useTextureView: Boolean) {
        videoView.setUseTextureView(useTextureView)
    }

    @ReactProp(name = PROP_SECURE_VIEW, defaultBoolean = true)
    fun useSecureView(videoView: ReactExoplayerView, useSecureView: Boolean) {
        videoView.useSecureView(useSecureView)
    }

    @ReactProp(name = PROP_HIDE_SHUTTER_VIEW, defaultBoolean = false)
    fun setHideShutterView(videoView: ReactExoplayerView, hideShutterView: Boolean) {
        videoView.setHideShutterView(hideShutterView)
    }

    @ReactProp(name = PROP_CONTROLS, defaultBoolean = false)
    fun setControls(videoView: ReactExoplayerView, controls: Boolean) {
    }

    @ReactProp(name = PROP_SUBTITLE_STYLE)
    fun setSubtitleStyle(videoView: ReactExoplayerView, src: ReadableMap?) {
        videoView.setSubtitleStyle(SubtitleStyle.Companion.parse(src))
    }

    @ReactProp(name = PROP_BUFFER_CONFIG)
    fun setBufferConfig(videoView: ReactExoplayerView, bufferConfig: ReadableMap?) {
        var minBufferMs = DefaultLoadControl.DEFAULT_MIN_BUFFER_MS
        var maxBufferMs = DefaultLoadControl.DEFAULT_MAX_BUFFER_MS
        var bufferForPlaybackMs = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS
        var bufferForPlaybackAfterRebufferMs =
            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
        var maxHeapAllocationPercent: Double =
            ReactExoplayerView.Companion.DEFAULT_MAX_HEAP_ALLOCATION_PERCENT
        var minBackBufferMemoryReservePercent: Double =
            ReactExoplayerView.Companion.DEFAULT_MIN_BACK_BUFFER_MEMORY_RESERVE
        var minBufferMemoryReservePercent: Double =
            ReactExoplayerView.Companion.DEFAULT_MIN_BUFFER_MEMORY_RESERVE
        if (bufferConfig != null) {
            minBufferMs =
                if (bufferConfig.hasKey(PROP_BUFFER_CONFIG_MIN_BUFFER_MS)) bufferConfig.getInt(
                    PROP_BUFFER_CONFIG_MIN_BUFFER_MS
                ) else minBufferMs
            maxBufferMs =
                if (bufferConfig.hasKey(PROP_BUFFER_CONFIG_MAX_BUFFER_MS)) bufferConfig.getInt(
                    PROP_BUFFER_CONFIG_MAX_BUFFER_MS
                ) else maxBufferMs
            bufferForPlaybackMs =
                if (bufferConfig.hasKey(PROP_BUFFER_CONFIG_BUFFER_FOR_PLAYBACK_MS)) bufferConfig.getInt(
                    PROP_BUFFER_CONFIG_BUFFER_FOR_PLAYBACK_MS
                ) else bufferForPlaybackMs
            bufferForPlaybackAfterRebufferMs = if (bufferConfig.hasKey(
                    PROP_BUFFER_CONFIG_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                )
            ) bufferConfig.getInt(PROP_BUFFER_CONFIG_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS) else bufferForPlaybackAfterRebufferMs
            maxHeapAllocationPercent = if (bufferConfig.hasKey(
                    PROP_BUFFER_CONFIG_MAX_HEAP_ALLOCATION_PERCENT
                )
            ) bufferConfig.getDouble(PROP_BUFFER_CONFIG_MAX_HEAP_ALLOCATION_PERCENT) else maxHeapAllocationPercent
            minBackBufferMemoryReservePercent = if (bufferConfig.hasKey(
                    PROP_BUFFER_CONFIG_MIN_BACK_BUFFER_MEMORY_RESERVE_PERCENT
                )
            ) bufferConfig.getDouble(PROP_BUFFER_CONFIG_MIN_BACK_BUFFER_MEMORY_RESERVE_PERCENT) else minBackBufferMemoryReservePercent
            minBufferMemoryReservePercent = if (bufferConfig.hasKey(
                    PROP_BUFFER_CONFIG_MIN_BUFFER_MEMORY_RESERVE_PERCENT
                )
            ) bufferConfig.getDouble(PROP_BUFFER_CONFIG_MIN_BUFFER_MEMORY_RESERVE_PERCENT) else minBufferMemoryReservePercent
            videoView.setBufferConfig(
                minBufferMs,
                maxBufferMs,
                bufferForPlaybackMs,
                bufferForPlaybackAfterRebufferMs,
                maxHeapAllocationPercent,
                minBackBufferMemoryReservePercent,
                minBufferMemoryReservePercent
            )
        }
    }

    private fun startsWithValidScheme(uriString: String?): Boolean {
        val lowerCaseUri = uriString!!.lowercase(Locale.getDefault())
        return (lowerCaseUri.startsWith("http://") || lowerCaseUri.startsWith("https://") || lowerCaseUri.startsWith(
            "content://"
        ) || lowerCaseUri.startsWith("file://") || lowerCaseUri.startsWith("asset://"))
    }

    @ResizeMode.Mode
    private fun convertToIntDef(resizeModeOrdinalString: String): Int {
        if (!TextUtils.isEmpty(resizeModeOrdinalString)) {
            val resizeModeOrdinal = resizeModeOrdinalString.toInt()
            return ResizeMode.toResizeMode(resizeModeOrdinal)
        }
        return ResizeMode.RESIZE_MODE_FIT
    }

    companion object {
        private const val REACT_CLASS = "RCTVideo"
        private const val PROP_SRC = "src"
        private const val PROP_SRC_URI = "uri"
        private const val PROP_SRC_TYPE = "type"
        private const val PROP_DRM = "drm"
        private const val PROP_DRM_TYPE = "type"
        private const val PROP_DRM_LICENSESERVER = "licenseServer"
        private const val PROP_DRM_HEADERS = "headers"
        private const val PROP_SRC_HEADERS = "requestHeaders"
        private const val PROP_RESIZE_MODE = "resizeMode"
        private const val PROP_REPEAT = "repeat"
        private const val PROP_SELECTED_AUDIO_TRACK = "selectedAudioTrack"
        private const val PROP_SELECTED_AUDIO_TRACK_TYPE = "type"
        private const val PROP_SELECTED_AUDIO_TRACK_VALUE = "value"
        private const val PROP_SELECTED_TEXT_TRACK = "selectedTextTrack"
        private const val PROP_SELECTED_TEXT_TRACK_TYPE = "type"
        private const val PROP_SELECTED_TEXT_TRACK_VALUE = "value"
        private const val PROP_TEXT_TRACKS = "textTracks"
        private const val PROP_PAUSED = "paused"
        private const val PROP_MUTED = "muted"
        private const val PROP_VOLUME = "volume"
        private const val PROP_BACK_BUFFER_DURATION_MS = "backBufferDurationMs"
        private const val PROP_BUFFER_CONFIG = "bufferConfig"
        private const val PROP_BUFFER_CONFIG_MIN_BUFFER_MS = "minBufferMs"
        private const val PROP_BUFFER_CONFIG_MAX_BUFFER_MS = "maxBufferMs"
        private const val PROP_BUFFER_CONFIG_BUFFER_FOR_PLAYBACK_MS = "bufferForPlaybackMs"
        private const val PROP_BUFFER_CONFIG_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS =
            "bufferForPlaybackAfterRebufferMs"
        private const val PROP_BUFFER_CONFIG_MAX_HEAP_ALLOCATION_PERCENT =
            "maxHeapAllocationPercent"
        private const val PROP_BUFFER_CONFIG_MIN_BACK_BUFFER_MEMORY_RESERVE_PERCENT =
            "minBackBufferMemoryReservePercent"
        private const val PROP_BUFFER_CONFIG_MIN_BUFFER_MEMORY_RESERVE_PERCENT =
            "minBufferMemoryReservePercent"
        private const val PROP_PREVENTS_DISPLAY_SLEEP_DURING_VIDEO_PLAYBACK =
            "preventsDisplaySleepDuringVideoPlayback"
        private const val PROP_PROGRESS_UPDATE_INTERVAL = "progressUpdateInterval"
        private const val PROP_REPORT_BANDWIDTH = "reportBandwidth"
        private const val PROP_SEEK = "seek"
        private const val PROP_RATE = "rate"
        private const val PROP_MIN_LOAD_RETRY_COUNT = "minLoadRetryCount"
        private const val PROP_MAXIMUM_BIT_RATE = "maxBitRate"
        private const val PROP_PLAY_IN_BACKGROUND = "playInBackground"
        private const val PROP_CONTENT_START_TIME = "contentStartTime"
        private const val PROP_DISABLE_FOCUS = "disableFocus"
        private const val PROP_DISABLE_BUFFERING = "disableBuffering"
        private const val PROP_DISABLE_DISCONNECT_ERROR = "disableDisconnectError"
        private const val PROP_FOCUSABLE = "focusable"
        private const val PROP_FULLSCREEN = "fullscreen"
        private const val PROP_USE_TEXTURE_VIEW = "useTextureView"
        private const val PROP_SECURE_VIEW = "useSecureView"
        private const val PROP_SELECTED_VIDEO_TRACK = "selectedVideoTrack"
        private const val PROP_SELECTED_VIDEO_TRACK_TYPE = "type"
        private const val PROP_SELECTED_VIDEO_TRACK_VALUE = "value"
        private const val PROP_HIDE_SHUTTER_VIEW = "hideShutterView"
        private const val PROP_CONTROLS = "controls"
        private const val PROP_SUBTITLE_STYLE = "subtitleStyle"

        /**
         * toStringMap converts a [ReadableMap] into a HashMap.
         *
         * @param readableMap The ReadableMap to be conveted.
         * @return A HashMap containing the data that was in the ReadableMap.
         * @see 'Adapted from https://github.com/artemyarulin/react-native-eval/blob/master/android/src/main/java/com/evaluator/react/ConversionUtil.java'
         */
        fun toStringMap(readableMap: ReadableMap?): Map<String, String?>? {
            if (readableMap == null) return null
            val iterator = readableMap.keySetIterator()
            if (!iterator.hasNextKey()) return null
            val result: MutableMap<String, String?> = HashMap()
            while (iterator.hasNextKey()) {
                val key = iterator.nextKey()
                result[key] = readableMap.getString(key)
            }
            return result
        }
    }
}
