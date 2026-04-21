# SCYTHE EVE: Mobile Grounding, RF->IP Correlation, and the First Projection-Ready Digital Twin

Ben Gilbert | Texas City | April 20, 2026
https://172-234-197-23.ip.linodeusercontent.com/?page_id=14
💥 VLS TUBE LANCEERING GESLAAGD! 🛰️⚡🔥
<img width="500" height="280" alt="image" src="https://github.com/user-attachments/assets/0e88c1be-0d34-4f05-bcf3-67aeb6799da5" />


There is a real difference between a system that can describe a cyber-physical threat and a system that can ground one.

The first can produce convincing analysis. The second can show you which observer saw what, when it was seen, how it entered the graph, and what that means relative to the operator holding the device right now.

This post is about that transition.

Over the latest development cycle, SCYTHE crossed from static graph analysis into a live, mobile, sensor-grounded workflow:

- GraphOps can now request observed data when evidence is thin.
- Android devices now act as durable observer anchors instead of just thin clients.
- RF and network observations can now be joined into graph-native `RF_TO_IP_BINDING` edges.
- The mobile app now renders an observer-relative digital twin projection feed that is ready for an ARCore follow-on.

That is not just a UI upgrade. It is a change in the kind of intelligence the system can produce.

---

## The Core Shift: From Inference-Heavy to Sensor-Grounded

The earlier problem was clear: the graph could contain rich structure and still be epistemically weak.

GraphOps Bot already knew how to say, in effect, *I do not have enough observed evidence to answer this honestly.* What it needed was a governed path to go get the evidence instead of stopping at refusal.

That grounding path now exists.

The new EVE sensor lane gives SCYTHE a way to ingest observed events on demand while preserving a strict separation between:

1. Continuous lane — operator-visible streaming for live awareness  
2. Grounding lane — explicit graph mutation for evidence-backed reasoning

That separation matters. It prevents raw ingest from silently contaminating the reasoning graph while still giving the operator a live operational view.

---

## EVE Sensor Grounding: A Clean Two-Lane Architecture

The EVE grounding work turned the earlier localhost-only prototype into a real system surface:

- orchestrator-managed `eve_stream_ws` and `eve_stream_http`
- backend config exposure through `/api/config/streams`
- health and grounding routes:
  - `/api/sensor/eve/health`
  - `/api/sensor/eve/ground`
- GraphOps policy hooks that preflight the sensor stream before asking high-stakes questions

The important design decision was not just adding another stream. It was refusing to collapse operator visibility and graph mutation into the same lane.

That produced a more disciplined model:

- continuous lane for visibility, telemetry, and operator confidence
- grounding lane for short, observed bursts that can change the epistemic posture of the graph

That is the difference between “we have packets on a screen” and “we have evidence we can reason over.”

---

## SCYTHE EVE on Android: The Observer Becomes Part of the Graph

The Android work pushed that architecture onto an actual field device.

Instead of building a parallel app, the existing `ScytheCommandApp` was upgraded into SCYTHE EVE:

- live relay resolution from `/api/config/streams`
- a mobile `ScytheSensorService`
- GPS and WiFi recon still posted through existing HTTP paths
- WebSocket relay events for:
  - position
  - heartbeat
  - WiFi scan summaries
  - observed infrastructure flow events

More importantly, the Android device is no longer just a UI shell. It now behaves like a durable observer.

That change is visible in the logs:

```text
Created recon entity: android-388bfdb841efb651
Created recon entity: android-388bfdb841efb651
Created recon entity: android-388bfdb841efb651
```

At first glance, that looks repetitive. Operationally, it is exactly what you want: a continuously refreshed observer anchor with stable identity and evolving position.

That observer identity is now carried consistently across:

- recon entity upserts
- relay events
- observed infrastructure flows
- digital twin projection requests

The phone is no longer just “connected.” It is now a first-class node in the intelligence model.

---

## Offline Demo Mode: Explaining the Architecture Without a Live Server

One of the more important product decisions in this cycle was adding a local, in-app demonstration mode that does not require a SCYTHE instance.

The new `eve_demo.html` asset makes the lane split understandable even when the backend is unreachable:

- continuous lane visualized as flowing operator-visible relay events
- grounding lane visualized as delayed observed node/edge injection
- counters for events, bursts, nodes, and edges
- bridge support for local sensor status and settings control

Why does this matter?

Because it turns architecture into an operator experience. The distinction between *streaming telemetry* and *evidence-backed graph mutation* is not just a backend concern anymore. It is visible, teachable, and testable on-device.

---

## RF->IP Correlation: The First Native Cyber-Physical Join

This was the next critical step.

SCYTHE already had RF context. It already had network context. What it lacked was a lightweight, repo-native way to join them without depending on older heavyweight experimental stacks.

That gap is now closed with a rolling RF/IP correlation engine:

- `POST /api/rf-ip-correlation/observe/rf`
- `POST /api/rf-ip-correlation/observe/network`
- `GET /api/rf-ip-correlation/status`
- `GET /api/rf-ip-correlation/bindings`

The engine performs a short-window scoring pass over:

- time alignment
- periodicity overlap
- entropy similarity
- optional spatial coherence
- identity persistence signals

When the score passes threshold, SCYTHE emits a graph-native:

- observed RF node
- `RF_TO_IP_BINDING` edge

through the `WriteBus`, not through an ad hoc side path.

This is what turns “maybe this emitter belongs to that infrastructure” into a durable, queryable graph fact with provenance.

---

## Auto-Promoting Android Observers into Sensors

Another subtle but important advancement came from tightening the observer model itself.

Android recon entities are now automatically promoted into:

- `sensor:<entity_id>` nodes
- sensor-to-recon assignment edges

That means a mobile observer is no longer just a periodically updated recon marker. It is a sensor anchor with durable graph semantics.

That unlocks cleaner downstream logic for:

- activity emission
- observed-vs-inferred separation
- sensor provenance tracking
- projection from observer viewpoint

It also fixed a coupled API bug in the assignment path: the sensor assignment route now correctly uses `recon_entity_id`, matching the actual `SensorRegistry` contract.

This is the sort of change that looks small in a diff and large in system behavior.

---

## The First Projection-Ready Digital Twin

Once the observer existed as a durable sensor and once RF/IP bindings could be emitted natively, the next move was obvious:

do not stream the raw graph into AR.

Project it relative to the observer.

That projection slice now exists in the backend:

- `GET /api/digital-twin/projection`
- `GET /api/ar/projection`

These routes derive an observer-relative payload from live system state:

- current Android observer identity
- nearby recon entities
- recent `RF_TO_IP_BINDING` observations
- distance
- absolute bearing
- relative bearing
- elevation

The key design principle is the one that matters most for mixed reality systems:

> the global graph remains the truth layer; the mobile client receives a viewpoint-relative projection of that truth

That payload now feeds a new in-app `digital_twin.html` surface inside SCYTHE EVE.

It is not the final AR renderer yet. It is the projection-first layer that an AR renderer can consume without having to understand the entire graph model.

Example shape:

```json
{
  "observer": {
    "observer_id": "android-388bfdb841efb651",
    "lat": 32.7767,
    "lon": -96.7970,
    "heading_deg": 0.0
  },
  "entities": [
    {
      "entity_id": "RE-DEMO-BIND",
      "type": "RF_IP_BOUND",
      "distance_m": 380.0,
      "absolute_bearing_deg": 38.0,
      "relative_bearing_deg": 38.0,
      "elevation_deg": 11.0,
      "confidence": 0.91
    }
  ]
}
```

This is the exact bridge the hybrid Cesium + ARCore architecture needed.

---

## Why the Projection Slice Matters

The digital twin work is important not because it adds another visualization, but because it formalizes the boundary between:

- global truth
- local viewpoint

Cesium remains the natural surface for:

- planetary state
- swarm playback
- graph evolution over time
- cluster and infrastructure topology

The mobile observer surface is now becoming the natural place for:

- local signal tracing
- operator-relative target display
- field verification of graph claims
- mixed-reality attribution workflows

This is what makes the system composable. A global graph can drive a local operator view without forcing the local device to become a graph engine.

---

## What Changed Operationally

Put all of this together and the workflow is different now.

Before:

- GraphOps could detect when evidence was weak
- the mobile app was largely a client surface
- RF and network context were adjacent but not durably joined
- AR-oriented work existed as separate experiments

Now:

- GraphOps can trigger live grounding when the evidence posture is sparse
- Android devices act as durable observer/sensor anchors
- RF and network events can resolve into graph-native observed bindings
- the mobile app can render observer-relative projection payloads directly
- the next ARCore step has a stable contract to consume

That is a real systems milestone.

---

## What Comes Next

The obvious follow-on is to replace the current north-up twin surface with the real geospatial AR renderer:

- feed device heading and pose into the projection request
- make bearings view-relative instead of north-relative
- reuse the existing `AndroidAppSceneview` geospatial anchor work
- anchor projected entities into camera space with ARCore / SceneView

At that point the stack becomes fully continuous:

1. phone sensor observes  
2. relay publishes  
3. graph grounds observed facts  
4. RF/IP correlation emits cyber-physical bindings  
5. projection service turns graph truth into observer-relative payloads  
6. AR renderer places those targets into the operator’s scene

That is where “network intelligence” becomes spatially grounded operator intelligence.

---

SCYTHE now has the beginnings of a full cyber-physical attribution loop:

- observed mobile sensors
- governed grounding
- graph-native RF/IP joins
- observer-relative digital twin projection

The next step is not inventing a new architecture.

It is finishing the last meter between the projected truth we now have and the AR scene that can render it in the field.


