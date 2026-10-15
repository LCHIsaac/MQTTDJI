# Telemetry UI (in-app)

Five telemetry pages read live values via `DjiTelemetryCollector` (`KeyManager.listen` + `PerceptionManager` listeners) exposed on `AircraftConnectionPort.aircraftTelemetry`.

## Metrics not bound (MSDK 5.14.0)

| UI field | Reason |
|----------|--------|
| GPS constellation | No stable `FlightControllerKey` constellation string in use; shows `—`. |
| RC stick axes (L/R H/V) | `RemoteControllerKey` stick value keys are not exposed on the provided SDK artifact for this app; shows `—`. |
| RC battery % | No reliable `RemoteControllerKey` charge key on RC-N2 / RC Pro in this SDK build; shows `—`. |
| Obstacle F/B/L/R on Mini 4 Pro | `ObstacleData` sector layout may be sparse; distances use perception bins when the aircraft publishes them. |

Publisher / audience tokens and MQTT secrets are not shown on telemetry pages.
