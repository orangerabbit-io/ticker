package io.orangerabbit.ticker.source.coinbase

import io.orangerabbit.ticker.asset.AssetClass
import io.orangerabbit.ticker.asset.AssetQuote
import io.orangerabbit.ticker.asset.Currency
import io.orangerabbit.ticker.asset.ExchangeInfo
import io.orangerabbit.ticker.asset.Meta
import io.orangerabbit.ticker.asset.QuoteExtended
import io.orangerabbit.ticker.asset.QuotePrice
import io.orangerabbit.ticker.asset.QuoteSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Response models ported from the CLI's coinbase unary models.

@Serializable
data class Response(
    val products: List<ResponseProduct> = emptyList(),
)

@Serializable
data class ResponseProduct(
    @SerialName("product_id") val productId: String = "",
    @SerialName("product_type") val productType: String = "",
    @SerialName("base_display_symbol") val baseDisplaySymbol: String = "",
    @SerialName("base_name") val baseName: String = "",
    @SerialName("quote_currency_id") val quoteCurrencyId: String = "",
    val price: String = "",
    @SerialName("price_percentage_change_24h") val pricePercentageChange24h: String = "",
    @SerialName("volume_24h") val volume24h: String = "",
    val status: String = "",
)

private val productsJson = Json { ignoreUnknownKeys = true }

fun parseProductsResponse(body: String): Response = productsJson.decodeFromString(Response.serializer(), body)

// transformProduct transforms a single market product into an AssetQuote. Futures products
// are not supported and return null so they are skipped, not crashed on. Numeric strings
// that fail to parse are treated as zero. Ported from the CLI's spot product transform.
fun transformProduct(product: ResponseProduct): AssetQuote? {
    if (product.productType == "FUTURE") {
        return null
    }

    val price = product.price.toDoubleOrNull() ?: 0.0
    val changePercent = product.pricePercentageChange24h.toDoubleOrNull() ?: 0.0

    return AssetQuote(
        name = product.baseName,
        symbol = product.baseDisplaySymbol + ".CB",
        assetClass = AssetClass.CRYPTOCURRENCY,
        currency = Currency(from = product.quoteCurrencyId.uppercase(), to = ""),
        quotePrice = QuotePrice(
            price = price,
            change = price * changePercent / 100,
            changePercent = changePercent,
        ),
        quoteExtended = QuoteExtended(
            volume = product.volume24h.toDoubleOrNull() ?: 0.0,
        ),
        quoteSource = QuoteSource.COINBASE,
        exchange = ExchangeInfo(
            isActive = product.status == "online",
        ),
        meta = Meta(
            isVariablePrecision = true,
            symbolInSourceApi = product.productId,
        ),
    )
}

// transformProducts transforms market products into AssetQuotes, skipping futures products
fun transformProducts(products: List<ResponseProduct>): List<AssetQuote> =
    products.mapNotNull(::transformProduct)
