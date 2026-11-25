package com.merrimentlabs.ducky

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    TimerController.ACTION_FIRE -> TimerController.onAlarmFired(context)
                    TimerController.ACTION_CANCEL -> TimerController.cancel(context)
                    TimerController.ACTION_PAUSE -> TimerController.pause(context)
                    TimerController.ACTION_RESUME -> TimerController.resume(context)
                    TimerController.ACTION_TICK -> TimerController.onTick(context)
                    // Ring actions carry the session they were drawn for; a
                    // missing session (0) can't match any ring and is dropped.
                    TimerController.ACTION_STOP_RING ->
                        TimerController.stopRinging(context, intent.ringSession())
                    TimerController.ACTION_EXTEND_RING ->
                        TimerController.extendRing(context, intent.ringSession())
                    // Boot: re-arm or re-ring from the persisted state (may start the ring
                    // service).
                    Intent.ACTION_BOOT_COMPLETED -> TimerController.rescheduleAfterBoot(context)
                    // An update of this app kills a ringing service, and any alarm that outlived it
                    // is aimed at the old build's components: the same re-derive, after clearing
                    // them. An update that renames the launcher aliases also drops the enabled
                    // one, so put the icon back on the user's color without waiting for a visit.
                    Intent.ACTION_MY_PACKAGE_REPLACED -> {
                        TimerController.onPackageReplaced(context)
                        runCatching { LauncherIcon.apply(context, TimerStore.settings(context).seedColorArgb) }
                    }
                    // TIME_SET only: a time-zone change leaves epoch millis
                    // (the deadline, the alarm, the chronometers) untouched.
                    Intent.ACTION_TIME_CHANGED -> TimerController.onTimeChanged(context)
                }
                TimerController.awaitWidgetDelivery()
            } finally {
                pending.finish()
            }
        }
    }

    private fun Intent.ringSession(): Long =
        getLongExtra(TimerController.EXTRA_RING_SESSION, 0L)
}
