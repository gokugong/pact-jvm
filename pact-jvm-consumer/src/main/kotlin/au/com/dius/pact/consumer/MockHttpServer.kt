package au.com.dius.pact.consumer

import au.com.dius.pact.model.*
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsServer
import org.apache.commons.lang3.StringEscapeUtils
import org.apache.http.entity.ContentType
import org.slf4j.LoggerFactory
import scala.collection.JavaConversions
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

fun mockServer(pact: RequestResponsePact, config: MockProviderConfig): MockServer {
  return when (config) {
    is MockHttpsProviderConfig -> MockHttpsServer(pact, config)
    else -> MockHttpServer(pact, config)
  }
}

val LOGGER = LoggerFactory.getLogger(MockServer::class.java)!!

abstract class MockServer(val pact: RequestResponsePact,
                          val config: MockProviderConfig,
                          private val server: HttpServer) : HttpHandler {
  private val mismatchedRequests = ConcurrentHashMap<Request, MutableList<PactVerificationResult>>()
  private val requestMatcher = RequestMatching.apply(JavaConversions.asScalaBuffer(pact.interactions).toSeq())

  override fun handle(exchange: HttpExchange) {
    val request = toPactRequest(exchange)
    LOGGER.debug("Received request: $request")
    val response = generatePactResponse(request)
    LOGGER.debug("Generating response: $response")
    pactResponseToHttpExchange(response, exchange)
  }

  private fun pactResponseToHttpExchange(response: Response, exchange: HttpExchange) {
    exchange.responseHeaders.putAll(response.headers.mapValues { listOf(it.value) })
    if (response.body.isPresent) {
      val bytes = response.body.value.toByteArray()
      exchange.sendResponseHeaders(response.status, bytes.size.toLong())
      exchange.responseBody.write(bytes)
    } else {
      exchange.sendResponseHeaders(response.status, 0)
    }
    exchange.close()
  }

  private fun generatePactResponse(request: Request): Response {
    val matchResult = requestMatcher.matchInteraction(request)
    when (matchResult) {
      is FullRequestMatch -> {
        val interaction = matchResult.interaction() as RequestResponseInteraction
        return interaction.response
      }
      is PartialRequestMatch -> {
        val interaction = matchResult.problems().keys().head() as RequestResponseInteraction
        mismatchedRequests.putIfAbsent(interaction.request, mutableListOf())
        mismatchedRequests[interaction.request]?.add(PactVerificationResult.PartialMismatch(
          ScalaCollectionUtils.toList(matchResult.problems()[interaction])))
      }
      else -> {
        mismatchedRequests.putIfAbsent(request, mutableListOf())
        mismatchedRequests[request]?.add(PactVerificationResult.UnexpectedRequest(request))
      }
    }
    return invalidResponse(request)
  }

  private fun invalidResponse(request: Request) =
    Response(500, mapOf("Access-Control-Allow-Origin" to "*", "Content-Type" to "application/json",
      "X-Pact-Unexpected-Request" to "1"), OptionalBody.body("{ \"error\": \"Unexpected request : " +
      StringEscapeUtils.escapeJson(request.toString()) + "\" }"))

  private fun toPactRequest(exchange: HttpExchange): Request {
    val headers = exchange.requestHeaders.mapValues { it.value.joinToString(", ") }
    val bodyContents = exchange.requestBody.bufferedReader(calculateCharset(headers)).readText()
    val body = if (bodyContents.isNullOrEmpty()) {
      OptionalBody.empty()
    } else {
      OptionalBody.body(bodyContents)
    }
    return Request(exchange.requestMethod, exchange.requestURI.path,
      PactReader.queryStringToMap(exchange.requestURI.query), headers, body)
  }

  private fun initServer() {
    server.createContext("/", this)
  }

  fun start() = server.start()

  fun stop() = server.stop(1)

  init {
    initServer()
  }

  fun runAndWritePact(pact: RequestResponsePact, pactVersion: PactSpecVersion, testFn: PactTestRun): PactVerificationResult {
    start()
    waitForServer()

    try {
      testFn.run(this)
    } catch(e: Exception) {
      return PactVerificationResult.Error(e, validateMockServerState())
    } finally {
      stop()
    }

    val result = validateMockServerState()
    if (result is PactVerificationResult.Ok) {
      val pactDirectory = pactDirectory()
      LOGGER.debug("Writing pact ${pact.consumer.name} -> ${pact.provider.name} to file ${pact.fileForPact(pactDirectory)}")
      pact.write(pactDirectory, pactVersion)
    }

    return result
  }

  private fun validateMockServerState(): PactVerificationResult {
    if (mismatchedRequests.isNotEmpty()) {
      return PactVerificationResult.Mismatches(mismatchedRequests.values.flatten())
    }
    return PactVerificationResult.Ok
  }

  private fun waitForServer() {

  }
}

open class MockHttpServer(pact: RequestResponsePact, config: MockProviderConfig): MockServer(pact, config, HttpServer.create(config.address(), 0))
open class MockHttpsServer(pact: RequestResponsePact, config: MockProviderConfig): MockServer(pact, config, HttpsServer.create(config.address(), 0))

fun calculateCharset(headers: Map<String, String>): Charset {
  val contentType = headers.entries.find { it.key.toUpperCase() == "CONTENT-TYPE" }
  val default = Charset.forName("ISO-8859-1")
  if (contentType != null) {
    try {
      return ContentType.parse(contentType.value)?.charset ?: default
    } catch(e: Exception) {
      LOGGER.debug("Failed to parse the charset from the content type header", e)
    }
  }
  return default
}

fun pactDirectory() = System.getProperty("pact.rootDir", "target/pacts")!!
