# Ticker Android v1.1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the app usable from scratch (normalized symbols, searchable symbol add, list-first lot editor) and restyle it as a dark-first monospace terminal.

**Architecture:** All logic lands in `:shared` (config normalization, Yahoo search client, Coinbase catalog client, reverse `.X` mapping, merge/dedupe/cap) with `:shared:jvmTest` coverage; `:composeApp` gets the search sheet, lot-list sheet, theme store, and pinned palettes. Both live-probe gates were resolved 2026-10-14 (see Task 2/3 fixtures — shapes are observed, not documented).

**Tech Stack:** Kotlin 2.3.21 MPP, Compose Multiplatform 1.9.0, Ktor 3.5.0 (MockEngine tests), kotlinx-serialization, kaml, Koin 4.1.0, Preferences DataStore 1.1.7.

**Spec:** `docs/superpowers/specs/2026-10-14-v1.1-usability-design.md`

## Global Constraints

- No GMS/tracking/analytics; no WorkManager; no new network hosts (search reuses `query1.finance.yahoo.com`, catalog reuses `api.coinbase.com`, `.X` map stays on `raw.githubusercontent.com`).
- `expectSuccess = false` per request + sanitized errors (`transportFailure`) — same pattern as existing clients.
- Foreground-only polling; 30s floor (unchanged).
- No dynamic color / Material You. Pinned palettes only (exact hex in Task 7). BTC orange is `#F7931A` in both palettes.
- No runtime font fetching; fonts bundled under `composeApp/src/commonMain/composeResources/font/` (JetBrains Mono v2.304, Regular + SemiBold, OFL.txt alongside).
- `ThemeMode` must NOT enter `SettingsUpdate`/`TickerConfig` (it would round-trip into exported YAML). Separate DataStore named `"settings"`.
- Commits: conventional (`feat:`, `fix:`, `test:`, `chore:`); one logical change each.

## Review Focus

1. **Lowercase input** — typing `btc` in any add path (search add, exact-symbol add, YAML import watchlist/lots) must store `BTC`; a lowercase entry must never produce an `unavailable` row. Pinned by normalization tests (Task 1) + `withSymbolAdded` dedupe test (Task 1).
2. **Yahoo search failure/rate-limit (429 observed live without cookies)** — sheet degrades to empty results + "search failed" note; manual/exact add still works; no crash. Pinned in Task 4.
3. **Coinbase catalog pagination** — unauthenticated full list is 933 products over cursor pages (`pagination.next_cursor`/`has_next`); stopping after page one silently hides ~700 products. Pinned in Task 3.
4. **Theme-mode disagreement** — `QuoteRow.changeColor` reads `isSystemInDarkTheme()` directly today; forcing LIGHT/DARK in settings must move every color off system-dark detection or the two disagree. Pinned by Task 7 rewiring to theme-provided colors.
5. **Config round-trip** — lowercase YAML import must normalize on load and re-export the normalized form (documented divergence from the CLI). Pinned in Task 1 round-trip tests.

---

### Task 1: Symbol normalization + case-insensitive dedupe

**Files:**
- Modify: `shared/src/commonMain/kotlin/io/orangerabbit/ticker/config/TickerConfig.kt`
- Modify: `shared/src/commonMain/kotlin/io/orangerabbit/ticker/config/YamlConfig.kt` (`parseTickerConfig`, `:45`)
- Modify: `shared/src/commonMain/kotlin/io/orangerabbit/ticker/config/ConfigJson.kt` (`parseTickerConfigJson`, `:45`)
- Modify: `composeApp/src/androidMain/kotlin/io/orangerabbit/ticker/ui/HomeViewModel.kt:133-140` (`addSymbol`)
- Modify: `shared/src/commonMain/kotlin/io/orangerabbit/ticker/poll/QuotePoller.kt:254-261` (`missingSymbols`)
- Test: `shared/src/jvmTest/kotlin/io/orangerabbit/ticker/config/YamlConfigTest.kt`, `.../config/ConfigJsonTest.kt`, `.../poll/QuotePollerTest.kt`

**Interfaces:**
- Produces: `fun normalizeSymbol(raw: String): String` (trim + `uppercase()`, no locale args) in `config` package; `fun TickerConfig.normalized(): TickerConfig` (uppercases every `watchlist` entry and `Lot.symbol` in every group, preserves everything else); `fun List<String>.withSymbolAdded(symbol: String): List<String>` (normalizes, appends only if no case-insensitive duplicate exists).

- [ ] **Step 1: Write failing tests**

In `YamlConfigTest.kt` / `ConfigJsonTest.kt`:
- `parseTickerConfig` / `parseTickerConfigJson` with a lowercase watchlist entry and a lowercase `Lot.symbol` in the YAML/JSON: parsed config contains uppercased forms everywhere (default group watchlist included).
- Round-trip: parse lowercase YAML → `toYaml()` → output watchlist/lots are uppercase; parse lowercase JSON → `toJson()` → same.
In a new-or-existing config test: `withSymbolAdded(listOf("BTC"), " btc ")` == `["BTC"]`; `withSymbolAdded(listOf("ARKW"), "arkw")` == `["ARKW"]` (no dup); `withSymbolAdded(emptyList(), "btc")` == `["BTC"]`.
In `QuotePollerTest.kt`: poller with a group watchlisted as `btc` (pre-normalization-style config) and Yahoo returning symbol `BTC` → `btc` not in `unavailable`.

- [ ] **Step 2: Run** `./gradlew :shared:jvmTest` — new tests FAIL (no normalization today).

- [ ] **Step 3: Implement.** `normalizeSymbol`/`normalized()`/`withSymbolAdded` in `TickerConfig.kt`; apply `normalized()` inside both parse functions right after `toDomain()`, before `validateTickerConfig`. `HomeViewModel.addSymbol` replaces the `trimmed in group.watchlist` check at `:137` with `group.watchlist.withSymbolAdded(symbol)` (normalize + case-insensitive dedupe in the shared helper). `missingSymbols` builds its two present-sets and compares against lowercased keys (mirror the existing `lowercase()` idiom in `TickerConfig.kt:52`).

- [ ] **Step 4: Run** `./gradlew :shared:jvmTest` — PASS (full suite; no existing test re-pins case-sensitivity).

- [ ] **Step 5: Commit** `git commit -m "feat: normalize symbols case-insensitively across parse and add paths"`

### Task 2: Yahoo search client

**Files:**
- Modify: `shared/src/commonMain/kotlin/io/orangerabbit/ticker/source/yahoo/YahooClient.kt`
- Modify: `shared/src/commonMain/kotlin/io/orangerabbit/ticker/source/yahoo/YahooModels.kt`
- Create: `shared/src/jvmTest/resources/yahoo/search-apple.json`
- Test: `shared/src/jvmTest/kotlin/io/orangerabbit/ticker/source/yahoo/YahooClientTest.kt`, `YahooTransformTest.kt`

**Interfaces:**
- Consumes: existing session flow (`sessionStore.preload()/get()/refresh()`, `cookieHeader`, header constants, `transportFailure`).
- Produces: `suspend fun YahooClient.search(query: String): Result<List<SymbolSearchResult>>` where `SymbolSearchResult(symbol: String, name: String, exchange: String, source: SearchResultSource)` and `enum class SearchResultSource { YAHOO, COINBASE }` are defined in Task 4's `source/SymbolSearch.kt`. Internally: `data class SearchResponse(val quotes: List<SearchResponseQuote> = emptyList())`, `data class SearchResponseQuote(exchange: String, shortname: String, longname: String, exchDisp: String, quoteType: String, symbol: String)` (`ignoreUnknownKeys`, camelCase as observed — NOT `shortName`), `fun parseSearchResponse(body: String): SearchResponse`, `fun transformSearchQuotes(response: SearchResponse): List<SymbolSearchResult>` in `YahooModels.kt`.

- [ ] **Step 1: Commit the fixture** `shared/src/jvmTest/resources/yahoo/search-apple.json` — observed live 2026-10-14 (trimmed):

```json
{
  "quotes": [
    {"exchange":"NMS","shortname":"Apple Inc.","quoteType":"EQUITY","symbol":"AAPL","index":"quotes","score":453307.0,"typeDisp":"Equity","longname":"Apple Inc.","exchDisp":"NASDAQ","sector":"Technology","industry":"Consumer Electronics","dispSecIndFlag":true,"isYahooFinance":true},
    {"exchange":"CCC","shortname":"Bitcoin USD","quoteType":"CRYPTOCURRENCY","symbol":"BTC-USD","index":"quotes","score":368856.0,"typeDisp":"Cryptocurrency","longname":"Bitcoin USD","exchDisp":"CCC","isYahooFinance":true}
  ]
}
```

- [ ] **Step 2: Write failing tests.** Transform: `transformSearchQuotes(parseSearchResponse(fixture("search-apple.json")))` → first result `SymbolSearchResult(symbol="AAPL", name="Apple Inc.", exchange="NASDAQ", source=YAHOO)`; crypto row maps `name` from `shortname`, `exchange` from `exchDisp`. Client (MockEngine, scripted replies): 200 + fixture body → `Result.success` with 2 results, request path `/v1/finance/search`, query params `q, quotesCount=10, newsCount=0, lang=en-US, region=US`, `Cookie` header present, no `crumb` param; 429 body `Too Many Requests` → `Result.failure` with sanitized message.

- [ ] **Step 3: Run** `./gradlew :shared:jvmTest` — FAIL (nothing to compile against).

- [ ] **Step 4: Implement.** `search` in `YahooClient.kt` beside `requestQuotes`: session handshake if no stored session (cookies suffice — no crumb), `GET $baseURL/v1/finance/search`, same UA/Accept/Accept-Language discipline, `expectSuccess = false`, non-200 → `transportFailure`; catch-all → `Result.failure` (rethrow `CancellationException`).

- [ ] **Step 5: Run** `./gradlew :shared:jvmTest` — PASS.

- [ ] **Step 6: Commit** `git commit -m "feat: yahoo symbol search client"`

### Task 3: Coinbase catalog (cursor pagination + TTL cache)

**Files:**
- Modify: `shared/src/commonMain/kotlin/io/orangerabbit/ticker/source/coinbase/CoinbaseClient.kt`
- Modify: `shared/src/commonMain/kotlin/io/orangerabbit/ticker/source/coinbase/CoinbaseModels.kt`
- Create: `shared/src/jvmTest/resources/coinbase/catalog-page-1.json`, `catalog-page-2.json`
- Test: `shared/src/jvmTest/kotlin/io/orangerabbit/ticker/source/coinbase/CoinbaseClientTest.kt`

**Interfaces:**
- Produces: `suspend fun CoinbaseClient.listProducts(): Result<List<ProductCatalogEntry>>` with `data class ProductCatalogEntry(productId: String, baseName: String, baseDisplaySymbol: String)` (in `CoinbaseModels.kt`). Constructor gains `clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }` (mirrors `SymbolsClient`). Response DTO gains `@SerialName("pagination") val pagination: ResponsePagination?` where `data class ResponsePagination(@SerialName("next_cursor") val nextCursor: String = "", @SerialName("has_next") val hasNext: Boolean = false)`.
- Pagination contract (observed live 2026-10-14): `GET /api/v3/brokerage/market/products?limit=250` → 933 products over 4 pages; loop while `pagination.has_next == true`, passing `cursor=<next_cursor>`.

- [ ] **Step 1: Write failing tests** (MockEngine scripted queue):
  - Two pages then `has_next=false` → concatenated entries in order; second request carried `cursor` param.
  - Within 24h TTL, second `listProducts()` call issues no HTTP requests; after `advanceTimeBy` past TTL, it re-fetches.
  - Non-200 on first page → `Result.failure` (sanitized), no cache write.

  Fixture `catalog-page-1.json` (trimmed from live probe):
```json
{"products":[{"product_id":"BTC-USD","base_name":"Bitcoin","base_display_symbol":"BTC","product_type":"SPOT","status":"online"}],
 "pagination":{"next_cursor":"c2Vjb25k","has_next":true}}
```
  `catalog-page-2.json`: one more product, `"pagination":{"next_cursor":"","has_next":false}`.

- [ ] **Step 2: Run** `./gradlew :shared:jvmTest` — FAIL.

- [ ] **Step 3: Implement.** Cursor loop with in-memory cache (`entries`, `cachedAt`, 24h TTL — mirror `SymbolsClient.kt:37-83` structure); `expectSuccess = false`, sanitized failures.

- [ ] **Step 4: Run** `./gradlew :shared:jvmTest` — PASS.

- [ ] **Step 5: Commit** `git commit -m "feat: coinbase product catalog with cursor pagination and ttl cache"`

### Task 4: Reverse `.X` map + SymbolSearch merge

**Files:**
- Modify: `shared/src/commonMain/kotlin/io/orangerabbit/ticker/source/Symbols.kt`
- Create: `shared/src/commonMain/kotlin/io/orangerabbit/ticker/source/SymbolSearch.kt`
- Test: `shared/src/jvmTest/kotlin/io/orangerabbit/ticker/source/SymbolSearchTest.kt` (+ reverse-map case in `SymbolsTest.kt`)

**Interfaces:**
- Consumes: `YahooClient.search` (Task 2), `CoinbaseClient.listProducts` (Task 3), `SymbolsClient.fetchSymbolMap` (existing).
- Produces: `fun reverseSymbolMap(forward: TickerSymbolMap): Map<String, String>` — `sourceSymbol → tickerSymbol`, first row wins (mirror `parseSymbolsCsv`'s first-wins rule at `Symbols.kt:31`).
  `class SymbolSearch(private val yahoo: YahooClient, private val coinbase: CoinbaseClient, private val symbols: SymbolsClient)` with `suspend fun search(query: String): SymbolSearchOutcome`:
  `data class SymbolSearchOutcome(val results: List<SymbolSearchResult>, val yahooFailed: Boolean, val catalogFailed: Boolean)` (cap 15, ≤10 from Yahoo per `quotesCount=10`).
  Merge rules: Yahoo results verbatim first (server-ranked), then Coinbase; dedupe by final proposed symbol (case-insensitive); Coinbase mapping — reverse-map `productId` through `reverseSymbolMap`: hit → `tickerSymbol` (the `.X` shorthand), miss → `baseDisplaySymbol + ".CB"` (same rule as `transformProduct`); match = case-insensitive substring on `productId` OR `baseName`; fallback when `listProducts` fails → entries synthesized from reverse map rows with COINBASE source (`productId = sourceSymbol`, `baseName = ""`, `baseDisplaySymbol = ""`), matching `productId` only — surfaced in UI as "symbol match only". `catalogFailed = true` in that case. Yahoo failure → `yahooFailed = true`, contributes no rows; both failing → empty results, never an exception.

- [ ] **Step 1: Write failing tests**: reverse map first-wins; merge order Yahoo→Coinbase; dedupe (`BTC-USD` → both propose `BTC.X` → one row); cap at 15; `.CB` proposal when no shorthand; live-catalog failure → CSV fallback entries + `catalogFailed=true`; yahoo failure flag; empty-result path.

- [ ] **Step 2: Run** `./gradlew :shared:jvmTest` — FAIL.

- [ ] **Step 3: Implement** per the rules above.

- [ ] **Step 4: Run** `./gradlew :shared:jvmTest` — PASS.

- [ ] **Step 5: Commit** `git commit -m "feat: merge yahoo and coinbase symbol search with reverse .x mapping"`

### Task 5: Search sheet UI + HomeViewModel wiring

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/io/orangerabbit/ticker/ui/HomeViewModel.kt` (ctor + search state)
- Modify: `composeApp/src/androidMain/kotlin/io/orangerabbit/ticker/App.kt` (Koin: `single { SymbolSearch(get(), get(), get()) }`)
- Modify: `composeApp/src/commonMain/kotlin/io/orangerabbit/ticker/ui/GroupTabsScreen.kt` (replace `SymbolsSheet` `:409-468` and its invocation `:235-243`)
- Gate: `./gradlew :composeApp:compileKotlinJvm` (no composeApp test source set exists)

**Interfaces:**
- Consumes: `SymbolSearch.search` (Task 4), `addSymbol` action-time capture (`targetAt(selectedGroupIndex())`, `HomeViewModel.kt:136`).
- Produces: `HomeViewModel(query, poller, symbolSearch)` gains `fun onSearchQueryChanged(query: String)` and `val searchState: StateFlow<SearchState?>` where `data class SearchState(val query: String, val loading: Boolean, val outcome: SymbolSearchOutcome?)` (null = idle/cleared). Debounce 300 ms in the VM (`@OptIn(FlowPreview::class)`, `flatMapLatest` on a query flow + `delay(300)`, ≥2 chars, cancelled per new input). UI enum label for `SearchResultSource`: `YAHOO → "Yahoo"`, `COINBASE → "Coinbase"`.
- Sheet: `SearchSheet(existing: List<String>, state: SearchState?, onQueryChanged: (String) -> Unit, onAdd: (String) -> Unit, onRemove: (String) -> Unit, onAddExact: (String) -> Unit, onDismiss: () -> Unit)` — existing symbols listed with remove (as today); result row: proposed symbol (mono), name (muted), source tag; tap-add **keeps the sheet open** (do NOT copy the close-on-add wrapper at `:237-240`); added symbol shows an "added" state (row-level, cleared on next query change); permanent last row "Add exact symbol…" calls `onAddExact(query)`; no-results → "no matches — add `<query>` as an exact symbol".

- [ ] **Step 1: Wire VM** (debounce flow as specified; `onAddExact` = `onAddSymbol` with raw query — Task 1 normalizes it).
- [ ] **Step 2: Implement the sheet**, invoke from the existing `showSymbolsSheet` path.
- [ ] **Step 3: Run** `./gradlew :composeApp:compileKotlinJvm :shared:jvmTest` — PASS.
- [ ] **Step 4: Commit** `git commit -m "feat: searchable add-symbol sheet with exact-symbol escape hatch"`

### Task 6: Lot editor, list-first

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/orangerabbit/ticker/ui/GroupTabsScreen.kt:653-811` (`LotEditorSheet` and invocation `:246-262`)
- Gate: `./gradlew :composeApp:compileKotlinJvm`

**Interfaces:**
- Consumes: existing `onAdd/onUpdate/onDelete` callbacks and action-time capture — signature unchanged.
- Produces: sheet titled `Lots — <group name>`; list state machine inside the sheet: `sealed interface LotSheetMode { data object List; data class Editor(val editingIndex: Int?) }` (`null` index = new lot). List rows: `SYMBOL  qty @ cost  fee x` — mono, numerics right-aligned; empty state "No lots yet" + single **Add lot** button; tap row → editor prefilled with `Double.toString()` values (as today `:804` `parseFinite`) + **Update** / **Delete**; **Add lot** → blank editor → **Save**; blank editor never the initial state; exactly one creation path.

- [ ] **Step 1: Restructure the sheet** into the two modes; callback wiring unchanged.
- [ ] **Step 2: Run** `./gradlew :composeApp:compileKotlinJvm` — PASS.
- [ ] **Step 3: Commit** `git commit -m "feat: list-first lot editor with per-lot edit and single add path"`

### Task 7: Fonts + pinned palettes + monospace numerics

**Files:**
- Create: `composeApp/src/commonMain/composeResources/font/jetbrains_mono_regular.ttf`, `jetbrains_mono_semibold.ttf`, `OFL.txt` (JetBrains Mono v2.304, github.com/JetBrains/JetBrainsMono release assets)
- Modify: `composeApp/src/commonMain/kotlin/io/orangerabbit/ticker/ui/TickerTheme.kt`, `QuoteRow.kt`, `SummaryBar.kt`, `PositionsSection.kt`, `GroupTabsScreen.kt` (lot rows)
- Gate: `./gradlew :composeApp:compileKotlinJvm :composeApp:assembleDebug`

**Interfaces:**
- Produces (in `TickerTheme.kt`): `val TickerMono: FontFamily` (`Font(Res.font.jetbrains_mono_regular)` + `Font(Res.font.jetbrains_mono_semibold, FontWeight.SemiBold)`); pinned `darkColorScheme(background = Color(0xFF0B0E14), surface = Color(0xFF12161F), surfaceVariant = Color(0xFF1A1F2B), onSurface = Color(0xFFE6E9EF), onSurfaceVariant = Color(0xFF8B93A1), outline = Color(0xFF232936), …)` and `lightColorScheme(background = Color(0xFFFAFBFC), surface = Color(0xFFFFFFFF), onSurface = Color(0xFF171C26), onSurfaceVariant = Color(0xFF5C6470), outline = Color(0xFFE3E6EB), …)`; positive/negative as a theme-provided pair — `data class TickerColors(val positive: Color, val negative: Color)` + `LocalTickerColors` provided by `TickerTheme` (dark: `#34D399`/`#F87171`, light: `#059669`/`#DC2626`); BTC orange stays `Color(0xFFF7931A)` (replace `BitcoinOrange` usages).
- Apply `TickerMono` to: QuoteRow symbol/price/day-change, SummaryBar values, PositionsSection columns, lot rows — right-aligned numeric columns; labels/body stay default sans.

- [ ] **Step 1: Download the two weights + OFL.txt** from the v2.304 GitHub release into `composeResources/font/`.
- [ ] **Step 2: Build theme** (palettes, `LocalTickerColors`, `TickerMono`).
- [ ] **Step 3: Rewire call sites** — `QuoteRow.changeColor` (`:31`) and `symbolColor` (`:37`) stop reading `isSystemInDarkTheme()` and take theme-provided colors; SummaryBar/PositionsSection/lot rows go mono + right-aligned; `unavailable` footer (`GroupTabsScreen.kt:319-328`) and `ErrorBanner` (`:343`) become muted mono with warning tint; sheets use `surfaceVariant`, 16 dp padding, consistent corner radius.
- [ ] **Step 4: Run** `./gradlew :composeApp:compileKotlinJvm :composeApp:assembleDebug :shared:jvmTest` — PASS.
- [ ] **Step 5: Commit** `git commit -m "feat: dark-first terminal theme with bundled jetbrains mono numerics"`

### Task 8: ThemeMode setting (store + picker + wiring)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/orangerabbit/ticker/ui/ThemeMode.kt` (`enum class ThemeMode { SYSTEM, LIGHT, DARK }` + `interface ThemeSettings { val mode: StateFlow<ThemeMode>; suspend fun set(mode: ThemeMode) }`)
- Create: `composeApp/src/androidMain/kotlin/io/orangerabbit/ticker/SettingsStore.kt` — `preferencesDataStore(name = "settings")`, key `stringPreferencesKey("theme_mode")`, impl of `ThemeSettings` (default SYSTEM; invalid stored value → SYSTEM)
- Create: `composeApp/src/jvmMain/kotlin/io/orangerabbit/ticker/SettingsStore.jvm.kt` — in-memory `MutableStateFlow` impl (the jvm target is a compile gate only, no desktop app exists)
- Modify: `App.kt` (`single<ThemeSettings>`), `MainActivity.kt:42-46` (`collectAsState` on `themeSettings.mode` → `TickerTheme(darkTheme = when(mode) { SYSTEM -> isSystemInDarkTheme(); LIGHT -> false; DARK -> true })`), `GroupTabsScreen.kt:470-598` (`SettingsSheet` gains a 3-option `ThemeMode` picker; new callback `onThemeModeChanged: (ThemeMode) -> Unit`)
- Gate: `./gradlew :composeApp:compileKotlinJvm :composeApp:assembleDebug`

**Interfaces:**
- Produces: `ThemeSettings.mode` as the single source of truth; `TickerTheme` signature becomes `fun TickerTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)` unchanged, but callers pass resolved boolean; settings sheet calls `onThemeModeChanged` which persists immediately (no Save-button batching — theme is presentation, not `SettingsUpdate`).

- [ ] **Step 1: Define enum + interface, both impls, Koin binding.**
- [ ] **Step 2: Wire MainActivity + settings picker.**
- [ ] **Step 3: Run** `./gradlew :composeApp:compileKotlinJvm :composeApp:assembleDebug :shared:jvmTest` — PASS.
- [ ] **Step 4: Commit** `git commit -m "feat: switchable system/light/dark theme persisted in settings datastore"`

### Task 9: Whole-branch verification

- [ ] `./gradlew :shared:jvmTest :composeApp:compileKotlinJvm :composeApp:assembleDebug` — all green.
- [ ] `grep -ri "gms\|analytics\|workmanager" --include="*.kts" --include="*.toml" composeApp shared gradle` — no new hits (CI does this scan).
- [ ] Device smoke by the user (per spec: UI changes verified on device): search `bitcoin` → pickable rows from both sources; add keeps sheet open; `Edit lots` opens list-first; theme picker persists across process death.
- [ ] Update `README.md`/config docs if any documented behavior changed (normalization on load; theme setting) — `git commit -m "docs: v1.1 behavior notes"` if touched.
