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

import slamdata.Predef._
import quasar.common.{PhaseResult, PhaseResultW}
import quasar.connector.CompileM
import quasar.contrib.pathy.ADir
import quasar.fp._
import quasar.fp.numeric._
import quasar.frontend.{SemanticErrors, SemanticErrsT}
import quasar.frontend.logicalplan.{LogicalPlan => LP, _}
import quasar.sql._
import quasar.std.StdLib.set._

import matryoshka._
import matryoshka.data.Fix
import matryoshka.implicits._
import scalaz._, Scalaz._

package object quasar {
  private def phase[A: RenderTree](label: String, r: SemanticErrors \/ A):
      CompileM[A] =
    EitherT(r.point[PhaseResultW]) flatMap { a =>
      (a.set(Vector(PhaseResult.tree(label, a)))).liftM[SemanticErrsT]
    }

  /** Compiles a query into raw LogicalPlan, which has not yet been optimized or
    * typechecked.
    */
  // TODO: Move this into the SQL package, provide a type class for it in core.
  def precompile[T: Equal: RenderTree]
    (query: Blob[Fix[Sql]], vars: Variables, basePath: ADir)
    (implicit TR: Recursive.Aux[T, LP], TC: Corecursive.Aux[T, LP])
      : CompileM[T] = {
    import SemanticAnalysis._
    for {
      ast      <- phase("SQL AST", query.right)
      substAst <- phase("Variables Substituted", ast.mapExpressionM(Variables.substVars(_, vars)))
      absAst   <- phase("Absolutized", substAst.map(_.mkPathsAbsolute(basePath)).right)
      normed   <- phase("Normalized Projections", absAst.map(normalizeProjections[Fix[Sql]]).right)
      sortProj <- phase("Sort Keys Projected", normed.map(projectSortKeys[Fix[Sql]]).right)
      annBlob  <- phase("Annotated Tree", (sortProj.traverse(annotate[Fix[Sql]])))
      scope    =  annBlob.scope.collect{ case func: FunctionDecl[_] => func }
      logical  <- phase("Logical Plan", Compiler.compile[T](annBlob.expr, scope) leftMap (_.wrapNel))
    } yield logical
  }

  private val optimizer = new Optimizer[Fix[LP]]
  private val lpr = optimizer.lpr

  /** Optimizes and typechecks a `LogicalPlan` returning the improved plan.
    */
  def preparePlan(lp: Fix[LP]): CompileM[Fix[LP]] =
    for {
      optimized   <- phase("Optimized", optimizer.optimize(lp).right)
      typechecked <- phase("Typechecked", lpr.ensureCorrectTypes(optimized).disjunction)
      rewritten   <- phase("Rewritten Joins", optimizer.rewriteJoins(typechecked).right)
    } yield rewritten

  /** Identify plans which reduce to a (set of) constant value(s). */
  def refineConstantPlan(lp: Fix[LP]): List[Data] \/ Fix[LP] =
    lp.project match {
      case Constant(Data.Set(records)) => records.left
      case Constant(value)             => List(value).left
      case _                           => lp.right
    }

  /** Returns the `LogicalPlan` for the given SQL^2 query, or a list of
    * results, if the query was foldable to a constant.
    */
  def queryPlan(
    blob: Blob[Fix[Sql]], vars: Variables, basePath: ADir, off: Natural, lim: Option[Positive]):
      CompileM[List[Data] \/ Fix[LP]] =
    precompile[Fix[LP]](blob, vars, basePath)
      .flatMap(lp => preparePlan(addOffsetLimit(lp, off, lim)))
      .map(refineConstantPlan)

  def addOffsetLimit[T]
    (lp: T, off: Natural, lim: Option[Positive])
    (implicit T: Corecursive.Aux[T, LP])
      : T = {
    val skipped = Drop(lp, constant[T](Data.Int(off.value)).embed).embed
    lim.fold(
      skipped)(
      l => Take(skipped, constant[T](Data.Int(l.value)).embed).embed)
  }
}
