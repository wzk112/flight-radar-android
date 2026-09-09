# Airline identification and artwork

2026-09-09 update:
- 800 static ICAO/IATA mappings plus the existing 6,004-entry operator-name fallback. This is not an authoritative current registry. Normalizes whitespace/case; distinguishes ICAO callsigns, IATA flight numbers and registration strings.
- 100 bundled PNG logos, including SF Airlines, FedEx, Cargolux, China Cargo, Polar, Atlas and common international/Chinese passenger carriers. Original source colors are retained except near-black pixels lifted for visibility on the black display.
- Bundled artwork precedes disk/network. Successful downloads persist. Separate single-thread logo queue does not occupy the flight-data executor.
- Missing artwork uses a known IATA/ICAO code; UNKNOWN only when identity cannot be determined.
- Generic gstatic tail and empty images rejected, including old disk cache. Online fallback is pics.avs.io. Network failures wait 15 minutes; placeholder responses wait seven days. Retry timestamps survive restart.
- GI, I9 and AZ remote artwork blocked because inspected provider images represented earlier code holders. Other online images remain best-effort; arbitrary provider branding cannot be automatically proven correct.
- Logo aspect ratio preserved, dot sampling increased. All waiting views are notified on the UI thread; duplicate downloads are coalesced.

Sources/license: app/src/main/assets/AIRLINE-DATA-LICENSE.txt and airline-logos/SOURCES.txt.
Validation: assembleDebug, assembleDebugAndroidTest, lintDebug passed; Xiaomi 17 Ultra DeviceChecks 62 checks passed. Inspected fixture screenshots of single SF flight and multiple SF/FedEx/Hainan/unknown flights. Fixtures do not establish live flight-data coverage.
