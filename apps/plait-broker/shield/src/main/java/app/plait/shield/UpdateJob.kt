package app.plait.shield

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.PersistableBundle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import rikka.shizuku.Shizuku

/**
 * The daily blocklist update: once a day, on unmetered Wi-Fi and with the battery not low, re-applies what was last
 * applied. Lists that did not change cost one small request each, and the hosts file is only rewritten when something
 * changed. Persisted, so it survives reboots; scheduled when blocklists are applied and cancelled when turned off.
 */
class UpdateJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        thread(name = "shield-update") {
            val done = runCatching { update() }.getOrElse { e ->
                Updater.prefs(this).edit().putLong(Updater.CHECKED_AT, System.currentTimeMillis())
                    .putString(Updater.RESULT, "Update failed: ${e.message}; trying again later").apply()
                false
            }
            jobFinished(params, !done)                       // not done: retry with JobScheduler's backoff
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true    // Wi-Fi went away mid-run: try again later

    /** True when finished (updated, nothing to do, or blocklists are off); false to be retried. */
    private fun update(): Boolean {
        val prefs = Updater.prefs(this)
        if (!prefs.getBoolean(Updater.ACTIVE, false)) {
            cancel(this)
            return true
        }
        val ids = prefs.getStringSet(Updater.APPLIED_LISTS, null).orEmpty()
        val lists = BLOCKLISTS.filter { it.id in ids }
        if (lists.isEmpty()) return true
        if (!waitForBroker()) {
            prefs.edit().putString(Updater.RESULT, "Broker was not reachable; trying again later").apply()
            return false
        }
        Updater.apply(this, lists, prefs.getString(Updater.APPLIED_ALLOW, "").orEmpty(), force = false)
        return true
    }

    /** Broker hands its Binder to every new process of an allowed app; give it a moment to arrive. */
    private fun waitForBroker(): Boolean {
        if (Broker.allowed()) return true
        val arrived = CountDownLatch(1)
        val listener = Shizuku.OnBinderReceivedListener { arrived.countDown() }
        Shizuku.addBinderReceivedListenerSticky(listener)
        try {
            arrived.await(20, TimeUnit.SECONDS)
        } finally {
            Shizuku.removeBinderReceivedListener(listener)
        }
        return Broker.allowed()
    }

    companion object {
        private const val JOB_ID = 1
        /** Bump when the job's constraints change, so an already scheduled job is replaced. */
        private const val SCHEDULE_VERSION = 1
        private const val VERSION_KEY = "schedule_version"

        /**
         * Schedules the daily update unless it already is. Rescheduling an existing periodic job restarts its period,
         * so opening the app every day must not do it.
         */
        fun schedule(context: Context) {
            val jobs = context.getSystemService(JobScheduler::class.java)
            val pending = jobs.getPendingJob(JOB_ID)
            if (pending != null && pending.extras.getInt(VERSION_KEY) == SCHEDULE_VERSION) return
            // Wi-Fi, and not a metered one such as a phone's hotspot. Cellular never qualifies, even when the
            // carrier marks it unmetered.
            val wifi = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                .build()
            jobs.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, UpdateJob::class.java))
                    .setRequiredNetwork(wifi)
                    .setRequiresBatteryNotLow(true)
                    .setPeriodic(TimeUnit.DAYS.toMillis(1))
                    .setPersisted(true)
                    .setExtras(PersistableBundle().apply { putInt(VERSION_KEY, SCHEDULE_VERSION) })
                    .build()
            )
        }

        fun cancel(context: Context) = context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }
}
