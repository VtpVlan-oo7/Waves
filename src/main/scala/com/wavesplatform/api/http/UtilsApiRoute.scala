package com.wavesplatform.api.http

import java.security.SecureRandom

import akka.http.scaladsl.server.Route
import com.wavesplatform.common.utils._
import com.wavesplatform.crypto
import com.wavesplatform.settings.RestAPISettings
import com.wavesplatform.state.diffs.CommonValidation
import com.wavesplatform.transaction.smart.script.{Script, ScriptCompiler}
import com.wavesplatform.utils.Time
import io.swagger.annotations._
import javax.ws.rs.Path
import play.api.libs.json._

@Path("/utils")
@Api(value = "/utils", description = "Useful functions", position = 3, produces = "application/json")
case class UtilsApiRoute(timeService: Time, settings: RestAPISettings) extends ApiRoute {

  import UtilsApiRoute._

  private def seed(length: Int) = {
    val seed = new Array[Byte](length)
    new SecureRandom().nextBytes(seed) //seed mutated here!
    Json.obj("seed" -> Base58.encode(seed))
  }

  override val route: Route = pathPrefix("utils") {
    compile ~ compileContract ~ estimate ~ time ~ seedRoute ~ length ~ hashFast ~ hashSecure ~ sign ~ transactionSerialize
  }

  @Path("/script/compile")
  @ApiOperation(value = "Compile", notes = "Compiles string code to base64 script representation", httpMethod = "POST")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(
        name = "code",
        required = true,
        dataType = "string",
        paramType = "body",
        value = "Script code",
        example = "true"
      )
    ))
  @ApiResponses(
    Array(
      new ApiResponse(code = 200, message = "base64 or error")
    ))
  def compile: Route = path("script" / "compile") {
    (post & entity(as[String])) { code =>
      parameter('assetScript.as[Boolean] ? false) { isAssetScript =>
        complete(
          ScriptCompiler(code, isAssetScript).fold(
            e => ScriptCompilerError(e), {
              case (script, complexity) =>
                Json.obj(
                  "script"     -> script.bytes().base64,
                  "complexity" -> complexity,
                  "extraFee"   -> CommonValidation.ScriptExtraFee
                )
            }
          )
        )
      }
    }
  }

  @Path("/script/compileContract")
  @ApiOperation(value = "Compile Contract", notes = "Compiles string code to base64 contract representation", httpMethod = "POST")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(
        name = "code",
        required = true,
        dataType = "string",
        paramType = "body",
        value = "Contract code",
        example = "true"
      )
    ))
  @ApiResponses(
    Array(
      new ApiResponse(code = 200, message = "base64 or error")
    ))
  def compileContract: Route = path("script" / "compileContract") {
    (post & entity(as[String])) { code =>
      complete(
        ScriptCompiler
          .contract(code)
          .fold(
            e => ScriptCompilerError(e), {
              case (contract) =>
                Json.obj(
                  "script"     -> contract.bytes().base64,
                  "complexity" -> 0,
                  "extraFee"   -> CommonValidation.ScriptExtraFee
                )
            }
          )
      )
    }
  }
  @Path("/script/estimate")
  @ApiOperation(value = "Estimate", notes = "Estimates compiled code in Base64 representation", httpMethod = "POST")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(
        name = "code",
        required = true,
        dataType = "string",
        paramType = "body",
        value = "A compiled Base64 code",
        example = "AQa3b8tH"
      )
    ))
  @ApiResponses(
    Array(
      new ApiResponse(code = 200, message = "base64 or error")
    ))
  def estimate: Route = path("script" / "estimate") {
    (post & entity(as[String])) { code =>
      complete(
        Script
          .fromBase64String(code)
          .left
          .map(_.m)
          .flatMap { script =>
            ScriptCompiler.estimate(script, script.stdLibVersion).map((script, _))
          }
          .fold(
            e => ScriptCompilerError(e), {
              case (script, complexity) =>
                Json.obj(
                  "script"     -> code,
                  "scriptText" -> Script.decompile(script),
                  "complexity" -> complexity,
                  "extraFee"   -> CommonValidation.ScriptExtraFee
                )
            }
          )
      )
    }
  }

  @Path("/time")
  @ApiOperation(value = "Time", notes = "Current Node time (UTC)", httpMethod = "GET")
  @ApiResponses(
    Array(
      new ApiResponse(code = 200, message = "Json with time or error")
    ))
  def time: Route = (path("time") & get) {
    complete(Json.obj("system" -> System.currentTimeMillis(), "NTP" -> timeService.correctedTime()))
  }

  @Path("/seed")
  @ApiOperation(value = "Seed", notes = "Generate random seed", httpMethod = "GET")
  @ApiResponses(
    Array(
      new ApiResponse(code = 200, message = "Json with peer list or error")
    ))
  def seedRoute: Route = (path("seed") & get) {
    complete(seed(DefaultSeedSize))
  }

  @Path("/seed/{length}")
  @ApiOperation(value = "Seed of specified length", notes = "Generate random seed of specified length", httpMethod = "GET")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(name = "length", value = "Seed length ", required = true, dataType = "integer", paramType = "path")
    ))
  @ApiResponse(code = 200, message = "Json with error message")
  def length: Route = (path("seed" / IntNumber) & get) { length =>
    if (length <= MaxSeedSize) complete(seed(length))
    else complete(TooBigArrayAllocation)
  }

  @Path("/hash/secure")
  @ApiOperation(value = "Hash", notes = "Return SecureCryptographicHash of specified message", httpMethod = "POST")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(
        name = "message",
        value = "Message to hash",
        required = true,
        paramType = "body",
        dataType = "string"
      )
    ))
  @ApiResponses(
    Array(
      new ApiResponse(code = 200, message = "Json with error or json like {\"message\": \"your message\",\"hash\": \"your message hash\"}")
    ))
  def hashSecure: Route = (path("hash" / "secure") & post) {
    entity(as[String]) { message =>
      complete(Json.obj("message" -> message, "hash" -> Base58.encode(crypto.secureHash(message))))
    }
  }

  @Path("/hash/fast")
  @ApiOperation(value = "Hash", notes = "Return FastCryptographicHash of specified message", httpMethod = "POST")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(
        name = "message",
        value = "Message to hash",
        required = true,
        paramType = "body",
        dataType = "string"
      )
    ))
  @ApiResponses(
    Array(
      new ApiResponse(code = 200, message = "Json with error or json like {\"message\": \"your message\",\"hash\": \"your message hash\"}")
    ))
  def hashFast: Route = (path("hash" / "fast") & post) {
    entity(as[String]) { message =>
      complete(Json.obj("message" -> message, "hash" -> Base58.encode(crypto.fastHash(message))))
    }
  }
  @Path("/sign/{privateKey}")
  @ApiOperation(value = "Hash", notes = "Return FastCryptographicHash of specified message", httpMethod = "POST")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(
        name = "privateKey",
        value = "privateKey",
        required = true,
        paramType = "path",
        dataType = "string",
        example = "3kMEhU5z3v8bmer1ERFUUhW58Dtuhyo9hE5vrhjqAWYT"
      ),
      new ApiImplicitParam(
        name = "message",
        value = "Message to hash (base58 string)",
        required = true,
        paramType = "body",
        dataType = "string"
      )
    ))
  @ApiResponses(
    Array(
      new ApiResponse(code = 200, message = "Json with error or json like {\"message\": \"your message\",\"hash\": \"your message hash\"}")
    ))
  def sign: Route = (path("sign" / Segment) & post) { pk =>
    entity(as[String]) { message =>
      complete(
        Json.obj("message" -> message,
                 "signature" ->
                   Base58.encode(crypto.sign(Base58.decode(pk).get, Base58.decode(message).get))))
    }
  }

  @Path("/transactionSerialize")
  @ApiOperation(value = "Serialize transaction", notes = "Serialize transaction", httpMethod = "POST")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(
        name = "json",
        required = true,
        paramType = "body",
        dataType = "string",
        value = "Transaction data including <a href='transaction-types.html'>type</a> and signature/proofs"
      )
    ))
  def transactionSerialize: Route = (pathPrefix("transactionSerialize") & post) {
    handleExceptions(jsonExceptionHandler) {
      json[JsObject] { jsv =>
        parseOrCreateTransaction(jsv)(tx => Json.obj("bytes" -> tx.bodyBytes().map(_.toInt & 0xff)))
      }
    }
  }
}

object UtilsApiRoute {
  val MaxSeedSize     = 1024
  val DefaultSeedSize = 32
}
