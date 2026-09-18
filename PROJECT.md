# ECHO — On-Device Incident Investigation

> **One-liner:** A real, offline, demo-ready Android app for the iQOO smartphone that continuously watches, listens, and senses during an experiment — then lets you ask an on-device AI investigator *"what went wrong?"* with evidence-backed answers.

**Competition:** iQOO City Battles 2026
**Platform:** Android (optimized for iQOO smartphones)
**Status:** Greenfield — nothing built yet; this document is the source of truth.

---

## 1. Problem

Makers, students, and engineers who run physical experiments or build hardware rarely know **why something failed**. By the time they notice the failure, the evidence — the sound the machine made, the motion right before the part fell, the exact second things went wrong — is gone. Video recordings help, but scrubbing minutes of footage and correlating it with what you remember is slow, manual, and often inconclusive.

**ECHO solves this:** start a session, do your work, and when something goes wrong, interrogate the session like a forensic investigator. ECHO has been watching the whole time.

---

## 2. Target User

Anyone who **performs experiments or builds things** and needs to understand what could have possibly gone wrong during a build or experiment:

- Robotics / electronics hobbyists debugging a rig
- Students running physics or chemistry demos
- Engineers validating prototypes on a bench

They don't want to set up cameras, mics, and logging rigs. They have a powerful phone in their pocket — **ECHO turns that iQOO phone into the entire investigation lab.**

---

## 3. Core Principle (Non-Negotiable)

**The app must be the real product — not a mock.**

- Pressing **START SESSION** genuinely begins monitoring via the phone's **camera, microphone, motion sensors, local storage, and on-device/local AI**.
- The app continuously: observes the incident → extracts events → correlates multimodal evidence → builds a temporal timeline → tracks confirmed/unconfirmed events → retains relevant evidence.
- The dashboard **controls and displays the real underlying pipeline** — never a visualization of pre-generated data.
- Pressing **END SESSION** genuinely stops monitoring/processing and produces a complete **incident summary**: detected sequence, timestamps, evidence, uncertainties, and notable events.

**Guiding constraint:** the smallest technically reliable implementation that genuinely works on the target iQOO device. Prioritize a working end-to-end product and physical demo over feature count.

---

## 4. Product Flow

```
┌─────────────┐     ┌──────────────────────────────────────┐     ┌──────────────┐
│ START       │────▶│  LIVE PIPELINE (on-device, offline)  │────▶│ END SESSION  │
│ SESSION     │     │                                      │     │              │
└─────────────┘     │  Camera ──▶ frame events             │     │ Incident     │
                    │  Mic ─────▶ audio events             │     │ Summary +    │
                    │  Motion ──▶ vibration/shock events   │     │ Timeline +   │
                    │        │                             │     │ Evidence     │
                    │        ▼                             │     └──────┬───────┘
                    │  Event extraction & correlation      │            │
                    │  Temporal timeline builder           │            ▼
                    │  Evidence retention (key clips/data) │     ┌──────────────┐
                    │  Live dashboard (real pipeline)      │     │ AI           │
                    └──────────────────────────────────────┘     │ INVESTIGATOR │
                                                                 │ (post-session│
                                                                 │  chat, local │
                                                                 │  AI first)   │
                                                                 └──────────────┘
```

### 4.1 Session (START SESSION)
1. User grants camera/mic permissions once, then presses START.
2. Pipeline begins: sensors stream in, events are extracted and timestamped in real time.
3. Live dashboard shows: active sensor states, event feed, timeline as it builds, confirmed vs. unconfirmed event badges, storage being retained.

### 4.2 End of Session (END SESSION)
1. Monitoring and processing genuinely stop.
2. App generates the **Incident Summary**: detected event sequence, timestamps, evidence links, uncertainty flags, notable events.
3. Summary + full timeline + evidence are persisted locally.

### 4.3 Investigation (post-session)
The user chats with the in-app **AI Investigator** about that session's *actual* data. Example:

> **User:** "Something went wrong with the machine. What could have caused it?"
>
> **ECHO:** "An abnormal sound was detected at 14:32:07, followed by rapid object movement at 14:32:09 and an impact at 14:32:13. These events may indicate that the machine behavior preceded the object fall."

Requirements for the investigator:
- Inspects the **actual events, timestamps, sensor observations, evidence, and temporal relationships from that session** — dynamically investigates whatever the user asks.
- **No hardcoded questions or answers.** It must find relevant events/evidence itself.
- Clearly distinguishes **observations** vs. **inferences** vs. **uncertainty** vs. **verified evidence** in its answers.
- Answers are evidence-backed and cite timestamps.

---

## 5. AI Architecture — Local-First, Strictly

**Local AI is the mandatory first and last hop for all data.**

```
Raw session data ──▶ [ LOCAL AI ] ──▶ (optional, if needed) ──▶ [ EXTERNAL AI API ]
                        │                                              │
                        │◀────────── external output must ─────────────┘
                        │            pass back through local AI
                        ▼
                  User-facing answer
```

Rules:
1. **All raw data goes through the local AI first.** Local AI structures, filters, and summarizes the session data before anything leaves the device (if anything ever does).
2. If the local AI cannot summarize/explain the full session, an **external AI model via API may be used** — but only the local-AI-processed data, and the external output must **return through the local AI** before reaching the user.
3. Default experience is **fully offline**. External API is a fallback path, clearly surfaced in the UI when used.
4. Never send raw continuous sensor streams off-device.

### 5.1 On-device AI components (target stack)
| Task | Candidate technology (verify against device) |
|---|---|
| Audio event detection (abnormal sounds, impacts, glass, motors) | Audio classification model via **TensorFlow Lite / LiteRT** (e.g., YAMNet-class embeddings + thresholding) |
| Visual event detection (fast motion, object movement/fall, person presence) | **MediaPipe** / LiteRT tasks (object detection + motion deltas between frames) |
| Motion events (vibration spikes, shock, device/device-contact) | Native **SensorManager** (accelerometer, gyroscope, linear acceleration, gravity/rotation tilt) + simple peak detection |
| Environment context (magnetic disturbance, pressure transients, light change, occlusion, carried-phone detection) | Native **SensorManager** (magnetometer, barometer, light, proximity, step counter; temperature/humidity when present) — all zero-permission, presence-gated |
| Local LLM (structuring events, summaries, investigator reasoning) | **LiteRT-LM (Kotlin API)** with Gemma-class models — backend decision researched & verified in §5.2 (MediaPipe LLM Inference API is maintenance-only as of late 2026) |
| Event correlation & timeline logic | Deterministic Kotlin code (timestamp windows, causality heuristics) — cheap, reliable, debuggable |

**Decision rule:** prefer the simplest sensor + threshold + small-model pipeline that works reliably on the demo device. Deterministic code > model wherever possible; models only where perception is genuinely needed (sound classification, motion detection, scene change).

### 5.2 Local LLM selection — researched & verified (Sept 2026)

**Runtime: LiteRT-LM** (`com.google.ai.edge.litertlm:litertlm-android`), Google's production-ready successor to the MediaPipe LLM Inference API (which is now maintenance-only). Verified capabilities: `Backend.CPU()`, `Backend.GPU()`, `Backend.NPU()` on Android; streaming via Kotlin Flow; built-in tool/function calling (constrained decoding); thinking-token budgeting; Multi-Token Prediction (MTP) for >2x decode speed on GPU.

**Official benchmark data** (Google AI Edge, 2026) — the numbers that drive the decision:

| Model | Size | Device | CPU prefill/decode (tk/s) | GPU prefill/decode (tk/s) | Notes |
|---|---|---|---|---|---|
| **Gemma4-E2B** | 2.58 GB | Samsung S26 Ultra | 557 / 47 | **3808 / 52** | TTFT 0.3s on GPU; peak mem 676 MB (GPU) vs 1733 MB (CPU) |
| **Gemma3-1B** | 1.0 GB | Samsung S24 Ultra | 177 / 33 | **1191 / 24** | Smallest reliable option |
| Qwen2.5-1.5B | 1.6 GB | Samsung S25 Ultra | 298 / 34 | 1668 / 31 | Alternative |
| Gemma4-E4B | 3.65 GB | Samsung S26 Ultra | 195 / 18 | 1293 / 22 | Too slow for quality gain — rejected |
| FunctionGemma | 289 MB | Samsung S25 Ultra | 2238 / 154 | — | Purpose-built function caller |

**Decision for iQOO flagship (12–16 GB LPDDR5X, UFS 4.0, top-tier Snapdragon 8-series with Hexagon NPU + Adreno GPU):**

1. **Primary: Gemma4-E2B via GPU backend + MTP enabled.** Fits comfortably in RAM/storage, ~50 tk/s decode = fluent chat, 0.3s TTFT = instant-feeling investigator, GPU cuts peak memory ~2.5x vs CPU. iQOO's Adreno GPU is the same performance class as the benchmarked Samsung S25/S26 Ultra devices.
2. **Fallback A: Gemma3-1B GPU** — if E2B shows RAM pressure alongside the live pipeline, or demo device is a lower-tier iQOO.
3. **Backend order: GPU → NPU → CPU.** GPU is the benchmark-backed path. The **NPU (Hexagon) backend exists in LiteRT-LM on Android** but requires bundling NPU native libraries and has no published benchmark table yet — treat as M0 experiment, not a dependency. CPU always works as the safe floor (~47 tk/s decode on E2B is still usable).
4. **Enhancement path: FunctionGemma (289 MB) as a tool-caller** over the timeline DB (`get_events_between(t1,t2)`, `find_events(type=IMPACT)`) — deterministic retrieval as tools keeps the investigator grounded in real data and non-hallucinatory. Evaluate in M3 if plain retrieval-prompting is insufficient.

**Architectural note:** the LLM is post-session (investigator) + summary generation, so it never competes with camera/mic/sensor pipelines during live monitoring. Pre-load the engine in the background near END SESSION (`engine.initialize()` takes up to ~10s — must run off the main thread).

**M0 on-device verification checklist (do this first, on the actual iQOO phone):**
- [ ] Load Gemma4-E2B + Gemma3-1B via LiteRT-LM; measure prefill/decode/TTFT/peak memory on GPU vs NPU vs CPU
- [ ] Verify GPU backend (`libOpenCL.so` declared in manifest) on the iQOO's Adreno driver
- [ ] Try `Backend.NPU()` with bundled Hexagon/QNN libs; record whether it beats GPU on prefill or battery
- [ ] Enable MTP speculative decoding; measure decode gain
- [ ] Soak test: 10-min session with detectors running, then LLM summary + 10 investigator questions; watch thermals/battery
- [ ] Kill-file: if E2B misbehaves, ship 1B; if GPU driver is flaky, ship CPU

---

## 6. System Architecture

### 6.1 Layers
1. **Sensor Layer** — CameraX (camera), AudioRecord (mic), SensorManager (accelerometer/gyro). Runs in a **foreground service** so monitoring survives screen-off during long sessions.
2. **Perception Layer** — lightweight detectors per modality producing timestamped **raw event candidates** (e.g., `AUDIO_SPIKE`, `FAST_MOTION`, `IMPACT`, `MOTION_BURST`).
3. **Correlation Layer** — merges candidates into **events** with confidence levels (confirmed/unconfirmed) using temporal windows across modalities (e.g., audio spike + motion burst within N ms = one confirmed "incident event").
4. **Evidence Layer** — retains only relevant evidence: short ring-buffer clips around events (video/audio snippets), feature snapshots, sensor traces. Continuous raw recording is *not* retained — only event-anchored evidence (privacy + storage).
5. **Timeline Layer** — ordered temporal event store (Room/local DB): event type, timestamp, modality, confidence, evidence references, uncertainty notes.
6. **AI Layer** — local LLM + local models for summary generation and investigation; optional, local-gated external API fallback.
7. **UI Layer** — Compose screens: Session (live pipeline dashboard), Summary, Timeline/Evidence browser, Investigator chat, Session history.

### 6.2 Data model (minimal)
```
Session(id, startedAt, endedAt, summary, deviceMeta)
Event(id, sessionId, timestampMs, type, modality[], confidence, description, evidenceIds[])
Evidence(id, eventId, kind[VIDEO_CLIP|AUDIO_CLIP|SENSOR_TRACE|FRAME], path, durationMs)
Insight(id, sessionId, question, answer, citedEventIds[], usedExternalAI)
```

---

## 7. Feature Scope

### MVP (must ship — the demo)
- [ ] START/END session with real foreground-service monitoring (camera, mic, motion)
- [ ] Real-time event extraction from all modalities (audio, vision, motion, environment)
- [ ] Multimodal event correlation + temporal timeline with confirmed/unconfirmed states
- [ ] Event-anchored evidence retention (clips/sensor traces around events)
- [ ] Live dashboard that reflects and controls the real pipeline
- [ ] END SESSION → full incident summary (sequence, timestamps, evidence, uncertainties, notable events)
- [ ] AI Investigator chat answering arbitrary questions from the session's real data, citing timestamps and evidence, separating observation/inference/uncertainty
- [ ] Local AI handling all data first; external API only as a gated fallback
- [ ] Session history + evidence playback
- [ ] Works fully offline end-to-end

### Explicitly out of scope (non-goals)
- Mock dashboards or pre-generated demo data of any kind
- Cloud sync, accounts, or telemetry
- Multi-device or remote monitoring
- Long-form continuous video recording/storage
- Excessive feature breadth — smallest reliable implementation wins

---

## 8. Evaluation Criteria Alignment (iQOO City Battles 2026)

| Criterion | Weight | How ECHO scores it |
|---|---|---|
| **End Product** | 30% | A genuinely working, installed, offline Android app that performs its full loop live on stage — not a website or mockup. |
| **Novelty & Impact** | 20% | Forensic-style incident investigation for physical experiments is an unexplored consumer angle; real value for makers/engineers. |
| **Creative Phone Use** | 15% | Uses the iQOO phone as a complete forensic lab: camera + mic + motion sensors + NPU-class on-device AI simultaneously, with evidence retention — not just one sensor. |
| **Technical Depth** | 15% | Real multimodal pipeline: sensor services, on-device ML (LiteRT/MediaPipe), local LLM inference, event correlation engine, local-first AI orchestration with gated external fallback. |
| **Mobile Office Kit** | 10% | Generates shareable incident reports/summaries and evidence bundles from the phone — a document-producing tool. |
| **Demo & Presentation** | 10% | High-drama live demo: cause a small failure on stage, press END, ask ECHO "what happened?", get timestamped, evidence-cited explanation. |

---

## 9. Demo Script (design the product around this)

1. Open ECHO on the iQOO phone → press **START SESSION**.
2. Run a small physical experiment on stage (e.g., a motorized rig or structure under load).
3. Sabotage/failure occurs (planned but unannounced): abnormal sound, sudden movement, impact.
4. Show the live dashboard lighting up with real detected events as they happen.
5. Press **END SESSION** → incident summary appears instantly.
6. Ask: *"Something went wrong with the machine. What could have caused it?"*
7. ECHO answers with the actual causal chain and timestamps, citing retained evidence.
8. Play back the evidence clips at the cited timestamps.
9. Show the shareable incident report.

---

## 10. Technical Risks & Mitigations

| Risk | Mitigation |
|---|---|
| On-device LLM too slow/weak for full-session explanation | Keep investigator questions scoped via deterministic retrieval (query → relevant events → small context); local LLM reasons over a compact evidence subset, not the whole session. External API is the documented fallback, local-gated both ways. |
| False positives flooding the timeline | Conservative thresholds tuned on the demo device; unconfirmed-event state instead of deletion; confidence surfaced in UI. |
| Storage/memory pressure from video evidence | Ring-buffer capture; retain only short clips anchored to events; cap session evidence size. |
| Monitoring killed by OS battery management | Foreground service + persistent notification; instruct demo device battery settings exemption. |
| Demo-day variance (noise, lighting) | Tune thresholds in the actual venue beforehand; carry a pre-recorded fallback session captured on the same device. |

---

## 11. Roadmap

| Milestone | Deliverable |
|---|---|
| **M0 — Spike** | Verify on target iQOO device: CameraX + AudioRecord + SensorManager streaming, LiteRT audio/vision models, local LLM inference latency. Kill-file anything unreliable. |
| **M1 — Pipeline** | Foreground service capturing all modalities → timestamped event candidates → basic correlation → timeline DB. |
| **M2 — Evidence & UI** | Event-anchored evidence retention, live dashboard controlling the real pipeline, START/END session. |
| **M3 — AI Layer** | Local LLM summary generation + investigator chat over real session data with citations and uncertainty labeling; local-gated external fallback. |
| **M4 — Demo Polish** | Incident report export/share, session history, threshold tuning on venue-like conditions, full demo rehearsal. |

---

## 12. Success Criteria

- ✅ A stranger can install ECHO on an iQOO phone, run a real experiment, end the session, and get a correct, evidence-backed answer to an *unscripted* question about what went wrong.
- ✅ Everything works with the phone in **airplane mode**.
- ✅ Every claim in the AI's answers traces to a timestamped event with retained evidence.
- ✅ The demo causes at least one "how is this possible on a phone?" moment.
