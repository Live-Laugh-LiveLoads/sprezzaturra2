# Idea Forge

A voice-first idea capture app for Android. Talk through an idea (Memo) or record a whole meeting
with the screen off (Meeting). Each recording is transcribed and then run through a team of AI
agents: a **Verifier** cleans the transcript, a **Brainstormer** and an **Evaluator** debate ideas
over several rounds, a **Planner** turns the best ones into plans, and after you approve a plan an
**Executor** writes the deliverables. Your phone notifies you when plans are ready.

Everything is stored on the phone (Room database plus files). Only the audio and text you send to
the providers you configure leave the device.

- Architecture and pipeline diagrams: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- Agent prompts (editable in the app too): [app/src/main/assets/prompts](app/src/main/assets/prompts)
- Output schemas: [app/src/main/assets/schemas](app/src/main/assets/schemas)

## Get the APK without Android Studio

Every push to `main` runs the GitHub Actions workflow in `.github/workflows/android.yml`, which runs
the unit tests and builds a debug APK.

1. Open the repository on GitHub → **Actions** → the latest **Android build** run.
2. Download the **idea-forge-debug-apk** artifact (a zip containing `app-debug.apk`).

The **End-to-end test on emulator** job runs separately, so the APK is available even while it runs.

## Build locally

Requirements: JDK 17 and the Android SDK (API 35, build tools, NDK 27.2.12479018 and CMake 3.22.1
for the on-device Whisper library; Gradle downloads missing SDK parts if `sdkmanager` licenses are
accepted). Create `local.properties` with `sdk.dir=/path/to/Android/sdk` or set `ANDROID_HOME`.

```bash
./gradlew assembleDebug                 # app/build/outputs/apk/debug/app-debug.apk
./gradlew :core:test testDebugUnitTest  # JVM unit tests (pipeline, schemas, stitching, audio math)
./gradlew connectedDebugAndroidTest     # end-to-end test on a connected device or emulator
```

To skip compiling whisper.cpp (faster builds, ~1.5 MB smaller APK, no on-device transcription):
`./gradlew assembleDebug -Pideaforge.whisperNative=false`.

### Signed release APK

1. Create a keystore once:
   ```bash
   keytool -genkeypair -v -keystore release.jks -alias ideaforge -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Create `keystore.properties` in the project root (it is git-ignored):
   ```properties
   storeFile=/absolute/path/to/release.jks
   storePassword=...
   keyAlias=ideaforge
   keyPassword=...
   ```
3. `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.

In CI, add these repository secrets and the workflow also uploads a signed **idea-forge-release-apk**:
`IDEAFORGE_KEYSTORE_BASE64` (`base64 -w0 release.jks`), `IDEAFORGE_KEYSTORE_PASSWORD`,
`IDEAFORGE_KEY_ALIAS`, `IDEAFORGE_KEY_PASSWORD`.

The debug build installs as `com.ideaforge.debug`, so it can sit next to a release build.

## Sideload onto the phone

**Without a computer:** copy `app-debug.apk` to the phone (Drive, USB, email), open it from the
Files app, and allow "Install unknown apps" for that app when Android asks (Settings → Apps →
Special app access → Install unknown apps). On Android 8 to 9 the switch is per app in the same place;
on very old builds it is "Unknown sources" under Security.

**With adb:**
1. On the phone: Settings → About phone → tap **Build number** 7 times, then Settings → System →
   Developer options → enable **USB debugging**.
2. Connect USB, accept the prompt on the phone, then:
   ```bash
   adb install -r app-debug.apk
   ```

If your phone is older than Android 8.0, lower `minSdk` in `gradle/libs.versions.toml`
(the only place it is set). Paths below API 26 are untested.

## Get API keys

| Use | Provider | Where |
|---|---|---|
| Agents (default) | xAI Grok | https://console.x.ai → API Keys |
| Agents | OpenAI | https://platform.openai.com/api-keys |
| Agents | OpenRouter (many models) | https://openrouter.ai/keys |
| Agents | Anthropic (OpenAI-compatible endpoint) | https://console.anthropic.com → API Keys |
| Agents | Ollama on your LAN | no key; base URL `http://<computer-ip>:11434/v1`, run `OLLAMA_HOST=0.0.0.0 ollama serve` |
| Transcription (default) | OpenAI Whisper (`whisper-1`) or diarized (`gpt-4o-transcribe-diarize`) | https://platform.openai.com/api-keys |
| Transcription | Groq Whisper (`whisper-large-v3-turbo`, fast and cheap) | https://console.groq.com/keys |

Keys are stored with EncryptedSharedPreferences (AES-256, Android Keystore), are never logged, and
are not included in exports or backups.

## First-run setup

1. Open the app. The setup screen asks for an agent provider key (xAI by default), a transcription
   key (or on-device transcription), and a monthly spending cap.
2. Allow notifications and the microphone when asked.
3. Accept the prompt to exempt Idea Forge from battery optimisation; without it some phones stop
   long meetings when the screen is off.
4. Optional: Settings → Agents to pick a model per agent (tap "pick from list" to fetch the
   provider's model list), Settings → Pipeline to add a glossary of names and jargon.
5. Tap **Memo** and talk for a minute, then stop. You'll get notifications when the transcript is
   ready and when plans are ready; tap the second to approve plans.

**Meeting mode and consent.** Illinois requires every party's consent to record a conversation.
Before every meeting the app shows "Everyone present has agreed to be recorded" and will not start
until you confirm it. The confirmation time is saved with the recording (Audio tab → events, and in
exports).

## How a session flows

Record → Transcribe → Verify → [Review pause] → (Brainstorm ⇄ Evaluate) × N → Plan → notify →
you approve → Execute → notify.

- During a meeting, each 5-minute chunk is transcribed as soon as it closes.
- Each stage saves its output; failures resume from the last finished step.
- Session screen → tap any stage chip to see its output metadata, re-run it, or edit its JSON
  output (validated against the schema; later stages then re-run on the edit).
- Turn on Settings → Pipeline → **Pause after Verify** to fix the transcript before agents read it.
- Plans screen: Approve, Reject (with a reason, kept in the archive), Edit then approve,
  multi-select, and "Approve all scoring at least X".
- Deliverables are Markdown (or code) files you can view, copy, or share with the share sheet.
- Search covers all cleaned transcripts and ideas.
- Export a session as one Markdown file or a ZIP (audio, transcripts, every agent output as Markdown
  and JSON). Settings → Data has full backup and restore, and import of an existing audio file.

## Configurable defaults

All defaults live in `app/src/main/java/com/ideaforge/settings/AppSettings.kt` and can be changed in Settings.

| Setting | Default | Notes |
|---|---|---|
| Agent provider | xAI Grok, `https://api.x.ai/v1` | Any OpenAI-compatible endpoint can be added |
| Model for every agent | `grok-4-fast-reasoning` | Per-agent; use "pick from list" if xAI renames models |
| Temperatures | Verifier 0.1, Brainstormer 0.9, Evaluator 0.3, Planner 0.4, Executor 0.5 | |
| JSON mode | On (off for the Anthropic profile) | `response_format: json_object` |
| Transcription | Cloud, OpenAI `whisper-1`, language `en`, diarize on | Diarization only with `*-diarize` models |
| On-device model | `base.en`, 2 threads | `tiny.en` is faster; both are slow on old phones |
| Brainstorm/evaluate rounds | 2 (1 to 4) | Stops early when the shortlist stops changing |
| Shortlist size | 5 | |
| Options per core idea | 10 (prompt asks for 6 to 13) | |
| Stages | All on; Pause after Verify off; auto start on | |
| Monthly spending cap | $10 (0 = no cap) | Pipeline pauses when reached |
| Prices | List prices for Grok, OpenAI, Groq models | Editable; unknown models count as $0 |
| Chunk length / overlap | 5 min / 5 s | |
| Audio | AAC-LC 16 kHz mono 32 kbps (≈14 MB per hour) | Mic source `MIC` (try `VOICE_RECOGNITION` if it sounds over-processed) |
| Battery | Warn at 15%, stop at 5% when not charging | |
| Storage | Stop when under 50 MB free | |
| Interruptions | Pause on calls and audio-focus loss, auto-resume | |
| Wake lock while recording | On | |
| Approve-all threshold | 7.0 | |
| Auto-delete audio | Off (0 days) | Transcripts and outputs are kept |
| Notifications | All on | |

## Android platform limits and what the app does instead

- **Starting the microphone in the background** (Android 14+): a microphone foreground service can
  only start while the app is visible. Recording always starts from a tap in the app, then keeps
  running with the screen off in a foreground service with a persistent notification.
- **Background apps get silent audio** (Android 9+) unless a microphone foreground service runs.
  All recording happens in that service.
- **The system may kill the app anyway** (low memory, aggressive OEM battery savers). It cannot
  restart the microphone by itself afterwards (Android 14+ forbids it). The audio is written in
  crash-safe ADTS chunks, so at most a few seconds are lost; on next launch the app finalizes what
  was captured, notifies you, and processes it.
- **Phone calls**: detecting calls directly needs the phone-state permission, so the app instead
  pauses when Android reports a call audio mode or audio focus loss, and when the mic is silenced
  (Android 10+). It resumes automatically after the call.
- **Long background work**: WorkManager stops workers after 10 minutes unless they run in the
  foreground, and Android 12+ blocks starting that from the background. Stages are saved one by
  one (and plans/deliverables one at a time), so a stopped worker resumes where it left off.
- **Diarization**: only providers that return speaker labels (OpenAI `gpt-4o-transcribe-diarize`)
  produce Speaker 1/2. Labels are per chunk in long meetings, so the same person can get a different
  label in a later chunk; rename speakers in the Transcript tab.
- **Backups exclude API keys** because they are encrypted with a key that never leaves this phone.

## Project layout

```
core/      Pure Kotlin: transcript stitching, JSON schema validation, LLM and Whisper clients,
           agents, pipeline orchestrator, cost metering, plus test fakes (src/testFixtures)
app/       Android app: Compose UI, Room, settings, recording service, workers, notifications, export
whisper/   whisper.cpp JNI bridge, audio decoding, model download
docs/      Architecture
```
