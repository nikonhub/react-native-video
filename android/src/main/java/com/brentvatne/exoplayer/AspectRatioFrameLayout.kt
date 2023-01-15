/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.brentvatne.exoplayer

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * A [FrameLayout] that resizes itself to match a specified aspect ratio.
 */
class AspectRatioFrameLayout @JvmOverloads constructor(context: Context?,
                                                       attrs: AttributeSet? = null
) : FrameLayout(
    context!!, attrs
) {
    private var videoAspectRatio = 0f

    @ResizeMode.Mode
    internal var resizeMode = ResizeMode.RESIZE_MODE_FIT
    /**
     * Get the aspect ratio that this view should satisfy.
     *
     * @return widthHeightRatio The width to height ratio.
     */
    /**
     * Set the aspect ratio that this view should satisfy.
     *
     * @param widthHeightRatio The width to height ratio.
     */
    var aspectRatio: Float
        get() = videoAspectRatio
        set(widthHeightRatio) {
            if (videoAspectRatio != widthHeightRatio) {
                videoAspectRatio = widthHeightRatio
                requestLayout()
            }
        }

    fun invalidateAspectRatio() {
        videoAspectRatio = 0f
    }

    /**
     * Sets the resize mode which can be of value [ResizeMode.Mode]
     *
     * @param resizeMode The resize mode.
     */
    fun setResizeMode(@ResizeMode.Mode resizeMode: Int) {
        if (this.resizeMode != resizeMode) {
            this.resizeMode = resizeMode
            requestLayout()
        }
    }

    /**
     * Gets the resize mode which can be of value [ResizeMode.Mode]
     *
     * @return resizeMode The resize mode.
     */
    @ResizeMode.Mode
    fun getResizeMode(): Int {
        return resizeMode
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (videoAspectRatio == 0f) {
            // Aspect ratio not set.
            return
        }
        val measuredWidth = measuredWidth
        val measuredHeight = measuredHeight
        var width = measuredWidth
        var height = measuredHeight
        val viewAspectRatio = measuredWidth.toFloat() / measuredHeight
        val aspectDeformation = videoAspectRatio / viewAspectRatio - 1
        if (Math.abs(aspectDeformation) <= MAX_ASPECT_RATIO_DEFORMATION_FRACTION) {
            // We're within the allowed tolerance.
            return
        }
        when (resizeMode) {
            ResizeMode.RESIZE_MODE_FIXED_WIDTH -> height =
                (measuredWidth / videoAspectRatio).toInt()

            ResizeMode.RESIZE_MODE_FIXED_HEIGHT -> width =
                (measuredHeight * videoAspectRatio).toInt()

            ResizeMode.RESIZE_MODE_FILL -> {}
            ResizeMode.RESIZE_MODE_CENTER_CROP -> {
                width = (measuredHeight * videoAspectRatio).toInt()

                // Scale video if it doesn't fill the measuredWidth
                if (width < measuredWidth) {
                    val scaleFactor = measuredWidth.toFloat() / width
                    width = (width * scaleFactor).toInt()
                    height = (measuredHeight * scaleFactor).toInt()
                }
            }

            else -> if (aspectDeformation > 0) {
                height = (measuredWidth / videoAspectRatio).toInt()
            } else {
                width = (measuredHeight * videoAspectRatio).toInt()
            }
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }

    companion object {
        /**
         * The [FrameLayout] will not resize itself if the fractional difference between its natural
         * aspect ratio and the requested aspect ratio falls below this threshold.
         *
         *
         * This tolerance allows the view to occupy the whole of the screen when the requested aspect
         * ratio is very close, but not exactly equal to, the aspect ratio of the screen. This may reduce
         * the number of view layers that need to be composited by the underlying system, which can help
         * to reduce power consumption.
         */
        private const val MAX_ASPECT_RATIO_DEFORMATION_FRACTION = 0.01f
    }
}
