package com.fqapp.fqapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

// androidx.media:media 1.7.0 把这三个类只发在 android.support.v4.media 下
// （androidx/media/session/ 里只有 MediaButtonReceiver）。写成 androidx.media.session
// 会直接编译不过——别照直觉"修正"回 androidx。
// 它们留在 import 块里会被 ktlint 的 import-ordering 判违规（注释阻住自动修复），
// 所以把说明放在导入之后，导入本身按字典序排：android.* → androidx.* → java.*。

/**
 * 听书 / 听视频（listen mode）的前台保活服务：
 * 1. 提高进程优先级，防 MIUI / HyperOS / ColorOS 等厂商系统冻结后台进程导致音频中断。
 * 2. 常驻媒体通知卡片：展示当前书名/剧名与章节名，提供「上一章/集」、「播放/暂停」、
 *    「下一章/集」、「关闭」控制。
 * 3. 点击通知主体或系统媒体卡片快速拉起应用回到播放页。
 *
 * Note: 通知走 MediaStyle + MediaSessionCompat，不是普通通知——普通通知在 Android 12+
 * 折叠态不显示动作按钮，也不会进系统媒体卡片 / 锁屏媒体控制 / 耳机按键路由。
 *
 * 通知大图与媒体卡封面用当前作品封面（issue #123），对齐官方 `dn2/i.java`
 * （`PlayNotificationImpl`）：官方把 `bookInfo.thumbUrl` 交给
 * `ImageLoaderUtils.fetchBitmap` 下载解码，然后同一张 Bitmap 既写进通知的
 * `setLargeIcon`，也写进媒体会话的 `METADATA_KEY_ALBUM_ART`；拿不到或下载失败
 * 时回退成应用图标。
 */
class ListenKeepAliveService : Service() {

    private var currentTitle: String = ""
    private var currentCoverUrl: String = ""

    /** 通知大图的缓存（同一张封面只下载 + 解码一次）。 */
    private val coverCache = CoverBitmapCache<Bitmap>()

    /** 只是取封面，不阻塞 `onStartCommand`：网络请求不能跑在主线程。 */
    private val coverLoader = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fqapp-listen-cover").apply { isDaemon = true }
    }

    private var currentEpisode: String = ""
    private var isPlaying: Boolean = true
    private var hasPrev: Boolean = true
    private var hasNext: Boolean = true
    private var mediaSession: MediaSessionCompat? = null
    private var positionMs: Long = 0L
    private var durationMs: Long = 0L
    private var speed: Float = 1f

    /**
     * 播放期间的 PARTIAL_WAKE_LOCK。
     *
     * ExoPlayer 自带的 wake lock 只在「正在出音频」时持有，章节自动切换的取源/建播放器
     * 空档（[audio_playback_controller] 后台 `_openChapter` → 网络 `_fetchSource`）里 CPU
     * 一旦随息屏进入休眠，Dart 定时器与取源协程会被 Doze 冻结，30s 取源超时后抛错 →
     * 用户看到「播放出错，请稍后重试」。这里用服务级 wake lock 覆盖整个播放会话（含切章
     * 空档），暂停/停止即释放，避免长期耗电。WAKE_LOCK 权限见 AndroidManifest。
     *
     * 切章空档 Dart 推来的 `playing` 是 false（`_syncForeground` 的 isPlaying 含
     * `!_loading`，媒体卡片在取源期间就该显示暂停态），如果立刻释放 wake lock，恰好在
     * 最需要 CPU 的取源窗口把锁交了出去。所以暂停态走宽限释放：60s 后复查仍未播放才
     * 真正释放——一次取源最多 30s 超时，宽限窗口足够覆盖；真正的长时间暂停照样省电。
     */
    private var wakeLock: PowerManager.WakeLock? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var releasePending: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    /** 播放中持有、暂停/停止走宽限释放（见 [wakeLock] 注释）。 */
    private fun syncWakeLock(playing: Boolean) {
        if (playing) {
            releasePending?.let { mainHandler.removeCallbacks(it) }
            releasePending = null
            if (wakeLock?.isHeld == true) return
            val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = manager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            )
            lock.setReferenceCounted(false)
            try {
                lock.acquire()
                wakeLock = lock
            } catch (error: Throwable) {
                Log.w(TAG, "listen wake lock failed", error)
                runCatching { lock.release() }
            }
        } else {
            scheduleWakeLockRelease()
        }
    }

    /**
     * 宽限释放：等 [WAKE_LOCK_RELEASE_GRACE_MS] 后复查，仍在播放（宽限期内切章
     * 完成会再推 playing=true 并取消本回调）就不释放；否则交还 CPU。
     * CPU 休眠只可能发生在锁已释放之后，所以这个回调要么准时执行、要么晚执行，
     * 晚执行的代价只是锁多持有片刻，不影响正确性。
     */
    private fun scheduleWakeLockRelease() {
        releasePending?.let { mainHandler.removeCallbacks(it) }
        val action = Runnable {
            releasePending = null
            if (isPlaying) return@Runnable
            wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
            wakeLock = null
        }
        releasePending = action
        mainHandler.postDelayed(action, WAKE_LOCK_RELEASE_GRACE_MS)
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        releasePending?.let { mainHandler.removeCallbacks(it) }
        releasePending = null
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        wakeLock = null
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NOTIFICATION_ID)
        mediaSession?.release()
        mediaSession = null
        coverLoader.shutdownNow()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.listen_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.listen_channel_name)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action != null) {
            when (action) {
                ACTION_PLAY_PAUSE -> {
                    NativePlayerPlugin.dispatchNotificationAction(ACTION_NAME_PLAY_PAUSE)
                    return START_NOT_STICKY
                }

                ACTION_PREV -> {
                    NativePlayerPlugin.dispatchNotificationAction(ACTION_NAME_PREV)
                    return START_NOT_STICKY
                }

                ACTION_NEXT -> {
                    NativePlayerPlugin.dispatchNotificationAction(ACTION_NAME_NEXT)
                    return START_NOT_STICKY
                }

                ACTION_STOP -> {
                    NativePlayerPlugin.dispatchNotificationAction(ACTION_NAME_STOP)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }

        val title = intent?.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
            ?: currentTitle.takeIf { it.isNotBlank() }
            ?: getString(R.string.listen_notification_title)
        val episode = intent?.getStringExtra(EXTRA_EPISODE)
            ?: currentEpisode
        val coverUrl = intent?.getStringExtra(EXTRA_COVER_URL) ?: currentCoverUrl
        val playing = intent?.getBooleanExtra(EXTRA_PLAYING, isPlaying) ?: isPlaying
        val prev = intent?.getBooleanExtra(EXTRA_HAS_PREV, hasPrev) ?: hasPrev
        val next = intent?.getBooleanExtra(EXTRA_HAS_NEXT, hasNext) ?: hasNext
        val position = intent?.getLongExtra(EXTRA_POSITION_MS, positionMs) ?: positionMs
        val duration = intent?.getLongExtra(EXTRA_DURATION_MS, durationMs) ?: durationMs
        val rate = intent?.getFloatExtra(EXTRA_SPEED, speed) ?: speed

        currentTitle = title
        currentEpisode = episode
        currentCoverUrl = coverUrl
        isPlaying = playing
        hasPrev = prev
        hasNext = next
        positionMs = position
        durationMs = duration
        speed = rate
        syncWakeLock(playing)

        val notification = buildNotification(title, episode, isPlaying, hasPrev, hasNext)
        syncPlaybackState(isPlaying, positionMs, durationMs, speed)
        // 封面是异步的：当前这一张还在路上时先用应用图标顶上，取到之后再刷一遍
        // 通知（官方 `dn2/i.java` 的 `FMPlayerSDK.updateNotification` 是同一做法）。
        ensureCover(coverUrl)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(title: String, episode: String, playing: Boolean, hasPrev: Boolean, hasNext: Boolean): Notification {
        val text = episode.ifBlank { getString(R.string.listen_notification_text) }

        val contentPendingIntent = contentIntent()

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentPendingIntent)
            .setOngoing(playing)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // 媒体通知样式：折叠态直接露出控制，并进系统媒体卡片/锁屏媒体控制。
            // 普通通知在 Android 12+ 会把动作按钮藏在展开态里，且不进媒体区。
            .setStyle(
                MediaStyle()
                    .setMediaSession(ensureMediaSession().sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        // 大图 = 当前作品封面。没封面（或还没下载完）时不设，系统会用应用
        // 图标补位——这正是 issue #123 里「一大一小两个应用图标」的现状。
        coverCache.value?.let { builder.setLargeIcon(it) }

        // 上一章 / 上一集
        val prevIntent = PendingIntent.getService(
            this,
            REQUEST_PREV,
            Intent(this, ListenKeepAliveService::class.java).apply { action = ACTION_PREV },
            flags
        )
        builder.addAction(
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_previous,
                getString(R.string.listen_prev),
                prevIntent
            ).build()
        )

        // 播放 / 暂停
        val playPauseIcon = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseText = if (playing) getString(R.string.listen_pause) else getString(R.string.listen_play)
        val playPauseIntent = PendingIntent.getService(
            this,
            REQUEST_PLAY_PAUSE,
            Intent(this, ListenKeepAliveService::class.java).apply { action = ACTION_PLAY_PAUSE },
            flags
        )
        builder.addAction(
            NotificationCompat.Action.Builder(
                playPauseIcon,
                playPauseText,
                playPauseIntent
            ).build()
        )

        // 下一章 / 下一集
        val nextIntent = PendingIntent.getService(
            this,
            REQUEST_NEXT,
            Intent(this, ListenKeepAliveService::class.java).apply { action = ACTION_NEXT },
            flags
        )
        builder.addAction(
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_next,
                getString(R.string.listen_next),
                nextIntent
            ).build()
        )

        // 关闭：暂停并撤掉常驻通知。播放中通知是 ongoing，没有这个按钮用户划不掉它。
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, ListenKeepAliveService::class.java).apply { action = ACTION_STOP },
            flags
        )
        builder.addAction(
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.listen_stop),
                stopIntent
            ).build()
        )

        return builder.build()
    }

    /**
     * 取当前封面的 Bitmap。只在 URL 变了的时候真的去下载；同一个 URL 只解码一次。
     *
     * 取到之后刷一次通知与媒体元数据——系统把大图缓存在通知对象里，只 setLargeIcon
     * 而不重新 notify 是不会更新的（官方 `dn2/i.java` 的 `FMPlayerSDK.updateNotification`
     * 就是这一步）。
     */
    private fun ensureCover(url: String) {
        if (!coverCache.select(url)) return
        // 没有封面：`select` 已经把缓存清空了，下一次 buildNotification 自然不
        // 带大图，不会把上一本的封面留在通知上；此刻也不需要提前 notify。
        if (url.isBlank()) return
        coverLoader.execute {
            val bitmap = downloadCover(url)
            // 回到主线程再动通知 / 媒体会话。
            Handler(Looper.getMainLooper()).post {
                // 下载期间又换了书：丢掉这份结果（官方用 URL 比较做同一件事）。
                if (!coverCache.accept(url, bitmap)) return@post
                refreshNotification()
            }
        }
    }

    /** 用当前状态重建并重发通知（通知栏与媒体会话一起刷）。 */
    private fun refreshNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(currentTitle, currentEpisode, isPlaying, hasPrev, hasNext)
        )
        syncPlaybackState(isPlaying, positionMs, durationMs, speed)
    }

    /** 下载 + 解码封面。失败一律返回 null，调用方按「没有封面」处理。 */
    private fun downloadCover(url: String): Bitmap? {
        coverCache.value?.let { return it }
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = COVER_TIMEOUT_MS
            connection.readTimeout = COVER_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "fqapp")
            try {
                // 通知大图最大也就 256dp@3x 上下，按 512px 采样解码，别把整张
                // 原图读进内存。
                val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                connection.inputStream.use { BitmapFactory.decodeStream(it, null, options) }
            } finally {
                connection.disconnect()
            }
        } catch (error: IOException) {
            Log.w(TAG, "listen cover download failed", error)
            null
        } catch (error: SecurityException) {
            // 缺少 INTERNET 权限或明文 HTTP 被禁：当作没有封面处理。
            Log.w(TAG, "listen cover blocked", error)
            null
        }
    }

    /** 点击通知卡片 / 系统媒体卡片都拉起主界面。 */
    private fun contentIntent(): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        } ?: return null
        return PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 通知用的媒体会话：系统媒体卡片、锁屏控制与耳机/蓝牙按键都靠它路由。
     * 回调与通知按钮共用 [NativePlayerPlugin.dispatchNotificationAction] 这一条通路，
     * 不另起一套控制逻辑。
     */
    private fun ensureMediaSession(): MediaSessionCompat {
        mediaSession?.let { return it }
        val session = MediaSessionCompat(this, MEDIA_SESSION_TAG)
        session.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = NativePlayerPlugin.dispatchNotificationAction(ACTION_NAME_PLAY_PAUSE)

            override fun onPause() = NativePlayerPlugin.dispatchNotificationAction(ACTION_NAME_PLAY_PAUSE)

            override fun onSkipToPrevious() = NativePlayerPlugin.dispatchNotificationAction(ACTION_NAME_PREV)

            override fun onSkipToNext() = NativePlayerPlugin.dispatchNotificationAction(ACTION_NAME_NEXT)

            override fun onStop() = NativePlayerPlugin.dispatchNotificationAction(ACTION_NAME_STOP)
        })
        session.setSessionActivity(contentIntent())
        session.isActive = true
        mediaSession = session
        return session
    }

    /**
     * 把播放状态与时长告诉系统：系统媒体卡片的进度条与两侧时间就来自这里。
     *
     * [positionMs] + [speed] 交给 `setState` 后由系统**自行外推**（位置 = position +
     * 经过时间 × speed），所以不必按帧刷新，只在播放/暂停、切章、拖动、倍速变化时同步即可。
     * 总时长必须放进 [MediaMetadataCompat]，否则卡片两侧都是 00:00。
     */
    private fun syncPlaybackState(playing: Boolean, positionMs: Long, durationMs: Long, speed: Float) {
        val session = ensureMediaSession()
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentEpisode)
            .putLong(
                MediaMetadataCompat.METADATA_KEY_DURATION,
                durationMs.coerceAtLeast(0L)
            )
        // 系统媒体卡片 / 锁屏的封面来自这里（官方 `w06/a.java` 的
        // `METADATA_KEY_ALBUM_ART`）。通知的 setLargeIcon 与它是同一张 Bitmap。
        coverCache.value?.let {
            metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
        }
        session.setMetadata(metadata.build())
        // NOTE: ACTION_SEEK_TO is deliberately NOT advertised. The session
        // previously claimed it without an onSeekTo override, so the system
        // media card offered a seek bar whose drags went nowhere. Real seek
        // forwarding needs a Dart-side receiver first: the method channel
        // only carries a String action and no handler has a 'seek' case, so
        // a Kotlin-side onSeekTo today would be silently dropped. Re-add
        // the flag together with onSeekTo + a Dart seek handler, not alone.
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_STOP
        val state = if (playing) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    state,
                    positionMs.coerceAtLeast(0L),
                    if (speed > 0f) speed else 1f
                )
                .build()
        )
    }

    companion object {
        const val CHANNEL_ID = "listen_playback"
        const val NOTIFICATION_ID = 47

        const val EXTRA_TITLE = "title"
        const val EXTRA_EPISODE = "episode"
        const val EXTRA_PLAYING = "playing"
        const val EXTRA_HAS_PREV = "hasPrev"
        const val EXTRA_HAS_NEXT = "hasNext"
        const val EXTRA_POSITION_MS = "positionMs"
        const val EXTRA_DURATION_MS = "durationMs"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_COVER_URL = "coverUrl"

        const val ACTION_PLAY_PAUSE = "com.fqapp.fqapp.ACTION_PLAY_PAUSE"
        const val ACTION_PREV = "com.fqapp.fqapp.ACTION_PREV"
        const val ACTION_NEXT = "com.fqapp.fqapp.ACTION_NEXT"
        const val ACTION_STOP = "com.fqapp.fqapp.ACTION_STOP"

        private const val REQUEST_PREV = 101
        private const val REQUEST_PLAY_PAUSE = 102
        private const val REQUEST_NEXT = 103

        private const val REQUEST_STOP = 104

        private const val MEDIA_SESSION_TAG = "fqapp-listen"

        private const val WAKE_LOCK_TAG = "fqapp::listen-playback"

        /**
         * 暂停态的宽限释放窗口。取源协程的失败上限是 Dart 侧 30s 超时，60s 的
         * 宽限覆盖「一次切章 + 重试」绰绰有余；真暂停 60s 后正常交还 CPU。
         */
        private const val WAKE_LOCK_RELEASE_GRACE_MS = 60_000L

        private const val TAG = "fqapp-listen"

        /** 封面下载的建连 / 读超时：后台服务里不能挂住太久。 */
        private const val COVER_TIMEOUT_MS = 8_000

        /** Dart 侧 `_handleRemoteAction` 认识的动作名；通知按钮与媒体会话共用一份。 */
        private const val ACTION_NAME_PLAY_PAUSE = "playPause"
        private const val ACTION_NAME_PREV = "prev"
        private const val ACTION_NAME_NEXT = "next"
        private const val ACTION_NAME_STOP = "stop"
    }
}
