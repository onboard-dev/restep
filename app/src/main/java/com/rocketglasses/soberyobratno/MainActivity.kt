package com.rocketglasses.soberyobratno

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var store: SessionStore
    private lateinit var camera: PhotoCapture
    private lateinit var hud: Hud
    private lateinit var voice: VoiceEngine
    private lateinit var server: TransferServer
    private lateinit var directSync: DirectSync
    private var pendingPhoto: ByteArray? = null
    private var pendingTakenAt = 0L
    // 撮影直後に数秒だけ見せる写真（縮小版）。
    private var preview: Bitmap? = null
    private val endPreview = Runnable { preview?.recycle(); preview = null; hud.invalidate() }
    private lateinit var album: StepMemoAlbum
    // アルバムへの保存（音声の圧縮を含む）は順番に、画面を止めずに行う。
    private val albumWriter = Executors.newSingleThreadExecutor()
    private var status = "準備完了"
    private var syncPage = false
    private var capturing = false
    private var finishRequested = false
    private var exitAfterFinish = false
    private var quietMode = false
    private val hideMenu = Runnable {
        if (!syncPage && !capturing && pendingPhoto == null && store.current() != null) {
            quietMode = true
            hud.invalidate()
        }
    }
    private var pairCode = ""
    private var lastSwipeAt = 0L
    private var lastSwipeDirection = 0
    private var lastDoubleTapAt = 0L
    private val wifi by lazy { applicationContext.getSystemService(WIFI_SERVICE) as WifiManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = SessionStore(this)
        if (store.sessions().length() == 0) store.newSession()
        camera = PhotoCapture(this)
        album = StepMemoAlbum(this)
        pairCode = getPreferences(MODE_PRIVATE).getString("pairCode", null) ?: run {
            val code = (100000 + SecureRandom().nextInt(900000)).toString()
            getPreferences(MODE_PRIVATE).edit().putString("pairCode", code).apply()
            code
        }
        hud = Hud()
        directSync = DirectSync(this) { hud.invalidate() }
        server = TransferServer(store, pairCode, { directSync.token },
            port = if (packageName.endsWith(".inputtest")) 8766 else 8765,
            onSynced = { ids -> markSynced(ids) }) { id ->
            val completed = java.util.concurrent.CountDownLatch(1)
            var allowed = false
            var failure: Exception? = null
            runOnUiThread {
                try {
                    val isCurrent = store.current()?.optString("id") == id
                    if (!isCurrent || (!capturing && pendingPhoto == null && !finishRequested)) {
                        store.deleteSession(id)
                        allowed = true
                        if (isCurrent) voice.cancelNote()
                        setStatus("スマホから記録を削除しました")
                        showMenu(false)
                    }
                } catch (e: Exception) { failure = e }
                finally { completed.countDown() }
            }
            check(completed.await(15, java.util.concurrent.TimeUnit.SECONDS)) { "Deletion timed out" }
            failure?.let { throw it }
            allowed
        }
        setContentView(hud)
        voice = VoiceEngine(this,
            onCommand = { command -> runOnUiThread { handleCommand(command) } },
            onNote = { note, audio -> runOnUiThread { savePhoto(note, audio) } },
            onStatus = { message -> runOnUiThread { setStatus(message) } })
        ensurePermissions()
        debugIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        debugIntent(intent)
    }

    private fun debugIntent(intent: Intent?) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        when (intent?.getStringExtra("debugAction")) {
            "directSync" -> openDirectSync()
            "stopSync" -> { directSync.stop(); syncPage = false; showMenu() }
            "photo" -> takePhoto()
            "pendingPhotoFixture" -> if (packageName.endsWith(".inputtest")) {
                pendingPhoto = File(cacheDir, "input-fixture.jpg").readBytes()
                voice.expectNote("gesture test note")
                showMenu(false)
            }
            "doubleTap" -> {
                val id = android.view.InputDevice.getDeviceIds().firstOrNull {
                    android.view.InputDevice.getDevice(it)?.name?.startsWith("ROKID,PSOC-TP") == true
                } ?: return
                val now = SystemClock.uptimeMillis()
                for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    dispatchKeyEvent(KeyEvent(now, now, action, KeyEvent.KEYCODE_BACK, 0, 0, id, 158))
                }
            }
            "cancelPhoto" -> if (!capturing) {
                pendingPhoto = null
                voice.cancelNote()
                voice.mute(false)
                setStatus("準備完了")
                showMenu()
            }
            "cameraSample" -> camera.take { result ->
                result.onSuccess {
                    store.addCameraSample(it)
                    Log.i("MemoryCamera", "Camera sample saved: ${it.size} bytes")
                    runOnUiThread { setStatus("カメラ見本を保存。スマホと同期してください") }
                }.onFailure { Log.e("MemoryCamera", "Camera sample failed", it) }
            }
            "testCamera" -> camera.take { result ->
                result.onSuccess {
                    File(cacheDir, "camera-test.jpg").writeBytes(it)
                    Log.i("MemoryCamera", "Test photo captured: ${it.size} bytes")
                }.onFailure { Log.e("MemoryCamera", "Test photo failed", it) }
              }
            "saveEmpty" -> savePhoto("")
            "newSession" -> newSession()
        }
    }

    override fun onResume() {
        super.onResume()
        try { server.start() } catch (e: Exception) { setStatus("同期: ${e.message}") }
        if (hasPermissions()) voice.start()
        if (syncPage && !directSync.active && wifi.isWifiEnabled &&
            syncPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) directSync.start()
        showMenu()
    }

    override fun onPause() {
        hud.removeCallbacks(hideMenu)
        voice.stop()
        super.onPause()
    }

    override fun onDestroy() {
        voice.close()
        directSync.stop()
        server.stop()
        camera.close()
        albumWriter.shutdown() // 書きかけの保存は最後まで続ける
        hud.removeCallbacks(endPreview)
        preview?.recycle(); preview = null
        super.onDestroy()
    }

    private fun ensurePermissions() {
        if (!hasPermissions()) requestPermissions(arrayOf(Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO), 1)
    }

    private fun hasPermissions() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>,
                                            grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            if (syncPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) directSync.start()
            else setStatus("付近のデバイスと位置情報を許可してください")
            return
        }
        if (hasPermissions()) voice.start() else setStatus("カメラとマイクの許可が必要です")
    }

    private fun handleCommand(command: String) {
        when (command) {
            VoiceEngine.COMMAND_PHOTO -> takePhoto()
        }
    }

    private fun takePhoto() {
        if (store.current() == null) { setStatus("前スワイプ: 新しい記録"); return }
        if (!hasPermissions()) { ensurePermissions(); return }
        if (pendingPhoto != null) { setStatus("説明を話して「送信」、またはタップ"); return }
        if (capturing) return
        directSync.stop()
        capturing = true
        syncPage = false
        showMenu(false)
        voice.mute(true)
        setStatus("撮影中...")
        voice.suspendForCamera { released -> runOnUiThread {
            if (isDestroyed || isFinishing) return@runOnUiThread
            if (!released) {
                capturing = false
                finishRequested = false; exitAfterFinish = false
                voice.mute(false)
                voice.resumeAfterCamera()
                setStatus("写真: 音声処理中。もう一度どうぞ")
                return@runOnUiThread
            }
            camera.take { result -> runOnUiThread {
            capturing = false
            result.fold({ bytes ->
                pendingPhoto = bytes
                pendingTakenAt = System.currentTimeMillis()
                showPreview(bytes)
                if (!finishRequested) voice.expectNote()
                voice.mute(false)
                setStatus("LOADING")
                voice.resumeAfterCamera()
                if (finishRequested) savePhoto("")
            }, { error ->
                finishRequested = false; exitAfterFinish = false
                voice.mute(false)
                voice.resumeAfterCamera()
                setStatus("写真: ${error.message ?: "エラー"}")
            })
            } }
        } }
    }

    private fun savePhoto(note: String, audio: ByteArray? = null) {
        val bytes = pendingPhoto ?: return
        try {
            val step = store.addStep(bytes, note, audio)
            saveToAlbum(bytes, store.current()?.optString("id"), step, audio)
            pendingPhoto = null
            hud.removeCallbacks(endPreview); endPreview.run()
            voice.cancelNote()
            voice.mute(false)
            setStatus("手順 ${step.getInt("number")} を保存中…")
            if (finishRequested) completeSession() else showMenu()
        } catch (e: Exception) {
            finishRequested = false; exitAfterFinish = false
            setStatus("保存: ${e.message}")
        }
    }

    /** 写真に作業ID・手順番号・説明・音声を入れて DCIM/Camera に保存する（Hi Rokid の同期でスマホへ）。 */
    private fun saveToAlbum(jpeg: ByteArray, sessionId: String?, step: org.json.JSONObject, audio: ByteArray?) {
        if (sessionId.isNullOrEmpty()) return
        val number = step.getInt("number")
        val note = step.optString("note")
        val takenAt = pendingTakenAt.takeIf { it > 0 } ?: System.currentTimeMillis()
        albumWriter.execute {
            try {
                album.save(jpeg, sessionId, number, note, audio, takenAt)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    setStatus("手順 $number をアルバムに保存しました")
                    if (!finishRequested && store.current() != null) showMenu() // 待機表示に切り替わる前に5秒見せる
                }
            } catch (e: Exception) {
                Log.e("StepMemoAlbum", "Album save failed", e)
                runOnUiThread { if (!isDestroyed) setStatus("保存: アルバム ${e.message ?: "エラー"}") }
            }
        }
    }

    private fun showPreview(jpeg: ByteArray) {
        hud.removeCallbacks(endPreview)
        preview?.recycle()
        preview = try { previewBitmap(jpeg) } catch (e: Exception) { Log.w("MemoryCamera", "Preview failed", e); null }
        hud.postDelayed(endPreview, PREVIEW_MS)
        hud.invalidate()
    }

    /** 1/4 に縮小して読み、EXIF の向きに合わせて回す。 */
    private fun previewBitmap(jpeg: ByteArray): Bitmap? {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = 4 })
            ?: return null
        val degrees = when (ExifInterface(jpeg.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun newSession() {
        if (capturing) return
        if (pendingPhoto != null) { setStatus("先に写真を保存してください"); return }
        directSync.stop()
        if (store.current() != null) {
            syncPage = false
            setStatus("記録を継続。ダブルタップで終了")
            showMenu()
            return
        }
        store.newSession()
        syncPage = false
        setStatus("新しい記録を開始")
        showMenu()
    }

    private fun finishSession() {
        if (syncPage) { directSync.stop(); syncPage = false; showMenu(); return }
        Log.i("MemoryInput", "finish requested capturing=$capturing pending=${pendingPhoto != null} queued=$finishRequested active=${store.current() != null}")
        if (finishRequested) {
            exitAfterFinish = true
            setStatus("終了処理後にアプリを閉じます")
            return
        }
        if (store.current() == null) { exitToHome(); return }
        finishRequested = true
        showMenu(false)
        if (capturing) { setStatus("撮影後に終了します"); return }
        if (pendingPhoto != null) {
            setStatus("終了中: 写真とメモを保存")
            if (!voice.submitNote()) savePhoto(voice.currentDraft(), voice.takeAudio())
            return
        }
        completeSession()
    }

    private fun completeSession() {
        try {
            store.finishSession()
            finishRequested = false
            syncPage = false
            setStatus("終了しました。ダブルタップで閉じる")
            showMenu(false)
            Log.i("MemoryInput", "Session finished; exit=$exitAfterFinish")
            if (exitAfterFinish) exitToHome()
        } catch (e: Exception) {
            finishRequested = false; exitAfterFinish = false
            setStatus("保存: ${e.message}")
        }
    }

    private fun exitToHome() {
        Log.i("MemoryInput", "Exit to glasses home")
        try { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        catch (e: Exception) { Log.w("MemoryInput", "Home screen unavailable", e) }
        finish()
    }

    private fun showMenu(autoHide: Boolean = true) {
        quietMode = false
        hud.removeCallbacks(hideMenu)
        if (autoHide && !syncPage && !capturing && pendingPhoto == null && store.current() != null) {
            hud.postDelayed(hideMenu, 5000)
        }
        hud.invalidate()
    }

    private fun setStatus(value: String) {
        status = value
        if (isError(value))
            showMenu(false)
        hud.invalidate()
    }

    private fun isLoading() = status == "LOADING" || status.startsWith("音声認識を読み込み")

    private fun isError(value: String) =
        value.startsWith("写真:") || value.startsWith("保存:") || value.startsWith("音声:")

    private fun openWifiSettings() {
        try {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        } catch (_: Exception) {
            setStatus("グラスの設定でWi-Fiを開いてください")
        }
    }

    private fun syncPermissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }.toTypedArray()

    private fun openDirectSync() {
        if (pendingPhoto != null || capturing) { setStatus("先に写真を保存してください"); return }
        syncPage = true
        showMenu(false)
        if (!wifi.isWifiEnabled) { openWifiSettings(); return }
        val permissions = syncPermissions()
        if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(permissions, 2)
        } else directSync.start()
    }

    /** Rokid sends vendor swipe scan codes 183/184 instead of DPAD on some firmware. */
    private fun swipeDirection(event: KeyEvent): Int {
        val rokid = event.device?.name?.startsWith("ROKID,PSOC-TP") == true
        if (rokid) {
            when (event.scanCode) {
                183 -> return 1
                184 -> return -1
            }
            val forward = KeyEvent.keyCodeFromString("KEYCODE_SPRITE_SWIPE_FORWARD")
            val back = KeyEvent.keyCodeFromString("KEYCODE_SPRITE_SWIPE_BACK")
            if (forward != KeyEvent.KEYCODE_UNKNOWN && event.keyCode == forward) return 1
            if (back != KeyEvent.KEYCODE_UNKNOWN && event.keyCode == back) return -1
            // Physical forward: RIGHT then DOWN. Physical back: LEFT (sometimes twice) then UP.
            return when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> 1
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> -1
                else -> 0
            }
        }
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP -> 1
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN -> -1
            else -> 0
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val rokid = event.device?.name?.startsWith("ROKID,PSOC-TP") == true
        val spriteDoubleTap = KeyEvent.keyCodeFromString("KEYCODE_SPRITE_DOUBLE_TAP")
        if (rokid && (event.keyCode == KeyEvent.KEYCODE_BACK || event.scanCode == 202 ||
                    (spriteDoubleTap != KeyEvent.KEYCODE_UNKNOWN && event.keyCode == spriteDoubleTap))) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val now = SystemClock.uptimeMillis()
                if (now - lastDoubleTapAt > 350) {
                    lastDoubleTapAt = now
                    Log.d("MemoryInput", "doubleTap scan=${event.scanCode} key=${event.keyCode}")
                    finishSession()
                }
            }
            return true
        }
        val direction = swipeDirection(event)
        if (direction != 0) {
            Log.d("MemoryInput", "key=${event.keyCode} scan=${event.scanCode} phase=${event.action} repeat=${event.repeatCount} device=${event.device?.name} direction=$direction")
            if (event.action == KeyEvent.ACTION_UP) {
                val now = SystemClock.uptimeMillis()
                val duplicateWindow = if (event.device?.name?.startsWith("ROKID,PSOC-TP") == true) 700 else 250
                if (direction != lastSwipeDirection || now - lastSwipeAt > duplicateWindow) {
                    lastSwipeAt = now
                    lastSwipeDirection = direction
                    if (direction > 0) {
                        if (!syncPage) newSession()
                    } else { openDirectSync() }
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (event.repeatCount == 0) {
                    Log.d("MemoryInput", "tap scan=${event.scanCode} device=${event.device?.name} pending=${pendingPhoto != null}")
                    if (syncPage) {
                        if (!wifi.isWifiEnabled) openWifiSettings()
                        else if (!directSync.active) openDirectSync()
                        else { directSync.stop(); syncPage = false; showMenu() }
                    }
                    else if (pendingPhoto != null) {
                        if (voice.submitNote()) setStatus("写真とメモを保存中")
                        else savePhoto(voice.currentDraft(), voice.takeAudio())
                    } else takePhoto()
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (syncPage) { directSync.stop(); syncPage = false; showMenu(); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun localIp(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList().asSequence()
                .filter { it.name == "wlan0" && it.isUp }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull()?.hostAddress
        } catch (_: Exception) { null }
    }

    // スマホが保存を確認した手順の ID（「未同期の手順」の数に使う）。
    private val syncedPrefs by lazy { getSharedPreferences("synced-steps", MODE_PRIVATE) }
    private fun markSynced(ids: List<String>) = synchronized(syncedPrefs) {
        val all = (syncedPrefs.getStringSet("ids", emptySet()) ?: emptySet()).toMutableSet()
        if (all.addAll(ids)) syncedPrefs.edit().putStringSet("ids", all).apply()
        hud.postInvalidate()
    }
    private fun unsyncedCount(): Int = synchronized(syncedPrefs) {
        val synced = syncedPrefs.getStringSet("ids", emptySet()) ?: emptySet()
        val sessions = store.sessions()
        var count = 0
        for (i in 0 until sessions.length()) {
            val steps = sessions.getJSONObject(i).getJSONArray("steps")
            for (j in 0 until steps.length()) if (steps.getJSONObject(j).getString("id") !in synced) count++
        }
        count
    }

    private companion object {
        const val NOTE_LINE = 22 // 全角文字が読める大きさで1行に収まる文字数
        const val PREVIEW_MS = 3000L
    }

    private inner class Hud : View(this@MainActivity) {
        // 日本語の字形で描く（指定しないと「写」などが中国語（簡体字）の字形になる）。
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textLocale = java.util.Locale.JAPAN }
        private fun line(canvas: Canvas, text: String, y: Float, size: Float = 22f) {
            paint.textSize = size
            val textWidth = paint.measureText(text)
            if (textWidth > 436f) paint.textSize = size * 436f / textWidth
            canvas.drawText(text, 22f, y, paint)
        }
        private fun title(canvas: Canvas, text: String) {
            line(canvas, text, 44f, 30f)
            paint.strokeWidth = 2f
            canvas.drawLine(20f, 58f, 460f, 58f, paint)
        }
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
            val sx = width / 480f; val sy = height / 640f
            canvas.save(); canvas.scale(sx, sy)
            if (quietMode && !syncPage && !capturing && pendingPhoto == null) {
                line(canvas, "待機中：撮影はタップか「撮影」同期は後スワイプ", 40f, 16f)
                canvas.restore()
                return
            }
            val shown = preview
            if (syncPage) drawSync(canvas) else if (shown != null && pendingPhoto != null) drawPreview(canvas, shown) else drawRecord(canvas)
            canvas.restore()
        }

        /** 同期画面。タイトルは同期の状態、本文は「何をすればよいか」だけ。 */
        private fun drawSync(canvas: Canvas) {
            val transferring = server.transferring
            val connected = directSync.bleConnected || server.recentlyActive
            title(canvas, when { transferring -> "スマホに転送中"; connected -> "スマホと同期中"; else -> "スマホから切断中" })
            val unsynced = unsyncedCount()
            line(canvas, "未同期の手順は${unsynced}件です", 106f, 22f)
            when {
                transferring -> line(canvas, "転送中です。そのままお待ちください", 170f, 20f)
                connected -> line(canvas, "スマホとつながっています", 170f, 20f)
                else -> {
                    line(canvas, "スマホ側で同期ボタンを押してください", 170f, 20f)
                    if (unsynced == 0 && server.everTransferred) line(canvas, "同期は完了しています", 207f, 18f)
                }
            }
            val detail = directSync.state
            if (detail != "スマホからの接続待ち" && detail != "オフ") line(canvas, detail, 250f, 16f)
            line(canvas, if (directSync.active) "タップ: 同期を閉じる" else "タップ: もう一度つなぐ", 300f, 18f)
            // 自動でつながらないときだけ使う手動接続の情報。
            line(canvas, "つながらないとき（手動接続）", 360f, 16f)
            line(canvas, "Wi-Fi名: ${directSync.ssid}", 392f, 15f)
            line(canvas, "パスワード: ${directSync.wifiPassword}", 420f, 15f)
            line(canvas, if (directSync.groupHost.isEmpty()) "IP: 準備中" else "IP: ${directSync.groupHost}  コード: $pairCode", 448f, 15f)
            line(canvas, "同じWi-Fiなら IP: ${localIp() ?: "なし"}  コード: $pairCode", 476f, 15f)
            canvas.drawLine(20f, 562f, 460f, 562f, paint)
            line(canvas, status.take(42), 599f, 17f)
            postInvalidateDelayed(700)
        }

        private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)

        /** 撮影直後のプレビュー。この間も説明の録音は始まっている。 */
        private fun drawPreview(canvas: Canvas, photo: Bitmap) {
            val scale = minOf(440f / photo.width, 440f / photo.height)
            val w = photo.width * scale; val h = photo.height * scale
            val left = (480f - w) / 2; val top = 30f
            canvas.drawBitmap(photo, null, RectF(left, top, left + w, top + h), photoPaint)
            line(canvas, "撮影しました", top + h + 45f, 24f)
            line(canvas, "続けて手順の説明を話してください", top + h + 85f, 19f)
        }

        /** 記録画面。タイトルは記録の状態、本文は今すること、操作の案内は1か所だけ。 */
        private fun drawRecord(canvas: Canvas) {
            val current = store.current()
            val count = current?.getJSONArray("steps")?.length() ?: 0
            title(canvas, if (current == null) "記録完了" else "記録中")
            line(canvas, if (current == null) "手順の記録は完了しました" else "手順の記録: ${count}件", 106f, 20f)
            when {
                capturing -> {
                    line(canvas, "撮影中", 170f, 22f)
                    line(canvas, "動かないでください", 207f, 19f)
                }
                current == null -> line(canvas, "新しい記録を始めるには前スワイプ", 175f, 19f)
                pendingPhoto == null -> {
                    line(canvas, "「撮影」と言うか、タップ", 175f, 22f)
                    line(canvas, "写真を撮ってから説明を話します", 212f, 18f)
                }
                else -> {
                    line(canvas, if (isLoading()) "マイク準備中..." else "手順の説明を話してください", 175f, 22f)
                    line(canvas, "話し終えたら「送信」", 212f, 19f)
                    line(canvas, "言い直すときは「やり直し」", 247f, 18f)
                }
            }
            // 操作の案内（ここだけに書く）
            if (current != null) line(canvas, if (pendingPhoto != null) "タップ: 写真とメモを保存" else "タップ: 撮影", 310f, 18f)
            line(canvas, "前スワイプ: 記録の継続・新規", 342f, 18f)
            line(canvas, "後スワイプ: スマホと同期", 374f, 18f)
            line(canvas, if (current == null) "ダブルタップ: 閉じる" else "ダブルタップ: 記録完了", 406f, 18f)
            val spokenNote = if (pendingPhoto != null) voice.currentDraft() else ""
            if (pendingPhoto != null) {
                // 説明の入力中は、今の手順のメモだけを出す（前回のメモが残っているように見えないように）。
                line(canvas, "今のメモ:", 450f, 17f)
                line(canvas, spokenNote.ifBlank { "（話した内容がここに出ます）" }.take(NOTE_LINE), 480f, 18f)
                if (spokenNote.length > NOTE_LINE) line(canvas, spokenNote.drop(NOTE_LINE).take(NOTE_LINE), 508f, 18f)
            } else if (count > 0) {
                val last = current!!.getJSONArray("steps").getJSONObject(count - 1).optString("note")
                line(canvas, "前回のメモ:", 450f, 17f)
                line(canvas, (last.ifBlank { "説明なし" }).take(NOTE_LINE), 480f, 18f)
                if (last.length > NOTE_LINE) line(canvas, last.drop(NOTE_LINE).take(NOTE_LINE), 508f, 18f)
            }
            if (pendingPhoto != null) postInvalidateDelayed(250)
            canvas.drawLine(20f, 562f, 460f, 562f, paint)
            // 下の欄は「いまの出来事」だけ（保存した・エラーなど）。案内は上に書いたので繰り返さない。
            val message = when {
                finishRequested -> "記録を終了中 - 現在の手順を保存"
                status.startsWith("手順 ") -> status
                isError(status) -> status
                pendingPhoto != null && status.startsWith("メモ入力中") -> "メモを聞き取り中"
                else -> ""
            }
            if (message.isNotEmpty()) line(canvas, message.take(42), 595f, 17f)
        }
    }
}
