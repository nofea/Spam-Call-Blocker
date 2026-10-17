package com.sheen.spamblocker.screening

import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.TelecomManager
import com.sheen.spamblocker.di.AppScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Entry point for SPEC §2.1: maps `Call.Details` in and a `CallResponse` out. */
@AndroidEntryPoint
class SpamScreeningService : CallScreeningService() {

    @Inject lateinit var screener: CallScreener
    @Inject @AppScope lateinit var scope: CoroutineScope

    override fun onScreenCall(callDetails: Call.Details) {
        if (callDetails.callDirection == Call.Details.DIRECTION_OUTGOING) {
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }
        scope.launch {
            val result = screener.screen(
                handle = callDetails.handle?.schemeSpecificPart,
                presentation = presentationOf(callDetails.handlePresentation),
                timestampMillis = System.currentTimeMillis(),
            )
            respondToCall(callDetails, responseFor(result.decision))
        }
    }

    companion object {
        /** SPEC §2.2. `setSkipCallLog` is deliberately never used. */
        fun responseFor(decision: Decision): CallResponse = CallResponse.Builder().apply {
            when (decision) {
                Decision.REJECT -> setDisallowCall(true).setRejectCall(true).setSkipNotification(true)
                Decision.SILENCE -> setSilenceCall(true)
                Decision.WARN, Decision.ALLOW -> Unit
            }
        }.build()

        fun presentationOf(value: Int): Presentation = when (value) {
            TelecomManager.PRESENTATION_ALLOWED -> Presentation.ALLOWED
            TelecomManager.PRESENTATION_RESTRICTED -> Presentation.RESTRICTED
            TelecomManager.PRESENTATION_PAYPHONE -> Presentation.PAYPHONE
            else -> Presentation.UNKNOWN
        }
    }
}
