# Attribution and modification notice

Flight Radar Android is adapted from ESP32 Flight Radar by delphicchen.

- Upstream: https://github.com/delphicchen/esp32_flight_radar
- Reviewed revision: `ffb0edc3c32c60592c7893f02b20ba93141757dd`
- License: Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International. See LICENSE.
- Changes: native Android UI, Android networking/decoding, Android lifecycle/display protection, alarm scheduling, touch handling, labels and persistent configuration. ESPHome hardware drivers are not included. Home Assistant integration was removed at the user's request.

Offline assets extracted from upstream `aircraft_db.h`:

- Silhouettes: plane-watch/pw-silhouettes, upstream snapshot 20260219, CC BY-NC-SA 4.0. https://github.com/plane-watch/pw-silhouettes
- Locally drawn silhouette entries: delphicchen's `tools/make_local_silhouettes.py`.
- Designators, manufacturers, operators and ICAO24 allocation blocks: rikgale/ICAOList, ICAO Doc 8643 / Doc 8585 / Annex 10, as attributed in upstream. https://github.com/rikgale/ICAOList
- Performance and engine data: TU Delft OpenAP and upstream supplemental CSVs. https://github.com/junzis/openap
- The upstream table contains 318 rows; duplicate designators are keyed to 316 unique codes by the Android asset importer. The complete original header is preserved in tools/upstream-aircraft-db.h.

Map data is downloaded from https://github.com/delphicchen/flight-radar-maps. Its upstream generator credits Natural Earth (public domain), OurAirports (public domain), and additional regional sources when present. This app does not claim that every geographic region has airspace polygons.

Flight data: adsb.lol, OpenSky Network, airplanes.live. Route/registration enrichment: adsbdb. Weather: Open-Meteo and RainViewer. Each service has independent conditions and availability.

This adaptation is provided for non-commercial use under CC BY-NC-SA 4.0, without endorsement by the original authors or data providers. The app is for hobby display, not operational air traffic control or navigation.

Street map data © OpenStreetMap contributors (ODbL); cartographic tiles from OpenStreetMap Standard. Attribution is visible in the app. https://www.openstreetmap.org/copyright

AWC / NOAA aviation weather: METAR observations and station directory from https://aviationweather.gov/data/api/ . Station snapshot retrieved 2026-09-09. Chinese field decoding is implemented by this app; original reports remain visible.

Address search fallback: Photon public geocoding API (https://github.com/komoot/photon), based on OpenStreetMap contributor data under ODbL. Attribution is shown in the position dialog.

Airline identity mapping: OpenFlights-derived ODbL database with local corrections; see app/src/main/assets/AIRLINE-DATA-LICENSE.txt. Airline artwork remains owned by its respective rights holders and is not relicensed under CC BY-NC-SA; see app/src/main/assets/airline-logos/SOURCES.txt.
