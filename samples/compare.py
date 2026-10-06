#!/usr/bin/env python3
"""ReStep: 文字起こし方式の比較スクリプト（標準ライブラリだけで動く）。

使い方（PowerShell、samples フォルダで）:
    $env:OPENROUTER_API_KEY = "sk-or-..."      # 自分のキー。チャットや GitHub に貼らないこと
    python compare.py                           # 既定のモデルで比較
    python compare.py --list-models             # OpenRouter の文字起こしモデル一覧を見る
    python compare.py --models openai/whisper-large-v3 google/gemini-3.5-transcribe
    python compare.py --dry-run                 # APIを呼ばずに、前処理と正解の読み込みだけ確認

正解.txt（同じフォルダ）に「ファイル名 言った内容」を1行ずつ書くと、誤認識率（CER）も出ます。
結果は results.csv に保存されます。
"""
import argparse, array, base64, csv, glob, json, os, re, sys, time, unicodedata, wave
import urllib.request, urllib.error

DEFAULT_MODELS = [
    "openai/whisper-large-v3-turbo",
    "openai/gpt-4o-transcribe",
    "google/gemini-3.5-transcribe",
    "qwen/qwen3-asr-flash-2026-02-10",
    "deepgram/nova-3",
]
API = "https://openrouter.ai/api/v1"


# ---------- 前処理 ----------
def read_wav(path):
    with wave.open(path) as w:
        assert w.getsampwidth() == 2 and w.getnchannels() == 1, "16bit モノラルの WAV のみ対応"
        return w.getframerate(), array.array("h", w.readframes(w.getnframes()))


def write_wav(path, rate, samples):
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate); w.writeframes(samples.tobytes())


def collapse_silence(samples, rate, thresh_db=-60.0, pad=0.2, gap=0.3):
    """長い無音を短くする。話している部分の前後 pad 秒は残し、区間の間は gap 秒の無音でつなぐ。
    Whisper 系は長い無音で変な文章を作ることがあるため、その対策の比較用。"""
    frame = rate // 100  # 10ms
    n = len(samples) // frame
    loud = []
    for i in range(n):
        seg = samples[i * frame:(i + 1) * frame]
        rms = (sum(s * s for s in seg) / frame) ** 0.5
        loud.append(rms > 0 and 20 * __import__("math").log10(rms / 32768) > thresh_db)
    keep = [False] * n
    p = int(pad * 100)
    for i, v in enumerate(loud):
        if v:
            for j in range(max(0, i - p), min(n, i + p + 1)):
                keep[j] = True
    out = array.array("h")
    silence = array.array("h", [0] * int(gap * rate))
    i = 0
    first = True
    while i < n:
        if keep[i]:
            j = i
            while j < n and keep[j]:
                j += 1
            if not first:
                out.extend(silence)
            out.extend(samples[i * frame:j * frame])
            first = False
            i = j
        else:
            i += 1
    return out


# ---------- 誤認識率 ----------
def normalize(text):
    text = unicodedata.normalize("NFKC", text)
    return re.sub(r"[\s。、，．,.!?！？「」『』（）()・…]+", "", text).lower()


def edit_distance(a, b):
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def cer(reference, hypothesis):
    ref, hyp = normalize(reference), normalize(hypothesis)
    if not ref:
        return None
    return edit_distance(ref, hyp) / len(ref)


def load_truth(folder):
    path = os.path.join(folder, "正解.txt")
    truth = {}
    if os.path.exists(path):
        for line in open(path, encoding="utf-8-sig"):
            parts = re.split(r"[\s　]+", line.strip(), maxsplit=1)
            if len(parts) == 2 and parts[0].lower().endswith(".wav") and parts[1].strip():
                truth[parts[0]] = parts[1].strip()
    return truth


# ---------- 文字起こし ----------
def openrouter(model, wav_path, key):
    body = json.dumps({
        "model": model,
        "input_audio": {"data": base64.b64encode(open(wav_path, "rb").read()).decode(), "format": "wav"},
        "language": "ja",
    }).encode()
    req = urllib.request.Request(API + "/audio/transcriptions", data=body, method="POST",
                                 headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
    t = time.time()
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            data = json.load(r)
        usage = data.get("usage") or {}
        return data.get("text", "").strip(), time.time() - t, usage.get("cost"), None
    except urllib.error.HTTPError as e:
        return "", time.time() - t, None, f"HTTP {e.code}: {e.read().decode('utf-8', 'replace')[:200]}"
    except Exception as e:  # noqa
        return "", time.time() - t, None, str(e)


def vosk_small(model_dir, wav_path):
    try:
        from vosk import Model, KaldiRecognizer, SetLogLevel
    except ImportError:
        return None
    SetLogLevel(-1)
    t = time.time()
    model = Model(model_dir)
    rate, samples = read_wav(wav_path)
    rec = KaldiRecognizer(model, rate)
    parts = []
    raw = samples.tobytes()
    for i in range(0, len(raw), 8000):
        if rec.AcceptWaveform(raw[i:i + 8000]):
            parts.append(json.loads(rec.Result()).get("text", ""))
    parts.append(json.loads(rec.FinalResult()).get("text", ""))
    return "".join("".join(parts).split()), time.time() - t


_sherpa = {}


def sherpa_qwen3(model_dir, wav_path):
    """スマホ内実行を想定した Qwen3-ASR 0.6B（int8）を PC で測る。pip install sherpa-onnx numpy が必要。"""
    try:
        import numpy as np
        import sherpa_onnx
    except ImportError:
        return None
    if not os.path.isdir(model_dir):
        return None
    if model_dir not in _sherpa:
        _sherpa[model_dir] = sherpa_onnx.OfflineRecognizer.from_qwen3_asr(
            conv_frontend=os.path.join(model_dir, "conv_frontend.onnx"),
            encoder=os.path.join(model_dir, "encoder.int8.onnx"),
            decoder=os.path.join(model_dir, "decoder.int8.onnx"),
            tokenizer=os.path.join(model_dir, "tokenizer"),
            num_threads=4, max_new_tokens=128,
            hotwords="Language: Japanese")  # 言語指定がないと中国語で出力されることがある
    rec = _sherpa[model_dir]
    rate, samples = read_wav(wav_path)
    t = time.time()
    st = rec.create_stream()
    st.accept_waveform(rate, np.array(samples, dtype=np.float32) / 32768)
    rec.decode_stream(st)
    return st.result.text.strip(), time.time() - t


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default=here)
    ap.add_argument("--models", nargs="*", default=DEFAULT_MODELS)
    ap.add_argument("--no-trim", action="store_true", help="無音を詰めた版を作らない")
    ap.add_argument("--vosk-model", default=os.path.join(here, "..", "app", "src", "main", "assets", "model-ja"))
    ap.add_argument("--sherpa-dir", default=os.path.join(here, "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25"),
                    help="Qwen3-ASR 0.6B int8 のフォルダ（あれば qwen3-asr-0.6b-local を比較に加える）")
    ap.add_argument("--list-models", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    key = os.environ.get("OPENROUTER_API_KEY", "")

    if args.list_models:
        with urllib.request.urlopen(API + "/models?output_modalities=transcription", timeout=30) as r:
            for m in json.load(r)["data"]:
                print(m["id"])
        return

    wavs = sorted(p for p in glob.glob(os.path.join(args.dir, "*.wav")) if not p.endswith("_trim.wav"))
    truth = load_truth(args.dir)
    variants = ["そのまま"] + ([] if args.no_trim else ["無音を詰める"])
    rows = []
    for wav in wavs:
        name = os.path.basename(wav)
        trimmed = wav[:-4] + "_trim.wav"
        if not args.no_trim:
            rate, samples = read_wav(wav)
            write_wav(trimmed, rate, collapse_silence(samples, rate))
            print(f"{name}: {len(samples) / rate:.1f}秒 → 無音を詰めて {len(read_wav(trimmed)[1]) / rate:.1f}秒")
        for variant in variants:
            path = wav if variant == "そのまま" else trimmed
            engines = [("vosk-small-ja", None), ("qwen3-asr-0.6b-local", "@sherpa")] + [(m, m) for m in args.models]
            for label, model in engines:
                if args.dry_run:
                    continue
                if model == "@sherpa":
                    r = sherpa_qwen3(args.sherpa_dir, path)
                    if r is None:
                        continue
                    text, sec, cost, err = r[0], r[1], None, None
                elif model is None:
                    r = vosk_small(args.vosk_model, path)
                    if r is None:
                        if variant == "そのまま" and name == os.path.basename(wavs[0]):
                            print("（vosk が入っていないため vosk-small-ja は省略。pip install vosk で追加できます）")
                        continue
                    text, sec, cost, err = r[0], r[1], None, None
                else:
                    if not key:
                        sys.exit("環境変数 OPENROUTER_API_KEY が設定されていません。")
                    text, sec, cost, err = openrouter(model, path, key)
                score = cer(truth[name], text) if name in truth and not err else None
                rows.append(dict(file=name, variant=variant, engine=label, text=text, error=err or "",
                                 seconds=round(sec, 2), cost=cost, cer=None if score is None else round(score, 3),
                                 truth=truth.get(name, "")))
                shown = f"CER {score:.0%}" if score is not None else ""
                print(f"[{name}] {label} ({variant}) {sec:.1f}s {shown} → {text or err}")
    if args.dry_run:
        print("正解の読み込み:", truth or "（正解.txt に内容がありません）")
        return
    out = os.path.join(args.dir, "results.csv")
    with open(out, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()) if rows else ["file"])
        w.writeheader(); w.writerows(rows)
    print("\n保存しました:", out)
    # 方式ごとの平均 CER
    summary = {}
    for r in rows:
        if r["cer"] is not None:
            summary.setdefault((r["engine"], r["variant"]), []).append(r["cer"])
    if summary:
        print("\n== 方式ごとの平均誤認識率（小さいほど良い） ==")
        for (engine, variant), v in sorted(summary.items(), key=lambda kv: sum(kv[1]) / len(kv[1])):
            print(f"  {sum(v) / len(v):5.0%}  {engine}  ({variant}, {len(v)}件)")


if __name__ == "__main__":
    main()
