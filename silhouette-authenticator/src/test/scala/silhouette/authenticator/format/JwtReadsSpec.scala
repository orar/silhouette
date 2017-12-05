/**
 * Licensed to the Minutemen Group under one or more contributor license
 * agreements. See the COPYRIGHT file distributed with this work for
 * additional information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package silhouette.authenticator.format

import java.time.Instant

import io.circe.syntax._
import org.specs2.concurrent.ExecutionEnv
import org.specs2.matcher.Scope
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import silhouette.authenticator.{ StatefulReads, StatelessReads }
import silhouette.authenticator.format.JwtReads._
import silhouette.crypto.Base64
import silhouette.jwt.{ Claims, Reads }
import silhouette.specs2.WaitPatience
import silhouette.{ Authenticator, AuthenticatorException, LoginInfo }

import scala.json.ast._
import scala.util.{ Failure, Try }

/**
 * Test case for the [[JwtReads]] class.
 *
 * @param ev The execution environment.
 */
class JwtReadsSpec(implicit ev: ExecutionEnv) extends Specification with Mockito with WaitPatience {

  "The instance" should {
    "be a StatelessReads" in new Context {
      jwtReads must beAnInstanceOf[StatelessReads]
    }

    "be a StatefulReads" in new Context {
      jwtReads must beAnInstanceOf[StatefulReads]
    }
  }

  "The `read` method" should {
    "return a failed future if the underlying reads returns a `Failure`" in new Context {
      val exception = new RuntimeException("test")
      underlyingJwtReads.read(jwt) returns Failure(exception)

      jwtReads.read(jwt) must throwA[RuntimeException].like {
        case e =>
          e must be equalTo exception
      }.awaitWithPatience
    }

    "throw an `AuthenticatorException` if the `jwtID` isn't set in a claim" in new Context {
      underlyingJwtReads.read(jwt) returns Try(claims.copy(jwtID = None))

      jwtReads.read(jwt) must throwA[AuthenticatorException].like {
        case e =>
          e.getMessage must be equalTo MissingClaimValue.format("jwtID")
      }.awaitWithPatience
    }

    "throw an `AuthenticatorException` if the `subject` isn't set in a claim" in new Context {
      underlyingJwtReads.read(jwt) returns Try(claims.copy(subject = None))

      jwtReads.read(jwt) must throwA[AuthenticatorException].like {
        case e =>
          e.getMessage must be equalTo MissingClaimValue.format("subject")
      }.awaitWithPatience
    }

    "throw an `AuthenticatorException` if the `subject` claim cannot be parsed" in new Context {
      underlyingJwtReads.read(jwt) returns Try(claims.copy(subject = Some(Base64.encode("invalid"))))

      jwtReads.read(jwt) must throwA[AuthenticatorException].like {
        case e =>
          e.getMessage must be equalTo JsonParseError.format("invalid")
      }.awaitWithPatience
    }

    "throw an `AuthenticatorException` if the `tags` in the custom claim JSON isn't an array" in new Context {
      underlyingJwtReads.read(jwt) returns Try(claims.copy(custom = JObject(Map("tags" -> JString("some string")))))

      jwtReads.read(jwt) must throwA[AuthenticatorException].like {
        case e =>
          e.getMessage must be equalTo UnexpectedJsonValue.format("JString(some string)", "JArray")
      }.awaitWithPatience
    }

    "throw an `AuthenticatorException` if the values in the JSON `tags` array are not strings" in new Context {
      underlyingJwtReads.read(jwt) returns Try(claims.copy(custom = JObject(Map("tags" -> JArray(JNumber(1))))))

      jwtReads.read(jwt) must throwA[AuthenticatorException].like {
        case e =>
          e.getMessage must be equalTo UnexpectedJsonValue.format("JNumber(1)", "JString")
      }.awaitWithPatience
    }

    "throw an `AuthenticatorException` if the `fingerprint` in the custom claim JSON isn't a string" in new Context {
      underlyingJwtReads.read(jwt) returns Try(claims.copy(custom = JObject(Map("fingerprint" -> JNumber(1)))))

      jwtReads.read(jwt) must throwA[AuthenticatorException].like {
        case e =>
          e.getMessage must be equalTo UnexpectedJsonValue.format("JNumber(1)", "JString")
      }.awaitWithPatience
    }

    "throw an `AuthenticatorException` if the `payload` in the custom claim JSON isn't an object" in new Context {
      underlyingJwtReads.read(jwt) returns Try(claims.copy(custom = JObject(Map("payload" -> JNumber(1)))))

      jwtReads.read(jwt) must throwA[AuthenticatorException].like {
        case e =>
          e.getMessage must be equalTo UnexpectedJsonValue.format("JNumber(1)", "JObject")
      }.awaitWithPatience
    }

    "create an authenticator representation from the JWT" in new Context {
      underlyingJwtReads.read(jwt) returns Try(claims)

      jwtReads.read(jwt) must beEqualTo(authenticator).awaitWithPatience
    }
  }

  /**
   * The context.
   */
  trait Context extends Scope {

    /**
     * A test JWT.
     *
     * This isn't a valid JWT, but a valid JWT is not needed because we mock the JWT implementation.
     */
    val jwt = "a.test.jwt"

    /**
     * An instant of time.
     */
    val instant = Instant.parse("2017-10-22T20:50:45.0Z")

    /**
     * The login info.
     */
    val loginInfo = LoginInfo("credentials", "john@doe.com")

    /**
     * A JWT claims object.
     */
    val claims = Claims(
      issuer = Some("issuer"),
      subject = Some(Base64.encode(loginInfo.asJson.toString())),
      audience = Some(List("test")),
      expirationTime = Some(instant),
      notBefore = Some(instant),
      issuedAt = Some(instant),
      jwtID = Some("id"),
      custom = JObject(Map(
        "tags" -> JArray(JString("tag1"), JString("tag2")),
        "fingerprint" -> JString("fingerprint"),
        "payload" -> JObject(Map(
          "secure" -> JTrue
        ))
      ))
    )

    /**
     * The authenticator representation of the claims object.
     */
    val authenticator = Authenticator(
      id = "id",
      loginInfo = loginInfo,
      touched = Some(instant),
      expires = Some(instant),
      fingerprint = Some("fingerprint"),
      tags = Seq("tag1", "tag2"),
      payload = Some(JObject(Map(
        "secure" -> JTrue
      )))
    )

    /**
     * A mock of the underlying JWT reads.
     */
    val underlyingJwtReads = mock[Reads]

    /**
     * The JWT authenticator reads.
     */
    val jwtReads = JwtReads(underlyingJwtReads)
  }
}