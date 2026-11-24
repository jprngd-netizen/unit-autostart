package com.gsalem.unitautostart

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
// androidx.media so embarca o MediaBrowserCompat legado (android.support.v4);
// os controles vem do MediaControllerCompat pelo session token do browser.
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaBrowserCompat.ConnectionCallback
import android.support.v4.media.session.MediaControllerCompat

/**
 * Escuta o despertar do radio (tela ligando) e, uma vez por despertar:
 *   1. abre o Ultrasonic, se nao houver musica tocando;
 *   2. manda play() pelo MediaBrowserService dele (nunca keyevent cego);
 *   3. volta para o HOME (o DashWheel).
 * Tudo dentro do aparelho: nao depende de rede, IP ou ADB.
 */
class WakeService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> screenOffAt = System.currentTimeMillis()
                Intent.ACTION_SCREEN_ON -> {
                    Log.i(TAG, "tela ligou")
                    // 3 s: o vendor ainda esta subindo a UI dele.
                    handler.postDelayed({ trigger(this@WakeService) }, START_DELAY_MS)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification())
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)
        instance = this
        Log.i(TAG, "servico de despertar ativo")
    }

    override fun onDestroy() {
        instance = null
        runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Início do rádio", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Início do rádio")
            .setContentText("Abrindo o DashWheel e a música ao ligar")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val TAG = "UnitAutostart"
        private const val CHANNEL = "wake"
        private const val NOTIFICATION_ID = 41
        private const val START_DELAY_MS = 3_000L
        /** A tela precisa ter ficado apagada este tempo para valer como um novo despertar. */
        private const val MIN_SCREEN_OFF_MS = 20_000L
        /** Dois SCREEN_ON em menos de 15 s sao o mesmo despertar. */
        private const val DEBOUNCE_MS = 15_000L

        /** Pacotes cuja tela nunca deve ser coberta. */
        private val PROTECTED = setOf(
            "com.williexing.android.apps.xcdvr1", // DVR
            "com.tw.service",                     // servico do vendor (camera de re)
            "com.android.dialer",
            "com.android.incallui"
        )

        /** Tentativas de fazer a musica comecar (browser, depois teclas). */
        private const val PLAY_TRIES = 3
        /** Teto para a biblioteca do player responder. */
        private const val LIBRARY_TIMEOUT_MS = 6_000L
        private const val PLAYER_PACKAGE = "org.moire.ultrasonic"
        private const val PLAYER_SERVICE = "org.moire.ultrasonic.service.PlaybackService"

        private var browser: MediaBrowserCompat? = null
        private var instance: WakeService? = null
        private var screenOffAt = 0L
        private var lastRunAt = 0L

        fun start(context: Context) {
            val intent = Intent(context, WakeService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        /** Roda a sequencia (o servico precisa estar vivo para o despertar ser escutado). */
        fun trigger(context: Context, manual: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!manual) {
                if (now - lastRunAt < DEBOUNCE_MS) {
                    Log.i(TAG, "despertar ignorado: outro SCREEN_ON ha menos de 15 s")
                    return
                }
                val offFor = if (screenOffAt > 0) now - screenOffAt else Long.MAX_VALUE
                if (offFor < MIN_SCREEN_OFF_MS) {
                    Log.i(TAG, "despertar ignorado: tela ficou apagada so ${offFor / 1000} s")
                    return
                }
            }
            lastRunAt = now
            Thread {
                try {
                    runSequence(context)
                } catch (t: Throwable) {
                    Log.e(TAG, "falha na sequencia", t)
                }
            }.start()
        }

        private fun runSequence(context: Context) {
            if (blockedByProtectedScreen(context)) {
                Log.i(TAG, "nada feito: tela protegida (camera/DVR/chamada) na frente")
                return
            }
            val audio = context.getSystemService(AudioManager::class.java)
            if (audio?.isMusicActive == true) {
                Log.i(TAG, "musica ja tocando; so volto ao HOME")
            } else {
                openPlayer(context)
                // O player leva alguns segundos para subir o PlaybackService.
                Thread.sleep(4_000)
                play(context)
                Thread.sleep(3_000)
            }
            if (blockedByProtectedScreen(context)) {
                Log.i(TAG, "HOME nao enviado: tela protegida")
                return
            }
            goHome(context)
            // Por ultimo e fora do caminho critico: se houver versao nova no
            // repositorio publico, baixa e abre o instalador (uma vez a cada 6 h).
            UpdateChecker.checkAndInstall(context)
        }

        private fun openPlayer(context: Context) {
            val launch = context.packageManager.getLaunchIntentForPackage(PLAYER_PACKAGE)
            if (launch == null) {
                Log.w(TAG, "$PLAYER_PACKAGE nao instalado")
                return
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            Log.i(TAG, "abrindo $PLAYER_PACKAGE")
            context.startActivity(launch)
        }

        /**
         * Liga a musica. Tentativa 1 manda play() (retoma a fila, se houver);
         * as seguintes pedem a biblioteca ao player e tocam aleatorio, porque um
         * processo morto dormindo volta sem fila. A tecla de midia e o ultimo
         * recurso, so com o player em primeiro plano. Tudo conferido por
         * isMusicActive — nunca um envio cego.
         */
        private fun play(context: Context) {
            val audio = context.getSystemService(AudioManager::class.java)
            repeat(PLAY_TRIES) { i ->
                if (audio.isMusicActive) {
                    Log.i(TAG, "musica ja tocando")
                    return
                }
                val fromLibrary = i > 0
                browserPlay(context, fromLibrary)
                Thread.sleep(1_500)
                if (!audio.isMusicActive) mediaKeyPlay(context, audio)
                Thread.sleep(2_000)
                if (audio.isMusicActive) {
                    Log.i(TAG, "musica tocando apos a tentativa ${i + 1}")
                    return
                }
                Log.w(TAG, "nada tocando apos a tentativa ${i + 1}")
            }
            Log.w(TAG, "musica nao comecou apos $PLAY_TRIES tentativas")
        }

        /**
         * Fala com o MediaBrowserService do player. O browser e os callbacks vivem
         * na thread principal (exigencia da lib); esta thread so espera o resultado.
         *
         * [fromLibrary] = true: em vez de play(), le a raiz da biblioteca, escolhe
         * um no aleatorio (ou "musicas") e toca com shuffle ligado.
         */
        private fun browserPlay(context: Context, fromLibrary: Boolean): Boolean {
            val main = Handler(Looper.getMainLooper())
            val done = java.util.concurrent.CountDownLatch(1)
            val acted = java.util.concurrent.atomic.AtomicBoolean(false)

            main.post {
                val callback = object : ConnectionCallback() {
                    override fun onConnected() {
                        val current = browser
                        try {
                            val token = current?.sessionToken
                            if (token == null) {
                                Log.w(TAG, "browser conectado sem session token")
                            } else if (!fromLibrary) {
                                MediaControllerCompat(context, token).transportControls.play()
                                acted.set(true)
                                Log.i(TAG, "play() enviado pelo MediaControllerCompat")
                                done.countDown()
                                return
                            }
                            val root = current?.root
                            if (root == null) {
                                Log.w(TAG, "biblioteca sem raiz")
                                done.countDown()
                                return
                            }
                            Log.i(TAG, "raiz da biblioteca: id=$root")
                            current.subscribe(root, object : MediaBrowserCompat.SubscriptionCallback() {
                                override fun onChildrenLoaded(
                                    parentId: String,
                                    children: MutableList<MediaBrowserCompat.MediaItem>
                                ) {
                                    children.forEach {
                                        Log.i(TAG, "item: id=${it.mediaId} titulo=${it.description.title} tocavel=${it.isPlayable}")
                                    }
                                    val pick = pickRandomNode(children)
                                    if (pick == null) {
                                        Log.w(TAG, "nenhum no tocavel na raiz da biblioteca")
                                    } else if (token != null) {
                                        val controls = MediaControllerCompat(context, token).transportControls
                                        // Shuffle antes de carregar: o player ja monta a fila embaralhada.
                                        runCatching {
                                            controls.setShuffleMode(android.support.v4.media.session.PlaybackStateCompat.SHUFFLE_MODE_ALL)
                                            Log.i(TAG, "shuffle ligado")
                                        }.onFailure { Log.w(TAG, "nao consegui ligar shuffle: ${it.message}") }
                                        controls.playFromMediaId(pick.mediaId, null)
                                        acted.set(true)
                                        Log.i(TAG, "tocando da biblioteca: ${pick.description.title} (${pick.mediaId})")
                                    }
                                    done.countDown()
                                }

                                override fun onError(parentId: String) {
                                    Log.w(TAG, "biblioteca nao carregou os filhos de $parentId")
                                    done.countDown()
                                }
                            })
                            // Rede lenta nao pode travar o despertar.
                            main.postDelayed({ done.countDown() }, LIBRARY_TIMEOUT_MS)
                        } catch (t: Throwable) {
                            Log.e(TAG, "falha ao falar com o player", t)
                            done.countDown()
                        }
                    }

                    override fun onConnectionFailed() {
                        Log.w(TAG, "MediaBrowser recusou a conexao")
                        done.countDown()
                    }

                    override fun onConnectionSuspended() {
                        Log.w(TAG, "MediaBrowser suspenso")
                        done.countDown()
                    }
                }
                browser = MediaBrowserCompat(context, ComponentName(PLAYER_PACKAGE, PLAYER_SERVICE), callback, null)
                Log.i(TAG, "conectando ao MediaBrowserService do player (biblioteca=$fromLibrary)")
                browser?.connect()
            }

            done.await(8, java.util.concurrent.TimeUnit.SECONDS)
            main.post { runCatching { browser?.disconnect() } }
            browser = null
            return acted.get()
        }

        /**
         * O no da raiz que rende musica: primeiro um de aleatorio, depois o de
         * musicas, depois qualquer tocavel. Compara por id e por titulo, em
         * portugues e em ingles, porque o id muda entre versoes do player.
         */
        private fun pickRandomNode(children: List<MediaBrowserCompat.MediaItem>): MediaBrowserCompat.MediaItem? {
            fun text(item: MediaBrowserCompat.MediaItem) =
                (item.mediaId + " " + (item.description.title?.toString() ?: "")).lowercase()

            val randomWords = listOf("random", "aleator", "shuffle", "surprise")
            val songsWords = listOf("song", "musica", "música", "track", "faixa")
            val playable = children.filter { it.isPlayable || it.isBrowsable }
            return playable.firstOrNull { item -> randomWords.any { it in text(item) } }
                ?: playable.firstOrNull { item -> songsWords.any { it in text(item) } }
                ?: playable.firstOrNull()
        }

        /** Tecla de midia so com o player em primeiro plano: nunca em outro app. */
        private fun mediaKeyPlay(context: Context, audio: AudioManager) {
            val top = foregroundPackage(context)
            if (top != PLAYER_PACKAGE) {
                Log.w(TAG, "tecla de midia NAO enviada: primeiro plano e $top")
                return
            }
            audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
            audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
            Log.i(TAG, "tecla de midia enviada")
        }

        private fun goHome(context: Context) {
            val home = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            Log.i(TAG, "voltando ao HOME")
            context.startActivity(home)
        }

        /** Primeiro plano pela UsageStats; sem a permissao, nao bloqueia nada. */
        private fun foregroundPackage(context: Context): String? {
            val manager = context.getSystemService(android.app.usage.UsageStatsManager::class.java) ?: return null
            val now = System.currentTimeMillis()
            val events = manager.queryEvents(now - 15_000, now)
            var last: String? = null
            val event = android.app.usage.UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) last = event.packageName
            }
            return last
        }

        private fun blockedByProtectedScreen(context: Context): Boolean =
            foregroundPackage(context)?.let { it in PROTECTED } ?: false
    }
}
