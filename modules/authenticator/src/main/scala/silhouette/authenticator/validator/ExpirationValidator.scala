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
package silhouette.authenticator.validator

import java.time.Clock

import silhouette.authenticator.Validator._
import silhouette.authenticator.validator.ExpirationValidator._
import silhouette.authenticator.{ Authenticator, Validator }

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

/**
 * A validator that checks if an [[Authenticator]] is expired.
 *
 * If the [[Authenticator.expires]] property isn't set, then this validator returns always true.
 *
 * @param clock The clock implementation to validate against.
 */
final case class ExpirationValidator(clock: Clock) extends Validator {

  /**
   * Checks if the [[Authenticator]] is valid.
   *
   * @param authenticator The [[Authenticator]] to validate.
   * @param ec            The execution context to perform the async operations.
   * @return True if the [[Authenticator]] is valid, false otherwise.
   */
  override def isValid(authenticator: Authenticator)(
    implicit
    ec: ExecutionContext
  ): Future[Status] = Future.successful {
    if (authenticator.expiresIn(clock).forall(_ >= 0.millis)) {
      Valid
    } else {
      Invalid(Seq(Error.format(authenticator.expiresIn(clock).map(_.neg()).getOrElse(0.millis))))
    }
  }
}

/**
 * The companion object.
 */
object ExpirationValidator {
  val Error = "Authenticator is expired %s ago"
}
