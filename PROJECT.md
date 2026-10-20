# Project: Wiener Linien Android Overhaul

## Architecture
- **Layered Architecture**:
  - **Data Layer**:
    - `api/`: Retrofit `WienerLinienApiService`, `ApiClient` with Gson serialization.
    - `model/`: Room database `AppDatabase`, `SavedStopDao`, `CachedDepartureDao`, entities (`SavedStop`, `CachedDeparture`), and `DepartureRepository`.
    - `util/`: `StopSearchUtil` with bundled asset fallback and CSV parsing.
  - **Presentation Layer**:
    - `ui/`: `MainActivity`, `AddStopActivity`, `WidgetConfigureActivity`, `MainViewModel`, `DepartureGroupAdapter`, `DepartureRowView`, `VehicleBadgeView`.
    - Modern Material 3 DayNight theme (`Theme.Material3.DayNight.NoActionBar`), vector vehicle drawables, responsive `RecyclerView`.
  - **Widget & Background Layer**:
    - `widget/`: `DepartureWidgetProvider` (RemoteViews management, size-aware layouts, `goAsync()` manual refresh), `WidgetPrefs`.
    - `worker/`: `WidgetRefreshWorker` (WorkManager periodic/one-time background execution without background service start).
  - **Test Layer**:
    - `src/test/`: Comprehensive unit and regression test suite covering API deserialization, domain models, caching repository, search utilities, and widget formatting.

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | Build & Environment Setup | Configure JDK 17, Android SDK path, and update root/app Gradle configs cleanly | M1 | Survey / R4 |
| 2 | Test Dependencies & Harness | Add JUnit, Mockito, Truth, Coroutines-Test, MockWebServer to `app/build.gradle` | M1 | Survey / R4 |
| 3 | Room Departure Caching | Create `CachedDeparture` entity and DAO with timestamp and stop correlation | M1 | Survey / R3 |
| 4 | Offline Repository Fallback | Update `DepartureRepository` to store responses in Room and fallback to cache when offline | M1 | Survey / R3 |
| 5 | Query Sanitization & Direction Filter | Guard empty `rblNumbers` (avoid API error 321), implement direction/steig filtering | M1 | Survey / R3 |
| 6 | Bundled Stop Assets | Bundle baseline Vienna transit stops CSV in `assets/` for offline search resilience | M1 | Survey / R3 |
| 7 | Data & Model Unit Tests | Write comprehensive tests for API models, JSON deserialization, repository, and search | M1 | Survey / R4 |
| 8 | Material 3 DayNight Theming | Implement `Theme.Material3.DayNight.NoActionBar`, dark mode (`values-night`), Material typography | M2 | Survey / R1 |
| 9 | Layout Hierarchy & Recycling | Remove `NestedScrollView` wrapping `RecyclerView` in `activity_main.xml`, enable view recycling | M2 | Survey / R1 |
| 10 | Vehicle Type Badging | Add vector icons (U-Bahn, Tram, Bus, Night Bus) and official Wiener Linien line colors | M2 | Survey / R1 |
| 11 | Live vs Scheduled Indicators | Explicit "LIVE" badge/pulse vs scheduled indicator, exact clock departure time (`HH:mm`) | M2 | Survey / R1 |
| 12 | State Feedback & Undo Deletion | Persistent error banner with Retry button, undoable stop deletion via Snackbar, empty state action | M2 | Survey / R1 |
| 13 | Eliminate Background Service Crash | Remove `WidgetUpdateService`, use `goAsync()` in `DepartureWidgetProvider` and coroutines in Worker | M3 | Survey / R2, R3 |
| 14 | Widget Offline Display | Display cached departures with "Offline · Updated HH:mm" banner instead of blanking out | M3 | Survey / R2, R3 |
| 15 | Responsive Widget Scaling | Support 2x2, 3x2, and 4x2 launcher dimensions with size-aware row population without clipping | M3 | Survey / R2 |
| 16 | Widget Line & Departure Badging | Apply transit line colors and live vs scheduled indicators to widget rows | M3 | Survey / R2 |
| 17 | Widget Manual & WorkManager Refresh | Provide manual refresh action with instant feedback and reliable WorkManager periodic refresh | M3 | Survey / R2, R3 |
| 18 | E2E & Tier 1-4 Test Verification | Verify 100% test suite pass across all tiers, API parsing, repository, widget, and UI formatting | M4 | Survey / R4 |
| 19 | Adversarial Hardening (Tier 5) | Stress test edge cases (malformed JSON, timeouts, empty lists, rapid refresh spam) | M4 | Survey / R4 |
| 20 | Final Gradle Build & Lint Verification | Clean `./gradlew assembleDebug`, `./gradlew testDebugUnitTest`, `./gradlew lintDebug` | M4 | Acceptance Criteria |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Build Infra, Data Caching & Offline Resilience | Features 1, 2, 3, 4, 5, 6, 7 | none | COMPLETED |
| M2 | Material 3 UI/UX, Vehicle Badging & State Handling | Features 8, 9, 10, 11, 12 | M1 | COMPLETED |
| M3 | Resilient Home Screen Widget & Background Engine | Features 13, 14, 15, 16, 17 | M1 | COMPLETED |
| M4 | Comprehensive Verification & Final Gradle Gate | Features 18, 19, 20 | M1, M2, M3 | COMPLETED |

## Interface Contracts
### Data Layer (`DepartureRepository`) ↔ Presentation / Widget Layer
- `suspend fun fetchDepartures(stops: List<SavedStop>, forceRefresh: Boolean = false): Result<List<DepartureGroup>>`
  - When online: queries Wiener Linien API, caches departures in Room, returns fresh departures.
  - When offline or API error: returns cached departures marked with `isCached = true` and cache timestamp.
  - When `stops` is empty: returns `Result.success(emptyList())` without calling network.
- `DepartureGroup`:
  - `val stop: SavedStop`
  - `val departures: List<DepartureItem>`
  - `val isFromCache: Boolean`
  - `val lastUpdatedMillis: Long`
- `DepartureItem`:
  - `val lineName: String`
  - `val lineType: String?` (e.g. `ptMetro`, `ptTram`, `ptBusCity`, `ptBusNight`)
  - `val destination: String`
  - `val countdown: Int` (minutes remaining)
  - `val isRealtime: Boolean`
  - `val departureTimePlanned: String?` (ISO or HH:mm)
  - `val departureTimeReal: String?` (ISO or HH:mm)

### Widget Provider (`DepartureWidgetProvider`) ↔ WorkManager (`WidgetRefreshWorker`)
- `DepartureWidgetProvider`:
  - Handles `ACTION_REFRESH` via `goAsync()` executing a coroutine to fetch data via `DepartureRepository` and update RemoteViews directly.
  - Sets `RemoteViews` with line colors, destination, countdown, live indicator, and updated timestamp.
  - Handles offline state by displaying cached departures with an "Offline · HH:mm" warning banner.
- `WidgetRefreshWorker`:
  - Extends `CoroutineWorker`.
  - In `doWork()`, calls `DepartureRepository.fetchDepartures()`, updates `AppWidgetManager` directly using `DepartureWidgetProvider.buildWidgetViews()`.
  - Does NOT call `startService()`.

## Code Layout
- `wienerlinien/app/src/main/java/com/wienerlinien/app/`
  - `api/`: `ApiClient.kt`, `WienerLinienApiService.kt`
  - `model/`: `Database.kt`, `Models.kt`, `DepartureRepository.kt`, `SavedStopDao.kt`, `CachedDepartureDao.kt`, `CachedDeparture.kt`
  - `ui/`: `MainActivity.kt`, `AddStopActivity.kt`, `WidgetConfigureActivity.kt`, `MainViewModel.kt`, `DepartureGroupAdapter.kt`, `DepartureRowView.kt`, `VehicleBadgeHelper.kt`
  - `widget/`: `DepartureWidgetProvider.kt`, `WidgetPrefs.kt`
  - `worker/`: `WidgetRefreshWorker.kt`
  - `util/`: `StopSearchUtil.kt`
- `wienerlinien/app/src/main/res/`
  - `drawable/`: `ic_subway.xml`, `ic_tram.xml`, `ic_bus.xml`, `ic_night_bus.xml`, `bg_widget_line_chip.xml`, badge drawables
  - `layout/`: `activity_main.xml`, `activity_add_stop.xml`, `item_stop_card.xml`, `view_departure_row.xml`, `widget_departure.xml`
  - `values/`: `colors.xml`, `themes.xml`, `strings.xml`, `dimens.xml`
  - `values-night/`: `colors.xml`, `themes.xml`
  - `xml/`: `widget_info.xml`
- `wienerlinien/app/src/main/assets/`
  - `stops_vienna.csv`: Baseline static transit stops data
- `wienerlinien/app/src/test/java/com/wienerlinien/app/`
  - `api/`: `ApiModelDeserializationTest.kt`
  - `model/`: `DepartureRepositoryTest.kt`, `ModelMappingTest.kt`
  - `ui/`: `CountdownFormatterTest.kt`, `VehicleBadgeTest.kt`
  - `widget/`: `WidgetPrefsTest.kt`, `WidgetFormattingTest.kt`
  - `util/`: `StopSearchUtilTest.kt`
