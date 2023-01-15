package com.brentvatne.exoplayer

import android.content.Context
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter
import com.google.android.exoplayer2.upstream.DefaultLoadErrorHandlingPolicy
import com.google.android.exoplayer2.upstream.LoadErrorHandlingPolicy

class DefaultReactExoplayerConfig(context: Context?) : ReactExoplayerConfig {
    override val bandwidthMeter: DefaultBandwidthMeter
    override var disableDisconnectError = false

    init {
        bandwidthMeter = DefaultBandwidthMeter.Builder(context!!).build()
    }

    override fun buildLoadErrorHandlingPolicy(minLoadRetryCount: Int): LoadErrorHandlingPolicy {
        return if (disableDisconnectError) {
            // Use custom error handling policy to prevent throwing an error when losing network connection
            ReactExoplayerLoadErrorHandlingPolicy(minLoadRetryCount)
        } else DefaultLoadErrorHandlingPolicy(minLoadRetryCount)
    }
}
