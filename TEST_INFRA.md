# E2E Test Infra: Wiener Linien Android Overhaul

## Test Philosophy
- Opaque-box, requirement-driven. No dependency on implementation design.
- Methodology: Category-Partition + Boundary Value Analysis + Pairwise Combinatorial + Real-World Workload Testing.

## Feature Inventory & Test Coverage Goals
| # | Feature | Source (requirement) | Tier 1 (Coverage) | Tier 2 (Boundary) | Tier 3 (Cross-Feature) |
|---|---------|---------------------|:-----------------:|:-----------------:|:---------------------:|
| 1 | API Deserialization & Monitor Parsing | ORIGINAL_REQUEST §R4 | 5 | 5 | ✓ |
| 2 | Domain Models & Vehicle Line Mapping | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ |
| 3 | Departure Repository & Room Caching | ORIGINAL_REQUEST §R3 | 5 | 5 | ✓ |
| 4 | Offline Resilience & Fallback | ORIGINAL_REQUEST §R3 | 5 | 5 | ✓ |
| 5 | Stop Search & Asset Fallback | ORIGINAL_REQUEST §R1, R3 | 5 | 5 | ✓ |
| 6 | Countdown & Live Status Formatting | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ |
| 7 | Vehicle Badging & Transit Line Colors | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ |
| 8 | Widget RemoteViews & Sizing Adapters | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ |
| 9 | Widget Background & Worker Updates | ORIGINAL_REQUEST §R2, R3 | 5 | 5 | ✓ |
| 10 | Error State, Banner & Retry Handling | ORIGINAL_REQUEST §R1, R3 | 5 | 5 | ✓ |

## Test Architecture
- Frameworks: JUnit 4, Kotlinx-Coroutines-Test, MockWebServer, Google Truth, AndroidX Core-Testing.
- Location: `wienerlinien/app/src/test/java/com/wienerlinien/app/`
- Test Runner: `./gradlew testDebugUnitTest`
- Direct verification of API JSON payloads, Room entities and DAOs, Repository fallback mechanisms, View formatting helpers, and Widget size calculations.

## Real-World Application Scenarios (Tier 4)
| # | Scenario | Features Exercised | Complexity |
|---|----------|--------------------|------------|
| 1 | Commuter morning rush: online monitor with mixed U-Bahn, Tram, and Bus departures with live countdowns | F1, F2, F3, F6, F7 | High |
| 2 | Subway tunnel blackout: user loses network connectivity, app and widget immediately display cached departures with offline timestamp | F3, F4, F8, F10 | High |
| 3 | Fresh app launch without internet: stop search utilizes bundled CSV asset data to allow stop discovery and bookmarking | F5, F10 | Medium |
| 4 | Widget home screen resize: widget scales gracefully across 2x2, 3x2, and 4x2 grid dimensions displaying optimal row count | F8, F9 | Medium |
| 5 | Rapid manual refresh spam: user clicks widget/app refresh repeatedly; repository throttles and guards against redundant concurrent network calls | F3, F9, F10 | High |

## Coverage Thresholds
- Tier 1 (Feature Coverage): ≥ 5 tests per feature (≥ 50 test cases)
- Tier 2 (Boundary & Corner Cases): ≥ 5 tests per feature (≥ 50 test cases)
- Tier 3 (Cross-Feature Combinations): ≥ 10 tests covering major feature interactions
- Tier 4 (Real-World Scenarios): ≥ 5 realistic application-level scenarios
- Total Target: ≥ 115 test cases
