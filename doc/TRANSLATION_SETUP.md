# Comment translation feature

This fork adds an opt-in **on-device comment translation** feature powered by
[llama.cpp](https://github.com/ggml-org/llama.cpp) and a small GGUF-format LLM.
Translations run fully offline; no server is contacted.

## For users who don't care about translation

Nothing to do. The feature is **disabled by default**. The app builds and runs
exactly as before. The only cost is ~10 MB of native library shipped in the
APK that is loaded lazily, only if you turn translation on.

## What you need to enable it

| Requirement | Notes |
|---|---|
| arm64-v8a phone running Android 7+ | armv8.6-a SIMD is required for usable speed; covers Tensor G1+, Snapdragon 8 Gen 1+, Dimensity 8000+, and any Pixel / modern flagship |
| ~1–3 GB free RAM | Depends on model size |
| ~1 GB free storage | For the model file |
| A GGUF translation model | See "Recommended models" below |

The first build will download ~30 MB of llama.cpp source via CMake
`FetchContent` (cached afterwards) and compile it with the NDK. First build
takes ~5 minutes; subsequent builds reuse the cache.

## Build & install

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The Android Gradle Plugin will auto-install the NDK and CMake on first run if
they aren't already present.

## On-phone setup

1. Open NewPipe → **Settings → Translation**.
2. Toggle **"Enable comment translation"** ON.
3. Tap **"Grant All Files Access"**. In the system page that opens, allow
   NewPipe to access all files. This is required so the app can read the
   model file directly from external storage; otherwise Android's scoped
   storage / SELinux blocks `mmap` on SAF-derived file descriptors.
4. Push your model to the phone, e.g.:
   ```bash
   adb push gemma-3-1b-it-Q6_K.gguf /sdcard/Documents/translation/
   ```
   Btw I dowloaded the model from [here](https://huggingface.co/bartowski/google_gemma-3-1b-it-GGUF/tree/main).
5. Tap **"Model file"** and pick the file you just pushed.
6. Pick a **target language** (default: English (US)).
7. Open any video → comments now have a small **"Translate"** button. Tap to
   translate; tap again to show the original.

## Recommended models

The model has to be in **GGUF** format. It must be a chat-instruction-tuned
LLM — translation is done as a "translate this to X" prompt.

| Model | Quant | Size | Speed (Pixel 9) | Quality |
|---|---|---|---|---|
| **Gemma 3 1B IT** ([bartowski/google_gemma-3-1b-it-GGUF](https://huggingface.co/bartowski/google_gemma-3-1b-it-GGUF)) | Q6_K | ~800 MB | **fast** (a few s) | good |
| Gemma 3 4B IT | Q4_K_M | ~2.5 GB | medium (10–20 s) | very good |
| Qwen 2.5 1.5B Instruct | Q4_K_M | ~1 GB | fast | good |

**Avoid hybrid SSM/state-space models** like the original `translategemma-4b-it`
— they're tuned for TPU/GPU and run ~50× slower than vanilla transformers on
CPU. A 1B vanilla transformer beats a 4B hybrid by orders of magnitude on a
phone.

## Model file persistence on reinstall

The app stores the model's **filesystem path**, not a copy of the model. When
you reinstall the app, the file itself stays where you put it, but the
"All Files Access" permission and the path setting are lost — just re-grant
the permission and re-pick the file. No 800 MB to recopy.

## Performance tuning

The native build enables the right ARM SIMD extensions (`dotprod`, `i8mm`,
`fp16`) via `-march=armv8.6-a+dotprod+i8mm+fp16`. Without these, ggml
falls back to scalar code that is ~50–100× slower for quantized matmul.
If you're running on an older arm64 chip without these features, drop the
flag in `app/build.gradle.kts` to e.g. `armv8.4-a+dotprod` — speed will be
worse but it will at least run.

GPU offload via OpenCL/Vulkan is **not** wired up. The model runs CPU-only.

## How it's wired

| File | Purpose |
|---|---|
| `app/build.gradle.kts` | NDK + CMake configuration, ARM SIMD flags |
| `app/src/main/cpp/CMakeLists.txt` | Pulls a pinned llama.cpp release via `FetchContent` |
| `app/src/main/cpp/llama_translator.cpp` | JNI: backend init, model load, translate, release |
| `app/src/main/java/.../translation/LlamaTranslator.java` | Native binding, fails gracefully if `.so` can't load |
| `app/src/main/java/.../translation/TranslationManager.java` | Singleton: model lifecycle, translation cache, in-flight tracking, auto-translate sessions |
| `app/src/main/java/.../settings/TranslationSettingsFragment.java` | Settings UI: toggle, file picker, language picker, grant-permission button |
| `app/src/main/java/.../info_list/holder/CommentInfoItemHolder.java` | Adds the "Translate" button on each comment |
| `app/src/main/java/.../fragments/list/comments/CommentRepliesFragment.java` | Shows the translated parent + auto-translates up to 10 replies |

The native lib is loaded lazily by the JVM; users who don't enable translation
never call `System.loadLibrary` (the lib is in the APK but unused).

## Auto-translate cap

When you open replies of a translated comment, up to **10 replies** are
auto-translated as they scroll into view. To change the cap, edit
`MAX_AUTO_TRANSLATIONS` at the top of `TranslationManager.java`.

## Disabling

Settings → Translation → toggle off. The "Translate" button disappears from
all comments and the native library stays unused.
