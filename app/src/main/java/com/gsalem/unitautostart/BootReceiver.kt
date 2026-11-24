package com.gsalem.unitautostart

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** Sobe o servico no boot (o raro caso de reboot) para o despertar ja ser escutado. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "boot/replace recebido: ${intent.action}")
        WakeService.start(context)
    }

    private companion object {
        const val TAG = "UnitAutostart"
    }
}
