# SCYTHE Optimization Sprint + Hypergraph Export Expansion

## Status: Hypergraph Export Complete ✅ | WS Normalization Fixed ✅

## Hypergraph Export Expansion — DONE ✅
- [x] hypergraph-viewer.js — `<hypergraph-viewer>` Web Component
  - Shadow DOM canvas + info panel + toolbar (PNG/JSON/mode-cycle buttons)
  - 4 modes: viewer / autopsy / rf (volumetric field) / lite
  - Fibonacci sphere deterministic layout for nodes without positions
  - InstancedMesh nodes (threat-level color), LineSegments edges (capped 1500)
  - 32³ Gaussian splat → Data3DTexture → GLSL3 ray-march for >300 nodes
  - Full disconnectedCallback() cleanup (renderer, controls, ResizeObserver, RAF, AbortController)
  - node-click event, exportPNG() / exportJSON() / exportField() methods
- [x] Backend: _gravity_snapshot_readonly() — pure read, zero scoring mutations
- [x] Backend: GET /api/gravity/export?format=json|html
- [x] Backend: GET /api/clusters/export-data/<id>
- [x] Backend: GET /api/clusters/export/<id>?format=bundle|json
- [x] UI: Gravity toolbar — 📸 PNG + 📦 BUNDLE buttons
- [x] UI: Cluster intel cards — 📦 BUNDLE badge per row
- [x] hypergraph-viewer.js loaded via <script defer> in command-ops

## WS Bootstrap Normalization Bug — FIXED ✅
- Root cause: bootstrap injects LAN IP relay URLs (192.168.1.185:8765/8766) directly
  Bootstrap takes priority over _streamCfg — _normaliseWsUrl was never applied to it
  Chrome blocks loopback→LAN WS upgrades (Private Network Access policy)
- Fix: apply _normaliseWsUrl() to bootstrap values in connectDataStreams()
  When page is at 127.0.0.1, rewrites 192.168.1.185 → 127.0.0.1 (services bind 0.0.0.0)

## Optimization Tiers 1-4 — ALL DONE ✅
(see checkpoint history for full detail)

## GraphOps Epistemic Upgrades — DONE ✅ (2026-04-19)
- [x] _INTERPRET_SYSTEM: enforce UNKNOWN format when node_count==0, require SENSOR/INFERRED labels
- [x] _summarize_result: add evidence_coverage field to LLM context
- [x] _build_report: compute evidence_posture (no-data/sparse/inference-heavy/evidence-backed)
  credibility field now includes nodes_seen, edges_seen, stale_inferences estimate

## EVE Sensor Grounding — DONE ✅ (2026-04-20)
- [x] Orchestrator + instance config: eve_stream_ws / eve_stream_http args forwarded through bootstrap.js
- [x] Backend: /api/config/streams now exposes eve URLs; added /api/sensor/eve/health and /api/sensor/eve/ground
- [x] UI: GraphOps Bot now shows EVE online/offline + last pull / injection delta and a Ground GraphOps control
- [x] Chat policy: sparse / inference-heavy GraphOps requests now preflight eve-streamer and can pull a short observed burst
- [x] Two-lane separation preserved: operator-visible EVE health stays separate from explicit graph mutation

## SCYTHE EVE Android MVP — DONE ✅ (2026-04-20)
- [x] Reused `ScytheCommandApp` as the repo-native Android base instead of creating a second app module
- [x] Added relay config resolution from `/api/config/streams` with fallback derivation for stream WS ingest
- [x] Upgraded `ScytheSensorService` into a mobile EVE-style streamer:
  - GPS + WiFi recon still post to existing HTTP endpoints
  - live WebSocket relay now emits heartbeat, position, WiFi scan, and observed infra flow events
  - native status broadcasts now include relay URL, relay health, event count, burst count, and last uplink
- [x] Native UI now surfaces EVE relay status/counters in the app footer and settings screen
- [x] Debug APK built successfully at `ScytheCommandApp/app/build/outputs/apk/debug/app-debug.apk`
- [x] Debug APK installed and launched on the paired Android device

## SCYTHE EVE Offline Lane Demo — DONE ✅ (2026-04-20)
- [x] Added a local `file:///android_asset/eve_demo.html` scene that works without any SCYTHE instance
- [x] Demo visualizes the lane split directly in-app:
  - continuous lane: phone sensor -> relay -> live operator view
  - grounding lane: GraphOps-style burst -> observed node/edge injection
- [x] Added app chrome entry points for the demo:
  - top bar `DEMO` button
  - loading overlay `Open Offline Demo` button
- [x] Wired the demo to the Android bridge where available so it can reflect local sensor status, relay URL, location, and start/stop the sensor service

## Traceroute Hop Grouping — DONE ✅
- [x] Frontend hop grouping: consecutive hops with same logical class → one Cesium entity
- [x] 3-tier GeoIP backend resolver: POP-code → cloud subnet → ip-api.com fallback
- [x] "No geolocated hops" message distinguishes private-only vs GeoIP-failure cases

## RF/IP Correlation Engine — DONE ✅ (2026-04-20)
- [x] Added `rf_ip_correlation_engine.py` as a lightweight rolling temporal join/scoring engine
- [x] Added API routes:
  - `POST /api/rf-ip-correlation/observe/rf`
  - `POST /api/rf-ip-correlation/observe/network`
  - `GET /api/rf-ip-correlation/status`
  - `GET /api/rf-ip-correlation/bindings`
- [x] RF/IP bindings now emit graph-native `RF_TO_IP_BINDING` edges through `WriteBus`
- [x] Android recon upserts now auto-promote into `sensor:<entity_id>` and auto-assign to `recon:<entity_id>`
- [x] Fixed recon sensor assignment route to call `SensorRegistry.assign_sensor(..., recon_entity_id=...)`

## Hybrid Digital Twin (Projection Slice) — DONE ✅ (2026-04-20)
- [x] Added observer-relative projection API routes:
  - `GET /api/digital-twin/projection`
  - `GET /api/ar/projection`
- [x] Projection payload now derives nearby recon entities and recent `RF_TO_IP_BINDING` observations relative to an Android observer
- [x] Added Android in-app `digital_twin.html` that consumes the projection payload through the existing JS bridge
- [x] Added app entry points for the twin:
  - top bar `TWIN` button
  - loading overlay `Open Digital Twin` button
- [x] Added bridge support for stable Android observer IDs and projection fetches without opening file-origin network access

## Predictive Control Paths — DONE ✅ (2026-04-21)
- [x] Added `predictive_control_path_engine.py` as a small forecast service blending:
  - QuestDB temporal pressure
  - recent `RF_TO_IP_BINDING` confidence
  - fan-in / relay motifs
  - semantic identity-stitch candidates
- [x] Added forecast APIs:
  - `GET /api/control-path/predict`
  - `POST /api/control-path/predict/emit`
- [x] Forecast graph emission now creates distinct edge kinds:
  - `RF_TO_IP_PREDICTED`
  - `CONTROL_PATH_PREDICTED`
- [x] Forecast payloads and graph metadata now carry:
  - `confidence`
  - `time_horizon_s`
  - `supporting_evidence`
  - `provenance_rule`
  - explicit `forecast` / `obs_class=forecast`
- [x] `GET /api/digital-twin/projection` now includes `predictions` + forecast signal counts
- [x] Android `digital_twin.html` now renders forecast paths as ghost / dashed / pulsing overlays, never solid
- [x] UAV-like forecasts now carry DOMA-backed `motion_forecast` ghost waypoints plus observer-relative `projected_path` markers for forward motion visualization

## gRPC Control-Path Streaming — DONE ✅ (2026-04-21)
- [x] Extended `scythe.proto` with a binary `ControlPathStream` service plus typed `ControlPathPatch` / `ControlPathPoint` messages for forecast deltas
- [x] Added `ControlPathStreamServicer` to `scythe_grpc_server.py` using instance-side `/api/control-path/predict` polling + diffing to emit `upsert` / `delete` forecast patches
- [x] Regenerated `scythe_pb2.py` and `scythe_pb2_grpc.py` from the updated proto
- [x] Upgraded `scythe_grpc_client.js` to decode float/double protobuf fields and added `streamControlPaths(...)` with typed patch decoding for motion forecasts and projected paths

## RFUAV Evidence Layer — DONE ✅ (2026-04-21)
- [x] Added `rfuav_inference_service.py` as an upstream RF evidence normalizer for Stage 0.5 signal preprocessing
- [x] RFUAV output now becomes structured observed RF evidence with stable `rf.class/subtype/signal/temporal` fields
- [x] Added `POST /api/rfuav/observe` to ingest RFUAV inference results, emit observed RF graph artifacts, and feed RF/IP correlation
- [x] Predictive control-path scoring now consumes structured RF evidence as a bounded forecast signal instead of treating it as authority

## RFUAV Kafka Pipeline — DONE ✅ (2026-04-21)
- [x] Added canonical RFUAV detection event emission with Kafka producer support (`rf.uav.detections`, keyed by `sensor_id`)
- [x] Added `rfuav_kafka_consumer.py` so streamed RFUAV events can land on the same SCYTHE ingest path as HTTP observations
- [x] Added shared server-side RFUAV ingest helper so Kafka and REST both feed WriteBus, RF/IP correlation, and forecast-compatible evidence
- [x] Added QuestDB `rf_events` side feed for RFUAV detections
- [x] Added optional background Kafka consumer startup via `RFUAV_KAFKA_*` environment variables

## Browser Operator Geolocation — DONE ✅ (2026-04-21)
- [x] Added a globe-style browser geolocation prompt to `command-ops-visualization.html`
- [x] Live chat now sends browser latitude/longitude when available so guest chat operators no longer default to `0,0`
- [x] GraphOps Bot requests now include browser latitude/longitude context when available
- [x] Guest chat operator recon entities now prefer browser coordinates, fall back to IP geolocation, and otherwise omit location instead of using `0,0`

## WiFi Recon Enrichment — DONE ✅ (2026-04-21)
- [x] Added `recon_enrichment.py` to turn Android `wifi_ap` rf-nodes into structured observed WiFi intelligence with identity, RF profile, temporal, geo, behavior, and risk metadata
- [x] Randomized / locally-administered WiFi MACs now collapse into stable alias device IDs when fingerprinting is strong enough, with rolling session IDs and persistence state
- [x] `RFHypergraphStore.add_node()` now enriches WiFi nodes before graph publication and carries semantic labels into the HypergraphEngine
- [x] The graph→recon bridge now preserves enriched WiFi semantics, friendly names, ontology/type, and metadata instead of flattening everything to generic recon entities

## Recon Entity Cognition Upgrade — PENDING
- Current state: WiFi observations already carry identity / RF profile / temporal / geo / behavior / risk metadata, but recon entities still mostly surface as flat enriched blobs instead of first-class actor cognition
- [x] Schema foundation: enriched WiFi observations now emit graph-native companion nodes/edges for `mac_cluster`, `recon_session`, `behavior_profile`, and `rf_signature`, and recon entities surface stable cognition IDs plus a summarized `cognition` envelope
- [x] Streaming MAC clustering: `mac_cluster_engine.py` now assigns observations to probabilistic `mac_cluster_id`s using RF, temporal, spatial, protocol, and behavior similarity; WiFi enrichment now keys identity/session state off cluster-backed anchors and surfaces cluster confidence, randomized ratio, vendor likelihood, and assignment similarity
- [x] Promote rolling observation sessions into first-class recon/session records with duration, observation count, movement class, handoff count, cadence, displacement, heading, and timeline summaries
- [ ] Expand RF + temporal profiling into explicit behavior classifications (beacon / human / relay / infrastructure) with burstiness, periodicity, duty cycle, entropy, and explanation text
- [x] Stitch recon entities to existing RF→IP / ASN evidence so operators see bindings, carrier context, and confidence instead of isolated RF sightings
- [ ] Reuse the existing DOMA + kinematic path stack to attach motion vectors, heading, drift class, and short-horizon predictive presence to recon entities when geo cadence is sufficient
- [ ] Surface the upgraded identity / behavior / session / network summaries in recon APIs and operator UI labels so `wifi-xxxx` resolves into actor-style descriptions
- Notes:
  - Treat raw MAC addresses as weak evidence only; probabilistic continuity and cluster stability should become the canonical identity surface
  - `MacCluster` is the identity primitive; individual MACs are observations that may be randomized, spoofed, or rotated
  - Favor online / streaming clustering over batch clustering so Android/WiFi/BLE/RF observations can be absorbed incrementally during live ingest
  - The pairwise similarity function should weight protocol / IE fingerprint most strongly, then temporal and spatial continuity, with RF and behavior as supporting evidence
  - Randomized / locally administered MACs should explicitly downweight MAC identity and upweight protocol, temporal, spatial, and behavioral continuity
  - Persist similarity confidence and cluster stability so low-quality clusters stay visibly uncertain instead of looking authoritative
  - Use graph-native relationships like `(:Observation)-[:BELONGS_TO]->(:MacCluster)` and keep the door open for GDS/WCC-style connected-component clustering on similarity edges
  - The long-term target is cross-layer identity fusion: `MacCluster -> RFSignature -> RF_TO_IP binding -> control-path prediction`
  - Reuse `recon_enrichment.py`, `rf_ip_correlation_engine.py`, `predictive_control_path_engine.py`, and existing graph/recon bridges instead of creating a parallel enrichment store

## Recon Entity Log Suppression — DONE ✅ (2026-04-22)
- [x] `POST /api/recon/entity` now checks whether the target entity already exists in the room before logging
- [x] First-seen recon entities still log at INFO as `Created recon entity: ...`
- [x] Repeated upserts for the same recon entity now log at DEBUG instead of INFO, cutting recurring Android observer spam from normal server output

## QuestDB Window Query Fix — DONE ✅ (2026-04-22)
- [x] Root cause confirmed: this QuestDB build accepts `dateadd('s', ...)` but rejects `dateadd('ms', ...)` with HTTP 400
- [x] `questdb_query.py` now uses direct timestamp arithmetic (`now() - <microseconds>`) for recent-window filters, preserving millisecond precision without dialect mismatch
- [x] Added focused regression coverage so windowed QuestDB helpers no longer emit unsupported `dateadd('ms', ...)` SQL

## Hybrid Digital Twin Follow-on — PROJECTION SLICE DONE ✅ (2026-04-23)
- [x] Replace the north-up twin asset with a real ARCore/SceneView camera-space renderer inside `ScytheCommandApp`
- [x] Feed device heading into the projection request so relative bearings become view-relative instead of north-up
- [x] Reuse the existing `AndroidAppSceneview` SceneView/ARCore stack instead of introducing a second AR stack
- Notes:
  - Current Android renderer is a native SceneView camera-space slice driven by `/api/ar/projection`; true earth/terrain anchors remain a later refinement.
  - `TWIN` now launches the native AR activity and the debug APK builds and installs successfully on the paired device.

## SCYTHE App UX Follow-on — PLANNED
- Goal: evolve `ScytheCommandApp` from a developer-facing shell around a WebView into an operator-first cockpit that foregrounds mission state, actor meaning, and fast mode switching.
- Candidate improvements:
  - Replace the tiny top-bar launcher controls with a clearer navigation model: `Command`, `AR Twin`, `Sensors`, `Settings`, with larger touch targets and persisted last-used mode.
  - Consolidate server / relay / GPS / sensor / AR tracking state into a persistent health strip or chip row so operators can assess readiness at a glance.
  - Refactor the AR overlay from text-heavy status blocks toward confidence-colored markers, tap-to-expand actor cards, and a collapsible bottom sheet for nearby entities.
  - Rework settings around environment cards and connection workflows (`Local`, `Field Node`, `Tailscale`, `Demo`) instead of raw URL entry as the primary UX.
  - Keep the SCYTHE visual identity, but reduce the all-monospace / terminal density in favor of stronger visual hierarchy, iconography, spacing, and scan-friendly typography.
  - Prefer actor-style summaries in the UI (`Mobile AP · T-Mobile · moving NE · 0.82 confidence`) over backend artifact labels or raw IDs wherever enriched cognition is available.
- Delivery shape:
  - Land the information architecture and health-state cleanup first in native Android surfaces.
  - Then promote enriched recon/session/network cognition into the app’s cards, labels, and AR presentation layer.

## NIS Asset Bridge — DONE ✅ (2026-04-23)
- [x] Added `nis_scythe_bridge.py` to normalize NIS-derived SIGINT emitters into SCYTHE-native synthetic RF observations with stable IDs, protocol labels, and optional geospatial anchoring
- [x] Added API routes:
  - `POST /api/nis/sigint/simulate`
  - `GET /api/nis/sigint/summary`
  - `GET /api/nis/sar/scene-priors`
- [x] Added standalone observed RF node emission for synthetic RF observations so NIS-derived RF evidence persists in the graph even before any RF→IP binding exists
- [x] Added focused tests for NIS observation normalization, multibeam summary parsing, clean-cache parsing, and SAR prior summarization
- Notes:
  - The bridge intentionally reuses SCYTHE’s RF observation / graph publication seam instead of importing the NIS demo runtime directly into the server
  - SIGINT generation mirrors the protocol-band layout from `sigint_sim_env.py` but uses deterministic SCYTHE-native normalization and labeling
  - Multibeam post-processing is surfaced as summary/intensity metadata rather than a second visualization stack

## Visualization Backlog (from GraphOps Bot session analysis)
- graphops-arc-entropy: Arc entropy rhythm (beacons=periodic pulse, high entropy=flicker)
- graphops-identity-trails: Identity trails across IP changes (STITCH_IDENTITIES → color-lock)
- graphops-inference-ghost-arcs: Ghost arcs for inferred vs solid for evidence-backed edges
- graphops-cluster-flare: Vertical emission column for dense geo-stacked clusters

## Pending Backlog
- recon-cognition-schema: Graph-native MAC cluster / session / behavior profile surfaces for recon actors
- recon-network-stitching: Bind recon actors to RF→IP / ASN evidence with operator-visible confidence
- recon-motion-intelligence: Promote drift / heading / predictive presence into recon entities
- opt-backpressure: Add backpressure signals to ingest/orchestration
- opt-edge-compression: Graph wire format edge compression
- opt-event-spine: Event-driven WebSocket spine (replace polling)
- t3-gravity-get-mutation: GET /api/gravity/nodes mutates scoring singletons on every poll
- t3-edge-node-sampling: Edge/node sampling mismatch in gravity view
- sec-ping-ssrf: /api/ping SSRF risk
- t3-reasoning-bfs-consistency: Reasoning BFS can emit edges with missing endpoint nodes
- shadow_graph re_evaluate() auto-promotion via ws_ingest.py
- Android WebView auth token passthrough
