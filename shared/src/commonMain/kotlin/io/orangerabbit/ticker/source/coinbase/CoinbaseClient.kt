package io.orangerabbit.ticker.source.coinbase

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.orangerabbit.ticker.asset.AssetQuote
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock

// CoinbaseQuoteSource is the quote port consumed by the poller: asset quotes keyed by
// Coinbase product id.
interface CoinbaseQuoteSource {
    suspend fun getQuotes(productIds: List<String>): Result<List<AssetQuote>>
}

// CoinbaseClient is a client for the Coinbase market products API ported from the CLI's
// monitor/coinbase/unary package. The endpoint is unauthenticated, so no session or
// credentials are established here. The product catalog is fetched with cursor pagination
// and cached in memory for 24h.
class CoinbaseClient(
    private val http: HttpClient,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : CoinbaseQuoteSource {

    private var entries: List<ProductCatalogEntry>? = null
    private var cachedAt: Long = 0

    // listProducts returns the full product catalog, following cursor pagination until
    // has_next is false. The catalog is cached in memory for 24h; fetch failures are
    // returned as Result.failure and are not cached so a transient error is retried on
    // the next call.
    suspend fun listProducts(): Result<List<ProductCatalogEntry>> {
        val now = clock()
        val cachedEntries = entries
        if (cachedEntries != null && now - cachedAt < ttlMillis) {
            return Result.success(cachedEntries)
        }

        return try {
            val fetched = fetchCatalog()
            entries = fetched
            cachedAt = now
            Result.success(fetched)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("failed to list products: ${e.message}", e))
        }
    }

    private suspend fun fetchCatalog(): List<ProductCatalogEntry> {
        val products = mutableListOf<ResponseProduct>()
        var cursor = ""
        while (true) {
            val page = fetchProductsPage {
                parameters.append("limit", catalogPageSize.toString())
                if (cursor.isNotEmpty()) {
                    parameters.append("cursor", cursor)
                }
            }
            products += page.products
            // has_next alone does not advance pagination: an empty or repeated cursor
            // would refetch the same page forever, so it terminates the loop too
            val pagination = page.pagination ?: break
            if (!pagination.hasNext || pagination.nextCursor.isEmpty() || pagination.nextCursor == cursor) {
                break
            }
            cursor = pagination.nextCursor
        }
        // futures products are excluded from the catalog: the quote pipeline refuses
        // them (transformProduct), so proposing them would add rows that never quote
        return products
            .filter { it.productType != "FUTURE" }
            .map { ProductCatalogEntry(it.productId, it.baseName, it.baseDisplaySymbol) }
    }

    override suspend fun getQuotes(productIds: List<String>): Result<List<AssetQuote>> {
        if (productIds.isEmpty()) {
            return Result.success(emptyList())
        }

        return try {
            Result.success(transformProducts(fetchProductsPage {
                productIds.forEach { parameters.append("product_ids", it) }
            }.products))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("failed to get quotes: ${e.message}", e))
        }
    }

    private suspend fun fetchProductsPage(buildParameters: URLBuilder.() -> Unit): Response {
        val response = try {
            http.get("$baseURL/api/v3/brokerage/market/products") {
                url { buildParameters() }
                expectSuccess = false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw transportFailure("failed to make request", e)
        }

        if (response.status.value != HttpStatusCode.OK.value) {
            throw IllegalStateException("unexpected response: ${response.status.value}")
        }

        val body = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw transportFailure("failed to read response", e)
        }

        return try {
            parseProductsResponse(body)
        } catch (e: Exception) {
            throw IllegalStateException("failed to decode response: ${e.message}", e)
        }
    }

    companion object {
        private const val baseURL = "https://api.coinbase.com"
        private const val catalogPageSize = 250
        private const val ttlMillis = 24 * 60 * 60 * 1000L

        // transportFailure sanitizes transport errors: the original message and cause are
        // deliberately dropped so request details (e.g. request URLs) never leak.
        private fun transportFailure(message: String, cause: Exception): Exception {
            val status = (cause as? ResponseException)?.response?.status?.value
            return IllegalStateException(if (status != null) "$message: $status" else message)
        }
    }
}
