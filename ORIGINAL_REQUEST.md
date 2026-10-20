# Original User Request

## Initial Request — 2026-10-14T10:40:55Z

Comprehensive overhaul of the Wiener Linien Android application and home screen departure widget to production-quality standards, modernizing the UI/UX, enhancing real-time departure tracking, improving widget responsiveness and reliability, and establishing rigorous test verification.

Working directory: c:\Users\lazar\Documents\antigravity\WienerLinien-Android\wienerlinien
Integrity mode: development

## Requirements

### R1. Modern App User Experience & Styling
Upgrade the app UI with modern Material Design principles, clean typography, responsive layout hierarchy, clear vehicle type badging (U-Bahn, Tram, Bus), and intuitive departure status indicators (real-time vs scheduled countdowns). Provide smooth loading, empty, and error feedback across stop search, saved stops, and departure boards.

### R2. Resilient Home Screen Departure Widget
Overhaul the home screen widget to display configured transit stops and upcoming departures with clear line identifiers, countdown minutes, and destination badges. Ensure the widget layout scales gracefully across widget dimensions, includes a visible refresh action with last-updated timestamp or status, and handles empty or network-disconnected states cleanly.

### R3. Data Handling, Caching, and Background Reliability
Ensure robust error handling, network retry mechanisms, and offline resilience for saved stops and cached departures. Ensure periodic and on-demand widget update scheduling (via WorkManager and widget provider) operates efficiently without battery drain, stale display deadlocks, or background crashes.

### R4. Automated Testing & Verification
Establish an automated unit test suite covering API response deserialization, domain models, repository caching, and state formatting. Ensure the codebase builds cleanly with Gradle and passes all tests and lint checks.

## Acceptance Criteria

### Build & Verification
- [ ] Project builds cleanly via `./gradlew assembleDebug` with zero compilation errors.
- [ ] Automated unit test suite runs and passes via `./gradlew testDebugUnitTest`.
- [ ] Code passes `./gradlew lintDebug` with no fatal errors.

### App Functional & Visual Quality
- [ ] Stop search, addition, deletion, and selection function without crashes or state inconsistencies.
- [ ] Departure lists display line names/numbers, vehicle icons or badges, destinations, and accurate countdown times with visual distinction between live real-time countdowns and scheduled departures.
- [ ] Pull-to-refresh and network failure scenarios display clear, user-friendly indicators and retry options rather than silent failures or raw error dumps.

### Widget Functional & Visual Quality
- [ ] Home screen widget successfully renders saved/configured stops with their upcoming departure lines and minutes.
- [ ] Widget manual refresh and background scheduled updates refresh departure data accurately.
- [ ] Widget displays clear fallback/error state when no network connectivity is available or when no stops are configured.
