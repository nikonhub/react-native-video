package com.brentvatne.exoplayer

import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter
import com.google.android.exoplayer2.upstream.LoadErrorHandlingPolicy

/**
 * Extension points to configure the Exoplayer instance
 */
interface ReactExoplayerConfig {
    fun buildLoadErrorHandlingPolicy(minLoadRetryCount: Int): LoadErrorHandlingPolicy
    var disableDisconnectError: Boolean
    val bandwidthMeter: DefaultBandwidthMeter
}
