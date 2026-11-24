package com.gsalem.unitautostart

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Gatilhos manuais (teste/instalacao):
 *   RUN   -> sobe o servico e roda a sequencia agora, como se o radio tivesse acordado;
 *   START -> so sobe o servico (usado pelo instalador, sem mexer na tela).
 */
class RunReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_START) {
            Log.i(TAG, "START: subindo o servico apenas")
            WakeService.start(context)
            return
        }
        Log.i(TAG, "RUN manual recebido")
        WakeService.start(context)
        WakeService.trigger(context, manual = true)
    }

    private companion object {
        const val TAG = "UnitAutostart"
        const val ACTION_START = "com.gsalem.unitautostart.START"
    }
}
