package com.brentvatne.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager

class AudioBecomingNoisyReceiver(context: Context) : BroadcastReceiver() {
    private val context: Context
    private var listener: BecomingNoisyListener = BecomingNoisyListener.Companion.NO_OP

    init {
        this.context = context.applicationContext
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent.action) {
            listener.onAudioBecomingNoisy()
        }
    }

    fun setListener(listener: BecomingNoisyListener) {
        this.listener = listener
        val intentFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        context.registerReceiver(this, intentFilter)
    }

    fun removeListener() {
        listener = BecomingNoisyListener.Companion.NO_OP
        try {
            context.unregisterReceiver(this)
        } catch (ignore: Exception) {
            // ignore if already unregistered
        }
    }
}
