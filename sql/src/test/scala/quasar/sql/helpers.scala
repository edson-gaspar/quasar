/*
 * Copyright 2014–2017 SlamData Inc.
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

package quasar.sql

import slamdata.Predef._
import quasar.{Data, TermLogicalPlanMatchers}
import quasar.contrib.pathy.sandboxCurrent
import quasar.fp._
import quasar.fp.ski._
import quasar.frontend.logicalplan.{LogicalPlan => LP, _}
import quasar.sql.SemanticAnalysis._
import quasar.std._, StdLib._, structural._

import matryoshka.Algebra
import matryoshka.data.Fix
import matryoshka.implicits._
import org.specs2.matcher.MustThrownMatchers._
import pathy.Path._
import scalaz._, Scalaz._

trait CompilerHelpers extends TermLogicalPlanMatchers {
  val lpf = new LogicalPlanR[Fix[LP]]

  // TODO use `quasar.precompile`
  val compile: String => String \/ Fix[LP] = query => {
    for {
      attr   <- parseAndAnnotate(query)
      cld    <- Compiler.compile[Fix[LP]](attr, Nil).leftMap(_.toString)
    } yield cld
  }

  val parseAndAnnotate: String => String \/ Cofree[Sql, SemanticAnalysis.Annotations] = query => {
    for {
      parsed <- fixParser.parseExpr(Query(query)).leftMap(_.toString)
      normed <- normalizeProjections(parsed).right
      sort   <- projectSortKeys(normed).right
      attr   <- annotate(sort).leftMap(_.toString)
    } yield attr
  }

  val parseAndAnnotateUnsafe: String => Cofree[Sql, SemanticAnalysis.Annotations] = query => {
    parseAndAnnotate(query).valueOr(err => throw new RuntimeException(s"False assumption in test, could not parse and annotate due to underlying issue: $err"))
  }

  val optimizer = new Optimizer[Fix[LP]]
  val lpr = optimizer.lpr

  // Compile -> Optimize -> Typecheck -> Rewrite Joins
  val fullCompile: String => String \/ Fix[LP] =
    q => compile(q).flatMap { lp =>
      // TODO we should just call `quasar.preparePlan` here
      // but we need to remove core's dependency on sql first
      val withErrors = for {
        optimized <- optimizer.optimize(lp).right
        typechecked <- lpr.ensureCorrectTypes(optimized).disjunction
        rewritten <- optimizer.rewriteJoins(typechecked).right
      } yield rewritten

      withErrors.leftMap(_.list.toList.mkString(";"))
    }

  // NB: this plan is simplified and normalized, but not optimized. That allows
  // the expected result to be controlled more precisely. Assuming you know
  // what plan the compiler produces for a reference query, you can demand that
  // `optimize` produces the same plan given a query in some more deviant form.
  def compileExp(query: String): Fix[LP] =
    compile(query).fold(
      e => throw new RuntimeException("could not compile query for expected value: " + query + "; " + e),
      optimizer.optimize)

  // Compile the given query, including optimization and typechecking
  def fullCompileExp(query: String): Fix[LP] =
    fullCompile(query).valueOr(e =>
      throw new RuntimeException(s"could not full-compile query for expected value '$query': $e"))

  def testLogicalPlanCompile(query: String, expected: Fix[LP]) =
    compile(query).map(optimizer.optimize).toEither must beRight(equalToPlan(expected))

  def testTypedLogicalPlanCompile(query: String, expected: Fix[LP]) =
    fullCompile(query).toEither must beRight(equalToPlan(expected))

  def renameJoinSides
    (result: Fix[LP])
    (oldLeft: Symbol, newLeft: Symbol, oldRight: Symbol, newRight: Symbol):
      Fix[LP] = {
    val rename: Algebra[LP, Fix[LP]] = {
      case JoinSideName(name) if name ≟ oldLeft =>
        lpr.joinSideName(newLeft)
      case JoinSideName(name) if name ≟ oldRight =>
        lpr.joinSideName(newRight)
      case Join(l, r, t, JoinCondition(lName0, rName0, c)) =>
        val lName = (lName0 ≟ oldLeft).fold(newLeft, oldLeft)
        val rName = (rName0 ≟ oldRight).fold(newRight, oldRight)
        lpr.join(l, r, t, JoinCondition(lName, rName, c))
      case lp => lp.embed
    }
    result.cata[Fix[LP]](rename)
  }

  def read(file: String): Fix[LP] =
    lpf.read(sandboxCurrent(posixCodec.parsePath(Some(_), Some(_), κ(None), κ(None))(file).get).get)

  def makeObj(ts: (String, Fix[LP])*): Fix[LP] =
    MakeObjectN(ts.map(t => lpf.constant(Data.Str(t._1)) -> t._2): _*).embed
}
