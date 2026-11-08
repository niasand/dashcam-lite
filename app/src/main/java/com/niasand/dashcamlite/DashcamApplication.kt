package com.niasand.dashcamlite

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.niasand.dashcamlite.session.DashcamRuntime

class DashcamApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                // 与原版 PlayActivity.onPause 一样：离开界面就恢复录像，连接先保持。
                if (DashcamRuntime.client != null) {
                    runCatching { DashcamRuntime.ensureRecording() }
                }
            }
        })
    }
}
