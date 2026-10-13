# Ticker for Android — Design

Date: 2026-10-13
Status: Approved design (pending plan)
Reference implementation: [achannarasappa/ticker](https://github.com/achannarasappa/ticker) v5 (Go, CLI)
Prior candidate evaluated: [premnirmal/StockTicker](https://github.com/premnirmal/StockTicker) (rejected as foundation: widget-shaped data model, Yahoo-only, Firebase in upstream flavor)

## Goal

Android app replicating the `ticker` CLI: live stock/crypto quotes, positions with multiple cost-basis lots, groups, summary bar. Same `.ticker.yaml` config so one file serves desktop and phone.

**Success criteria**

1. Parses a real `.ticker.yaml` (watchlist, lots, groups, flags) without changes.
2. Shows quotes and positions matching the CLI's computed values (price, day change, change %, position value/cost/gain-loss/weight, summary).
3. Works fully on GrapheneOS with no Google Play Services installed.
4. Zero analytics/tracking/crash-reporting code or dependencies in any build variant.
5. In-app edits round-trip back to `.ticker.yaml`.

## Hard constraints

| Constraint | Rule |
|---|---|
| No GMS | No `com.google.android.gms`, Firebase, Play Billing, or Play Services dependency in any variant. Fulfilled by pure AndroidX + Ktor + Koin; verified by scanning the dependency tree in CI. |
| No tracking | No analytics, ads, or crash reporting SDKs. No network call that is not a quote/config fetch. |
| F-Droid-friendly distribution | Reproducible release build (R8 with pinned inputs, deterministic zip), signed APK published on GitHub Releases; F-Droid metadata later. No AAB-only. |
| De-Googled push/background | No push notifications. Foreground-only polling; no WorkManager in v1 (widget is out of scope and would introduce it). |
| Battery | Foreground auto-refresh floored at 30s regardless of config `interval` (desktop CLI keeps its own 5s default from the same file; the phone applies the floor). One immediate refresh on app resume. |

## Tech stack

Matches the sibling `clanker` project (proven on this host): Kotlin 2.3.21, Compose Multiplatform 1.9.0, Ktor 3.5.0 (OkHttp engine), kotlinx-serialization 1.9.0, Ktor client, Koin 4.1.0, coroutines 1.10.2. YAML via `kaml`. AGP 9.1.1, Gradle 9.3.1, JDK 21 dev shell (NixOS: `flake.nix` identical to clanker's — no Android SDK locally; Android build happens in CI).

Modules (clanker shape):

- `:shared` — everything testable on JVM: config model, quote aggregation, source clients, symbol mapping.
- `:composeApp` — thin Android shell: Compose UI, lifecycle wiring, DI graph, DataStore config storage.

`applicationId io.orangerabbit.ticker`, minSdk 28, targetSdk 36.

## Data model (ported 1:1 from CLI `internal/common/common.go`, `internal/asset/asset.go`)

- `Config`: `interval`, `watchlist`, `lots`, `groups`, `currency`, `currency-summary-only`, `currency-disable-unit-cost-conversion`, `show-*` flags, `sort`, `colors` (accepted, ignored — M3 dynamic theming instead), `cache` (accepted, ignored — phone always caches).
- `Lot(symbol, unitCost, quantity, fixedCost)` — quantities may be negative (shorts/netting).
- `AssetQuote`, `Asset`, `Position`, `PositionSummary`, `PositionChange`, `QuotePrice`, `QuoteExtended`, `Exchange(State)`, `AssetClass`, `QuoteSource` — same fields and semantics as the CLI structs.
- Aggregation: lots→`AggregatedLot`→positions; weights from currency-converted summary values; day/total change percents; the CLI's `calculateChangePercent` zero-guard. Ported verbatim with unit tests against hand-computed fixtures.

Divergences from the CLI, explicit:

1. `interval` floored at 30s on-device (see Battery).
2. `colors` ignored (theming).
3. No websocket (v1; see Sources).

## Sources (ported from CLI monitors; endpoints verified against CLI source)

**Yahoo Finance (default)** — `GET https://query1.finance.yahoo.com/v7/finance/quote?fields=...&symbols=...&formatted=true&lang=en-US&region=US&corsDomain=finance.yahoo.com&crumb=...` with cookies from `https://finance.yahoo.com` and crumb from `https://query2.finance.yahoo.com/v1/test/getcrumb`; desktop Chrome UA; single-redirect client; EU consent-redirect fallback (`consent.yahoo.com`); session (cookies+crumb) cached 24h, refreshed and retried once on ≥400. Fields per CLI `GetAssetQuotes`: price, change, change %, prev close, open, day high/low, volume, pre/post market, 52w range, market cap, shortName, currency.

**Coinbase (`.CB` suffix)** — `GET https://api.coinbase.com/api/v3/brokerage/market/products?product_ids=...` (the CLI's unauthenticated REST path). v1 polls on the same interval; **no WebSocket** (`wss://ws-feed.exchange.coinbase.com`) — streaming is battery-hostile on mobile and adds reconnection state; v2 candidate. Spot products map: price, 24h open/volume, change %; futures products deferred (rarely useful on a phone).

**`.X` shorthand** — `https://raw.githubusercontent.com/achannarasappa/ticker-static/master/symbols.csv`, fetched once per session, cached 24h, mapped `SOL.X`→`SOLANA.CG`-style source symbols.

**Currency rates** — Yahoo FX pairs `FROMTO=X` (e.g. `EURUSD=X`) including minor-unit handling (GBp), as in the CLI.

Symbol routing: no suffix → Yahoo; `.CB` → Coinbase (suffix stripped for the API call); `.X` → lookup then route. Symbols present in `lots` but absent from `watchlist` are implicitly watched (CLI rule).

## Config lifecycle

- First run: SAF file picker to import a `.ticker.yaml`; parsed with `kaml` into `Config`; stored in DataStore as normalized JSON. No direct filesystem path access (sandboxed storage).
- In-app edits: add/remove watchlist symbols, edit lots (qty/unit-cost/fixed-cost), manage groups, set interval/sort/display flags. Persisted to DataStore.
- Export: write a `.ticker.yaml` via SAF so the file can be copied back to the desktop. Round-trip guarantee: parse→export→parse yields an equivalent `Config`.
- Validation: unknown suffixes, negative interval, malformed lots produce a clear error state in the UI, not a crash.

## UI (Compose, single screen — CLI's surface, nothing more)

- Group tabs (default group first; order from config; tab cycling matches CLI TAB/SHIFT-TAB semantics).
- Quote rows: symbol, name, price, day change + %, colored by sign (green/red, Bitcoin orange `#F7931A` only for BTC branding); optional tags row (currency, exchange, delay) and fundamentals row (open, prev close, day range) per config flags.
- Positions section (when `show-positions`): per-symbol weight, avg cost, quantity, day change, total gain/loss; multi-lot symbols show aggregated avg cost.
- Summary bar (when `show-summary`): total value, day change (amount+%), total change (amount+%).
- Sorting: change-percent (default, closed markets last), `alpha`, `value`, `user` — same rules as CLI `internal/sorter`.
- Pull-to-refresh; lifecycle-aware foreground poll (floored 30s); visible error/retry state per refresh failure.
- Material 3, dynamic color on AOSP available; dark/light.
- Out of scope (deliberately): charts, news, alerts, search, notes, home-screen widget.

## Refresh architecture

- `QuotePoller` in `:shared`: owns symbol set per group, interval loop (coroutine `Flow`), emits quote updates via `StateFlow`.
- Session/cache: Yahoo session and `.X` map cached in memory + DataStore with TTL, mirroring the CLI's startup cache.
- No background work: app killed → no polling. WorkManager only arrives with a future widget feature.

## Testing (`:shared:jvmTest`, JDK 21, no Android SDK)

- Config: parse real `.ticker.yaml` fixtures (the README's example included); round-trip; deprecated `show-holdings`/`holdings` aliases; error cases.
- Aggregation: lots→positions→summary math, weights with currency conversion, negative quantities, zero-price guards.
- Yahoo: response transform from recorded fixture JSON; session refresh logic against mock transport (crumb flow, retry-on-401, EU consent path).
- Coinbase: product-response transform from recorded fixture.
- `.X` mapping: symbols.csv fixture.
- CI: `ci.yml` runs `:shared:jvmTest`; release workflow builds reproducible signed APK (keystore via secrets, like clanker's `release.yml`).

## Risks

| Risk | Mitigation |
|---|---|
| Yahoo session handshake is unofficial and fragile (EU consent, cookie rotation) | Port the CLI's exact flow/headers verbatim (proven, actively maintained); retry-once + user-visible error state; same fragility the CLI already has |
| Coinbase market-products endpoint auth requirements could change | Wire-tested on first implementation; fallback is Yahoo `BTC-USD`-style symbols which already work |
| `kaml` schema drift vs CLI's YAML | Fixture tests against the CLI README example + real user file |
| Reproducible R8 build for F-Droid | Pinned toolchain in CI; if full determinism is hard initially, ship signed APKs on GitHub Releases first, F-Droid metadata as follow-up |
