# ReStep 日本語化 引き継ぎメモ

- 元リポジトリ: https://github.com/oldmelnick-afk/restep （ローカル: C:\Users\aozor\Documents\ReStep）
- ビルドは Android Studio（ReStep フォルダ全体を開く）。クラウド側からは Android SDK に接続できず APK は作れない。
- APK: グラス用 `app\build\outputs\apk\debug\app-debug.apk` / スマホ用 `phone\build\outputs\apk\debug\phone-debug.apk`

## 2026-10-04 までの変更
- グラス用アプリ（app/）を日本語専用に改修。ロシア語・英語は削除（Vosk は日本語の小モデル1つだけ）。
  - モデル: vosk-model-small-ja-0.22 → app/src/main/assets/model-ja（download-model.ps1 で取得）
  - 撮影コマンド:「撮影」「写真を撮って」「シャッター」など。送信コマンド:「送信」「送信して」（VoiceCommands.kt）
  - 画面表示（MainActivity.kt / DirectSync.kt）を日本語化。versionName 0.19-ja
- エミュレータ確認用に app/build.gradle.kts の abiFilters に x86_64 を追加（実機用ビルドでは外してよい）。
- PhotoCapture.kt: 背面カメラ・1600x1200 が無い環境（エミュレータ）でも撮影できるようフォールバックを追加。
- メモ音声の保存（今回追加）
  - グラス: メモ中の音声を 16kHz モノラル WAV で録音（最長120秒）→ 写真・メモと一緒に保存（Wav.kt / VoiceEngine.kt / SessionStore.kt）。manifest の各ステップに "audio" キー。TransferServer に GET /audio/... を追加。
  - スマホ: 同期で音声を受信・保存（Library.kt）。ビューアに「音声を再生」「音声を書き出す」「音声をすべて書き出す」を追加。書き出し先は端末の Music/ReStep。

## 実機テストで出た課題（2026-10-04）
1. スマホとグラスの同期が成功しにくい（Android 版スマホアプリは実機未検証のプレビュー。失敗時のグラス画面の状態表示を要確認。同じ Wi-Fi の「Manual connection」は代替手段）
2. 長く使うと「撮影」に反応しなくなる（待機中を自由認識にしたことによる負荷、モデルの読み込み直し・メモリ(300MB/RAM 2GB)が疑わしい）
3. 音声メモの文字起こし精度が低く遅い（玄関→喧嘩、パキラ→人キー等）。2回言うと崩れる
4. スマホ側のエクスポート（PDF）が無い
5. 「Step completed」は逆順（組み立て）で戻す作業の進捗チェック。表示名を日本語化したい
6. （気づき）スマホで見る写真が90度横向き

## 文字起こしの方針（決定）
- Vosk 大モデル vosk-model-ja-0.22 は RAM 最大16GB 必要でグラス（RAM 2GB）では動かない。精度向上も数ポイント。
- グラスでは「撮影」「送信」の合図だけ認識し、メモは音声を録音 → 同期後にスマホ側で文字起こしする。
- 文字起こしの場所: スマホ内、または OpenRouter（https://openrouter.ai/api/v1/audio/transcriptions、JSON で input_audio に base64+format を渡す）。自宅サーバーは OpenAI 互換の URL を指定して流用する想定（OpenAI 標準は multipart 形式のため、方式の切り替えが必要になる可能性あり）。

## 文字起こし比較の結果と決定（2026-10-04）
- 4サンプルで比較（正解あり）。平均CER: Qwen3-ASR-Flash 25% / Gemini 26% / Deepgram 27% / Whisper-large-v3-turbo 30% / Vosk小 53% / gpt-4o-transcribe 67%（繰り返しを1回に圧縮してしまう）。「無音を詰める」前処理は逆効果なので採用しない。
- 決定: 音声の外部送信は可（私的利用、業務では使わない）。既定は OpenRouter の qwen/qwen3-asr-flash、予備は openai/whisper-large-v3-turbo。URL・モデル・キーは設定で変更可（自宅サーバー流用）。
- 「やり直し」: 聞き取ったらそれまでの文字と音声を捨て、その次の発話からをメモにする。グラス側は実装済み（VoiceEngine.redoNote）。スマホ側の文字起こし後も「最後の やり直し より後ろ」だけ採用する（未実装）。
- 録音の途切れの原因と対策: AudioRecord バッファが約0.1秒しかなく、Vosk の認識で読み取りが遅れると音声が捨てられていた。マイク読み取り専用スレッドを分離しバッファを4秒に拡大。さらに撮影後にモデルを再読み込みする間は録音されなかったので、モデルを待たずに先にマイクを開くようにした（要実機確認）。
- グラスのマイクは話し始めが遅れ、語尾（「みかん」の「ん」など）が切れる傾向。ハード側の特性の可能性があり、運用では先に「あの」と言ってから話す。

## スマホアプリ 0.2-ja（2026-10-04 実装、未ビルド）
- 文字起こし: Transcriber.kt。OpenRouter（JSON）と OpenAI互換（multipart、自宅サーバー用）。設定画面で方式・URL・モデル・APIキーを変更（端末内のみ保存）。既定 qwen/qwen3-asr-flash-2026-02-10、予備 openai/whisper-large-v3-turbo。
- 後処理 TranscriptCleaner: 最後の「やり直し」より前を捨て、末尾の「送信」を除く。結果は説明欄に入り、編集可。同期しても保持（Library.merge）。内容が空なら説明は変えない。
- スマホ内 Qwen3-ASR 0.6B（sherpa-onnx）は PC 実測で CER 24% だが 6〜9秒/件・約1GBのため見送り（言語指定「Language: Japanese」が必須）。
- PDF書き出し（PdfExporter.kt）: 表示順（組み立て順/分解順）、1手順1ページ。保存先 ダウンロード/ReStep。
- 写真の向き: EXIF の向きを反映（Photos.load）。画面を日本語化、「Step completed」→「戻し済み」。
- テスト: TranscriptCleaner と VoiceCommands(やり直し) は Kotlin で実行確認済み。Android 部分（画面・通信・PDF）は未ビルドなので Android Studio での確認が必要。

## 次にやること
1. 実機でメモ音声を数件録音 → 同期 → 「音声をすべて書き出す」→ サンプルを集める
2. サンプルで文字起こし方式（スマホ内 / OpenRouter のモデル）を比較
3. （実装済み・要実機確認）スマホアプリの文字起こし機能
4. グラス: 待機中を「撮影」だけの軽い認識に戻す、「やり直し」コマンド、録音開始をモデル読み込み前にする
5. スマホ: PDF 出力、写真の向き、画面の日本語化（Step completed → 戻し済み）
6. 同期の安定化（失敗時のグラス画面の状態表示を確認）
