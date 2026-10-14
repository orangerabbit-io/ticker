package io.orangerabbit.ticker

import android.app.Application
import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.orangerabbit.ticker.poll.QuotePoller
import io.orangerabbit.ticker.source.SymbolMapCache
import io.orangerabbit.ticker.source.SymbolSearch
import io.orangerabbit.ticker.source.SymbolsClient
import io.orangerabbit.ticker.source.coinbase.CoinbaseClient
import io.orangerabbit.ticker.source.coinbase.CoinbaseQuoteSource
import io.orangerabbit.ticker.source.yahoo.YahooClient
import io.orangerabbit.ticker.source.yahoo.YahooQuoteSource
import io.orangerabbit.ticker.source.yahoo.YahooSessionStore
import io.orangerabbit.ticker.ui.HomeViewModel
import org.koin.core.context.startKoin
import org.koin.dsl.bind
import org.koin.dsl.module

// appModule wires the app graph: quote sources over the OkHttp engine, the symbol map
// client, the foreground quote poller, and the DataStore-backed config repository.
val appModule = module {
    // DataStoreSessionStore: the Yahoo handshake session (cookies + crumb) persists in
    // DataStore with a 24h TTL and survives process death; stale entries are ignored
    // and re-handshaken.
    single<YahooSessionStore> { DataStoreSessionStore(get()) }
    // No HttpCookies plugin and OkHttp's default no-cookies jar: YahooClient performs
    // its own handshake and must not get automatic cookie storage. Automatic redirect
    // following is disabled on the shared client so no consumer (Coinbase, SymbolsClient)
    // can be redirected to a destination outside the spec's allowlist; YahooClient
    // follows and validates its own redirects against that allowlist.
    single { HttpClient(OkHttp) { followRedirects = false } }
    // The concrete client types are the primary bean so SymbolSearch can resolve them;
    // the QuoteSource interfaces stay resolvable through the secondary bindings used by
    // the poller.
    single { YahooClient(get(), get()) } bind YahooQuoteSource::class
    single { CoinbaseClient(get()) } bind CoinbaseQuoteSource::class
    // DataStoreSymbolMapCache persists the .X symbol map snapshot (CSV + fetch time)
    // with the same 24h TTL policy SymbolsClient applies to its in-memory cache.
    single<SymbolMapCache> { DataStoreSymbolMapCache(get()) }
    single { SymbolsClient(get(), get<SymbolMapCache>()) }
    single { SymbolSearch(get(), get(), get()) }
    single { QuotePoller(get(), get(), get()) }
    single<ConfigRepository> { ConfigRepositoryImpl(get()) }
    single { HomeViewModel(get(), get(), get()) }
}

// App starts Koin with appModule plus a Context binding for ConfigRepositoryImpl
// (DataStore and contentResolver access).
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val context: Context = this
        startKoin {
            modules(
                module { single { context } },
                appModule,
            )
        }
    }
}
