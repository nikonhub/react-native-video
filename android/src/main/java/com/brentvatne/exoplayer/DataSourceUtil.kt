package com.brentvatne.exoplayer

import com.facebook.react.bridge.ReactContext
import com.facebook.react.modules.network.CookieJarContainer
import com.facebook.react.modules.network.ForwardingCookieHandler
import com.facebook.react.modules.network.OkHttpClientProvider
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSource
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.HttpDataSource
import com.google.android.exoplayer2.util.Util
import okhttp3.Call
import okhttp3.JavaNetCookieJar

object DataSourceUtil {
    private var rawDataSourceFactory: DataSource.Factory? = null
    private var defaultDataSourceFactory: DataSource.Factory? = null
    private var defaultHttpDataSourceFactory: HttpDataSource.Factory? = null
    private var userAgent: String? = null
    fun setUserAgent(userAgent: String?) {
        DataSourceUtil.userAgent = userAgent
    }

    fun getUserAgent(context: ReactContext?): String? {
        if (userAgent == null) {
            userAgent = Util.getUserAgent(
                context!!, "ReactNativeVideo"
            )
        }
        return userAgent
    }

    fun getRawDataSourceFactory(context: ReactContext): DataSource.Factory? {
        if (rawDataSourceFactory == null) {
            rawDataSourceFactory = buildRawDataSourceFactory(context)
        }
        return rawDataSourceFactory
    }

    fun setRawDataSourceFactory(factory: DataSource.Factory?) {
        rawDataSourceFactory = factory
    }

    fun getDefaultDataSourceFactory(
        context: ReactContext,
        bandwidthMeter: DefaultBandwidthMeter?,
        requestHeaders: Map<String?, String?>?
    ): DataSource.Factory? {
        if (defaultDataSourceFactory == null || requestHeaders != null && !requestHeaders.isEmpty()) {
            defaultDataSourceFactory =
                buildDataSourceFactory(context, bandwidthMeter, requestHeaders)
        }
        return defaultDataSourceFactory
    }

    fun setDefaultDataSourceFactory(factory: DataSource.Factory?) {
        defaultDataSourceFactory = factory
    }

    fun getDefaultHttpDataSourceFactory(
        context: ReactContext,
        bandwidthMeter: DefaultBandwidthMeter?,
        requestHeaders: Map<String?, String?>?
    ): HttpDataSource.Factory? {
        if (defaultHttpDataSourceFactory == null || requestHeaders != null && !requestHeaders.isEmpty()) {
            defaultHttpDataSourceFactory =
                buildHttpDataSourceFactory(context, bandwidthMeter, requestHeaders)
        }
        return defaultHttpDataSourceFactory
    }

    fun setDefaultHttpDataSourceFactory(factory: HttpDataSource.Factory?) {
        defaultHttpDataSourceFactory = factory
    }

    private fun buildRawDataSourceFactory(context: ReactContext): DataSource.Factory {
        return RawResourceDataSourceFactory(context.applicationContext)
    }

    private fun buildDataSourceFactory(
        context: ReactContext,
        bandwidthMeter: DefaultBandwidthMeter?,
        requestHeaders: Map<String?, String?>?
    ): DataSource.Factory {
        return DefaultDataSource.Factory(
            context, buildHttpDataSourceFactory(context, bandwidthMeter, requestHeaders)
        )
    }

    private fun buildHttpDataSourceFactory(
        context: ReactContext,
        bandwidthMeter: DefaultBandwidthMeter?,
        requestHeaders: Map<String?, String?>?
    ): HttpDataSource.Factory {
        val client = OkHttpClientProvider.getOkHttpClient()
        val container = client.cookieJar as CookieJarContainer
        val handler = ForwardingCookieHandler(context)
        container.setCookieJar(JavaNetCookieJar(handler))
        val okHttpDataSourceFactory =
            OkHttpDataSource.Factory(client as Call.Factory).setUserAgent(getUserAgent(context))
                .setTransferListener(bandwidthMeter)
        if (requestHeaders != null) okHttpDataSourceFactory.setDefaultRequestProperties(
            requestHeaders as Map<String, String>
        )
        return okHttpDataSourceFactory
    }
}
