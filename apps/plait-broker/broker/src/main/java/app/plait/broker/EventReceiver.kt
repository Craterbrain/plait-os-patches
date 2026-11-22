package app.plait.broker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Turns the daemon's events (an app asked for something that now waits in the list, or hit a call that is never allowed)
 * into a quiet notification. Only the shell/system may send it (manifest permission); the text is built here from package
 * names, so nothing an app controls is shown unescaped beyond its own label.
 */
class EventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra("kind") ?: return
        val uid = intent.getIntExtra("uid", -1)
        val pkg = intent.getStringExtra("pkg") ?: return
        val what = intent.getStringExtra("what") ?: return
        val target = intent.getStringExtra("target")
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        val pm = context.packageManager
        fun label(p: String) = runCatching { pm.getApplicationInfo(p, 0).loadLabel(pm).toString() }.getOrDefault(p)
        val subject = label(pkg) + " asked to " + what.replaceFirstChar { it.lowercase() } + (target?.let { " " + label(it) } ?: "")
        val body = if (kind == "request") "Added to its list, switched off. Tap to review." else "Refused: it is never allowed through the broker."

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Requests", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Apps that asked the broker for something, and refusals"
            }
        )
        val open = PendingIntent.getActivity(
            context, uid,
            Intent(context, MainActivity::class.java).putExtra("uid", uid).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        nm.notify(
            (kind + uid + what + target).hashCode(),
            android.app.Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("Broker request")
                .setContentText(subject)
                .setStyle(android.app.Notification.BigTextStyle().bigText(subject + "\n" + body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setGroup(CHANNEL)
                .build()
        )
    }

    private companion object {
        const val CHANNEL = "requests"
    }
}
