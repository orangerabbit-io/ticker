package io.orangerabbit.ticker.source.coinbase

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.orangerabbit.ticker.asset.AssetQuote
import kotlinx.coroutines.CancellationException

// CoinbaseQuoteSource is the quote port consumed by the poller: asset quotes keyed by
// Coinbase product id.
interface CoinbaseQuoteSource {
    suspend fun getQuotes(productIds: List<String>): Result<List<AssetQuote>>
}

// CoinbaseClient is a client for the Coinbase market products API ported from the CLI's
// monitor/coinbase/unary package. The endpoint is unauthenticated, so no session or
// credentials are established here.
class CoinbaseClient(private val http: HttpClient) : CoinbaseQuoteSource {

    override suspend fun getQuotes(productIds: List<String>): Result<List<AssetQuote>> {
        if (productIds.isEmpty()) {
            return Result.success(emptyList())
        }

        return try {
            Result.success(transformProducts(fetchProducts(productIds)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("failed to get quotes: ${e.message}", e))
        }
    }

    private suspend fun fetchProducts(productIds: List<String>): List<ResponseProduct> {
        val response = try {
            http.get("$baseURL/api/v3/brokerage/market/products") {
                url {
                    productIds.forEach { parameters.append("product_ids", it) }
                }
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
            parseProductsResponse(body).products
        } catch (e: Exception) {
            throw IllegalStateException("failed to decode response: ${e.message}", e)
        }
    }

    companion object {
        private const val baseURL = "https://api.coinbase.com"

        // transportFailure sanitizes transport errors: the original message and cause are
        // deliberately dropped so request details (e.g. request URLs) never leak.
        private fun transportFailure(message: String, cause: Exception): Exception {
            val status = (cause as? ResponseException)?.response?.status?.value
            return IllegalStateException(if (status != null) "$message: $status" else message)
        }
    }
}
