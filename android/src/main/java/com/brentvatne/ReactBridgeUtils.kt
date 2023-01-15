package com.brentvatne

import com.facebook.react.bridge.ReadableMap

/*
* This file define static helpers to parse in an easier way input props
 */
object ReactBridgeUtils {
    /*
    retrieve key from map as int. fallback is returned if not available
     */
    fun safeGetInt(map: ReadableMap?, key: String?, fallback: Int): Int {
        return if (map != null && map.hasKey(key!!) && !map.isNull(key)) map.getInt(key) else fallback
    }

    /*
    retrieve key from map as double. fallback is returned if not available
     */
    fun safeGetDouble(map: ReadableMap?, key: String?, fallback: Double): Double {
        return if (map != null && map.hasKey(key!!) && !map.isNull(key)) map.getDouble(key) else fallback
    }
}
