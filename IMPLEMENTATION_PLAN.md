# ECHO — Implementation Plan

> **Status:** pre-implementation analysis complete, toolchain installed, project scaffolded.
> **First build is currently blocked on one missing prerequisite** (a full JDK — see §15).
> `PROJECT.md` remains the product source of truth; this document is the engineering plan derived from it.

---

## 1. What actually has to be built (the real end product)

A single installed Android APK that, with the phone in **airplane mode**, does this for real:

1. **START SESSION** → a foreground service opens CameraX, `AudioRecord`, and `SensorManager` streams on one shared monotonic clock.
2. Detectors convert raw streams into **observations** (numbers, no semantics) → **events** (typed, timestamped, confidence-tiered) → **multimodal correlation** (co-occurrence edges with Δt).
3. Ring buffers hold the last few seconds; when an event fires, a **window of evidence** (frame burst + audio clip + sensor trace) is flushed to disk. No continuous recording.
4. **Live dashboard** displays the real pipeline: per-detector rates, event feed as it grows, confidence badges, evidence bytes retained.
5. **END SESSION** → capture stops → deterministic incident summary computed → local LLM writes the narrative → session sealed.
6. **INVESTIGATE** → chat over that session's real events/evidence; answers cite event IDs + timestamps, and separate **OBSERVATION / INFERENCE / UNCERTAINTY / EVIDENCE**.
7. **Shareable incident report** (PDF/Markdown + evidence bundle) via the share sheet → the "Mobile Office Kit" criterion.

The LLM **never** sees raw camera/audio. It sees a compact, structured, deterministic digest — and its output is validated against that digest before display. That single constraint is what makes the product honest, fast, and non-hallucinating.

---

## 2. Environment audit (verified on the development machine, Sept 2026)

| Item | Verdict |
|---|---|
| OS | Ubuntu 24.04.4 LTS, kernel 7.0.0-28, x86_64 |
| CPU / RAM | 12 cores, 15 GiB total, ~9 GiB available |
| **Disk** | **~9.8 GB free of 79 GB (87 % used)** ← tightest constraint |
| JDK | OpenJDK 21.0.11 **JRE only — `javac` is NOT installed** ⚠️ blocks AGP |
| Kotlin / Gradle | Gradle **8.14.5 installed** at `~/Android/tools/gradle-8.14.5`; Kotlin comes via the Gradle plugin |
| Android SDK | ✅ installed at `~/Android/sdk` — build-tools 36.0.0, platform-tools 37.0.1 (adb), platform 36, cmdline-tools |
| **Connected Android device** | ❌ **none** — `lsusb` shows only a webcam, touch controller, BT radio |
| Emulator | `/dev/kvm` exists but the user is **not in the `kvm` group**; no system images (deliberately not installed) |
| Python / Node | 3.12.3 (+numpy, scipy), Node 24.16 |
| Other | git, curl, wget, ffmpeg, gcc, unzip, jq, VS Code; docker CLI present but daemon down; no cmake/clang/ninja |
| Network | ✅ dl.google.com, repo1.maven.org, huggingface.co, mediapipe-models GCS reachable. Measured ≈ 0.5–1.2 MB/s (slow) |

### 2.1 Verified artifact availability (no guesswork)

| Artifact | Version | Notes |
|---|---|---|
| `com.google.ai.edge.litertlm:litertlm-android` | **0.17.1** | AAR 20.5 MB; depends on `kotlin-reflect 2.4.0` ⇒ Kotlin **2.4.x** required |
| AGP | **8.13.2** stable (9.4.0 also stable) | 8.x chosen for config stability |
| CameraX | **1.6.2** stable | + `camera-camera2`, `camera-lifecycle`, `camera-view` |
| `com.google.mediapipe:tasks-audio` / `tasks-vision` | **1.0.0** | Audio classifier + vision tasks |
| YAMNet audio classifier `yamnet.tflite` | **4.13 MB**, ungated | `mediapipe-models` GCS bucket |
| EfficientDet-Lite0 `efficientdet_lite0.tflite` | **7.25 MB**, ungated | optional object labels |
| `litert-community/gemma-4-E2B-it-litert-lm` | ungated | `gemma-4-E2B-it.litertlm` 2.59 GB · **`-gpu` 2.01 GB** · **`_qualcomm_sm8750` 3.01 GB (NPU path)** |
| `On-device/Gemma3-1B-IT-litert-lm` | ungated | **`gemma3-1b-it-int4.litertlm` 584 MB** (small fallback) |
| `litert-community/functiongemma-270m-ft-mobile-actions` | ungated | `mobile_actions_q8_ekv1024.litertlm` **289 MB** (optional tool-caller) |
| `litert-community/gemma-3-270m-it` | ungated | includes `qualcomm.sm8750` 461 MB variant |

**Verified LiteRT-LM Kotlin API** (from official docs, so the plan does not depend on invented functions):
`Engine` / `EngineConfig(modelPath, backend, cacheDir)`, `Backend.CPU() | GPU() | NPU(nativeLibraryDir = …)`, `ConversationConfig(systemInstruction, initialMessages, samplerConfig, tools)`, streaming via `sendMessageAsync()` → `Flow`, `ExperimentalFlags.enableSpeculativeDecoding = true` (MTP), tools via `ToolSet` + `@Tool`/`@ToolParam`, multi-modality via `Content.ImageFile` / `Content.AudioBytes`.
GPU backend **requires** in the manifest:
```xml
<uses-native-library android:name="libvndksupport.so" android:required="false"/>
<uses-native-library android:name="libOpenCL.so" android:required="false"/>
```

### 2.2 Consequences carried into the design

- A JDK with `javac` must be installed before the first build (§15).
- **No annotation processors.** KSP's newest release still tracks Kotlin 2.2.21, but `litertlm-android` needs Kotlin 2.4.x. Room/Hilt are therefore replaced by a hand-written SQLite layer and manual DI — this removes the single most fragile part of the build.
- ~9.8 GB free ⇒ **no emulator system images**, and the 2 GB model must be budgeted deliberately (prefer the **2.01 GB gpu** variant, keep the **584 MB 1B** as the swap-in).
- The build host cannot validate camera/mic/sensor behaviour. Only the phone can. All non-Android logic is therefore kept in pure Kotlin so it is unit-testable here.

---

## 3. Target devices

| Phase | Device | SoC / RAM | Role |
|---|---|---|---|
| Phase 1 (now) | **Samsung Galaxy M12** | Exynos 850 (8× A55), **4 GB RAM**, Android 11, no gyroscope, Mali-G52 | **Stress test**: capture reliability, perf ceilings, false-positive tuning, whole UI/evidence flow |
| Demo | **iQOO 13 / 15 (SM8750-class)** | Snapdragon 8-series, 12–16 GB, Adreno GPU, Hexagon NPU | **Showcase**: full multimodal pipeline + Gemma4-E2B on GPU with MTP |

Because of this split, **`DeviceProfile` (LOW/HIGH) is a first-class concept**: the pipeline architecture is identical on every device; only detector rates, model choice, and backend differ.

| | LOW (M12-class) | HIGH (iQOO-class) |
|---|---|---|
| Camera fps / resolution | 3 fps, 320×240 | 8 fps, 640×480 |
| Audio classify interval | 1000 ms | 500 ms |
| Local model | Gemma3-1B-IT int4 (584 MB), CPU | Gemma4-E2B (2.01 GB gpu), GPU + MTP |
| LLM expectation | slow (single-digit tk/s) → **deterministic Kotlin answers cover it** | ~50 tk/s decode, ~0.3 s TTFT |
| NPU | n/a | stretch experiment only |

The iQOO/OriginOS battery manager kills background services aggressively, so the pre-flight screen makes the battery-exemption check a **first-class, surfaced requirement**.

---

## 4. Product architecture & data flow

```
                 ┌───────────────────── ONE MONOTONIC CLOCK (elapsedRealtimeNanos → epoch at t0) ─────────────────────┐
CameraX ImageAnalysis ─► FrameDiff/Brightness ─┐
AudioRecord 16k mono  ─► RMS/FFT gates ─► YAMNet┼─► OBSERVATIONS ─► EVENT EXTRACTOR ─► CORRELATION ─► TIMELINE (SQLite)
SensorManager 50 Hz   ─► Peak/EMA baseline ────┘        │                 │                │
                                                        │                 ▼                ▼
CHAT/SUMMARY ◄─ CLAIM VALIDATOR ◄─ LOCAL LLM ◄─ DIGEST ◄─┴──────────► EVIDENCE RETAINER (ring buffers → event windows)
                      ▲                                                 │
                      └──────── external API (consent-gated, reduced JSON only) ──────┘
```

Three-tier semantics is the backbone:

| Tier | Meaning | Example | Produced by |
|---|---|---|---|
| **Observation** | measurable primitive, no interpretation | `AUDIO_RMS=0.41 (baseline 0.08)`, `FRAME_MOTION=18.2%`, `ACCEL_PEAK=2.7g` | detectors (deterministic) |
| **Event** | typed occurrence + confidence tier | `IMPACT_TRANSIENT @ 00:01:42.310, CONFIRMED` | extractor + correlation |
| **Inference** | relational statement over events, with citations | "the impact *may have been preceded by* the motor tone change 0.8 s earlier" | LLM + rules, **always** labelled |

No causal claim is ever stored as fact. Relations are stored as `PRECEDES | CO_OCCURS | SUSTAINED_WITH` with Δt and window — "co-occurrence", never "caused by".

---

## 5. Android architecture

**Single Gradle module `:app`** (fastest builds, zero module wiring, no annotation processors inside one variant), package-split by concern, **manual DI** (`AppContainer`).

```
com.echo
├─ core/            AudioSpec, time (SessionClock, TimeWindow), model  ← NO Android imports
│  └─ device/       DeviceProfile, CapabilityProbe
├─ capture/         SessionService (FGS), CameraSource, AudioSource, SensorSource, RingBuffers
├─ perception/      AudioDetector, VisionDetector, MotionDetector, BaselineTracker
├─ fusion/          EventExtractor, CorrelationEngine, Salience          ← NO Android imports
├─ evidence/        EvidenceRetainer, FrameBurstStore, AudioClipStore, SensorTraceStore
├─ data/            SQLite layer (hand-written SQLiteOpenHelper), DAOs, repositories
├─ ai/              DigestBuilder, SessionRetriever, LocalLlmEngine, AnswerValidator,
│                   ExternalAiClient (fallback), AiOrchestrator         ← core logic Android-free
├─ report/          IncidentReportBuilder (PdfDocument + Markdown), ShareBundle
└─ ui/              preflight/ session/ dashboard/ summary/ timeline/ investigator/ history/
```

**Deliberate design rule:** `core`, `fusion`, `ai`, and `report` are **pure Kotlin**. Roughly 60 % of the logic (correlation, retrieval, digest, validator, report text) is unit-testable **on this Linux box with no phone and no emulator** — the main mitigation for having no attached device.

**Service & permissions (Android 14+/16 realities):**
- `SessionService : LifecycleService`, `android:foregroundServiceType="camera|microphone"`, persistent notification with live event count + END action.
- Permissions: `CAMERA`, `RECORD_AUDIO`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA`, `FOREGROUND_SERVICE_MICROPHONE`, `WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Accel/gyro at 50 Hz needs **no** permission.
- `minSdk 29` (covers the Android 11 M12), `targetSdk 36`, `compileSdk 36`. The on-device LLM is **feature-gated at runtime**, not by `minSdk`.
- UI binds to service state via a `SessionBus` (StateFlow) so the dashboard reflects the *service's* real state, not a ViewModel copy.
- Wake behaviour: `PARTIAL_WAKE_LOCK` + FGS keeps capture alive with the screen off.

*(Already implemented in the scaffold: manifest, permissions, launcher icon, theme, `MainActivity`, `CapabilityProbe`, `DeviceProfile`, pre-flight screen.)*

---

## 6. Perception layer — per modality: cost, limits, MVP status

| Modality | Method | Rate | Cost | Key limitation | MVP? |
|---|---|---|---|---|---|
| **Audio energy** | `AudioRecord` 16 kHz mono PCM16, 20 ms hops → RMS + band energies (Goertzel/FFT-256) + ZCR + spectral flux, adaptive EMA baseline (mean + k·σ) | continuous | <2 % of one core | Room noise raises the baseline; a fan can mask impacts | **Yes** |
| **Audio class** | MediaPipe `tasks-audio` AudioClassifier + YAMNet (4.13 MB), 975 ms window, **gated**: runs only when the energy gate is open | ≤2/s, ~0 idle | 3–8 % while gated open | YAMNet's 521 generic classes (Thump, Glass, Speech); no "motor failure" class — we infer *change*, not named faults | **Yes** |
| **Vision motion** | CameraX `ImageAnalysis`, 640×480 @8 fps, `KEEP_ONLY_LATEST`; luma → 160×120, frame-diff vs rolling background → changed-pixel % + motion centroid + brightness | 8 fps | 3–6 % | Rain/auto-exposure/flicker cause false motion; sensitivity must be exposed in the UI | **Yes** |
| **Vision objects** | EfficientDet-Lite0 (7.25 MB) on 1–2 fps keyframes → presence labels | 1–2 fps | 5–10 % during inference | Weak on small/odd objects; adds labels, not causes | Stretch |
| **Device motion** | `SensorManager` accelerometer (+gyro) @ ~50 Hz: |a| peak detection vs EMA baseline, variance windows, orientation change | 50 Hz | **The phone is the sensor** — it measures *device* motion, not the rig's. Fix: mechanically couple the phone to the rig/table (§10) | **Yes** |

Explicitly **not** doing: continuous video recording, per-frame scene description, or audio-to-text.

---

## 7. Event & evidence data model

```kotlin
Observation(id, sessionId, tMs, modality, kind, value: Double, unit, quality,
            baselineValue: Double?, deviationSigma: Double?)

Event(id, sessionId, tStartMs, tEndMs, type, modalities: Set<Modality>,
      tier: CONFIRMED | PROBABLE | UNCONFIRMED, confidence: Double, salience: Double,
      description: String, observationIds: List<Long>, evidenceIds: List<Long>)

EventRelation(id, fromEventId, toEventId, kind: PRECEDES | CO_OCCURS | SUSTAINED_WITH,
              deltaMs: Long, windowMs: Long, confidence: Double)

Evidence(id, sessionId, eventId?, kind: FRAME_BURST | AUDIO_CLIP | SENSOR_TRACE | VIDEO_CLIP,
         path, tStartMs, tEndMs, bytes, mime, sha256)

Insight(id, sessionId, askedAtMs, question, answerMd,
        citedEventIds: List<Long>, citedEvidenceIds: List<Long>,
        validatorStatus, usedExternalAi: Boolean, promptTokens, decodeMs)

Session(id, startedAtMs, endedAtMs?, status: RECORDING | INTERRUPTED | SEALED,
        deviceMeta(json), digestJson, summaryMd, baselineJson, evidenceBytes, eventCount)
```

**Event taxonomy (MVP):** `IMPACT_TRANSIENT`, `SUSTAINED_NOISE_START/STOP`, `TONE_CHANGE`, `SPEECH_PRESENT`, `RAPID_MOTION`, `MOTION_START/STOP`, `BRIGHTNESS_CHANGE`, `SCENE_CHANGE`, `DEVICE_SHOCK`, `VIBRATION_BURST`, `ORIENTATION_CHANGE`, plus fused `INCIDENT` and `PRE_INCIDENT_DEVIATION`.

**Correlation rules (deterministic, tunable, unit-tested):**
- A/V ±250 ms · audio↔motion ±400 ms · vision↔motion ±500 ms · sustained pairs = interval overlap.
- `CONFIRMED` needs ≥2 modalities with independent observations in-window; single strong modality → `PROBABLE`; below threshold → `UNCONFIRMED` (kept, greyed, never deleted).
- First 30 s = baseline window; later observations are scored as deviation-in-σ from baseline. `PRE_INCIDENT_DEVIATION` = a sustained shift 0.5–5 s *before* an `INCIDENT` — this is what lets the investigator say something useful about *why*.
- Salience = f(magnitude σ, modality count, tier) → drives both the digest cap and UI ordering.

**Evidence retention (the pragmatic decision):** MVP stores **`FRAME_BURST`** (10 downscaled JPEGs covering t−2 s…t+2 s, ~150 KB), **`AUDIO_CLIP`** (6 s WAV, 192 KB), **`SENSOR_TRACE`** (t−3 s…t+3 s accel/gyro CSV, tiny) ≈ **0.4 MB/event**; a 30-event session ≈ 12 MB.
**H.264 `VIDEO_CLIP` via a `MediaCodec` ring-muxer is Phase 2** — it is the single most failure-prone Android component (codec quirks, muxer seek tables, buffer starvation) and must not sit on the critical path. The UI plays audio + scrubs the frame burst as a filmstrip; if the encoder lands, the same evidence card plays a real clip.

---

## 8. Session lifecycle (state machine)

```
IDLE ──start()──► PREPARING ──ready──► RECORDING ──end()──► FINALIZING ──► SUMMARIZING ──► READY(INVESTIGATE) ──► ARCHIVED
                    │                      │                    │              │
              pre-flight checks:      detectors live,      stop capture,   LLM preloaded
              perms, storage>1GB,     ring buffers armed,  flush rings,    in background,
              battery exemption,      baseline learns     seal DB tx,     streaming summary
              service-alive probe     30 s, dashboard on   compute stats
```

- **PREPARING** runs a real pre-flight checklist and *displays pass/fail* (permissions, free storage, CameraX probe, AudioRecord probe, sensor presence, battery exemption, FGS survivability). A demo must fail loudly *before* the session, never during it.
- **RECORDING** tolerates individual failures: if the camera is lost, the session continues in degraded mode, the dashboard says so, and the summary records "vision unavailable from t=X".
- **FINALIZING → SUMMARIZING**: `engine.initialize()` (up to ~10 s) is kicked off **in the background during the last minute of RECORDING** so END → summary is instant. Summary streams token-by-token via Flow.
- **Crash/OS-kill recovery**: the session row is written at START; evidence is append-only on disk; the next launch offers "recover interrupted session".
- **Privacy lifecycle (explicit, in-app):** evidence is app-private (`filesDir`), never uploaded; **Delete session** wipes DB rows + evidence dir + digest; a privacy screen states exactly what leaves the device (nothing, unless a question is escalated); a storage meter caps the session (default 250 MB, oldest `UNCONFIRMED` evidence evicted first).

---

## 9. Investigator & query architecture (arbitrary questions, no hardcoding)

The key insight that makes this tractable on a small model: **a session is small.** Ten minutes of monitoring at conservative thresholds yields ~20–80 events ≈ 1.5–4 k tokens of structured digest — it fits in a phone LLM's context. So we do **not** need embeddings or vector search.

```
question ─► SessionRetriever (deterministic) ─► Digest ─► LocalLlmEngine (Gemma4-E2B, GPU+MTP / Gemma3-1B CPU)
                                                    │              │
                                            always: session header, timeline, │
                                            relations, baseline, evidence refs │
                                                    │              ▼
                                                    └────► AnswerValidator ──► rendered answer
                                                                   │ (+ re-prompt once on failure)
                                                                   ▼
                                                        ExternalAiClient (only if escalated)
```

1. **SessionRetriever** — rule-based, no LLM: time-hint parsing ("before the fall", "at the start"), modality/type filters ("sound", "impact", "motion"), ordering intent ("what happened first"), plus always-include header + timeline + relation edges. If events > `MAX_DIGEST_EVENTS` (≈120), keep top-salience events and include a per-minute aggregate so nothing is silently dropped.
2. **Digest** — compact, deterministic: session meta (relative offsets first), baseline stats, event list (`id`, `t=+mm:ss.mmm`, `type`, `modalities`, `tier`, `salience`, `evidence`), relation list (`E7 PRECEDES E9 by 812 ms`), evidence index. **No raw media, no device identifiers, no wall-clock before mapping.**
3. **System-prompt contract** (enforced, not merely requested): answer in four labelled blocks — `OBSERVED`, `POSSIBLE EXPLANATION`, `UNCERTAIN / NOT DETECTED`, `EVIDENCE` — cite `[E<id> @ +mm:ss.mmm]` for every factual claim, use only "may indicate / preceded / followed / co-occurred", and say **"insufficient evidence"** when the digest does not support an answer. FunctionGemma tool-calling is **not** needed for MVP because the full digest fits; it stays as an optional upgrade for long sessions.
4. **AnswerValidator (the anti-hallucination gate)** — runs on every answer, local or external:
   - extract every cited ID/timestamp/number → **must exist in the digest** (else targeted re-prompt, then strip the claim and mark it *unverified*);
   - reject causal verbs outside the allowlist ("caused", "because of", "due to") unless a relation edge of sufficient confidence exists — downgrade to "may have preceded";
   - require the four sections; if absent → re-prompt once → else render the deterministic fallback answer built **directly from the timeline in Kotlin** (so the app can always answer something truthful);
   - attach the validation report to the `Insight` row and show an "all claims verified against session data" badge.

This makes the success criterion "every claim traces to a timestamped event" **mechanically true** rather than a hope — and it is exactly what keeps the M12 usable despite its slow LLM.

---

## 10. Local ↔ external AI boundary (mandatory local-first, strictly)

Implemented as an explicit five-stage pipeline, with the gate visible in the UI:

1. **Local structure (always):** the `SessionDigest` is built by local code. Nothing leaves the device before it exists.
2. **Trigger gate:** an external call happens **only** when (a) the user taps *"Ask an external AI"* on a specific question, or (b) the local model's validator fails twice **and** the user accepts escalation. Never automatic, never for live data.
3. **Reduction & consent preview:** show the literal payload about to be sent (character + token count) and list the removed fields: raw audio ✗, raw frames ✗, device model/serial ✗, location ✗ (never collected), MAC/IP ✗. Wall-clock is replaced by session-relative offsets before sending and re-mapped on return. Confirmation is per-request; the key lives in `local.properties`/encrypted prefs, never in code.
4. **Return path through local processing:** the response is **not** displayed. It is (i) schema-checked, (ii) run through the same `AnswerValidator` against the digest, (iii) re-normalized by the **local** model into the four-section format with local citation formatting, (iv) stamped `usedExternalAi = true` and shown with a persistent banner ("External AI used — data was locally reduced first").
5. **Failure = local-only:** any network error, timeout, or validation failure silently falls back to the local answer, and the UI says which path produced it. Airplane mode is a fully supported state.

**Concrete provider (recommended, optional fallback): Novita AI** — OpenAI-compatible `POST /v3/chat/completions`, API-key only, no heavy SDK, cheap per-token. This is the *only* network dependency in the app.

```
NOVITA_API_KEY=...        # in local.properties, injected via BuildConfig; never committed
model: "meta-llama/Llama-3-8B-Instruct"   # or any cheaper instruct model on the account
```

Not required for the demo — **the offline path is the demo path.**

---

## 11. Physical demo design (designed so correlation is genuinely demonstrable)

**Primary — "motorized rig fails under load"** (~₹500 of parts, repeatable in a venue):

- Rig: small 6 V DC motor with an unbalanced/propeller load on a wooden base; a weighted arm held by a screw; the **phone taped/standing on the same base plate** (so device-motion sensing is mechanically honest); the back camera aimed at the rig.
- Unannounced sequence:
  1. `START SESSION`. Baseline learns 30 s of normal motor hum (dashboard shows `SUSTAINED_NOISE`, low-variance `VIBRATION`).
  2. Operator **loosens the screw / adds load** → motor tone and vibration shift → `TONE_CHANGE` + `VIBRATION_BURST` (both `PROBABLE`).
  3. Weight **falls** → loud transient **`IMPACT_TRANSIENT`** + **`RAPID_MOTION`** (frame diff spike) + **`DEVICE_SHOCK`** within ~1 s → fused **`INCIDENT`, `CONFIRMED`** (3 modalities) — the dashboard lights up live on stage.
  4. `END SESSION` → summary appears; the `PRE_INCIDENT_DEVIATION` at step 2 is the "why".
  5. Ask the *unscripted* question: **"Something went wrong — what happened before the part fell?"** → the answer cites the tone change at `+00:41.2` (audio, PROBABLE), the motion burst at `+00:42.0`, the impact at `+00:42.6` (both CONFIRMED), and says the audio shift *may have preceded* the fall.
  6. Scrub evidence at those timestamps (frame filmstrip + audio clip; video clip if Phase 2 landed).
  7. Share the incident report to a Docs app on the phone.
- Second on-stage question proves non-hardcoding: **"Was anyone talking during the experiment?"** → YAMNet `SPEECH_PRESENT` events, or an honest "no speech detected" plus the uncertainty section.

**Fallbacks (in order):**
(a) drop a heavy book onto the table with the phone lying on it — impact + shock + frame motion, 100 % reliable, no rig;
(b) a *previously recorded real* session from the same device, opened from history;
(c) on-site threshold tuning via a settings panel that exposes sensitivity live (which itself demos "real pipeline").

**Honest limitation to state on stage:** the accelerometer senses the *device's* motion (coupled to the rig), not the rig's independently — which is exactly why the phone is mounted on the rig.

---

## 12. MVP vs. optional

**MVP (demo-critical — if only half of this ships, the demo still wins):**
1. FGS monitoring of all three modalities on a shared clock
2. Detectors: audio RMS/bands + gated YAMNet, frame-diff motion, accel peaks/variance
3. Baseline learning + deviation scoring
4. Event extraction + correlation + tiers + relations → timeline DB
5. Frame-burst + audio-clip + sensor-trace evidence retention with ring buffers
6. Live dashboard reflecting real detectors/events/storage
7. END → deterministic summary + local-LLM narrative (streamed), instant thanks to background preload
8. Investigator chat over the full digest + `AnswerValidator` + four-section output
9. Fully offline; session history + evidence playback; delete session
10. Incident report export (PDF + Markdown) + share, plus JSON/ZIP evidence bundle

**Optional (only if time remains):** H.264 `VIDEO_CLIP` evidence · EfficientDet object labels · `Backend.NPU()` (Qualcomm sm8750 model + QNN libs) · FunctionGemma tool-calling · external AI fallback wiring · grounded frame captions · session compare/diff · multi-session memory · extended tuning UI.

---

## 13. Technology choices & justification

| Concern | Choice | Why |
|---|---|---|
| Language/UI | **Kotlin 2.4.20 + Jetpack Compose (M3)** | Fastest real-time dashboard iteration; required by the AI SDK's Kotlin 2.4 metadata |
| Build | **AGP 8.13.2 + Gradle 8.14.5 + JDK 17/21 target**, `compileSdk/targetSdk 36`, `minSdk 29` | Verified available; avoids AGP 9 config churn |
| Annotation processing | **None** (no Room, no Hilt) — hand-written SQLite + manual DI | KSP cannot track Kotlin 2.4 yet; removes the most fragile build component |
| Camera | **CameraX 1.6.2 `ImageAnalysis`** | Device-quirk handling, `KEEP_ONLY_LATEST` backpressure, Y-plane for cheap diffing |
| Audio | **`AudioRecord` + own DSP**, **MediaPipe `tasks-audio` 1.0.0 + YAMNet** | DSP is free and reliable; YAMNet adds labels; both offline and ungated |
| Motion | **`SensorManager`** + own peak/EMA logic | Zero dependency, deterministic, testable |
| Storage | Hand-written **`SQLiteOpenHelper`** + plain files for evidence | Queryable timeline without annotation processing; evidence as files keeps RAM flat |
| Local LLM | **LiteRT-LM `litertlm-android` 0.17.1** — primary **Gemma4-E2B gpu (2.01 GB, GPU + MTP)**, fallback **Gemma3-1B int4 (584 MB)** | Verified API + ungated models; benchmark path ≈52 tk/s decode, ~0.38 s prefill for 1.5 k tokens, ~676 MB peak on GPU |
| External AI | **Novita AI** OpenAI-compatible HTTP | One key, no SDK, cheap; strictly consent-gated fallback |
| Report | **`android.graphics.pdf.PdfDocument` + Markdown + `FileProvider` share** | Zero extra deps, produces a real office document from the phone |
| Serialization | **kotlinx.serialization 1.11.0** | Digest/JSON export without reflection |
| Async | **Coroutines 1.11.0 + Flow**, `Dispatchers.Default` for detectors | Streaming summary + backpressure |
| Tests | **JUnit 4.13.2 on JVM** for `core`/`fusion`/`ai`/`report`; instrumented tests only where unavoidable | Only way to iterate well while the device is off-machine |

---

## 14. Risks & fallbacks

| # | Risk | Impact | Fallback |
|---|---|---|---|
| 1 | **No phone attached to this machine** | Cannot validate any real capture | Ship debuggable APKs + a JVM harness for all pure logic; connect USB/wireless-debug, or sideload and paste logcat |
| 2 | **`javac` missing (current blocker)** | No build at all | Install a JDK 21 tarball into `~/Android/tools` (Adoptium / Microsoft / Corretto all verified reachable) |
| 3 | **~9.8 GB free disk** (SDK + Gradle caches + 2 GB model) | Build or download fails | No emulator/system images; develop against the 584 MB 1B model, swap to E2B for the demo; `--no-daemon` if memory-tight |
| 4 | Slow network (0.5–1.2 MB/s) | Multi-GB downloads stall | Download once, cache aggressively; `adb push` the model into the app's files dir instead of downloading on stage |
| 5 | GPU/OpenCL driver flakiness on the iQOO | LLM won't init or crashes | Runtime backend chain **GPU → NPU → CPU** with measured timings shown in-app; CPU on Gemma3-1B as the floor |
| 6 | E2B RAM pressure alongside the live pipeline | OOM / jank | LLM is post-session; preload only in the final minute; free ring buffers before `initialize()` |
| 7 | NPU/QNN libs undocumented (the docs link probed 404s) | Wasted effort | **NPU is a stretch experiment, never a dependency** — though sm8750 model variants do exist (verified) |
| 8 | False positives flood the timeline | Demo looks silly | Conservative thresholds + `UNCONFIRMED` tier instead of deletion + sensitivity sliders + on-site tuning; the dashboard shows raw rates so it never looks "magic" |
| 9 | OS kills the FGS (OriginOS battery management) | Session dies mid-demo | Pre-flight survival probe, battery-exemption deep link, `PARTIAL_WAKE_LOCK`, alive-check timer that warns loudly on a stalled capture |
| 10 | Cross-modal timestamp drift | Correlation claims become wrong | One clock: every observation stamped with `elapsedRealtimeNanos` at capture, converted once at t0; a unit test asserts monotonicity; the dashboard shows measured Δt per fused event |
| 11 | LLM cites events/timestamps that don't exist | Breaks the core promise | `AnswerValidator` + Kotlin-generated deterministic fallback answer |
| 12 | Venue noise/lighting breaks detection | Demo failure | Book-drop fallback scenario; re-tune screen; history replay of a real in-venue session |
| 13 | MediaCodec video ring-buffer burns a day | Schedule loss | Frame-burst evidence is the MVP; video is a stretch |
| 14 | Thermal throttle during a long session | Detector rate drop | Low frame rate, gated classifiers, thermal logging, automatic rate degradation with a dashboard notice |

---

## 15. Current status & immediate next actions

### 15.1 Done

- Full environment audit (§2), artifact/API verification against live repositories.
- **Android toolchain installed** at `~/Android/sdk`: cmdline-tools (latest), build-tools 36.0.0, platform-tools 37.0.1 (adb), platform 36, licenses accepted.
- **Gradle 8.14.5** installed at `~/Android/tools/gradle-8.14.5`.
- **Project scaffolded and version-locked** (§13): `settings.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, root/app `build.gradle.kts`, `.gitignore`, `local.properties`, Gradle wrapper (`gradlew`, `gradle-wrapper.jar`, `gradle-wrapper.properties` with `networkTimeout=60000`).
- **First real feature implemented**: manifest with all capture/service permissions, adaptive launcher icon, Material 3 dark theme, `MainActivity`, `DeviceProfile` (LOW/HIGH tier detection), `CapabilityProbe` (8 real hardware checks), and the pre-flight Compose screen + ViewModel.

### 15.2 Immediate blocker

`/usr/lib/jvm/*` contains **JREs only** — `javac` is absent, and AGP fails at:
```
Toolchain installation '/usr/lib/jvm/java-21-openjdk-amd64' does not provide the required capabilities: [JAVA_COMPILER]
> Failed to calculate the value of task ':app:compileDebugJavaWithJavac' property 'javaCompiler'
```

**Fix (next action):** fetch a JDK 21 tarball into `~/Android/tools/jdk-21` and point Gradle at it (`org.gradle.java.home` in `gradle.properties`, or `JAVA_HOME`). All three candidate hosts returned HTTP 200:
`api.adoptium.net`, `aka.ms/download-jdk` (Microsoft), `corretto.aws` (Amazon).

Then: `gradle :app:assembleDebug` → install on the M12 over USB (`adb`) → confirm the pre-flight screen reports the real hardware.

### 15.3 Verifying the first APK works

Build verification order from here:
1. JDK installed → `:app:assembleDebug` succeeds (expect the first run to take 10–20 min of dependency downloads).
2. `adb install` on the M12 → pre-flight screen shows the real profile + 8 hardware checks.
3. Then M0 capture spike (§16).

---

## 16. Implementation order & effort estimate

Focused hours (not calendar). **★ = demo-critical path.**

| # | Work | Deliverable | Est. |
|---|---|---|---|
| ★0a | **Toolchain** (done) — SDK, Gradle, wrapper, scaffold | Version-locked project | ✅ |
| ★0b | **JDK fix + first green build + install on M12** | Debug APK running, pre-flight screen showing real hardware | 1 h |
| ★0c | **M0 — capture spike**: FGS + CameraX + `AudioRecord` + `SensorManager` streaming to on-screen live counters; YAMNet + frame-diff smoke test; LiteRT-LM load + latency measurement on the actual phone | Numbers on screen (frames/s, audio hops/s, sensor Hz) + measured model perf | 8 h |
| ★1 | **M1 — Pipeline**: `core/model`, SessionClock, detectors, BaselineTracker, EventExtractor, CorrelationEngine, SQLite layer, repositories; JVM unit tests for correlation tiers | Events accumulating in the DB from a real session | 10 h |
| ★2 | **M2 — Evidence + lifecycle**: ring buffers, EvidenceRetainer, PREPARING pre-flight, FINALIZING seal, dashboard (rates/feed/timeline/storage), START/END | Real session → timeline + evidence on disk | 10 h |
| ★3 | **M3 — AI**: DigestBuilder, SessionRetriever, LocalLlmEngine (GPU+MTP, background preload), system-prompt contract, AnswerValidator, summary streaming, investigator chat UI | Unscripted question answered with citations | 11 h |
| ★4 | **M4 — Report & polish**: PDF/Markdown report, evidence bundle share, session history, delete-session/privacy screen, sensitivity tuning UI, threshold tuning on site, rehearsal | Shareable incident report | 8 h |
| 5 | Stretch A: H.264 evidence clips | Real video playback | 6–8 h |
| 6 | Stretch B: EfficientDet labels · NPU experiment · FunctionGemma tools · external fallback wiring | Depth/novelty extras | 4 h each |

**Demo-critical path ≈ 39 h focused; full MVP ≈ 47 h; with stretch ≈ 60 h.** With the available week-plus, the goal is MVP plus Stretch A and at least one of Stretch B.

---

## 17. Definition of done — first working prototype (`v0.1`, end of ★1 + ★2)

1. Debug APK installs on the target device and launches **in airplane mode**.
2. Pre-flight screen verifies camera, mic, sensors, storage, battery exemption — and fails loudly on unmet items.
3. START SESSION runs a **foreground service** for ≥10 minutes with the screen off; live counters show real frames/s, audio frames/s, sensor Hz, event counts, and bytes retained.
4. A physical impact with the phone on the table produces, within 2 s: an audio event, a motion event, and a **fused `INCIDENT` marked `CONFIRMED`** — visible live on the dashboard.
5. Evidence for that incident exists on disk (frames + WAV + sensor trace) and plays back from the UI at the right timestamps.
6. END SESSION stops all capture (verifiable CPU/battery drop, no further DB writes) and produces a summary listing timestamps, tiers, relations, and uncertainty.
7. Session survives: battery-optimisation whitelisting → app restart → history shows the sealed session with intact evidence.
8. JVM unit tests green for correlation tiers, relation windows, digest construction, and answer validation.
9. No network calls occur with the external fallback disabled (verifiable in settings/logs).
10. **No mock data exists anywhere in the codebase** — every number on screen originates from a live sensor, a live detector, or the sealed session's DB.

---

## 18. Evaluation-criteria alignment (carried from PROJECT.md §8)

| Criterion | Weight | How this plan scores it |
|---|---|---|
| **End Product** | 30 % | A genuinely working, installed, offline app performing its full loop live on stage — the ★ path is defined as exactly that, with the M12 proving it degrades honestly rather than faking it |
| **Novelty & Impact** | 20 % | Forensic incident investigation for physical experiments; the observation/inference/uncertainty split is the novel, defensible core |
| **Creative Phone Use** | 15 % | Camera + mic + motion sensors + on-device LLM simultaneously, with evidence retention and device-mounted vibration sensing |
| **Technical Depth** | 15 % | Shared-clock multimodal fusion engine, gated on-device classifiers, local-first LLM orchestration with a claim validator, consent-gated external fallback |
| **Mobile Office Kit** | 10 % | PDF/Markdown incident report + evidence bundle shared straight from the phone |
| **Demo & Presentation** | 10 % | Scripted-but-unannounced rig failure, live dashboard, unscripted question, evidence scrub, share |
