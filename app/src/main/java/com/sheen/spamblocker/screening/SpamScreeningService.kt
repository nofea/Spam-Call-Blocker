package com.sheen.spamblocker.screening

import android.telecom.Call
import android.telecom.CallScreeningService

/**
 * Entry point for SPEC §2.1. Currently allows every call; the DecisionEngine
 * pipeline (§5) will be plugged in here.
 */
class SpamScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        respondToCall(callDetails, CallResponse.Builder().build())
    }
}
