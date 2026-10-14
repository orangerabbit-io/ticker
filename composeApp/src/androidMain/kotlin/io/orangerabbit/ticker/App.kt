package io.orangerabbit.ticker

import android.app.Application
import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.orangerabbit.ticker.poll.QuotePoller
import io.orangerabbit.ticker.source.SymbolsClient
import io.orangerabbit.ticker.source.coinbase.CoinbaseClient
import io.orangerabbit.ticker.source.coinbase.CoinbaseQuoteSource
import io.orangerabbit.ticker.source.yahoo.InMemorySessionStore
import io.orangerabbit.ticker.source.yahoo.YahooClient
import io.orangerabbit.ticker.source.yahoo.YahooQuoteSource
import io.orangerabbit.ticker.source.yahoo.YahooSessionStore
import org.koin.core.context.startKoin
import org.koin.dsl.module

// appModule wires the app graph: quote sources over the OkHttp engine, the symbol map
// client, the foreground quote poller, and the DataStore-backed config repository.
val appModule = module {
    // InMemorySessionStore: the Yahoo handshake session (cookies + crumb) is lost on
    // process death. The handshake is cheap, so this is acceptable; a DataStore-backed
    // store with a 24h TTL is the upgrade path.
    single<YahooSessionStore> { InMemorySessionStore() }
    // No HttpCookies plugin and OkHttp's default no-cookies jar: YahooClient performs
    // its own handshake and must not get automatic cookie storage.
    single { HttpClient(OkHttp) }
    single<YahooQuoteSource> { YahooClient(get(), get()) }
    single<CoinbaseQuoteSource> { CoinbaseClient(get()) }
    single { SymbolsClient(get()) }
    single { QuotePoller(get(), get(), get()) }
    single<ConfigRepository> { ConfigRepositoryImpl(get()) }
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
