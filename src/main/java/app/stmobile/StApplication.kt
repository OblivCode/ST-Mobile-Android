package app.stmobile

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import app.stmobile.node.NodeService

/**
 * Custom application class providing centralized activity lifecycle tracking
 * with a 1000ms debounce to filter out screen rotations and configuration changes.
 */
class StApplication : Application(), Application.ActivityLifecycleCallbacks {

    private val handler = Handler(Looper.getMainLooper())
    private var startedActivityCount = 0

    private val backgroundRunnable = Runnable {
        if (startedActivityCount == 0) {
            isAppInForeground = false
            NodeService.onAppBackgrounded(this)
        }
    }

    // @Volatile guarantees immediate cross-thread memory visibility:
    // written on the main UI thread by ActivityLifecycleCallbacks,
    // read by background worker threads in NodeService without stale CPU cache delay.
    @Volatile
    var isAppInForeground: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        handler.removeCallbacks(backgroundRunnable)
        startedActivityCount++
        if (startedActivityCount == 1) {
            isAppInForeground = true
            NodeService.onAppForegrounded()
        }
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivityCount = maxOf(0, startedActivityCount - 1)
        if (startedActivityCount == 0) {
            // Debounce configuration changes (rotation/density change: ~50-200ms)
            handler.removeCallbacks(backgroundRunnable)
            handler.postDelayed(backgroundRunnable, DEBOUNCE_DELAY_MS)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    companion object {
        const val DEBOUNCE_DELAY_MS = 1000L

        // Ensures application instance reference is immediately published across all CPU cores
        @Volatile
        var instance: StApplication? = null
            private set
    }
}
