# ECHO — On-Device Incident Investigation

A real, offline Android app that continuously watches, listens, and senses during a physical
experiment — then lets you interrogate the session like a forensic investigator:
*"what went wrong?"* — with timestamped, evidence-backed answers.

See **[PROJECT.md](PROJECT.md)** (product source of truth) and
**[IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)** (engineering plan, one-week schedule).

## Architecture in one line

```
sensors ──► Observation (σ-scored) ──► EventExtractor (named events) ──► SessionBus ──► dashboard / timeline
```

- **Capture layer** (`capture/`): `SessionService` (foreground service, one monotonic clock,
  partial wake lock) owns `CameraSource`, `AudioSource`, `SensorSource`, `EnvironmentSource`.
- **Perception layer** (`perception/`): deterministic DSP + `BaselineTracker` (adaptive EMA
  baseline; every value is scored as deviation-in-σ from "normal"). Pure Kotlin, JVM-tested.
- **Fusion layer** (`fusion/`): σ-threshold rules (`EventExtractor.kt`) map observations to
  **named event types** with dedup and sustained detection, then the M1 correlation engine
  (`CorrelationEngine.kt`) fuses co-occurring cross-modal events into CONFIRMED `INCIDENT`s
  (A/V ±250 ms · audio↔motion ±400 ms · vision↔motion ±500 ms windows, one fusion group per
  physical event), derives `CO_OCCURS`/`SUSTAINED_WITH`/`PRECEDES` relation edges, and emits
  `PRE_INCIDENT_DEVIATION` when a sustained shift began 0.5–5 s before an incident — the
  "why" the investigator cites. Co-occurrence, never causality. Pure Kotlin, JVM-tested.
- **Persistence** (`data/EchoStore.kt`): every extracted event is written synchronously to a
  SQLite database at `files/echo/echo.db` (dedicated app-private folder) *before* it reaches
  the dashboard — the timeline survives restarts and is the M3 investigator's data source.
- Everything is fail-soft: a missing sensor, permission, or model degrades that one channel and
  the dashboard says so — the session never crashes.

### Storage format

`echo.db` is a two-table SQLite store, deliberately storage-efficient (no per-row JSON, no
bloated ORM rows — ~120 bytes/event on disk):

- `sessions(id, started_at_epoch_ms, ended_at_epoch_ms, duration_ms, device_meta)`
- `events(id, session_id, runtime_id, t_start_ms, t_end_ms, type, modality_mask, tier,
  confidence, salience, description)` with an index on `(session_id, t_start_ms)`

Compact encodings: `modality_mask` is a 4-bit integer bitmask (`AUDIO=1, VISION=2, MOTION=4,
ENVIRONMENT=8`, codec JVM-tested in `ModalityCodecTest`), `tier` is a 0–2 ordinal, times are
epoch-ms INTEGERs. Verified on device: a 138 s session with 30 events stores in ~28 KB
including schema, and reads back in timeline order via plain SQL.

## Sensor matrix — what each sensor is for

Every sensor below is wired into the observation → extraction pipeline. Zero new permissions:
all of them are `SensorManager` channels plus the already-required camera/mic.

| Sensor | Channel / file | Observation kind | Event types it produces | Purpose in incident reconstruction | Used? |
|---|---|---|---|---|---|
| **Camera (rear)** | `CameraSource.kt` | `FRAME_MOTION` | `RAPID_MOTION`, `MOTION_CONTINUOUS` | Sees the rig: object movement, falls, scene changes. The primary *visual witness*. | ✅ |
| **Microphone** | `AudioSource.kt` (+ YAMNet via MediaPipe, energy-gated) | `AUDIO_RMS` | `IMPACT_TRANSIENT`, `SUSTAINED_NOISE`; YAMNet labels (speech, glass, thump) on the bus | Hears the rig: impacts, motor tone changes, abnormal sounds. The primary *acoustic witness*. | ✅ |
| **Accelerometer** | `SensorSource.kt` | `ACCEL_MAG` | `DEVICE_SHOCK`, `VIBRATION_BURST` | The phone mounted on the rig **is** the rig's motion sensor: shocks, sustained vibration, motor imbalance. | ✅ |
| **Gyroscope** | `SensorSource.kt` | `GYRO_MAG` | `ANGULAR_JOLT` | Rotational spikes = table bump / rig knocked — separates "hit" from "tilted" (raw \|a\| confuses both). Absent on gyro-less devices (Galaxy M12) → channel degrades. | ✅ |
| **Linear acceleration** | `SensorSource.kt` | `ACCEL_LIN` | `ACCEL_JOLT` | Gravity-compensated shock: a cleaner "it got hit" number than raw \|a\|, which mixes gravity with force. | ✅ |
| **Gravity / rotation vector** | `SensorSource.kt` (`perception/TiltTracker.kt`) | `TILT_ANGLE` | `TILT_CHANGE` | Static-orientation change = rig knocked over, mount slipped, phone picked up. Rotation-vector fallback works on gyro-less devices. | ✅ |
| **Magnetometer** | `EnvironmentSource.kt` | `MAG_FIELD` | `MAGNETIC_DISTURBANCE` | Motors, relays and large ferrous parts moving near the phone warp the local field — evidence of actuator/rig-part motion even when the camera can't see it. | ✅ |
| **Barometer** | `EnvironmentSource.kt` | `PRESSURE` | `PRESSURE_TRANSIENT` | A door/window opening/closing, a heavy object dropped nearby, or HVAC kicking in pushes a measurable pressure blip through the room — the *invisible witness* for events outside the camera's view. | ✅ |
| **Ambient light** | `EnvironmentSource.kt` | `LIGHT_LUX` | `LIGHT_CHANGE` | Lights switched, flash, shadows sweeping the rig; corroborates vision brightness evidence and explains frame-motion false positives. | ✅ |
| **Proximity** | `EnvironmentSource.kt` | `PROXIMITY_OCCLUSION` | `PROXIMITY_OCCLUSION` | Binary occlusion: something covered the phone, or the phone was stowed. Also a session-health signal — a covered phone is a degraded witness. | ✅ |
| **Step counter** | `EnvironmentSource.kt` | `STEP_DETECTED` | `STEP_DETECTED` | Steps while a session runs = the phone was picked up and carried. An **evidence-integrity** event: "was the witness moved?" | ✅ |
| **Ambient temperature** | `EnvironmentSource.kt` | `AMBIENT_TEMP` | `AMBIENT_TEMP` (transient-scored) | Rig overheating context. Presence-gated: most phones have no ambient thermometer; the dashboard reports it honestly. | ✅ if present |
| **Relative humidity** | `EnvironmentSource.kt` | `HUMIDITY` | `HUMIDITY` (transient-scored) | Spill/steam/wet-lab context. Presence-gated like temperature. | ✅ if present |
| **GPS / location** | — | — | — | **Excluded.** Location says *where*, not *what happened*; permission cost + the "nothing leaves the device / nothing collected" privacy claim for zero demo value. | ❌ |
| **Wi-Fi / BT signal** | — | — | — | **Excluded.** Needs location permission on modern Android, noisy, and the rig doesn't have Wi-Fi. | ❌ |
| **Front camera** | — | — | — | **Excluded.** Second viewpoint faces the operator, not the rig; halves the pipeline budget for no incident signal. | ❌ |
| **Step *detector* / significant motion** | — | — | — | **Excluded.** Low-power wake-up triggers solve a battery problem ECHO doesn't have (always-on FGS + wake lock); step-counter covers the "carried" signal already. | ❌ |
| **Rotation vector (as fused orientation)** | — | — | — | **Excluded as a separate channel** — it *is* used as the tilt fallback (see gravity row), but reading it as a third orientation source would duplicate gravity+gyro evidence. | ❌ (dup.) |
| **Vibration motor** | — | — | — | **Excluded (actuator, not sensor).** An haptic pulse mid-session would *contaminate* the accelerometer baseline and falsify evidence. | ❌ |
| **ToF / depth** | — | — | — | **Excluded.** Rare hardware; a whole second vision pipeline for marginal gain over frame-diff. | ❌ |

### Deliberate signal-honesty notes

- **Environment channels are transient-only.** Their baselines drift with the weather (barometer)
  or light conditions, so the extractor never emits `SUSTAINED_*` pairs for them — a sustained
  event would fire long after the incident it describes. See
  `EventExtractor.DEFAULT_TRANSIENT_ONLY`.
- **Baselines are per-channel** (`BaselineTracker`) with σ floors. Environment channels use a
  *relative* σ floor (fraction of |mean|) because a barometer resting at 101 kPa must not turn
  every gust into a 5σ event.
- **Tilt is deadband-scored, not σ-scored**: inside a 3° deadband the device counts as resting;
  `TILT_ANGLE` observations are only submitted beyond it.

## Investigating a past session (history + AI chat)

Every sealed session appears under **Previous sessions** on the dashboard. Tap one to open the
**investigator chat**: a deterministic `DigestBuilder` turns that session's persisted events into
a compact, citation-formatted digest, and your questions are answered over it.

- **Local first:** the digest is built on-device by pure Kotlin; raw audio, frames and sensor
  streams never leave the phone.
- **External model (optional, user-configured):** add an API key under *AI provider settings*
  for **Groq**, **Google AI Studio** or **OpenRouter** (all have free tiers). Keys are stored
  app-private and sent only to the provider you chose. The system prompt enforces
  citation-first, non-causal answers (`[E7 @ +00:36.104] … may have preceded …`).
- **Local LLM roadmap (M3):** LiteRT-LM with Gemma3-1B int4 on LOW-tier devices / Gemma4-E2B on
  the iQOO — the `DeviceProfile.llm` slot already exists. Until it lands, chat requires an
  external key; the M12 has no NPU and would be single-digit tokens/s on CPU.

## What a demo looks like

1. START SESSION → the phone is mounted on the rig, back camera aimed at it.
2. Baseline learns ~30 s of normal: motor hum, vibration, light, magnetic field.
3. The failure: tone change + vibration shift → weight falls → within ~1 s the dashboard shows
   `IMPACT_TRANSIENT` + `RAPID_MOTION` + `DEVICE_SHOCK` + `ANGULAR_JOLT` — a fused incident.
4. END SESSION → event list with timestamps and confidence tiers.
5. (M1+) Ask the investigator: *"what happened before the part fell?"* — answers cite the
   `TONE_CHANGE`/`PRE_INCIDENT_DEVIATION` events that preceded it.

## Build & test

```bash
./gradlew :app:assembleDebug        # APK at app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # 80 JVM tests: DSP, baselines, tilt, extraction, correlation tiers, rates
scripts/fetch_yamnet.sh             # fetch yamnet.tflite into assets (4.13 MB) before first run
```

No network is used at runtime; the YAMNet model ships in the APK.
