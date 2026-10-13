# Ticker for Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Android app replicating the `ticker` CLI: live quotes, multi-lot positions, groups, summary — reading the same `.ticker.yaml`.

**Architecture:** KMP with clanker's proven shape: `:shared` (pure Kotlin, `androidTarget` + `jvm()` for tests) holds config parsing, domain math, and source clients; `:composeApp` is a thin Android shell (Compose UI, DataStore, SAF import/export, Koin). Foreground-only polling, no GMS, no tracking SDKs.

**Tech Stack:** Kotlin 2.3.21, Compose Multiplatform 1.9.0, Ktor 3.5.0 (OkHttp engine, MockEngine in tests), kaml (YAML), kotlinx-serialization 1.9.0, Koin 4.1.0, coroutines 1.10.2, AGP 9.1.1, Gradle 9.3.1, JDK 21.

**Spec:** `docs/superpowers/specs/2026-10-13-ticker-android-design.md`

**Reference Go sources** (port, do not reinvent — all behaviors in this plan are verified against these):
- `internal/common/common.go` — Config, Lot, Asset/Quote/Position structs
- `internal/asset/asset.go` + `internal/asset/currency.go` — aggregation, `getCurrencyRateByUse`
- `internal/sorter/sorter.go` — sort modes
- `internal/monitor/yahoo/unary/*` — quote endpoint, session handshake, currency rates
- `internal/monitor/coinbase/unary/unary.go` — Coinbase REST transform
- `internal/cli/symbol/symbol.go` — `.X` map, CSV parse
- `docs/examples/.ticker.yaml` + README config block — fixture source

## Global Constraints

- No GMS/Firebase/Play Services/WorkManager/analytics/crashlytics dependency in any variant; verified by the CI dependency-scan job (Task 11).
- `applicationId io.orangerabbit.ticker`; minSdk 28; targetSdk 36.
- Foreground refresh interval floor: `max(config.interval, 30)` seconds. Config `interval` itself is never rewritten — only the poller applies the floor.
- Data sources exactly: Yahoo `query1.finance.yahoo.com/v7/finance/quote` (keyless, cookie+crumb session), Coinbase `api.coinbase.com/api/v3/brokerage/market/products` (unauthenticated), `.X` map from `https://raw.githubusercontent.com/achannarasappa/ticker-static/master/symbols.csv`. No other network destinations.
- No WebSocket in v1. No futures support in v1 (both Yahoo and Coinbase futures branches are omitted; Coinbase `product_type == "FUTURE"` products are skipped, not crashed on).
- Deprecated config keys `show-holdings` and `holdings` are accepted aliases for `show-positions` / `lots`.
- Config `colors:` key is parsed and ignored; `cache:` parsed and ignored.
- Every task: conventional commit, one logical change; `./gradlew :shared:jvmTest` must stay green.
- Local builds run under `nix develop` (JDK 21 only; no Android SDK locally — `assembleDebug`/`assembleRelease` run in CI).

## Review Focus

Failure modes the spec implies but task tests may miss — each pinned to a test below:

1. **Malformed `.ticker.yaml`** (bad indentation, wrong types, unknown keys) → user sees a readable error state, never a crash or silent empty config. Pinned: Task 2 parse-failure tests; surfaced: Task 10 error UI test.
2. **Yahoo session rejection mid-session** (cookies expire; Yahoo returns 401/403) → client refreshes session once and retries, then surfaces an error instead of showing stale-forever data. Pinned: Task 4 retry test.
3. **Empty/zero quote data** (Yahoo returns a symbol with `regularMarketPrice.raw = 0`, delisted symbol missing from response) → position math uses zero-guards (`calculateChangePercent`), missing symbols are reported as unavailable, not NaN. Pinned: Task 3 zero-guard test, Task 8 missing-symbol test.
4. **Negative quantities / zero quantity lots** → shorts net correctly, `unitValue`/`unitCost` stay 0 when quantity is 0 (no division by zero). Pinned: Task 3.
5. **Coinbase price-change percentage strings** (e.g. `"-1.2345"`, missing fields) parse to absolute change or 0 without crashing. Pinned: Task 6.

---

### Task 1: Project scaffold and CI

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts` (root, empty plugins), `gradle/libs.versions.toml`, `gradle.properties`, `flake.nix`
- Create: `shared/build.gradle.kts`, `shared/src/commonMain/kotlin/io/orangerabbit/ticker/Shared.kt` (placeholder object — removed in Task 2), `shared/src/jvmTest/kotlin/io/orangerabbit/ticker/SmokeTest.kt` (assert the placeholder exists — replaced in Task 2)
- Create: `composeApp/build.gradle.kts`, `composeApp/src/androidMain/AndroidManifest.xml`, `composeApp/src/androidMain/kotlin/io/orangerabbit/ticker/MainActivity.kt` (Activity hosting a "ticker" Text — replaced in Task 9), `composeApp/src/commonMain/composeResources` (empty dir placeholder not committed)
- Create: `.releaserc.json`, `.github/workflows/ci.yml`, `.github/workflows/release.yml`

**Interfaces:**
- Consumes: nothing.
- Produces: Gradle modules `:shared` (KMP: androidTarget + jvm, deps kaml/ktor-client-core/ktor-client-okhttp/koin-core/kotlinx-serialization-json/coroutines) and `:composeApp` (Android app, CMP); version properties `tickerVersionName`/`tickerVersionCode` on `:composeApp` (clanker's `clankerVersionName`/`clankerVersionCode` pattern); CI runs `:shared:jvmTest` + `:composeApp:assembleDebug` and uploads `composeApp-debug.apk`; release workflow = clanker's `release.yml` with names/secrets renamed (`TICKER_KEYSTORE_B64`, `TICKER_KEYSTORE_PASSWORD`, `TICKER_KEY_ALIAS`, `TICKER_KEY_PASSWORD`, APK `ticker-v<version>.apk`).

- [ ] **Step 1: Write version catalog.** Copy clanker's `gradle/libs.versions.toml` versions verbatim (kotlin 2.3.21, composeMultiplatform 1.9.0, ktor 3.5.0, koin 4.1.0, kotlinxSerialization 1.9.0, coroutines 1.10.2, agp 9.1.1, activityCompose 1.12.0); add `kaml = "0.72.0"` → `kaml = { module = "com.charleskorn.kaml:kaml", version.ref = "kaml" }`; drop clanker-only entries (sqldelight, tink, markdownRenderer). Gradle wrapper 9.3.1.
- [ ] **Step 2: Write module build files + manifests** following clanker's `shared/build.gradle.kts` (androidTarget jvmTarget 11 + `jvm()` target) and `composeApp/build.gradle.kts` (applicationId `io.orangerabbit.ticker`, minSdk 28, targetSdk 36, versionName/versionCode from `tickerVersionName`/`tickerVersionCode` gradle properties, release signing via env-provided keystore like clanker).
- [ ] **Step 3: Write `flake.nix`** — clanker's verbatim.
- [ ] **Step 4: Write placeholder sources + smoke test** (`SmokeTest`: `assertNotNull(Shared.placeholder)`).
- [ ] **Step 5: Run locally**

Run: `nix develop -c ./gradlew :shared:jvmTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Write `.releaserc.json`, `ci.yml`, `release.yml`** per Interfaces above (clanker's files with renames; semantic-release `@semantic-release/commit-analyzer`, `@semantic-release/release-notes-generator`, `@semantic-release/github`).
- [ ] **Step 7: Commit** `chore: gradle scaffold, nix shell, ci and release workflows`

### Task 2: Config model and `.ticker.yaml` parsing

**Files:**
- Create: `shared/src/commonMain/kotlin/io/orangerabbit/ticker/config/TickerConfig.kt`, `config/YamlConfig.kt` (kaml mapping + export)
- Test: `shared/src/jvmTest/kotlin/io/orangerabbit/ticker/config/YamlConfigTest.kt`
- Modify: delete Task 1's `Shared.kt` + `SmokeTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces (used by Tasks 3, 7, 8, 9):
  - `data class Lot(val symbol: String, val unitCost: Double = 0.0, val quantity: Double = 0.0, val fixedCost: Double = 0.0)`
  - `data class ConfigGroup(val name: String, val watchlist: List<String> = emptyList(), val lots: List<Lot> = emptyList())`
  - `data class TickerConfig(val interval: Int = 5, val watchlist: List<String> = emptyList(), val lots: List<Lot> = emptyList(), val groups: List<ConfigGroup> = emptyList(), val showTags: Boolean = false, val showFundamentals: Boolean = false, val showSeparator: Boolean = false, val showSummary: Boolean = false, val showPositions: Boolean = false, val sort: String? = null, val currency: String? = null, val currencySummaryOnly: Boolean = false, val currencyDisableUnitCostConversion: Boolean = false)`
  - `fun parseTickerConfig(yaml: String): Result<TickerConfig>` (kaml, `ignoreUnknownKeys` — `colors`/`cache`/`debug` accepted and dropped)
  - `fun TickerConfig.toYaml(): String`
  - `fun TickerConfig.withDefaultGroup(): TickerConfig` — top-level watchlist/lots become group `name = "default"` prepended to `groups`; returns config unchanged when no top-level entries exist.

- [ ] **Step 1: Write failing tests.** Fixtures as string literals in the test file:
  - `parses README example` — the CLI README config block (watchlist incl. `NET`,`BTC-USD`,`SOL.X`,`BIT-30MAY25-CDE.CB`, three lots incl. duplicate-symbol `ARKW`, `fixed_cost: 7.00`, groups with crypto watchlist, `show-*` flags, `interval: 5`, `currency: USD`); assert every field incl. `fixedCost == 7.00` and group contents.
  - `deprecated aliases map` — `show-holdings: true` → `showPositions == true`; `holdings:` list → group lots.
  - `round trip preserves semantics` — `parseTickerConfig(config.toYaml()) == config` for the README example (compare via `withDefaultGroup()` on both).
  - `malformed yaml returns failure` — `parseTickerConfig("watchlist: [unclosed")` is failure; `.exceptionOrNull()` message non-blank.
  - `negative quantity preserved` — `quantity: -5` lot parses to `-5.0`.
- [ ] **Step 2: Run** `nix develop -c ./gradlew :shared:jvmTest --tests "*YamlConfigTest*"` — Expected: FAIL (unresolved reference).
- [ ] **Step 3: Implement** the five declarations in the two files. kaml mapping is hand-rolled (deserialize to `@Serializable` raw DTOs with `classDiscriminator`-free nullable fields, then map to domain types with the alias rules) — do NOT force kaml to bind domain types directly, aliases and defaults don't fit.
- [ ] **Step 4: Run** same command — Expected: PASS.
- [ ] **Step 5: Commit** `feat: ticker config model and yaml parsing`

### Task 3: Domain model, aggregation, sorter

**Files:**
- Create: `shared/src/commonMain/kotlin/io/orangerabbit/ticker/asset/Types.kt` (enums + quote/position data classes), `asset/Aggregation.kt`, `asset/Sorter.kt`
- Test: `shared/src/jvmTest/kotlin/io/orangerabbit/ticker/asset/AggregationTest.kt`, `asset/SorterTest.kt`

**Interfaces:**
- Consumes: `TickerConfig`, `Lot` (Task 2).
- Produces (used by Tasks 4–8, 10):
  - `enum class AssetClass { CASH, STOCK, CRYPTOCURRENCY, CURRENCY, UNKNOWN }`, `enum class QuoteSource { YAHOO, COINBASE, UNKNOWN }`, `enum class ExchangeState { OPEN, PREMARKET, POSTMARKET, CLOSED }`
  - `data class Currency(val from: String, val to: String, val rate: Double = 0.0)`
  - `data class QuotePrice(val price: Double = 0.0, val prevClose: Double = 0.0, val open: Double = 0.0, val dayHigh: Double = 0.0, val dayLow: Double = 0.0, val change: Double = 0.0, val changePercent: Double = 0.0)`
  - `data class QuoteExtended(val fiftyTwoWeekHigh: Double = 0.0, val fiftyTwoWeekLow: Double = 0.0, val marketCap: Double = 0.0, val volume: Double = 0.0)`
  - `data class ExchangeInfo(val name: String = "", val delay: Double = 0.0, val state: ExchangeState = ExchangeState.OPEN, val isActive: Boolean = true, val isRegularTradingSession: Boolean = true)`
  - `data class Meta(val isVariablePrecision: Boolean = false, val orderIndex: Int = 0, val symbolInSourceApi: String = "")`
  - `data class AssetQuote(val name: String, val symbol: String, val assetClass: AssetClass, val currency: Currency, val quotePrice: QuotePrice, val quoteExtended: QuoteExtended, val quoteSource: QuoteSource, val exchange: ExchangeInfo, val meta: Meta)`
  - `data class PositionChange(val amount: Double = 0.0, val percent: Double = 0.0)`
  - `data class Position(val value: Double = 0.0, val cost: Double = 0.0, val quantity: Double = 0.0, val unitValue: Double = 0.0, val unitCost: Double = 0.0, val dayChange: PositionChange = PositionChange(), val totalChange: PositionChange = PositionChange(), val weight: Double = 0.0)`
  - `data class PositionSummary(val value: Double = 0.0, val cost: Double = 0.0, val totalChange: PositionChange = PositionChange(), val dayChange: PositionChange = PositionChange())`
  - `data class Asset(val name: String, val symbol: String, val assetClass: AssetClass, val currency: Currency, val position: Position, val quotePrice: QuotePrice, val quoteExtended: QuoteExtended, val quoteSource: QuoteSource, val exchange: ExchangeInfo, val meta: Meta)`
  - `data class CurrencyRates(val rates: Map<String, CurrencyRate>)`, `data class CurrencyRate(val from: String, val to: String, val rate: Double)`
  - `data class Portfolio(val assets: List<Asset>, val summary: PositionSummary)`
  - `fun getAssets(config: TickerConfig, group: ConfigGroup, quotes: List<AssetQuote>, rates: CurrencyRates): Portfolio` — port of `GetAssets` incl. `getLots` (first-lot wins for orderIndex; later lots `quantity +=`, `cost += quantity*unitCost`), `getPositionFromAssetQuote`, `getCurrencyRateByUse` (port from `asset/currency.go` verbatim: CURRENCY class → rates 1.0; `rate == 0` → 1.0; `currency-summary-only` → QuotePrice 1.0/Summary rate; `currency-disable-unit-cost-conversion` → PositionCost 1.0; currency set → QuotePrice rate; else Summary rate), `convertAssetQuotePriceCurrency`, `updatePositionWeights` (weights only when `summary.value != 0`, computed from currency-converted summary values).
  - `fun newSorter(sort: String?): (List<Asset>) -> List<Asset>` — `"alpha"` / `"value"` / `"user"` / default change-sort; stable sorts; `value`/`change` split active/inactive (`ExchangeInfo.isActive`), active first; inactive assets keep their relative order.
- [ ] **Step 1: Write failing tests** with hand-computed values:
  - `two lots aggregate and weight` — aggregation rule (port `getLots` exactly): first lot's cost = `(unitCost*quantity) + fixedCost`; each subsequent same-symbol lot adds `quantity*unitCost` only. Lots (20 @ 152.25, 20 @ 145.35), price 160.00 → qty 40, cost `5952.00`, value `6400.00`, totalChange `448.00`, percent `448/5952*100`; unitValue `160.00`, unitCost `148.80`.
  - `fixed cost on first lot` — lot with `fixed_cost: 7.00` → cost includes it.
  - `negative quantity nets` — lots (35@146.00, -5@150.00) → qty 30, cost `146*35 + (-5*150) = 4360`, value at 160 → 4800, unitValue 160, unitCost `4360/30`.
  - `zero quantity no division` — lot qty 0 → `unitValue == 0.0 && unitCost == 0.0` (guard `quantity != 0`), no NaN anywhere in `Portfolio`.
  - `weights sum to 100` — two positions with different values in summary currency; third asset with `summary.value == 0` keeps weight 0.
  - `currency-summary-only converts summary only` — GBP asset rate 1.25, config currency USD, `currencySummaryOnly = true` → `quotePrice.price` unchanged (pence), summary in USD; `currencyDisableUnitCostConversion` → `position.cost` not converted while value is.
  - `currency class skips conversion` — asset with class CURRENCY → all rates 1.0 regardless of config.
- Sorter tests: `change sort puts inactive last` (construct assets with `isActive = false` and higher changePercent — inactive still after actives); `alpha sort` ; `value sort desc`; `user sort by orderIndex` (set `Meta.orderIndex`); unknown string → change sort.
- [ ] **Step 2: Run** `nix develop -c ./gradlew :shared:jvmTest --tests "*asset*"` — Expected: FAIL.
- [ ] **Step 3: Implement** Types.kt, Aggregation.kt, Sorter.kt (straight ports; no futures branch — `QuoteFutures` deliberately absent from v1 model).
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `feat: quote domain model, position aggregation, sorters`

### Task 4: Yahoo Finance client

**Files:**
- Create: `shared/src/commonMain/kotlin/io/orangerabbit/ticker/source/yahoo/YahooModels.kt`, `source/yahoo/YahooClient.kt`, `source/yahoo/YahooSession.kt`
- Test: `shared/src/jvmTest/kotlin/io/orangerabbit/ticker/source/yahoo/YahooClientTest.kt`, `source/yahoo/YahooTransformTest.kt`
- Create: `shared/src/jvmTest/resources/yahoo/quote-regular.json`, `yahoo/quote-post.json` (fixtures)

**Interfaces:**
- Consumes: `AssetQuote`, `QuoteSource.YAHOO` (Task 3); ktor `HttpClient` (injected, MockEngine in tests).
- Produces (used by Tasks 5, 7, 8):
  - `interface YahooSessionStore { fun get(): YahooSession?; fun put(session: YahooSession) }`, `data class YahooSession(val cookies: List<Pair<String, String>>, val crumb: String)` (in-memory impl `InMemorySessionStore` for tests; DataStore-backed impl in Task 9).
  - `class YahooClient(http: HttpClient, sessionStore: YahooSessionStore) : suspend fun getQuotes(symbols: List<String>): Result<List<AssetQuote>>` — endpoint/headers/query per spec (fields list, `formatted=true&lang=en-US&region=US&corsDomain=finance.yahoo.com&crumb=`); request headers exactly: `Authority: query1.finance.yahoo.com`, `Accept: */*`, `Accept-Language: en-US,en;q=0.9`, `Origin: https://query1.finance.yahoo.com`, `User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36`; session reuse from store, refresh (GET `https://finance.yahoo.com` for cookies → GET `https://query2.finance.yahoo.com/v1/test/getcrumb` with `Authority: query2.finance.yahoo.com`, `Content-Type: text/plain`, `Origin: https://finance.yahoo.com`) on: no session, or status ≥ 400 → **retry quote request exactly once** after refresh; EU consent branch when cookie response is 3xx with `Location` containing `/consent` (follow CLI `getCookieEU`: extract sessionId + csrfToken, POST form `csrfToken, sessionId, namespace=yahoo, agree=agree` to `https://consent.yahoo.com/v2/collectConsent?sessionId=...` with GUCS cookies, Chrome sec-fetch header set); error when A3 cookie missing (`isRequiredCookieSet`).
  - Response models per `models.go`: `quoteResponse.result[]` with `ResponseFieldFloat { raw, fmt }`, `ResponseFieldString { raw, fmt }`; kaml/kotlinx `ignoreUnknownKeys = true`.
  - Transform per `helpers-quote.go`: CRYPTOCURRENCY/CURRENCY quoteType → classes (default STOCK; others → UNKNOWN); variable precision for crypto; market-state branches: `REGULAR` unchanged; `POST|POSTPOST` with postMarketPrice==0 → inactive session flag only; `PRE` with preMarketPrice==0 → inactive; `POST*` with postMarketPrice → price/change from post + regular combined; `PRE` with preMarketPrice → pre values; postMarketPrice != 0 fallback → post values + inactive; else inactive.
- [ ] **Step 1: Write fixture JSON** — `quote-regular.json`: two quotes (AAPL stock USD `marketState: "REGULAR"`, BTC-USD crypto `marketState: "REGULAR"`), realistic field values; `quote-post.json`: NVDA `marketState: "POST"` with postMarketPrice/change/changePercent set.
- [ ] **Step 2: Write failing transform tests** — parse `quote-regular.json` → 2 `AssetQuote`s with asserted price/change/class/currency/`isVariablePrecision` (BTC true, AAPL false); parse `quote-post.json` → price == postMarketPrice, change == post+regular change sum, `isRegularTradingSession == false`.
- [ ] **Step 3: Run** `nix develop -c ./gradlew :shared:jvmTest --tests "*Yahoo*"` — Expected: FAIL.
- [ ] **Step 4: Implement** models + transform (pure functions `transformResponseQuotes(...)`) + client.
- [ ] **Step 5: Run** — Expected: PASS.
- [ ] **Step 6: Write failing session-flow tests** (MockEngine queueing responses, assertions on the recorded requests):
  - `first request establishes session` — mock: cookie response (Set-Cookie incl. A3) → crumb response body `foo.bar` → quote response; assert quote request URL contains `crumb=foo.bar`, carries cookie header with A3; session persisted to store.
  - `session reuse skips handshake` — store pre-seeded; mock records requests → first outgoing request is the quote call, no cookie/crumb fetch.
  - `unauthorized refreshes and retries once` — quote returns 401 → handshake requests → quote returns 200; total quote attempts == 2.
  - `retry exhaustion surfaces error` — quote 401 both times → `Result.failure`.
  - `missing A3 cookie fails` — cookie response without A3 → failure with "A3" in message, no quote request made.
  - `EU consent flow` — cookie GET responds 303 `Location: https://consent.yahoo.com/v2/collectConsent?sessionId=S&csrf...`; consent-redirect GET returns redirect with GUCS Set-Cookie; POST consent 200 with A3 Set-Cookie → crumb → quote; assert POST body form-encodes `csrfToken`, `sessionId`, `namespace=yahoo`, `agree=agree`.
- [ ] **Step 7: Run** — Expected: PASS.
- [ ] **Step 8: Commit** `feat: yahoo finance quote client with session handshake`

### Task 5: Currency rates from Yahoo

**Files:**
- Modify: `shared/src/commonMain/kotlin/io/orangerabbit/ticker/source/yahoo/YahooClient.kt` (add method), `yahoo/YahooModels.kt` if needed
- Create: `shared/src/commonMain/kotlin/io/orangerabbit/ticker/asset/MinorUnits.kt`
- Test: `shared/src/jvmTest/kotlin/io/orangerabbit/ticker/source/yahoo/CurrencyRatesTest.kt`

**Interfaces:**
- Consumes: `YahooClient` session plumbing (Task 4), `CurrencyRates` (Task 3).
- Produces: `suspend fun YahooClient.getCurrencyRates(fromCurrencies: List<String>, toCurrency: String): Result<CurrencyRates>` — port of `GetCurrencyRates`: dedupe pairs `FROMTO=X`, skip empty/same-currency, fetch via same quote endpoint (fields `currency,regularMarketPrice`), derive `from` by stripping `=X` then `toCurrency` suffix, add minor-unit entries (`MinorUnitForCurrencyCode` table — the 11 majors AUD/CAD/CHF/CNY/EUR/GBP/HKD/INR/TWD/USD/ZAR, minor code like `GBp`, rate scaled `* 10^-2`).

- [ ] **Step 1: Write failing tests** — fixture response for `["EURUSD=X","GBPUSD=X"]` → rates EUR 1.10, GBP 1.25, GBp 0.0125 (scaled); `toCurrency == "USD"` with input containing `"USD"` → USD omitted from result; unknown minor currency (JPY) → no minor entry.
- [ ] **Step 2: Run** `--tests "*CurrencyRatesTest*"` — Expected: FAIL.
- [ ] **Step 3: Implement** `MinorUnits.kt` + method.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `feat: yahoo currency rate fetch with minor units`

### Task 6: Coinbase client

**Files:**
- Create: `shared/src/commonMain/kotlin/io/orangerabbit/ticker/source/coinbase/CoinbaseModels.kt`, `source/coinbase/CoinbaseClient.kt`
- Test: `shared/src/jvmTest/kotlin/io/orangerabbit/ticker/source/coinbase/CoinbaseTransformTest.kt`
- Create: `shared/src/jvmTest/resources/coinbase/products.json`

**Interfaces:**
- Consumes: `AssetQuote`, `QuoteSource.COINBASE`, `AssetClass.CRYPTOCURRENCY` (Task 3).
- Produces: `class CoinbaseClient(http: HttpClient) : suspend fun getQuotes(productIds: List<String>): Result<List<AssetQuote>>` — `GET https://api.coinbase.com/api/v3/brokerage/market/products?product_ids=<id1>&product_ids=<id2>`; response `{ "products": [...] }`; transform per CLI: spot only (`product_type == "FUTURE"` skipped); `symbol = base_display_symbol + ".CB"`, `name = base_name`, price/`price_percentage_change_24h`/`volume_24h` strings → doubles (parse failure → 0.0), `change = price * changePercent / 100`, `isActive = status == "online"`, `currency.from = quote_currency_id` uppercased, class CRYPTOCURRENCY, `isVariablePrecision = true`, `QuoteSource.COINBASE`.

- [ ] **Step 1: Write fixture** `products.json` — BTC-USD online with price `"107500.12"`, change `"-1.2345"`, volume `"8123.45"`; SOL-USD online; one FUTURE product; one product with missing/empty price fields.
- [ ] **Step 2: Write failing tests** — BTC transform asserts (symbol `BTC.CB`, price 107500.12, changePercent -1.2345, change = `107500.12 * -1.2345 / 100` ≈, isActive true); FUTURE skipped; missing-price product → price 0.0, no exception.
- [ ] **Step 3: Run** `--tests "*Coinbase*"` — Expected: FAIL.
- [ ] **Step 4: Implement** models + client.
- [ ] **Step 5: Run** — Expected: PASS.
- [ ] **Step 6: Commit** `feat: coinbase market products quote client`

### Task 7: Symbol routing and `.X` shorthand

**Files:**
- Create: `shared/src/commonMain/kotlin/io/orangerabbit/ticker/source/Symbols.kt` (CSV parse, map type, routing)
- Test: `shared/src/jvmTest/kotlin/io/orangerabbit/ticker/source/SymbolsTest.kt`
- Create: `shared/src/jvmTest/resources/symbols/symbols.csv` (small fixture incl. header-less rows, dupes)

**Interfaces:**
- Consumes: `ConfigGroup`, `TickerConfig` (Task 2); `QuoteSource` (Task 3).
- Produces (used by Task 8):
  - `data class SymbolSourceMap(val tickerSymbol: String, val sourceSymbol: String, val source: QuoteSource)`
  - `typealias TickerSymbolMap = Map<String, SymbolSourceMap>`
  - `fun parseSymbolsCsv(body: String): TickerSymbolMap` — rows `[ticker(.X), sourceSymbol, sourceId]`; sourceId `"cb"` → COINBASE else UNKNOWN; first occurrence wins on duplicate ticker (CSV has dupes).
  - `data class ResolvedGroup(val group: ConfigGroup, val yahooSymbols: List<String>, val coinbaseProducts: List<String>, val unresolved: List<String>)`
  - `fun resolveGroup(group: ConfigGroup, symbolMap: TickerSymbolMap): ResolvedGroup` — no suffix → yahoo; `.CB` suffix → strip suffix, coinbase product; `.X` → exact lookup with the `SOL.X`-style key (map key includes `.X`; the resolved `sourceSymbol` is the source-native symbol e.g. `SOL-USD`), source COINBASE → coinbase bucket, UNKNOWN source or missing key → `unresolved`. Watchlist order preserved per bucket; symbols in `lots` absent from watchlist appended to watchlist first (CLI implicit-watch rule, deduped case-insensitive).

- [ ] **Step 1: Write failing tests** — routing: `AAPL`→yahoo, `BTC.CB`→coinbase product `BTC`, `ACH.X` with fixture row `ACH.X,ACH-USD,cb` → coinbase `ACH-USD`, `10SET.X` (fixture `10SET.X,,xx`) → unresolved; implicit lot symbol added once (config with lots ABC, watchlist ABC,DEF → watchlist becomes ABC,DEF); CSV parse with duplicate row keeps first; empty body → empty map (not error).
- [ ] **Step 2: Run** `--tests "*Symbols*"` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `feat: symbol routing with ticker-static .X map`

### Task 8: QuotePoller

**Files:**
- Create: `shared/src/commonMain/kotlin/io/orangerabbit/ticker/poll/QuotePoller.kt`
- Test: `shared/src/jvmTest/kotlin/io/orangerabbit/ticker/poll/QuotePollerTest.kt`

**Interfaces:**
- Consumes: everything above — `TickerConfig.withDefaultGroup()`, `resolveGroup`, `YahooClient.getQuotes/getCurrencyRates`, `CoinbaseClient.getQuotes`, `parseSymbolsCsv` via a new `suspend fun fetchSymbolMap(): TickerSymbolMap` `SymbolsClient` wrapper (fetch CSV, 24h in-memory TTL, on fetch failure fall back to `emptyMap()`), `getAssets`, `newSorter` (Task 3).
- Produces (used by Task 9/10 UI):
  - `data class PollState(val group: ConfigGroup? = null, val assets: List<Asset> = emptyList(), val summary: PositionSummary? = null, val unavailable: List<String> = emptyList(), val error: String? = null, val isLoading: Boolean = false, val lastRefreshAt: Long = 0)`
  - `class QuotePoller(yahoo, coinbase, symbols: SymbolsClient, clock: () -> Long = { System.currentTimeMillis() })`:
    - `val state: StateFlow<PollState>`
    - `suspend fun start(config: TickerConfig, groupIndex: Int = 0)` — cancels prior loop; resolves group; fetches symbol map; immediately refreshes; then loops at `max(config.interval, 30).seconds` while active. Non-zero results missing from responses land in `unavailable` (union across sources); any source failure sets `error` (previous quotes retained — stale data visible with error banner, per Review Focus 2).
    - `suspend fun selectGroup(groupIndex: Int)` — switches group, immediate refresh.
    - `suspend fun refreshNow()` — immediate one-off refresh.
    - `fun stop()`.
  - Rates: each cycle, after quotes arrive, derive currency set from quotes' `currency.from`, call `getCurrencyRates(from, config.currency ?: "USD")`; on rates failure continue unconverted (rate 0 → the Task 3 guards apply).

- [ ] **Step 1: Write failing tests** (kotlinx-coroutines-test `runTest`, fake clients with scripted responses, virtual clock):
  - `initial refresh then interval ticks` — interval 30 → 3 quote calls at t=0, 30, 60 (assert via fake call counter).
  - `interval floor` — config interval 5 → call spacing 30s.
  - `group switch refreshes immediately` — `selectGroup(1)` → new group's symbols requested.
  - `source failure keeps stale quotes and sets error` — yahoo fails on 2nd tick → `error != null`, assets still previous.
  - `missing symbol lands in unavailable` — yahoo returns only one of two symbols → other in `unavailable`.
  - `rates merged into portfolio` — fake rate 2.0 with currency config → summary value doubles.
  - `stop halts loop` — after stop, no further calls.
- [ ] **Step 2: Run** `--tests "*QuotePoller*"` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `feat: foreground quote poller with interval floor`

### Task 9: Android shell — DataStore config, SAF import/export, group tabs

**Files:**
- Create: `composeApp/src/androidMain/kotlin/io/orangerabbit/ticker/App.kt` (Application with Koin), `ticker/ConfigStore.kt`, `ticker/ConfigRepository.kt`, `composeApp/src/commonMain/kotlin/io/orangerabbit/ticker/ui/TickerTheme.kt`, `ui/GroupTabsScreen.kt` (tabs + "no config imported" empty state), `ui/ConfigImport.kt` (SAF picker wiring)
- Modify: `composeApp/src/androidMain/kotlin/io/orangerabbit/ticker/MainActivity.kt` (replace placeholder), `composeApp/src/androidMain/AndroidManifest.xml` (application name, theme)
- Test: config-store logic stays in `:shared`: Create `shared/src/jvmTest/kotlin/io/orangerabbit/ticker/config/ConfigJsonTest.kt`

**Interfaces:**
- Consumes: `parseTickerConfig`/`toYaml`/`withDefaultGroup` (Task 2).
- Produces (used by Task 10):
  - `interface ConfigRepository { val config: StateFlow<TickerConfig?>; suspend fun import(uriString: String, yaml: String): Result<Unit>; suspend fun export(uriString: String): Result<Unit>; suspend fun save(config: TickerConfig) }` — `ConfigRepositoryImpl` over DataStore `Preferences` (key `config_json`), normalized JSON via kotlinx-serialization (DTO mirroring `TickerConfig`; NOT the YAML — YAML round-trip via SAF export re-serializes with `toYaml()`).
  - `fun parseTickerConfigJson(json: String): Result<TickerConfig>`, `fun TickerConfig.toJson(): String` (shared, tested).
  - Koin module `appModule` providing repository, session store (DataStore-backed `YahooSessionStore` with 24h TTL timestamp), `HttpClient(OkHttp)`, `QuotePoller`.
- [ ] **Step 1: Write failing tests** in `:shared` — `TickerConfig.toJson()/parseTickerConfigJson()` round-trip incl. groups + lots + every flag; parse of `""` → failure.
- [ ] **Step 2: Run** `--tests "*ConfigJsonTest*"` — Expected: FAIL.
- [ ] **Step 3: Implement** JSON DTOs + mapping in `:shared`. Run — PASS.
- [ ] **Step 4: Commit** `feat: config json serialization for datastore`
- [ ] **Step 5: Implement Android shell** — DataStore repository (SAF via `androidx.documentfile`? No: `contentResolver.openInputStream/openOutputStream` with persisted URI permissions), import → parse → save (failure → user-visible message via returned `Result`), export → `toYaml()`; `GroupTabsScreen` with tab row (from `config.withDefaultGroup().groups`) + empty state prompting import; MainActivity sets content with theme; Koin wiring. CI is the only build check for this task.
- [ ] **Step 6: Run** `nix develop -c ./gradlew :shared:jvmTest` — Expected: PASS (shell compiles in CI only).
- [ ] **Step 7: Commit** `feat: android shell with datastore config and saf import/export`

### Task 10: Quote UI

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/orangerabbit/ticker/ui/GroupTabsScreen.kt` (attach quote list), Create: `ui/QuoteRow.kt`, `ui/PositionsSection.kt`, `ui/SummaryBar.kt`, `ui/HomeViewModel.kt` (collects `QuotePoller.state`, lifecycle start/stop)
- Test: no new `:shared` logic (rendering only); verification is CI build + device smoke below.

**Interfaces:**
- Consumes: `PollState`, `Asset`, `PositionSummary` (Task 8); config flags (Task 2); `TickerConfig.withDefaultGroup()` group cycling.
- Produces: `HomeViewModel(poller, configRepo)` exposing `state: StateFlow<PollState>`, `refresh()`, `selectGroup(i)`, `start()`/`stop()` lifecycle hooks.

- [ ] **Step 1: Implement UI** —
  - Quote rows: symbol (bold), name (muted, truncated), right-aligned price + day-change `(+/-)amount (pct%)` colored green/red (use `--success`-equivalent Material colors; BTC rows with symbol starting `BTC` may use `#F7931A` accent); `showTags` → small row `currency · exchange · delay-text`; `showFundamentals` → open / prev close / day range line; crypto `isVariablePrecision` → 6-decimal price format, else 2.
  - Positions section when `showPositions`: per asset with non-zero quantity: weight %, avg cost (unitCost), quantity, day change, total gain/loss (amount + %).
  - Summary bar when `showSummary`: total value, day change (amount + %), total change (amount + %) — pinned top under tabs.
  - Sort: `config.sort` via `newSorter` already applied in poller — UI renders poller order; no UI sort switch in v1.
  - Pull-to-refresh (Material3 `PullToRefreshBox`) → `refresh()`; loading indicator from `PollState.isLoading`; error banner with Retry when `error != null`; `unavailable` symbols listed subtly at list end.
  - Add symbol sheet: text field → appends to default group watchlist via `ConfigRepository.save` (poller restarts via config flow observation); Lot editor sheet: symbol/quantity/unitCost/fixedCost fields, add/update/delete. Group tab swipe: horizontal pager cycling groups (TAB semantics).
- [ ] **Step 2: Run** `nix develop -c ./gradlew :shared:jvmTest` — Expected: PASS (regression only).
- [ ] **Step 3: Commit** `feat: quote, positions, and summary UI`
- [ ] **Step 4: Device smoke (manual gate for user)** — CI-built debug APK installed on GrapheneOS device; import real `.ticker.yaml`; confirm quotes/positions/summary match desktop `ticker` run side-by-side on the same watchlist. No local emulator exists on this host — user performs this check; any mismatch is a bug report back into the plan.

### Task 11: GMS-free verification, README, final gate

**Files:**
- Modify: `.github/workflows/ci.yml` (add dependency-scan step), Create: `README.md`
- Test: CI step itself.

- [ ] **Step 1: Add CI step** after jvmTest:
  ```yaml
  - name: Verify no Google/Tracking dependencies
    run: |
      ./gradlew :composeApp:dependencies --configuration releaseRuntimeClasspath > deps.txt
      if grep -Ei "firebase|play-services|com.google.android.gms|crashlytics|analytics|ads" deps.txt; then
        echo "Forbidden dependency found" && exit 1
      fi
  ```
  (Matches `com.google.android.gms` and tracking SDKs; `com.google.android.material` etc. would also match `com.google` — the grep above deliberately narrows to forbidden artifacts; androidx is unaffected.)
- [ ] **Step 2: Write README** — what it is, port of `achannarasappa/ticker`, same config file, build (`nix develop -c ./gradlew :shared:jvmTest`), install (CI APK artifacts / release APK), GrapheneOS note (no GMS required), privacy statement (network destinations list from Global Constraints).
- [ ] **Step 3: Push branch, open PR, verify CI green** (test + debug build + dependency scan).
- [ ] **Step 4: Commit** `docs: readme and gms-free ci gate`
