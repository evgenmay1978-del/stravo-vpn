package com.stravo.vpn.data.subscription

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.stravo.vpn.StravoApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/** OS-managed, network-constrained refresh of explicitly added, enabled sources. */
class SubscriptionRefreshJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        job = scope.launch {
            val container = (application as StravoApplication).container
            try {
                container.subscriptionMutex.withLock {
                    val due = container.subscriptions.sources.value.filter {
                        it.isRefreshDue() && container.subscriptionImporter.hasSourceUrl(it.id)
                    }
                    for (source in due) {
                        when (val result = container.subscriptionImporter.refreshSource(source.id)) {
                            is ImportOutcome.Success -> {
                                val before = container.subscriptions.nodes.value.filter { it.sourceId == source.id }.map { it.id }
                                val saved = container.subscriptions.applyImport(result.subscription, result.nodes)
                                val session = com.stravo.vpn.engine.box.TunnelSession(this@SubscriptionRefreshJob)
                                if (saved && session.wanted && session.sourceId == source.id &&
                                    container.vpnEngine.observeState().value.state.isActive &&
                                    before != result.nodes.map { it.id }) {
                                    runCatching {
                                        androidx.core.content.ContextCompat.startForegroundService(this@SubscriptionRefreshJob,
                                            android.content.Intent(this@SubscriptionRefreshJob,
                                                com.stravo.vpn.engine.box.StravoVpnService::class.java)
                                                .setAction(com.stravo.vpn.engine.box.StravoVpnService.ACTION_RESUME))
                                    }
                                }
                            }
                            is ImportOutcome.Failure -> Unit // Keep old profiles; retry at the next scheduled opportunity.
                        }
                    }
                }
            } finally {
                if (isActive) jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val JOB_ID = 7214
        fun schedule(context: Context) {
            val container = (context.applicationContext as StravoApplication).container
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            val needed = container.subscriptions.sources.value.any {
                it.enabled && it.updateIntervalHours > 0 && container.subscriptionImporter.hasSourceUrl(it.id)
            }
            if (!needed) {
                scheduler.cancel(JOB_ID)
            } else if (scheduler.allPendingJobs.none { it.id == JOB_ID }) {
                scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, SubscriptionRefreshJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(60 * 60 * 1000L)
                    .setPersisted(true)
                    .build())
            }
        }
    }
}
