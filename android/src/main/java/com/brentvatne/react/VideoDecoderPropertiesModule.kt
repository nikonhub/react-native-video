package com.brentvatne.react

import android.annotation.SuppressLint
import android.media.MediaCodecList
import android.media.MediaDrm
import android.media.MediaFormat
import android.media.UnsupportedSchemeException
import android.os.Build
import androidx.annotation.RequiresApi
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import java.util.UUID

@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
class VideoDecoderPropertiesModule(var reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(
        reactContext
    ) {
    override fun getName(): String {
        return "VideoDecoderProperties"
    }

    @SuppressLint("ObsoleteSdkInt")
    @ReactMethod
    fun getWidevineLevel(p: Promise) {
        var widevineLevel = 0
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
            p.resolve(widevineLevel)
            return
        }
        val WIDEVINE_UUID = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)
        val WIDEVINE_SECURITY_LEVEL_1 = "L1"
        val WIDEVINE_SECURITY_LEVEL_2 = "L2"
        val WIDEVINE_SECURITY_LEVEL_3 = "L3"
        val SECURITY_LEVEL_PROPERTY = "securityLevel"
        var securityProperty: String? = null
        try {
            val mediaDrm = MediaDrm(WIDEVINE_UUID)
            securityProperty = mediaDrm.getPropertyString(SECURITY_LEVEL_PROPERTY)
        } catch (e: UnsupportedSchemeException) {
            e.printStackTrace()
        }
        if (securityProperty == null) {
            p.resolve(widevineLevel)
            return
        }
        when (securityProperty) {
            WIDEVINE_SECURITY_LEVEL_1 -> {
                widevineLevel = 1
            }

            WIDEVINE_SECURITY_LEVEL_2 -> {
                widevineLevel = 2
            }

            WIDEVINE_SECURITY_LEVEL_3 -> {
                widevineLevel = 3
            }

            else -> {}
        }
        p.resolve(widevineLevel)
    }

    @SuppressLint("ObsoleteSdkInt")
    @ReactMethod
    fun isCodecSupported(mimeType: String?, width: Int, height: Int, p: Promise) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            p.resolve(false)
            return
        }
        val mRegularCodecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val format = MediaFormat.createVideoFormat(mimeType!!, width, height)
        val codecName = mRegularCodecs.findDecoderForFormat(format)
        if (codecName == null) {
            p.resolve(false)
        } else {
            p.resolve(true)
        }
    }

    @ReactMethod
    fun isHEVCSupported(p: Promise) {
        isCodecSupported("video/hevc", 1920, 1080, p)
    }
}
