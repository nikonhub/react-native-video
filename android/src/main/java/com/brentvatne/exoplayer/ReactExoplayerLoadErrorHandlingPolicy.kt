package com.brentvatne.exoplayer

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.upstream.DefaultLoadErrorHandlingPolicy
import com.google.android.exoplayer2.upstream.HttpDataSource.HttpDataSourceException
import com.google.android.exoplayer2.upstream.LoadErrorHandlingPolicy.LoadErrorInfo

class ReactExoplayerLoadErrorHandlingPolicy(minLoadRetryCount: Int) :
    DefaultLoadErrorHandlingPolicy(minLoadRetryCount) {
    private var minLoadRetryCount = Int.MAX_VALUE

    init {
        this.minLoadRetryCount = minLoadRetryCount
    }

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long {
        return if (loadErrorInfo.exception is HttpDataSourceException &&
            (loadErrorInfo.exception.message === "Unable to connect" || loadErrorInfo.exception.message === "Software caused connection abort")
        ) {
            // Capture the error we get when there is no network connectivity and keep retrying it
            1000 // Retry every second
        } else if (loadErrorInfo.errorCount < minLoadRetryCount) {
            Math.min((loadErrorInfo.errorCount - 1) * 1000, 5000)
                .toLong() // Default timeout handling
        } else {
            C.TIME_UNSET // Done retrying and will return the error immediately
        }
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int {
        return Int.MAX_VALUE
    }
}
