package com.brentvatne.exoplayer

import android.view.View
import androidx.annotation.StringDef
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.events.RCTEventEmitter
import com.google.android.exoplayer2.metadata.Metadata
import com.google.android.exoplayer2.metadata.emsg.EventMessage
import com.google.android.exoplayer2.metadata.id3.Id3Frame
import com.google.android.exoplayer2.metadata.id3.TextInformationFrame
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy

internal class VideoEventEmitter(reactContext: ReactContext) {
    private val eventEmitter: RCTEventEmitter
    private var viewId = View.NO_ID

    @Retention(RetentionPolicy.SOURCE)
    @StringDef(
        EVENT_LOAD_START,
        EVENT_LOAD,
        EVENT_ERROR,
        EVENT_PROGRESS,
        EVENT_SEEK,
        EVENT_END,
        EVENT_FULLSCREEN_WILL_PRESENT,
        EVENT_FULLSCREEN_DID_PRESENT,
        EVENT_FULLSCREEN_WILL_DISMISS,
        EVENT_FULLSCREEN_DID_DISMISS,
        EVENT_STALLED,
        EVENT_RESUME,
        EVENT_READY,
        EVENT_BUFFER,
        EVENT_PLAYBACK_STATE_CHANGED,
        EVENT_IDLE,
        EVENT_TIMED_METADATA,
        EVENT_AUDIO_BECOMING_NOISY,
        EVENT_AUDIO_FOCUS_CHANGE,
        EVENT_PLAYBACK_RATE_CHANGE,
        EVENT_BANDWIDTH
    )
    internal annotation class VideoEvents

    init {
        eventEmitter = reactContext.getJSModule(RCTEventEmitter::class.java)
    }

    fun setViewId(viewId: Int) {
        this.viewId = viewId
    }

    fun loadStart() {
        receiveEvent(EVENT_LOAD_START, null)
    }

    fun load(
        duration: Double,
        currentPosition: Double,
        videoWidth: Int,
        videoHeight: Int,
        videoTracks: WritableArray?,
        trackId: String?
    ) {
        val event = Arguments.createMap()
        event.putDouble(EVENT_PROP_DURATION, duration / 1000.0)
        event.putDouble(EVENT_PROP_CURRENT_TIME, currentPosition / 1000.0)
        val naturalSize = Arguments.createMap()
        naturalSize.putInt(EVENT_PROP_WIDTH, videoWidth)
        naturalSize.putInt(EVENT_PROP_HEIGHT, videoHeight)
        if (videoWidth > videoHeight) {
            naturalSize.putString(EVENT_PROP_ORIENTATION, "landscape")
        } else {
            naturalSize.putString(EVENT_PROP_ORIENTATION, "portrait")
        }
        event.putMap(EVENT_PROP_NATURAL_SIZE, naturalSize)
        event.putString(EVENT_PROP_TRACK_ID, trackId)
        event.putArray(EVENT_PROP_VIDEO_TRACKS, videoTracks)

        // TODO: Actually check if you can.
        event.putBoolean(EVENT_PROP_FAST_FORWARD, true)
        event.putBoolean(EVENT_PROP_SLOW_FORWARD, true)
        event.putBoolean(EVENT_PROP_SLOW_REVERSE, true)
        event.putBoolean(EVENT_PROP_REVERSE, true)
        event.putBoolean(EVENT_PROP_FAST_FORWARD, true)
        event.putBoolean(EVENT_PROP_STEP_BACKWARD, true)
        event.putBoolean(EVENT_PROP_STEP_FORWARD, true)
        receiveEvent(EVENT_LOAD, event)
    }

    fun progressChanged(
        currentPosition: Double,
        bufferedDuration: Double,
        seekableDuration: Double,
        currentPlaybackTime: Double
    ) {
        val event = Arguments.createMap()
        event.putDouble(EVENT_PROP_CURRENT_TIME, currentPosition / 1000.0)
        event.putDouble(EVENT_PROP_PLAYABLE_DURATION, bufferedDuration / 1000.0)
        event.putDouble(EVENT_PROP_SEEKABLE_DURATION, seekableDuration / 1000.0)
        event.putDouble(EVENT_PROP_CURRENT_PLAYBACK_TIME, currentPlaybackTime)
        receiveEvent(EVENT_PROGRESS, event)
    }

    fun bandwidthReport(bitRateEstimate: Double, height: Int, width: Int, id: String?) {
        val event = Arguments.createMap()
        event.putDouble(EVENT_PROP_BITRATE, bitRateEstimate)
        event.putInt(EVENT_PROP_WIDTH, width)
        event.putInt(EVENT_PROP_HEIGHT, height)
        event.putString(EVENT_PROP_TRACK_ID, id)
        receiveEvent(EVENT_BANDWIDTH, event)
    }

    fun seek(currentPosition: Long, seekTime: Long) {
        val event = Arguments.createMap()
        event.putDouble(EVENT_PROP_CURRENT_TIME, currentPosition / 1000.0)
        event.putDouble(EVENT_PROP_SEEK_TIME, seekTime / 1000.0)
        receiveEvent(EVENT_SEEK, event)
    }

    fun ready() {
        receiveEvent(EVENT_READY, null)
    }

    fun buffering(isBuffering: Boolean) {
        val map = Arguments.createMap()
        map.putBoolean(EVENT_PROP_IS_BUFFERING, isBuffering)
        receiveEvent(EVENT_BUFFER, map)
    }

    fun playbackStateChanged(isPlaying: Boolean) {
        val map = Arguments.createMap()
        map.putBoolean(EVENT_PROP_IS_PLAYING, isPlaying)
        receiveEvent(EVENT_PLAYBACK_STATE_CHANGED, map)
    }

    fun idle() {
        receiveEvent(EVENT_IDLE, null)
    }

    fun end() {
        receiveEvent(EVENT_END, null)
    }

    fun fullscreenWillPresent() {
        receiveEvent(EVENT_FULLSCREEN_WILL_PRESENT, null)
    }

    fun fullscreenDidPresent() {
        receiveEvent(EVENT_FULLSCREEN_DID_PRESENT, null)
    }

    fun fullscreenWillDismiss() {
        receiveEvent(EVENT_FULLSCREEN_WILL_DISMISS, null)
    }

    fun fullscreenDidDismiss() {
        receiveEvent(EVENT_FULLSCREEN_DID_DISMISS, null)
    }

    fun error(errorString: String?, exception: Exception) {
        _error(errorString, exception, "0001")
    }

    fun error(errorString: String?, exception: Exception, errorCode: String?) {
        _error(errorString, exception, errorCode)
    }

    fun _error(errorString: String?, exception: Exception, errorCode: String?) {
        // Prepare stack trace
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        exception.printStackTrace(pw)
        val stackTrace = sw.toString()
        val error = Arguments.createMap()
        error.putString(EVENT_PROP_ERROR_STRING, errorString)
        error.putString(EVENT_PROP_ERROR_EXCEPTION, exception.toString())
        error.putString(EVENT_PROP_ERROR_CODE, errorCode)
        error.putString(EVENT_PROP_ERROR_TRACE, stackTrace)
        val event = Arguments.createMap()
        event.putMap(EVENT_PROP_ERROR, error)
        receiveEvent(EVENT_ERROR, event)
    }

    fun playbackRateChange(rate: Float) {
        val map = Arguments.createMap()
        map.putDouble(EVENT_PROP_PLAYBACK_RATE, rate.toDouble())
        receiveEvent(EVENT_PLAYBACK_RATE_CHANGE, map)
    }

    fun timedMetadata(metadata: Metadata) {
        val metadataArray = Arguments.createArray()
        for (i in 0 until metadata.length()) {
            val entry = metadata[i]
            if (entry is Id3Frame) {
                val frame = entry
                var value = ""
                if (frame is TextInformationFrame) {
                    value = frame.value
                }
                val identifier = frame.id
                val map = Arguments.createMap()
                map.putString("identifier", identifier)
                map.putString("value", value)
                metadataArray.pushMap(map)
            } else if (entry is EventMessage) {
                val eventMessage = entry
                val map = Arguments.createMap()
                map.putString("identifier", eventMessage.schemeIdUri)
                map.putString("value", eventMessage.value)
                metadataArray.pushMap(map)
            }
        }
        val event = Arguments.createMap()
        event.putArray(EVENT_PROP_TIMED_METADATA, metadataArray)
        receiveEvent(EVENT_TIMED_METADATA, event)
    }

    fun audioFocusChanged(hasFocus: Boolean) {
        val map = Arguments.createMap()
        map.putBoolean(EVENT_PROP_HAS_AUDIO_FOCUS, hasFocus)
        receiveEvent(EVENT_AUDIO_FOCUS_CHANGE, map)
    }

    fun audioBecomingNoisy() {
        receiveEvent(EVENT_AUDIO_BECOMING_NOISY, null)
    }

    private fun receiveEvent(@VideoEvents type: String, event: WritableMap?) {
        eventEmitter.receiveEvent(viewId, type, event)
    }

    companion object {
        private const val EVENT_LOAD_START = "onVideoLoadStart"
        private const val EVENT_LOAD = "onVideoLoad"
        private const val EVENT_ERROR = "onVideoError"
        private const val EVENT_PROGRESS = "onVideoProgress"
        private const val EVENT_BANDWIDTH = "onVideoBandwidthUpdate"
        private const val EVENT_SEEK = "onVideoSeek"
        private const val EVENT_END = "onVideoEnd"
        private const val EVENT_FULLSCREEN_WILL_PRESENT = "onVideoFullscreenPlayerWillPresent"
        private const val EVENT_FULLSCREEN_DID_PRESENT = "onVideoFullscreenPlayerDidPresent"
        private const val EVENT_FULLSCREEN_WILL_DISMISS = "onVideoFullscreenPlayerWillDismiss"
        private const val EVENT_FULLSCREEN_DID_DISMISS = "onVideoFullscreenPlayerDidDismiss"
        private const val EVENT_STALLED = "onPlaybackStalled"
        private const val EVENT_RESUME = "onPlaybackResume"
        private const val EVENT_READY = "onReadyForDisplay"
        private const val EVENT_BUFFER = "onVideoBuffer"
        private const val EVENT_PLAYBACK_STATE_CHANGED = "onVideoPlaybackStateChanged"
        private const val EVENT_IDLE = "onVideoIdle"
        private const val EVENT_TIMED_METADATA = "onTimedMetadata"
        private const val EVENT_AUDIO_BECOMING_NOISY = "onVideoAudioBecomingNoisy"
        private const val EVENT_AUDIO_FOCUS_CHANGE = "onAudioFocusChanged"
        private const val EVENT_PLAYBACK_RATE_CHANGE = "onPlaybackRateChange"
        val Events = arrayOf(
            EVENT_LOAD_START,
            EVENT_LOAD,
            EVENT_ERROR,
            EVENT_PROGRESS,
            EVENT_SEEK,
            EVENT_END,
            EVENT_FULLSCREEN_WILL_PRESENT,
            EVENT_FULLSCREEN_DID_PRESENT,
            EVENT_FULLSCREEN_WILL_DISMISS,
            EVENT_FULLSCREEN_DID_DISMISS,
            EVENT_STALLED,
            EVENT_RESUME,
            EVENT_READY,
            EVENT_BUFFER,
            EVENT_PLAYBACK_STATE_CHANGED,
            EVENT_IDLE,
            EVENT_TIMED_METADATA,
            EVENT_AUDIO_BECOMING_NOISY,
            EVENT_AUDIO_FOCUS_CHANGE,
            EVENT_PLAYBACK_RATE_CHANGE,
            EVENT_BANDWIDTH
        )
        private const val EVENT_PROP_FAST_FORWARD = "canPlayFastForward"
        private const val EVENT_PROP_SLOW_FORWARD = "canPlaySlowForward"
        private const val EVENT_PROP_SLOW_REVERSE = "canPlaySlowReverse"
        private const val EVENT_PROP_REVERSE = "canPlayReverse"
        private const val EVENT_PROP_STEP_FORWARD = "canStepForward"
        private const val EVENT_PROP_STEP_BACKWARD = "canStepBackward"
        private const val EVENT_PROP_BUFFER_START = "bufferStart"
        private const val EVENT_PROP_BUFFER_END = "bufferEnd"
        private const val EVENT_PROP_DURATION = "duration"
        private const val EVENT_PROP_PLAYABLE_DURATION = "playableDuration"
        private const val EVENT_PROP_SEEKABLE_DURATION = "seekableDuration"
        private const val EVENT_PROP_CURRENT_TIME = "currentTime"
        private const val EVENT_PROP_CURRENT_PLAYBACK_TIME = "currentPlaybackTime"
        private const val EVENT_PROP_SEEK_TIME = "seekTime"
        private const val EVENT_PROP_NATURAL_SIZE = "naturalSize"
        private const val EVENT_PROP_TRACK_ID = "trackId"
        private const val EVENT_PROP_WIDTH = "width"
        private const val EVENT_PROP_HEIGHT = "height"
        private const val EVENT_PROP_ORIENTATION = "orientation"
        private const val EVENT_PROP_VIDEO_TRACKS = "videoTracks"
        private const val EVENT_PROP_HAS_AUDIO_FOCUS = "hasAudioFocus"
        private const val EVENT_PROP_IS_BUFFERING = "isBuffering"
        private const val EVENT_PROP_PLAYBACK_RATE = "playbackRate"
        private const val EVENT_PROP_ERROR = "error"
        private const val EVENT_PROP_ERROR_STRING = "errorString"
        private const val EVENT_PROP_ERROR_EXCEPTION = "errorException"
        private const val EVENT_PROP_ERROR_TRACE = "errorStackTrace"
        private const val EVENT_PROP_ERROR_CODE = "errorCode"
        private const val EVENT_PROP_TIMED_METADATA = "metadata"
        private const val EVENT_PROP_BITRATE = "bitrate"
        private const val EVENT_PROP_IS_PLAYING = "isPlaying"
    }
}
