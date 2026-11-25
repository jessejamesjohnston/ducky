package com.merrimentlabs.ducky.widget

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.merrimentlabs.ducky.TimerController

class AddTimeAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val seconds = parameters[SECONDS] ?: return
        // Launchers give no touch feedback for widget taps: a light click here
        // is the only confirmation the tap landed, so do it before any I/O.
        context.getSystemService(Vibrator::class.java)
            ?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        TimerController.addTime(context, seconds * 1000L)
        TimerController.awaitWidgetDelivery()
    }

    companion object {
        val SECONDS = ActionParameters.Key<Int>("seconds")
    }
}

/** Stop / +1 min from the widget's ringing row. Session-guarded in the controller. */
class RingAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val kind = parameters[KIND] ?: return
        val session = parameters[SESSION] ?: return
        context.getSystemService(Vibrator::class.java)
            ?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        when (kind) {
            STOP -> TimerController.stopRinging(context, session)
            EXTEND -> TimerController.extendRing(context, session)
        }
        TimerController.awaitWidgetDelivery()
    }

    companion object {
        val KIND = ActionParameters.Key<String>("kind")
        val SESSION = ActionParameters.Key<Long>("session")
        const val STOP = "stop"
        const val EXTEND = "extend"
    }
}

