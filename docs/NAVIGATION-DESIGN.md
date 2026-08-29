# Navigation feature — design & staging

Self-contained nav feature. Touch-points to existing code kept minimal:
one `tabs` entry, one `when(tab)` branch, and (later) reuse of speed-limit
maps + front camera. All new code lives in package `com.rfsat.dms.nav`.

## Three interfaces (so today's choices don't lock tomorrow's)

1. NavProvider — the ONLINE/OFFLINE split. route(), tiles(), search(),
   reverseGeocode(). Impls:
     - OnlineOsmProvider  (MapLibre vector tiles + OSRM/Valhalla routing) — online
     - OfflineProvider    (downloaded maps + on-device routing) — LATER
   UI never knows which is active -> adding offline changes no UI code.

2. RouteEngine — position + route -> maneuver stream (dist to next turn,
   instruction, turn geometry, lane hints). Single source consumed by every
   renderer + voice + haptic.

3. Presentation = BASE VIEW (one) + OVERLAYS (any combination).
   Renderers all consume the same RouteEngine stream.

## Presentation model: base + stackable overlays (modes are COMBINABLE)

BASE VIEWS (exactly one; full-screen, mutually exclusive):
  - MAP_2D_TOPDOWN     north-up / heading-up flat
  - MAP_2D_PERSPECTIVE tilted bird's-eye
  - MAP_3D             terrain + extruded buildings
  - CAMERA_AR          front-camera road view as the base

OVERLAYS (any subset, on any base):
  - ARROW_MANEUVER     big turn arrow (over road when base=CAMERA_AR)
  - SPEED_LIMIT        reuse existing speed-limit map data
  - LANE_GUIDANCE      which lane (data-dependent)
  - JUNCTION_VIEW      schematic at complex junctions
  - RIBBON_HUD         thin road-ahead ribbon, low clutter
  - VOICE              TTS prompts (non-visual)
  - HAPTIC             vibration turn cues (non-visual)

DISPLAY TRANSFORMS (orthogonal, apply to whatever is shown):
  - WINDSHIELD_MIRROR  horizontal flip + high-contrast for dashboard reflection
  - DAY / NIGHT / AUTO theme (reuse existing auto-dim)

State model: NavState { base: BaseView, overlays: Set<Overlay>,
  transform: {mirror:Bool, theme:Theme}, provider: NavProvider }.
Each renderer = one class; adding a mode = adding a class, touching no others.

## Staging
- v1: skeleton — nav tab, state model, mode-selector UI (base radio + overlay
  checkboxes), provider interface, ARROW renderer (works without map SDK:
  arrow + text from a mocked/simple straight-line route), VOICE stub, WINDSHIELD
  mirror transform. NO map SDK dependency yet.
- v2: integrate MapLibre -> MAP_2D_TOPDOWN/PERSPECTIVE/MAP_3D real tiles online.
- v3: OSRM/Valhalla real routing; RouteEngine real maneuvers; lane/junction.
- v4: CAMERA_AR base reusing the existing front-camera + detector overlays.
- v5: OfflineProvider on downloaded maps (offline routing + offline tiles).

## Feasibility notes
- Google turn-by-turn UI is NOT embeddable free (Navigation SDK = paid). Google
  Maps SDK embeds a map but needs API key + billing. So default stack is
  MapLibre+OSM (free, online now, offline later, same renderer). Google kept as
  a pluggable NavProvider option (SDK or intent hand-off), not the default.
- MapLibre GL Android does 2D/perspective/3D + offline vector tiles in one engine.
- Routing: OSRM/GraphHopper/Valhalla public or self-host; offline via on-device
  Valhalla or a routing graph built from the downloaded maps.
