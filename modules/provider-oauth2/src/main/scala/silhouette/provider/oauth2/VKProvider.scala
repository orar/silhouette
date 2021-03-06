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
package silhouette.provider.oauth2

import java.net.URI
import java.time.Clock

import io.circe.{ Decoder, HCursor, Json }
import silhouette.http.HttpClient
import silhouette.http.Method.GET
import silhouette.http.client.Request
import silhouette.provider.UnexpectedResponseException
import silhouette.provider.oauth2.OAuth2Provider._
import silhouette.provider.oauth2.VKProvider._
import silhouette.provider.social._
import silhouette.provider.social.state.StateHandler
import silhouette.{ ConfigURI, LoginInfo }

import scala.concurrent.{ ExecutionContext, Future }

/**
 * Base VK OAuth2 Provider.
 *
 * @see https://vk.com/dev/auth_sites
 * @see https://vk.com/dev/api_requests
 * @see https://vk.com/dev/users.get
 * @see https://vk.com/dev/objects/user
 */
trait BaseVKProvider extends OAuth2Provider {

  /**
   * The provider ID.
   */
  override val id = ID

  /**
   * The implicit access token decoder.
   *
   * VK provider needs it own JSON decoder to extract the email from response.
   */
  override implicit protected val accessTokenDecoder: Decoder[OAuth2Info] = VKProvider.infoDecoder(clock)

  /**
   * Builds the social profile.
   *
   * @param authInfo The auth info received from the provider.
   * @return On success the build social profile, otherwise a failure.
   */
  override protected def buildProfile(authInfo: OAuth2Info): Future[Profile] = {
    val uri = config.apiURI.getOrElse(DefaultApiURI).format(authInfo.accessToken)

    httpClient.execute(Request(GET, uri)).flatMap { response =>
      withParsedJson(response) { json =>
        // The API returns a 200 status code for errors, so we must rely on the JSON here to detect an error
        json.hcursor.downField("error").focus match {
          case Some(_) =>
            Future.failed(new UnexpectedResponseException(UnexpectedResponse.format(id, json, response.status)))
          case _ =>
            profileParser.parse(json, authInfo)
        }
      }
    }
  }
}

/**
 * The profile parser for the common social profile.
 */
class VKProfileParser(implicit val ec: ExecutionContext)
  extends SocialProfileParser[Json, CommonSocialProfile, OAuth2Info] {

  /**
   * Parses the social profile.
   *
   * @param json     The content returned from the provider.
   * @param authInfo The auth info to query the provider again for additional data.
   * @return The social profile from the given result.
   */
  override def parse(json: Json, authInfo: OAuth2Info): Future[CommonSocialProfile] = {
    json.hcursor.downField("response").downArray.focus.map(_.hcursor) match {
      case Some(response) =>
        Future.fromTry(response.downField("id").as[Long].getOrError(response.value, "id", ID)).map { id =>
          CommonSocialProfile(
            loginInfo = LoginInfo(ID, id.toString),
            firstName = response.downField("first_name").as[String].toOption,
            lastName = response.downField("last_name").as[String].toOption,
            email = authInfo.params.flatMap(_.get("email")),
            avatarUri = response.downField("photo_max_orig").as[String].toOption.map(uri => new URI(uri))
          )
        }
      case None =>
        Future.failed(new UnexpectedResponseException(JsonPathError.format(ID, "response", json)))

    }
  }
}

/**
 * The VK OAuth2 Provider.
 *
 * @param httpClient   The HTTP client implementation.
 * @param stateHandler The state provider implementation.
 * @param clock        The current clock instance.
 * @param config       The provider config.
 * @param ec           The execution context.
 */
class VKProvider(
  protected val httpClient: HttpClient,
  protected val stateHandler: StateHandler,
  protected val clock: Clock,
  val config: OAuth2Config
)(
  implicit
  override implicit val ec: ExecutionContext
) extends BaseVKProvider with CommonProfileBuilder {

  /**
   * The type of this class.
   */
  override type Self = VKProvider

  /**
   * The profile parser implementation.
   */
  override val profileParser = new VKProfileParser

  /**
   * Gets a provider initialized with a new config object.
   *
   * @param f A function which gets the config passed and returns different config.
   * @return An instance of the provider initialized with new config.
   */
  override def withConfig(f: OAuth2Config => OAuth2Config): Self =
    new VKProvider(httpClient, stateHandler, clock, f(config))
}

/**
 * The companion object.
 */
object VKProvider {

  /**
   * The provider ID.
   */
  val ID = "vk"

  /**
   * The used API version.
   */
  val ApiVersion = "5.85"

  /**
   * Default provider endpoint.
   */
  val DefaultApiURI = ConfigURI("https://api.vk.com/method/users.get?fields=id,first_name,last_name," +
    s"photo_max_orig&v=$ApiVersion&access_token=%s")

  /**
   * Converts the JSON into a [[OAuth2Info]] object.
   *
   * @param clock The current clock instance.
   */
  def infoDecoder(clock: Clock): Decoder[OAuth2Info] = (c: HCursor) => {
    for {
      accessToken <- c.downField(AccessToken).as[String]
      tokenType <- c.downField(TokenType).as[Option[String]]
      expiresIn <- c.downField(ExpiresIn).as[Option[Int]]
      refreshToken <- c.downField(RefreshToken).as[Option[String]]
      email <- c.downField("email").as[Option[String]]
    } yield {
      OAuth2Info(
        accessToken,
        tokenType,
        Some(clock.instant()),
        expiresIn,
        refreshToken,
        email.map(e => Map("email" -> e))
      )
    }
  }
}
