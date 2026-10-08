package com.rocketglasses.restep.phone

import android.Manifest
import android.app.*
import android.os.*
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.provider.MediaStore
import android.graphics.*
import android.net.Network
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity: Activity() {
    companion object {
        private var sharedLibrary: Library? = null
        private var syncing = false
        private var importing = false
        private var visible: java.lang.ref.WeakReference<MainActivity>? = null
    }
    private lateinit var library: Library
    private lateinit var link: GlassesLink
    private lateinit var body: LinearLayout
    private lateinit var message: TextView
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var settings: TranscriptionSettings
    private var transcribing = false
    private val busy get() = syncing || transcribing || importing
    private var selected: String? = null
    private var assembly = true
    private var player: MediaPlayer? = null
    private var playingPath: String? = null
    private val ink = Color.rgb(23,35,39)
    private val accent = Color.rgb(0,125,115)
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        library = sharedLibrary ?: Library(filesDir).also { sharedLibrary = it }
        settings = TranscriptionSettings(this)
        link = GlassesLink(this, { text -> message.text = text }, { network, info -> sync(network, info.getString("host"),info.getString("token")) })
        render()
    }
    override fun onResume() { super.onResume(); visible = java.lang.ref.WeakReference(this); render() }
    private fun label(text: String, size: Float = 16f) = TextView(this).apply { this.text = text; textSize = size; setTextColor(ink); setPadding(0,12,0,12) }
    private fun button(text: String, action: ()->Unit) = Button(this).apply { this.text = text; isAllCaps = false; setTextColor(accent); isEnabled = !busy; setOnClickListener { action() } }
    private fun render() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(246,248,247)) }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,32) }
        scroll.addView(body)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(0,insets.systemWindowInsetTop,0,insets.systemWindowInsetBottom); insets
        }
        setContentView(scroll)
        body.addView(label("StepMemo",34f))
        message = label(if(syncing) "同期中…" else if(transcribing) "文字起こし中…" else if(importing) "取り込み中…" else "グラスで記録した手順を見ます")
        body.addView(message)
        val session = library.sessions().firstOrNull { it.getString("id") == selected }
        if(session == null) showLibrary() else showSession(session)
    }
    private fun showLibrary() {
        body.addView(button("アルバムから取り込む") { importAlbum() })
        body.addView(label("先に Hi Rokid アプリでグラスのアルバムを同期してください。スマホの「Download/Hi Rokid」から StepMemo の写真を探して取り込みます（元の写真はそのまま）。",14f))
        body.addView(button("フォルダを選んで取り込む") { pickFolder() })
        body.addView(button("文字起こしの設定") { transcriptionSettings() })
        body.addView(label("旧方式: グラスと直接同期",18f))
        body.addView(button("同期") { connect() })
        body.addView(label("グラスで旧 ReStep を開き、後ろへスワイプして同期画面にしてください。転送中はこの画面を開いたままにします。",14f))
        body.addView(button("手動で接続（IPアドレス）") { manual() })
        if(library.pending().isNotEmpty()) body.addView(label("グラスへの削除反映が${library.pending().size}件、同期待ちです。"))
        body.addView(label("プロジェクト",24f))
        if(library.sessions().isEmpty()) body.addView(label("取り込むと、記録がここに表示されます。"))
        for(session in library.sessions()) {
            // 1つのボタンに「日付 ・ N手順」。既定の名前（分解 日付）なら「分解 」は省く。
            body.addView(button("${session.getString("name").removePrefix("分解 ")}  ・  ${session.getJSONArray("steps").length()}手順") { selected = session.getString("id"); render() })
        }
    }
    private fun showSession(session: JSONObject) {
        val id = session.getString("id")
        body.addView(button("‹ プロジェクト一覧") { selected = null; render() })
        body.addView(label(session.getString("name"),26f))
        val fromAlbum = session.optString("source") == Library.SOURCE_ALBUM
        body.addView(label(when {
            fromAlbum -> "グラスのアルバムから取り込み（同じ作業の写真を取り込むと、ここに追加されます）"
            session.has("endedAt") -> "グラスでの記録: 完了"
            else -> "グラスでの記録: 記録中（まだ終了していません）"
        }, 14f))
        body.addView(button("プロジェクト名を変更") { editText("プロジェクト名", session.getString("name")) { text -> library.edit(id) { it.put("name",text) }; render() } })
        body.addView(button(if(assembly) "組み立て順（逆順）で表示中 ・ 分解順に切り替え" else "分解順で表示中 ・ 組み立て順（逆順）に切り替え") { assembly = !assembly; render() })
        body.addView(button("PDFに書き出す") { exportPdf(session) })
        val untranscribed = session.getJSONArray("steps").objects().filter { hasAudio(it) && !it.optBoolean("transcribed") }
        if(untranscribed.isNotEmpty()) body.addView(button("音声を文字起こし（未処理${untranscribed.size}件）") { transcribe(id, untranscribed.map { it.getString("id") }) })
        val steps = session.getJSONArray("steps").objects().let { if(assembly) it.reversed() else it }
        for((index,step) in steps.withIndex()) {
            body.addView(label("${index + 1} / ${steps.size}  ·  記録した手順 ${step.getInt("number")}",20f))
            val image = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "手順の写真" }
            body.addView(image, LinearLayout.LayoutParams(-1,-2))
            val photo = library.photo(step.getString("photo"))
            if(photo.exists()) {
                Photos.load(photo, 800)?.let { image.setImageBitmap(it) }
            }
            body.addView(label(step.optString("note").ifBlank { "説明なし" },18f))
            val audioPath = step.optString("audio")
            val audioFile = if(audioPath.isNotEmpty()) library.audio(audioPath).takeIf { it.exists() } else null
            if(audioFile != null) {
                val row = LinearLayout(this)
                row.addView(button("▶ 音声を再生") { togglePlay(audioFile) }, LinearLayout.LayoutParams(0,-2,1f))
                row.addView(button(if(step.optBoolean("transcribed")) "文字起こしをやり直す" else "文字起こし") { transcribe(id, listOf(step.getString("id"))) }, LinearLayout.LayoutParams(0,-2,1f))
                row.addView(button("音声を書き出す") { exportAudio(listOf(audioFile to exportName(session,step))) }, LinearLayout.LayoutParams(0,-2,1f))
                body.addView(row)
            }
            body.addView(button("説明を編集") { editText("説明",step.optString("note")) { text ->
                library.edit(id) { s -> s.getJSONArray("steps").objects().first { it.getString("id") == step.getString("id") }.put("note",text) }; render()
            } })
            val check = CheckBox(this).apply { text = "戻し済み（組み立てで戻した手順にチェック）"; isChecked = step.optBoolean("completed"); isEnabled = !busy }
            check.setOnCheckedChangeListener { _, done -> library.edit(id) { s -> s.getJSONArray("steps").objects().first { it.getString("id") == step.getString("id") }.put("completed",done) } }
            body.addView(check)
        }
        val recorded = session.getJSONArray("steps").objects().mapNotNull { step ->
            step.optString("audio").takeIf { it.isNotEmpty() }?.let { library.audio(it) }?.takeIf { it.exists() }?.let { it to exportName(session,step) }
        }
        if(recorded.isNotEmpty()) body.addView(button("音声をすべて書き出す（${recorded.size}件）") { exportAudio(recorded) })
        body.addView(button("プロジェクトを削除") {
            AlertDialog.Builder(this).setTitle("このプロジェクトを削除しますか？").setMessage(
                if(fromAlbum) "このアプリの中の写真とメモを削除します。アルバムの元の写真は残り、次の取り込みでも取り込み直しません。"
                else "写真とメモはこのスマホから今すぐ、グラスからは次の同期で削除されます。")
                .setNegativeButton("キャンセル",null).setPositiveButton("削除") { _,_ ->
                    runCatching { library.delete(id); selected = null; render() }.onFailure { message.text = it.message }
                }.show()
        })
    }
    private fun hasAudio(step: JSONObject) = step.optString("audio").let { it.isNotEmpty() && library.audio(it).exists() }

    /** 写真の読み取り権限（Android 13 以降は「写真と動画」）。位置情報の権限は取らない。 */
    private fun photoPermission() = if(Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    /** Android 14 以降の「選択した写真のみ許可」。この状態では Download/Hi Rokid の写真がほとんど見えない。 */
    private fun partialPhotoAccess() = Build.VERSION.SDK_INT >= 34 &&
        checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED

    private fun importAlbum(asked: Boolean = false) {
        if(busy) return
        if(checkSelfPermission(photoPermission()) != PackageManager.PERMISSION_GRANTED) {
            if(asked) {
                message.text = if(partialPhotoAccess()) "写真へのアクセスが「選択した写真のみ」になっています。Download/Hi Rokid の写真を読むには、設定でこのアプリの「写真と動画」を「すべて許可」にするか、「フォルダを選んで取り込む」を使ってください（こちらは権限が要りません）。"
                    else "写真へのアクセスが許可されませんでした。「すべて許可」にするか、「フォルダを選んで取り込む」で Download/Hi Rokid を選んでください。"
                return
            }
            val wanted = if(Build.VERSION.SDK_INT >= 34) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else arrayOf(photoPermission())
            requestPermissions(wanted, 43); return
        }
        runImport { AlbumImport(this, library).let { it to it.fromMediaStore() } }
    }

    /** 権限を使わずに、利用者が選んだフォルダ（Download/Hi Rokid）から取り込む。 */
    private fun pickFolder() {
        if(busy) return
        val saved = getPreferences(MODE_PRIVATE).getString("folder", null)?.let { Uri.parse(it) }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply { saved?.let { putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, it) } }, 44)
    }

    @Deprecated("Activity の結果は従来の方法で受け取る")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val tree = data?.data
        if(requestCode != 44 || resultCode != RESULT_OK || tree == null) return
        runCatching { contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        getPreferences(MODE_PRIVATE).edit().putString("folder", tree.toString()).apply()
        runImport { AlbumImport(this, library).let { it to it.fromFolder(tree) } }
    }

    private fun runImport(source: () -> Pair<AlbumImport, List<AlbumImport.Candidate>>) {
        importing = true; render()
        worker.execute {
            val result = runCatching {
                val (album, candidates) = source()
                album.run(candidates) { index, total -> runOnUiThread { message.text = "取り込み中… $index / $total" } }
            }
            runOnUiThread {
                importing = false
                visible?.get()?.takeIf { !it.isDestroyed }?.let { screen ->
                    screen.render()
                    screen.message.text = result.fold({ r ->
                        if(r.checked == 0) "Download/Hi Rokid の写真が見えませんでした（0枚）。Hi Rokid でアルバムを同期したか、写真へのアクセスが「すべて許可」になっているかを確認してください。「フォルダを選んで取り込む」も使えます。"
                        else (if(r.imported == 0) "新しい StepMemo の写真はありませんでした（${r.checked}枚を確認）。" else "${r.imported}件の手順を取り込みました（音声あり ${r.withAudio}件）。音声はプロジェクトを開いて「音声を文字起こし」してください。") +
                            (if(r.failed > 0) "\n${r.failed}件は読めませんでした（次回もう一度試します）。" else "")
                    }, { "取り込めませんでした: ${it.message}" })
                }
            }
        }
    }

    /** 文字起こしの接続先（OpenRouter / OpenAI互換）の設定。キーはこのスマホの中だけに保存される。 */
    private fun transcriptionSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,12,24,12) }
        val mode = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("OpenRouter", "OpenAI互換（自宅サーバーなど）"))
            setSelection(settings.mode)
        }
        val url = EditText(this).apply { hint = "接続先 URL（…/v1 まで）"; setText(settings.baseUrl); inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI }
        val model = EditText(this).apply { hint = "モデル名"; setText(settings.model) }
        val key = EditText(this).apply {
            hint = "API キー（自宅サーバーで不要なら空欄）"; setText(settings.apiKey)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        mode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // 初期値のままなら、方式に合わせた初期値に切り替える。
                if(position == TranscriptionSettings.MODE_OPENAI && url.text.toString() == TranscriptionSettings.OPENROUTER_URL) url.setText(TranscriptionSettings.OPENAI_URL)
                if(position == TranscriptionSettings.MODE_OPENAI && model.text.toString() == TranscriptionSettings.OPENROUTER_MODEL) model.setText(TranscriptionSettings.OPENAI_MODEL)
                if(position == TranscriptionSettings.MODE_OPENROUTER && url.text.toString() == TranscriptionSettings.OPENAI_URL) url.setText(TranscriptionSettings.OPENROUTER_URL)
                if(position == TranscriptionSettings.MODE_OPENROUTER && model.text.toString() == TranscriptionSettings.OPENAI_MODEL) model.setText(TranscriptionSettings.OPENROUTER_MODEL)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        box.addView(label("方式"))
        box.addView(mode)
        box.addView(url); box.addView(model); box.addView(key)
        box.addView(label("既定: qwen/qwen3-asr-flash-2026-02-10。予備: ${TranscriptionSettings.OPENROUTER_BACKUP_MODEL}。音声は設定した接続先に送信されます。",13f))
        AlertDialog.Builder(this).setTitle("文字起こしの設定").setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("キャンセル",null).setPositiveButton("保存") { _,_ ->
                settings.mode = mode.selectedItemPosition
                settings.baseUrl = url.text.toString(); settings.model = model.text.toString(); settings.apiKey = key.text.toString()
                message.text = "文字起こしの設定を保存しました。"
            }.show()
    }

    /** 指定した手順の音声を順に文字起こしして、説明欄に入れる。「やり直し」より前と末尾の「送信」は除く。 */
    private fun transcribe(sessionId: String, stepIds: List<String>) {
        if(busy) return
        if(settings.mode == TranscriptionSettings.MODE_OPENROUTER && settings.apiKey.isBlank()) {
            message.text = "先に「文字起こしの設定」で OpenRouter の API キーを入力してください。"; return
        }
        transcribing = true; render()
        worker.execute {
            var done = 0; var empty = 0; var failure: String? = null
            for((index,stepId) in stepIds.withIndex()) {
                val step = library.sessions().firstOrNull { it.getString("id") == sessionId }
                    ?.getJSONArray("steps")?.objects()?.firstOrNull { it.getString("id") == stepId } ?: continue
                val file = step.optString("audio").takeIf { it.isNotEmpty() }?.let { library.audio(it) }?.takeIf { it.exists() } ?: continue
                runOnUiThread { message.text = "文字起こし中… ${index + 1} / ${stepIds.size}" }
                try {
                    val text = TranscriptCleaner.clean(Transcriber.transcribe(settings, file))
                    library.edit(sessionId) { s ->
                        val target = s.getJSONArray("steps").objects().first { it.getString("id") == stepId }
                        if(text.isNotEmpty()) target.put("note", text)
                        target.put("transcribed", true)
                    }
                    if(text.isEmpty()) empty++
                    done++
                } catch(e: Exception) {
                    failure = e.message ?: "文字起こしに失敗しました"
                    break
                }
            }
            runOnUiThread {
                transcribing = false
                visible?.get()?.takeIf { !it.isDestroyed }?.let { screen ->
                    screen.render()
                    screen.message.text = failure?.let { "文字起こしを中断しました（${done}件完了）: $it" }
                        ?: "文字起こしが完了しました（${done}件" + (if(empty > 0) "、うち${empty}件は内容なし" else "") + "）。内容は「説明を編集」で直せます。"
                }
            }
        }
    }

    /** 表示中の順序で、写真と説明を PDF にして「ダウンロード/StepMemo」に保存する。 */
    private fun exportPdf(session: JSONObject) {
        message.text = "PDFを作成中…"
        val steps = session.getJSONArray("steps").objects().let { if(assembly) it.reversed() else it }
        val title = session.getString("name") + (if(assembly) "（組み立て順）" else "（分解順）")
        val entries = steps.mapIndexed { index, step ->
            PdfExporter.Entry("手順 ${index + 1}（記録 ${step.getInt("number")}）", library.photo(step.getString("photo")), step.optString("note"))
        }
        val fileName = "stepmemo-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(session.getLong("createdAt"))) + (if(assembly) "-組み立て順" else "-分解順") + ".pdf"
        worker.execute {
            val result = runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/StepMemo")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("保存先を作れませんでした")
                try {
                    contentResolver.openOutputStream(uri)!!.use { PdfExporter.write(it, title, entries) }
                    contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                } catch(e: Exception) { contentResolver.delete(uri, null, null); throw e }
            }
            runOnUiThread { message.text = result.fold({ "PDFを「ダウンロード/StepMemo」フォルダに保存しました（$fileName）。" },{ "PDFを作れませんでした: ${it.message}" }) }
        }
    }

    private fun exportName(session: JSONObject, step: JSONObject) =
        "stepmemo-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(session.getLong("createdAt"))) + "-step" + step.getInt("number") +
            "." + step.optString("audio").substringAfterLast('.', "wav")
    private fun togglePlay(file: File) {
        val same = playingPath == file.path
        stopPlayback()
        if(same) return
        runCatching {
            player = MediaPlayer().apply { setDataSource(file.path); prepare(); setOnCompletionListener { stopPlayback() }; start() }
            playingPath = file.path
        }.onFailure { stopPlayback(); message.text = it.message ?: "再生できませんでした" }
    }
    private fun stopPlayback() { runCatching { player?.release() }; player = null; playingPath = null }
    /** 音声を端末の「Music/StepMemo」フォルダに書き出す（WAV か OGG。文字起こしの精度比較用）。 */
    private fun exportAudio(files: List<Pair<File,String>>) {
        message.text = "書き出し中…"
        worker.execute {
            val result = runCatching {
                for((file,name) in files) {
                    val values = ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                        put(MediaStore.Audio.Media.MIME_TYPE, AudioFiles.mimeType(file))
                        put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/StepMemo")
                        put(MediaStore.Audio.Media.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: error("保存先を作れませんでした")
                    contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                    contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
                }
            }
            runOnUiThread { message.text = result.fold({ "${files.size}件を Music/StepMemo フォルダに保存しました。" },{ "書き出せませんでした: ${it.message}" }) }
        }
    }
    private fun editText(title: String, value: String, save: (String)->Unit) {
        val input = EditText(this).apply { setText(value); minLines = 2 }
        AlertDialog.Builder(this).setTitle(title).setView(input).setNegativeButton("キャンセル",null).setPositiveButton("保存") { _,_ ->
            runCatching { save(input.text.toString()) }.onFailure { message.text = it.message }
        }.show()
    }
    private fun permissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if(Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
        if(Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }.toTypedArray()
    private fun connect() {
        val missing = permissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if(missing.isNotEmpty()) { requestPermissions(permissions(),42); return }
        runCatching { link.start() }.onFailure { message.text = it.message }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grants)
        if(requestCode == 43) { importAlbum(asked = true); return }
        if(requestCode == 42) {
            if(grants.isNotEmpty() && grants.all { it == PackageManager.PERMISSION_GRANTED }) connect()
            else message.text = "接続には「付近のデバイス」と位置情報の許可が必要です。「手動で接続」も使えます。"
        }
    }
    private fun manual() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,12,24,12) }
        box.addView(label("方法A（Wi-Fi Direct）: スマホの Wi-Fi 設定で、グラスに表示された「Wi-Fi名」に表示の「パスワード」でつなぎます。\n方法B: スマホとグラスを同じ Wi-Fi につなぎます。\nどちらも、グラスに表示された IP とコードを下に入力してください。",14f))
        val lastHost = getPreferences(MODE_PRIVATE).getString("lastHost", "192.168.49.1")!!.split('.')
        val fields = (0..3).map { i -> EditText(this).apply { hint = "0"; setText(lastHost.getOrNull(i) ?: ""); inputType = android.text.InputType.TYPE_CLASS_NUMBER; filters = arrayOf(android.text.InputFilter.LengthFilter(3)) } }
        val row = LinearLayout(this)
        fields.forEachIndexed { index, field -> row.addView(field,LinearLayout.LayoutParams(0,-2,1f)); if(index < 3) row.addView(label(".")) }
        box.addView(row)
        val code = EditText(this).apply {
            hint = "ペアコード（6桁）"; inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(getPreferences(MODE_PRIVATE).getString("lastCode", "") ?: "")   // 前回のコードを覚えておく
        }
        box.addView(code)
        val dialog = AlertDialog.Builder(this).setTitle("手動で同期").setView(box).setNegativeButton("キャンセル",null).setPositiveButton("同期",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val parts = fields.map { it.text.toString().toIntOrNull() ?: -1 }
            if(parts.any { it !in 0..255 } || !code.text.matches(Regex("[0-9]{6}"))) { code.error = "IPアドレス（0〜255の4つの数字）と6桁のコードを入力してください" }
            else {
                dialog.dismiss(); link.stop()
                val host = parts.joinToString(".")
                getPreferences(MODE_PRIVATE).edit().putString("lastHost", host).putString("lastCode", code.text.toString()).apply()
                sync(link.wifiNetworkFor(host),host,code.text.toString())
            }
        } }; dialog.show()
    }
    private fun sync(network: Network?, host: String, token: String) {
        if(busy) return
        syncing = true; render()
        worker.execute {
            val result = runCatching {
                for(id in library.pending()) { GlassesLink.request(network,host,token,"/session/$id","DELETE"); library.acknowledge(id) }
                val remote = JSONObject(GlassesLink.request(network,host,token,"/manifest").toString(Charsets.UTF_8))
                require(remote.getInt("schemaVersion") == 1)
                for(session in remote.getJSONArray("sessions").objects()) {
                    if(session.getString("id") in library.deleted()) continue
                    for(step in session.getJSONArray("steps").objects()) {
                        val path = step.getString("photo")
                        if(!library.photo(path).exists()) library.storePhoto(path,GlassesLink.request(network,host,token,"/photo/$path"))
                        // 音声は付属データ。取得に失敗しても同期全体は止めず、次回の同期でやり直す。
                        val audioPath = step.optString("audio")
                        if(audioPath.isNotEmpty() && !library.audio(audioPath).exists())
                            runCatching { library.storeAudio(audioPath,GlassesLink.request(network,host,token,"/audio/$audioPath")) }
                    }
                }
                library.merge(remote)
                // 保存できた手順をグラスに知らせる（グラスの「未同期の手順」の数に使う）。失敗しても同期は成功扱い。
                val ids = remote.getJSONArray("sessions").objects().flatMap { it.getJSONArray("steps").objects().map { step -> step.getString("id") } }
                if(ids.isNotEmpty()) runCatching { GlassesLink.request(network,host,token,"/synced?ids=" + ids.joinToString(",")) }
            }
            runOnUiThread {
                link.stop(); syncing = false
                visible?.get()?.takeIf { !it.isDestroyed }?.let { screen ->
                    screen.render(); screen.message.text = result.fold({ "同期が完了しました。${library.sessions().size}件のプロジェクトをこのスマホに保存しています。" },{ it.message ?: "同期に失敗しました。もう一度お試しください。" })
                }
            }
        }
    }
    override fun onPause() { stopPlayback(); super.onPause() }
    override fun onDestroy() { stopPlayback(); link.stop(); worker.shutdown(); super.onDestroy() }
}
