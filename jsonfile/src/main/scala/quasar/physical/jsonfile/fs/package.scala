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

package quasar.physical.jsonfile

import quasar._
import quasar.Predef._
import quasar.fp._, free._, numeric._
import quasar.fs._
import quasar.fs.mount._, FileSystemDef._
import quasar.effect._
import quasar.fs.FileSystemError._
import quasar.Planner.UnsupportedPlan
import quasar.qscript._
import pathy.Path, Path._
import quasar.contrib.pathy._
import scalaz._, Scalaz.{ ToIdOps => _, _ }
import FileSystemIndependentTypes._
import matryoshka._
import RenderTree.ops._

// import ygg.table._
// import ygg.json.JValue
// import Recursive.ops._

// XXX tests which pass too easily:
//
// [info]     + filter on date part, where the field isn't a timestamp [./temporal/invalidDateFilter.test]
// [info]     + reduce a literal set with negatives [./literalReductionWithNegative.test]
// [info]     + regex on non-string field [./guardedExpression.test]
//
// Planner Errors:
//
// fcc FuncApply(name: String, expected: String, actual: String)
// fcc InternalError(message: String)
// fcc NoFilesFound(dirs: List[ADir])
// fcc NonRepresentableData(data: Data)
// fcc NonRepresentableEJson(data: String)
// fcc NonRepresentableInJS(value: String)
// fcc ObjectIdFormatError(str: String)
// fcc PlanPathError(error: PathError)
// fcc UnboundVariable(name: Symbol)
// fcc UnsupportedFunction(name: String, hint: Option[String])
// fcc UnsupportedJS(value: String)
// fcc UnsupportedJoinCondition(cond: Fix[LogicalPlan])
// fcc UnsupportedPlan(plan: LogicalPlan[_], hint: Option[String])

package object fs extends fs.FilesystemEffect {
  val FsType = FileSystemType("jsonfile")

  type AFile                = quasar.contrib.pathy.AFile
  type ADir                 = quasar.contrib.pathy.ADir
  type APath                = quasar.contrib.pathy.APath
  type PathSegment          = quasar.contrib.pathy.PathSegment
  type Fix[F[_]]            = matryoshka.Fix[F]
  type AsTask[F[X]]         = Task[F ~> Task]
  type FixPlan              = matryoshka.Fix[LogicalPlan]
  type KVInject[K, V, S[_]] = KeyValueStore[K, V, ?] :<: S
  type MoveSemantics        = ManageFile.MoveSemantics
  type Task[A]              = scalaz.concurrent.Task[A]
  type Table                = ygg.table.Table

  val Task          = scalaz.concurrent.Task
  val MoveSemantics = ManageFile.MoveSemantics
  val Unimplemented = quasar.fs.FileSystemError.Unimplemented

  def kvEmpty[K, V] : AsTask[KeyValueStore[K, V, ?]]   = KeyValueStore.impl.empty[K, V]
  def kvOps[K, V, S[_]](implicit z: KVInject[K, V, S]) = KeyValueStore.Ops[K, V, S]
  def makeDirList(names: PathSegment*): DirList        = names.toSet
  def tmpName(n: Long): String                         = s"__quasar.ygg$n"
  def unknownPath(p: APath): FileSystemError           = pathErr(PathError pathNotFound p)
  def unknownPlan(lp: FixPlan): FileSystemError        = planningFailed(lp, UnsupportedPlan(lp.unFix, None))
  def cond[A](p: Boolean, ifp: => A, elsep: => A): A   = if (p) ifp else elsep

  def diff[A: RenderTree](l: A, r: A): RenderedTree = l.render diff r.render
  def showln[A: Show](x: A): Unit                   = println(x.shows)

  implicit class FixPlanOps(val self: FixPlan) {
    def to_s: String = FPlan("", self).toString
  }

  implicit class PathyRFPathOps(val path: Path[Any, Any, Sandboxed]) {
    def toAbsolute: APath = mkAbsolute(rootDir, path)
    def toJavaFile: jFile = new jFile(posixCodec unsafePrintPath path)
  }

  implicit class KVSOps[K, V, S[_]](val kvs: KVInject[K, V, S]) extends STypesFree[S, Fix] {
    private def Ops = KeyValueStore.Ops[K, V, S](kvs)
    private def maybe(condition: FSPred, action: FSUnit): FSPred = condition flatMap (p => cond[FSUnit](p, action, ()) map (_ => p))

    def keys: FS[Vector[K]]               = Ops.keys
    def contains(key: K): FSPred          = Ops contains key
    def put(key: K, value: V): FSUnit     = Ops.put(key, value)
    def get(key: K): FS[Option[V]]        = (Ops get key).run
    def delete(key: K): FSPred            = maybe(contains(key), Ops delete key)
    def move(src: K, dst: K): FSPred      = maybe(contains(src), Ops.move(src, dst))
    def modify(key: K, f: V => V): FSUnit = Ops.modify(key, f)
  }

  implicit def showPath: Show[APath]      = Show shows (posixCodec printPath _)
  implicit def showRHandle: Show[RHandle] = Show shows (r => "ReadHandle(%s, %s)".format(r.file.show, r.id))
  implicit def showWHandle: Show[WHandle] = Show shows (r => "WriteHandle(%s, %s)".format(r.file.show, r.id))
  implicit def showFixPlan: Show[FixPlan] = Show shows (lp => FPlan("", lp).toString)
}

package fs {
  final case class ReadPos(file: AFile, offset: Int, limit: Int)
  final case class WritePos(file: AFile, offset: Int)

  trait STypesFree[S[_], T[_[_]]] extends FileSystemContext.Free[S] with TTypes[T] {
    type FS[A]  = Free[S, A]
    type FSUnit = FS[Unit]
    type FSPred = FS[Boolean]

    def nextLong(implicit MS: MonotonicSeq :<: S) = MonotonicSeq.Ops[S].next

    type QPlan[A] = FileSystemErrT[PhaseResultT[FS, ?], A]
    def liftQP[F[_], A](fa: F[A])(implicit ev: F :<: S): QPlan[A] =
      lift(fa).into[S].liftM[PhaseResultT].liftM[FileSystemErrT]

    def ls(dir: ADir)(implicit KVF: KVFile[S]): FLR[DirList] = KVF.keys map (fs =>
      fs.map(_ relativeTo dir).unite.toNel
        .map(_ foldMap (f => firstSegmentName(f).toSet))
        .toRightDisjunction(unknownPath(dir))
    )
  }

  trait FilesystemEffect {
    val FsType: FileSystemType

    type FH = Chunks    // file map values
    type WH = WritePos  // write handle map values

    /** The read cursor type.
     *    Marklogic: ReadStream[ContentSourceIO]
     *    Couchbase: Cursor( Vector[JsonObject] )
     *        Spark: SparkCursor( Option[RDD[Data->Long]]->Int )
     */
    type RCursor = ReadPos

    /** The query representation type.
     *    Marklogic: XQuery
     *    Couchbase: N1QL
     *        Spark: RDD[Data]
     */
    type QRep = ygg.table.Table

    type KVFile[S[_]]  = KVInject[AFile, FH, S]
    type KVRead[S[_]]  = KVInject[RHandle, RCursor, S]
    type KVWrite[S[_]] = KVInject[WHandle, WH, S]
    type KVQuery[S[_]] = KVInject[QHandle, QRep, S]

    type Eff[A] = (
          Task
      :\: KeyValueStore[AFile, FH, ?]
      :\: KeyValueStore[RHandle, RCursor, ?]
      :\: KeyValueStore[WHandle, WH, ?]
      :\: KeyValueStore[QHandle, QRep, ?]
      :/: MonotonicSeq
    )#M[A]

    def initialEff(uri: ConnectionUri): AsTask[Eff] = (
          (Task delay reflNT[Task])
      |@| kvEmpty[AFile, FH]
      |@| kvEmpty[RHandle, RCursor]
      |@| kvEmpty[WHandle, WH]
      |@| kvEmpty[QHandle, QRep]
      |@| MonotonicSeq.fromZero
    )(_ :+: _ :+: _ :+: _ :+: _ :+: _)

    def fileSystem[S[_]](implicit
      TS: Task :<: S,
      KVF: KVFile[S],
      KVR: KVRead[S],
      KVW: KVWrite[S],
      KVQ: KVQuery[S],
      MS: MonotonicSeq :<: S
    ): FileSystem ~> Free[S, ?] = Tracer maybe new FsAlgebras[S].boundFs

    def optUri(cfg: FsCfg): Option[ConnectionUri] = Some(cfg) collect { case FsCfg(FsType, uri) => uri }

    def runFilesystem[S[_]](run: Eff ~> Task, onClose: => Unit)(implicit TS: Task :<: S, PE: PhysErr :<: S): DefinitionResult[Free[S, ?]] =
      DefinitionResult(
        run   = mapSNT(TS compose run) compose fileSystem,
        close = lift(Task delay onClose).into[S]
      )

    def definition[S[_]](implicit TS: Task :<: S, PE: PhysErr :<: S): FileSystemDef[Free[S, ?]] =
      FileSystemDef(cfg => optUri(cfg) map (uri => lift(initialEff(uri) map (run => runFilesystem(run, ()))).into[S].liftM[DefErrT]))
  }
}
