/*
 * Copyright 2021 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.phoenix.managers

import fr.acinq.lightning.utils.Either
import fr.acinq.phoenix.PhoenixBusiness
import fr.acinq.phoenix.data.LNUrl
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import org.kodein.log.LoggerFactory
import org.kodein.log.newLogger

class LNUrlManager(
    private val httpClient: HttpClient,
    loggerFactory: LoggerFactory
) : CoroutineScope by MainScope() {
    constructor(business: PhoenixBusiness) : this(
        httpClient = business.httpClient,
        loggerFactory = business.loggerFactory
    )

    private val log = newLogger(loggerFactory)

    /**
     * Get the LNUrl for this source. Throw exception if source is malformed, or invalid.
     * Will execute an HTTP request for some urls and parse the response into an actionable LNUrl object.
     */
    suspend fun extractLNUrl(source: String): LNUrl {
        return when (val result = interactiveExtractLNUrl(source)) {
            is Either.Left -> result.value
            is Either.Right -> continueLnUrl(result.value)
        }
    }

    /**
     * Attempts to extract a Bech32 URL from the string.
     * On success:
     * - if the LnUrl is a simple Auth, it's returned immediately
     * - otherwise the Url is returned (call `continueLnUrl` to fetch & parse the Url)
     *
     * Throws an exception if source is malformed, or invalid.
     */
    fun interactiveExtractLNUrl(source: String): Either<LNUrl.Auth, Url> {
        val url = try {
            LNUrl.parseBech32Url(source)
        } catch (e1: Exception) {
            log.debug { "cannot parse source=$source as a bech32 lnurl" }
            try {
                LNUrl.parseNonBech32Url(source)
            } catch (e2: Exception) {
                log.error { "cannot extract lnurl from source=$source: ${e1.message ?: e1::class} / ${e2.message ?: e2::class}"}
                throw LNUrl.Error.Invalid
            }
        }
        return when (url.parameters["tag"]) {
            // auth urls must not be called just yet
            LNUrl.Tag.Auth.label -> {
                val k1 = url.parameters["k1"]
                if (k1.isNullOrBlank()) {
                    throw LNUrl.Error.Auth.MissingK1
                } else {
                    Either.Left(LNUrl.Auth(url, k1))
                }
            }
            else -> Either.Right(url)
        }
    }

    /**
     * Executes the HTTP request for the LnUrl and parses the response
     * into an actionable LNUrl object.
     */
    suspend fun continueLnUrl(url: Url): LNUrl {
        val json = LNUrl.handleLNUrlResponse(httpClient.get(url))
        return LNUrl.parseLNUrlMetadata(json)
    }
}