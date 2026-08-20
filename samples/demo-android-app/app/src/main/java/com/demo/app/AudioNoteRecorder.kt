package com.demo.app

import android.content.Context
import android.media.MediaRecorder

class AudioNoteRecorder(private val context: Context) {

    fun startRecording() {
        val recorder = MediaRecorder()
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.prepare()
        recorder.start()
    }
}
