/*
 * Copyright 2014–2016 SlamData Inc.
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

package quasar.api.services

import quasar.Predef._
import quasar._, api._
import quasar.fp.numeric._
import quasar.sql.{Expr, Query}

import scala.collection.Seq

import argonaut._, Argonaut._
import org.http4s._, dsl._
import scalaz._, Scalaz._

package object query {
  import ToApiError.ops._

  implicit val QueryDecoder = new QueryParamDecoder[Query] {
    def decode(value: QueryParameterValue): ValidationNel[ParseFailure, Query] =
      Query(value.value).successNel[ParseFailure]
  }

  // https://github.com/puffnfresh/wartremover/issues/149
  @SuppressWarnings(Array("org.brianmckenna.wartremover.warts.NonUnitStatements"))
  object QueryParam extends QueryParamDecoderMatcher[Query]("q")

  def queryParam(params: Map[String, Seq[String]]): ApiError \/ Query =
    params.getOrElse("q", Seq.empty) match {
      case Seq(x) =>
        Query(x).right

      case Seq() =>
        ApiError.fromStatus(
          BadRequest withReason "No SQL^2 query found in URL."
        ).left

      case xs =>
        val ct = xs.size
        ApiError.fromMsg(
          BadRequest withReason "Multiple SQL^2 queries submitted.",
          s"The request may only contain a single SQL^2 query, found $ct.",
          "queryCount" := ct
        ).left
    }

  def parsedQueryRequest(
    req: Request,
    offset: Option[ValidationNel[ParseFailure, Natural]],
    limit: Option[ValidationNel[ParseFailure, Positive]]
  ): ApiError \/ (Expr, Option[Natural], Option[Positive]) =
    for {
      dir <- decodedDir(req.uri.path)
      qry <- queryParam(req.multiParams)
      xpr <- sql.parseInContext(qry, dir) leftMap (_.toApiError)
      off <- offsetOrInvalid(offset)
      lim <- limitOrInvalid(limit)
    } yield (xpr, off, lim)

  val bodyMustContainQuery: ApiError =
    ApiError.fromStatus(BadRequest withReason "No SQL^2 query found in message body.")

  def requestVars(req: Request) = Variables(req.params.collect {
    case (k, v) if k.startsWith(VarPrefix) => (VarName(k.substring(VarPrefix.length)), VarValue(v)) })

  def addOffsetLimit(query: sql.Expr, offset: Option[Natural], limit: Option[Positive]): sql.Expr = {
    val skipped = offset.fold(query)(o => sql.Binop(query, sql.IntLiteral(o.get), sql.Offset))
    limit.fold(skipped)(l => sql.Binop(skipped, sql.IntLiteral(l.get), sql.Limit))
  }

  private val VarPrefix = "var."
}