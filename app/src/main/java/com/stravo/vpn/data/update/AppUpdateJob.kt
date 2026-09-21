package com.stravo.vpn.data.update

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.stravo.vpn.StravoApplication
import kotlinx.coroutines.*

/** Manifest: exported=false, permission=android.permission.BIND_JOB_SERVICE. */
class AppUpdateJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch {
            try {
                val updates = (application as StravoApplication).container.appUpdates
                updates.check()
                updates.download(automatic = true)
            } finally { if (isActive) jobFinished(params, false) }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return true
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        private const val JOB_ID = 7215
        fun schedule(context: Context) {
            val prefs = context.getSharedPreferences("stravo.app.updates", Context.MODE_PRIVATE)
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (!prefs.getBoolean("autoCheck", true) && !prefs.getBoolean("autoDownloadWifi", true)) {
                scheduler.cancel(JOB_ID)
            } else if (scheduler.allPendingJobs.none { it.id == JOB_ID }) {
                scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, AppUpdateJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(6 * 60 * 60 * 1000L)
                    .setPersisted(true) // Existing RECEIVE_BOOT_COMPLETED permission is required.
                    .build())
            }
        }
    }
}
