package io.orangerabbit.ticker.source.yahoo

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.orangerabbit.ticker.asset.AssetQuote
import io.orangerabbit.ticker.asset.CurrencyRates
import kotlinx.coroutines.CancellationException

// YahooQuoteSource is the quote port consumed by the poller: asset quotes and currency
// conversion rates.
interface YahooQuoteSource {
    suspend fun getQuotes(symbols: List<String>): Result<List<AssetQuote>>

    suspend fun getCurrencyRates(fromCurrencies: List<String>, toCurrency: String): Result<CurrencyRates>
}

// YahooClient is a client for the Yahoo Finance quote API ported from the CLI's
// monitor/yahoo/unary package: a quote request authenticated with a session
// (cookies + crumb) established by a handshake and refreshed exactly once when
// rejected. The session handshake follows redirects explicitly and validates
// every redirect target against the destination allowlist before requesting it,
// so automatic redirect following is disabled here regardless of caller config;
// callers must also configure the HttpClient without automatic cookie storage.
class YahooClient(
    http: HttpClient,
    private val sessionStore: YahooSessionStore,
) : YahooQuoteSource {

    private val http: HttpClient = http.config { followRedirects = false }

    override suspend fun getQuotes(symbols: List<String>): Result<List<AssetQuote>> {
        if (symbols.isEmpty()) {
            return Result.success(emptyList())
        }

        return try {
            Result.success(transformResponseQuotes(fetchQuotes(symbols, quoteFields)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("failed to get quotes: ${e.message}", e))
        }
    }

    // getCurrencyRates accepts ISO 4217 currency codes and a target ISO 4217 currency code
    // and returns a conversion rate for each of the input currencies to the target currency.
    // Ported from the CLI's GetCurrencyRates.
    override suspend fun getCurrencyRates(fromCurrencies: List<String>, toCurrency: String): Result<CurrencyRates> {
        val to = if (toCurrency.isEmpty()) "USD" else toCurrency

        // Create currency pair symbols in format "FROMTO=X" (e.g., "EURUSD=X")
        val pairSymbols = LinkedHashSet<String>()
        for (fromCurrency in fromCurrencies) {
            if (fromCurrency.isEmpty() || fromCurrency == to) {
                continue
            }
            pairSymbols.add(fromCurrency.uppercase() + to + "=X")
        }

        if (pairSymbols.isEmpty()) {
            return Result.success(CurrencyRates(emptyMap()))
        }

        return try {
            Result.success(transformCurrencyRateQuotes(fetchQuotes(pairSymbols.toList(), currencyRateFields), to))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("failed to get currency rates: ${e.message}", e))
        }
    }

    private suspend fun fetchQuotes(symbols: List<String>, fields: List<String>): List<ResponseQuote> {
        // await any deferred session initialization first so a persisted session is
        // used on cold start instead of a redundant handshake racing the load
        sessionStore.preload()
        var session = sessionStore.get()
        if (session == null) {
            session = refresh()
        }

        var response = requestQuotes(symbols, fields, session)
        if (response.status.value >= 400) {
            session = refresh()
            response = requestQuotes(symbols, fields, session)
        }

        if (response.status.value != HttpStatusCode.OK.value) {
            throw IllegalStateException("unexpected response: ${response.status.value}")
        }

        val body = response.bodyAsText()
        return try {
            parseQuoteResponse(body).quoteResponse.result
        } catch (e: Exception) {
            throw IllegalStateException("failed to decode response: ${e.message}", e)
        }
    }

    private suspend fun refresh(): YahooSession {
        val session = try {
            val cookies = getCookie()
            YahooSession(cookies = cookies, crumb = getCrumb(cookies))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("session refresh failed: ${e.message}", e)
        }

        sessionStore.put(session)

        return session
    }

    private suspend fun requestQuotes(symbols: List<String>, fields: List<String>, session: YahooSession): HttpResponse =
        try {
            http.get("$baseURL/v7/finance/quote") {
                url {
                    parameters.append("fields", fields.joinToString(","))
                    parameters.append("symbols", symbols.joinToString(","))
                    parameters.append("formatted", "true")
                    parameters.append("lang", "en-US")
                    parameters.append("region", "US")
                    parameters.append("corsDomain", "finance.yahoo.com")
                    if (session.crumb.isNotEmpty()) {
                        parameters.append("crumb", session.crumb)
                    }
                }
                header("Authority", "query1.finance.yahoo.com")
                header(HttpHeaders.Accept, "*/*")
                header(HttpHeaders.AcceptLanguage, defaultAcceptLanguage)
                header(HttpHeaders.Origin, baseURL)
                header(HttpHeaders.UserAgent, defaultUserAgent)
                cookieHeader(session.cookies)
                expectSuccess = false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw transportFailure("failed to make request", e)
        }

    private suspend fun getCookie(): List<Pair<String, String>> {
        val response = try {
            http.get(sessionRootURL) {
                header("Authority", "finance.yahoo.com")
                header(HttpHeaders.Accept, defaultAcceptValue)
                header(HttpHeaders.AcceptLanguage, defaultAcceptLanguage)
                header(HttpHeaders.UserAgent, defaultUserAgent)
                expectSuccess = false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw transportFailure("error requesting a cookie", e)
        }

        val location = response.headers[HttpHeaders.Location]
        if (location != null && isEUConsentRedirect(response.status, location)) {
            return getCookieEU(location)
        }

        response.bodyAsText()

        val cookies = parseSetCookieHeaders(response.headers)
        if (!isRequiredCookieSet(cookies)) {
            throw IllegalStateException("session refresh error: A3 session cookie missing from response")
        }

        return cookies
    }

    // getCookieEU handles the EU consent flow: follow the consent redirect, agree
    // to the consent form with the GUCS cookies collected along the redirect, and
    // take the A3 cookies set by the consent endpoint. Ported from the CLI's getCookieEU.
    private suspend fun getCookieEU(redirectLocation: String): List<Pair<String, String>> {
        validateConsentLocation(redirectLocation)

        val observedURLs = mutableListOf(redirectLocation)

        val consentRedirectResponse = try {
            http.get(redirectLocation) {
                header("Authority", "consent.yahoo.com")
                header(HttpHeaders.Accept, defaultAcceptValue)
                header(HttpHeaders.AcceptLanguage, defaultAcceptLanguage)
                header(HttpHeaders.UserAgent, defaultUserAgent)
                expectSuccess = false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw transportFailure("error refreshing EU session", e)
        }

        consentRedirectResponse.bodyAsText()
        consentRedirectResponse.headers[HttpHeaders.Location]?.let(observedURLs::add)

        val gucsCookies = parseSetCookieHeaders(consentRedirectResponse.headers)
        if (gucsCookies.isEmpty()) {
            throw IllegalStateException("session refresh error: GUCS cookie missing from response")
        }

        val (sessionID, csrfToken) = extractSessionAndCSRF(observedURLs)

        val formBody = listOf(
            "agree" to "agree",
            "csrfToken" to csrfToken,
            "namespace" to "yahoo",
            "sessionId" to sessionID,
        ).sortedBy { it.first }
            .joinToString("&") { (key, value) -> "$key=${value.encodeURLParameter()}" }

        val consentURL = "$sessionConsentURL$sessionConsentPath?sessionId=$sessionID"

        val consentResponse = try {
            http.post(consentURL) {
                header(HttpHeaders.Origin, "https://consent.yahoo.com")
                header("Host", "consent.yahoo.com")
                header(HttpHeaders.Accept, defaultAcceptValue)
                header(HttpHeaders.AcceptLanguage, defaultAcceptLanguage)
                header(HttpHeaders.Referrer, consentURL)
                header(HttpHeaders.UserAgent, defaultUserAgent)
                header(HttpHeaders.ContentLength, formBody.encodeToByteArray().size.toString())
                header(HttpHeaders.AcceptEncoding, "gzip, deflate, br")
                header("DNT", "1")
                header("Sec-Ch-Ua", "\"Google Chrome\";v=\"134\", \"Chromium\";v=\"134\", \"Not-A.Brand\";v=\"24\"")
                header("Sec-Ch-Ua-Mobile", "?0")
                header("Sec-Ch-Ua-Platform", "\"Windows\"")
                header("Sec-Fetch-Dest", "document")
                header("Sec-Fetch-Mode", "navigate")
                header("Sec-Fetch-Site", "same-origin")
                header("Sec-Fetch-User", "?1")
                cookieHeader(gucsCookies)
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(formBody)
                expectSuccess = false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw transportFailure("HTTP protocol error attempting to agree to EU consent request", e)
        }

        consentResponse.bodyAsText()

        val cookies = parseSetCookieHeaders(consentResponse.headers)
        if (!isRequiredCookieSet(cookies)) {
            throw IllegalStateException(
                "session refresh error: A3 session cookie missing from response after agreeing to EU consent request",
            )
        }

        return cookies
    }

    private suspend fun getCrumb(cookies: List<Pair<String, String>>): String {
        val response = try {
            http.get("$sessionCrumbURL$sessionCrumbPath") {
                header("Authority", "query2.finance.yahoo.com")
                header(HttpHeaders.Accept, "*/*")
                header(HttpHeaders.AcceptLanguage, defaultAcceptLanguage)
                header(HttpHeaders.ContentType, "text/plain")
                header(HttpHeaders.Origin, sessionRootURL)
                header(HttpHeaders.UserAgent, defaultUserAgent)
                cookieHeader(cookies)
                expectSuccess = false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw transportFailure("error requesting a crumb", e)
        }

        if (response.status.value !in 200..299) {
            throw IllegalStateException(
                "session refresh error: unexpected response from Yahoo API when attempting to retrieve crumb: " +
                    "non-2xx response code: ${response.status.value}",
            )
        }

        return response.bodyAsText()
    }

    private fun HttpRequestBuilder.cookieHeader(cookies: List<Pair<String, String>>) {
        if (cookies.isNotEmpty()) {
            header(HttpHeaders.Cookie, cookies.joinToString("; ") { (name, value) -> "$name=$value" })
        }
    }

    companion object {
        private const val baseURL = "https://query1.finance.yahoo.com"
        private const val sessionRootURL = "https://finance.yahoo.com"
        private const val sessionCrumbURL = "https://query2.finance.yahoo.com"
        private const val sessionCrumbPath = "/v1/test/getcrumb"
        private const val sessionConsentURL = "https://consent.yahoo.com"
        private const val sessionConsentPath = "/v2/collectConsent"
        private const val consentLocationError = "session refresh error: unexpected consent redirect location"

        private const val defaultUserAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        private const val defaultAcceptValue =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8," +
                "application/signed-exchange;v=b3;q=0.7"
        private const val defaultAcceptLanguage = "en-US,en;q=0.9"

        private val quoteFields = listOf(
            "shortName",
            "regularMarketChange",
            "regularMarketChangePercent",
            "regularMarketPrice",
            "regularMarketPreviousClose",
            "regularMarketOpen",
            "regularMarketDayRange",
            "regularMarketDayHigh",
            "regularMarketDayLow",
            "regularMarketVolume",
            "postMarketChange",
            "postMarketChangePercent",
            "postMarketPrice",
            "preMarketChange",
            "preMarketChangePercent",
            "preMarketPrice",
            "fiftyTwoWeekHigh",
            "fiftyTwoWeekLow",
            "marketCap",
        )

        private val currencyRateFields = listOf("currency", "regularMarketPrice")

        private val sessionIdRegex = Regex("sessionId=(?:([A-Za-z0-9_-]*))")
        private val csrfTokenRegex = Regex("(?:gcrumb|csrfToken|csrf)=(?:([A-Za-z0-9_-]*))")

        // Maps transport failures to a fixed message plus status code only: HTTP-layer
        // exception messages can embed the request URL, which carries the crumb, so the
        // original message and cause are deliberately dropped.
        private fun transportFailure(message: String, cause: Exception): Exception {
            val status = (cause as? ResponseException)?.response?.status?.value
            return IllegalStateException(if (status != null) "$message: $status" else message)
        }

        // validateConsentLocation rejects redirect targets outside the destination
        // allowlist before any request is issued for them.
        private fun validateConsentLocation(location: String) {
            val url = try {
                Url(location)
            } catch (e: Exception) {
                throw IllegalStateException(consentLocationError)
            }

            val isAllowed = url.protocol == URLProtocol.HTTPS &&
                url.host.equals("consent.yahoo.com", ignoreCase = true) &&
                url.port == URLProtocol.HTTPS.defaultPort

            if (!isAllowed) {
                throw IllegalStateException(consentLocationError)
            }
        }

        private fun isEUConsentRedirect(status: HttpStatusCode, location: String): Boolean =
            "/consent" in location && status.value in 300..399

        private fun isRequiredCookieSet(cookies: List<Pair<String, String>>): Boolean =
            cookies.any { (name, _) -> name == "A3" }

        private fun parseSetCookieHeaders(headers: Headers): List<Pair<String, String>> =
            headers.getAll(HttpHeaders.SetCookie).orEmpty().mapNotNull { setCookie ->
                val parts = setCookie.substringBefore(';').split('=', limit = 2)
                if (parts.size != 2) null else parts[0].trim() to parts[1].trim()
            }

        private fun extractSessionAndCSRF(urls: List<String>): Pair<String, String> {
            val sessionID = urls.firstNotNullOfOrNull { sessionIdRegex.find(it)?.groupValues?.get(1) }
                ?: throw IllegalStateException("session refresh error: error unable to extract session id from redirected request URL")
            val csrfToken = urls.firstNotNullOfOrNull { csrfTokenRegex.find(it)?.groupValues?.get(1) }
                ?: throw IllegalStateException("session refresh error: error unable to extract CSRF token from Location header")

            return sessionID to csrfToken
        }
    }
}
