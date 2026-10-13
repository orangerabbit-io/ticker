package io.orangerabbit.ticker.source.yahoo

import io.ktor.client.HttpClient
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
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.orangerabbit.ticker.asset.AssetQuote
import kotlinx.coroutines.CancellationException

// YahooClient is a client for the Yahoo Finance quote API ported from the CLI's
// monitor/yahoo/unary package: a quote request authenticated with a session
// (cookies + crumb) established by a handshake and refreshed exactly once when
// rejected. Redirects are handled explicitly here, so callers must configure the
// HttpClient with followRedirects = false and without automatic cookie storage.
class YahooClient(
    private val http: HttpClient,
    private val sessionStore: YahooSessionStore,
) {

    suspend fun getQuotes(symbols: List<String>): Result<List<AssetQuote>> {
        if (symbols.isEmpty()) {
            return Result.success(emptyList())
        }

        return try {
            Result.success(fetchQuotes(symbols))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("failed to get quotes: ${e.message}", e))
        }
    }

    private suspend fun fetchQuotes(symbols: List<String>): List<AssetQuote> {
        var session = sessionStore.get()
        if (session == null) {
            session = refresh()
        }

        var response = requestQuotes(symbols, session)
        if (response.status.value >= 400) {
            session = refresh()
            response = requestQuotes(symbols, session)
        }

        if (response.status.value != HttpStatusCode.OK.value) {
            throw IllegalStateException("unexpected response: ${response.status.value}")
        }

        val body = response.bodyAsText()
        val result = try {
            parseQuoteResponse(body)
        } catch (e: Exception) {
            throw IllegalStateException("failed to decode response: ${e.message}", e)
        }

        return transformResponseQuotes(result.quoteResponse.result)
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

    private suspend fun requestQuotes(symbols: List<String>, session: YahooSession): HttpResponse =
        try {
            http.get("$baseURL/v7/finance/quote") {
                url {
                    parameters.append("fields", quoteFields.joinToString(","))
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
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("failed to make request: ${e.message}", e)
        }

    private suspend fun getCookie(): List<Pair<String, String>> {
        val response = try {
            http.get(sessionRootURL) {
                header("Authority", "finance.yahoo.com")
                header(HttpHeaders.Accept, defaultAcceptValue)
                header(HttpHeaders.AcceptLanguage, defaultAcceptLanguage)
                header(HttpHeaders.UserAgent, defaultUserAgent)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("error requesting a cookie: ${e.message}", e)
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
        val observedURLs = mutableListOf(redirectLocation)

        val consentRedirectResponse = try {
            http.get(redirectLocation) {
                header("Authority", "consent.yahoo.com")
                header(HttpHeaders.Accept, defaultAcceptValue)
                header(HttpHeaders.AcceptLanguage, defaultAcceptLanguage)
                header(HttpHeaders.UserAgent, defaultUserAgent)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("error refreshing EU session: ${e.message}", e)
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
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("HTTP protocol error attempting to agree to EU consent request: ${e.message}", e)
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
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("error requesting a crumb: ${e.message}", e)
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

        private val sessionIdRegex = Regex("sessionId=(?:([A-Za-z0-9_-]*))")
        private val csrfTokenRegex = Regex("(?:gcrumb|csrfToken|csrf)=(?:([A-Za-z0-9_-]*))")

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
