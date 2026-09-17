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
| Motion events (vibration spikes, shock, device/device-contact) | Native **SensorManager** (accelerometer, gyroscope) + simple peak detection |
| Local LLM (structuring events, summaries, investigator reasoning) | **MediaPipe LLM Inference API** with a small model (e.g., Gemma 2B-class) running on iQOO's Snapdragon NPU/GPU delegate |
| Event correlation & timeline logic | Deterministic Kotlin code (timestamp windows, causality heuristics) — cheap, reliable, debuggable |

**Decision rule:** prefer the simplest sensor + threshold + small-model pipeline that works reliably on the demo device. Deterministic code > model wherever possible; models only where perception is genuinely needed (sound classification, motion detection, scene change).

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
- [ ] Real-time event extraction from all three modalities
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
