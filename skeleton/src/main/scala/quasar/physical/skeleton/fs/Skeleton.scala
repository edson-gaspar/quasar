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

package quasar
package physical.skeleton

import slamdata.Predef._
import quasar.connector._
import quasar.contrib.pathy._
import quasar.fp._
import quasar.fp.numeric._
import quasar.fs._
import quasar.fs.mount._
import quasar.qscript._

import matryoshka._
import scalaz._
import scalaz.concurrent.Task
import scala.Predef.implicitly

object Skeleton extends BackendModule {

  // default QS subset; change if you're cool/weird/unique!
  type QS[T[_[_]]] = QScriptCore[T, ?] :\: EquiJoin[T, ?] :/: Const[ShiftedRead[AFile], ?]

  implicit def qScriptToQScriptTotal[T[_[_]]]: Injectable.Aux[QSM[T, ?], QScriptTotal[T, ?]] =
    ::\::[QScriptCore[T, ?]](::/::[T, EquiJoin[T, ?], Const[ShiftedRead[AFile], ?]])

  // make this your repr and monad
  type Repr = Unit
  type M[A] = Nothing

  def FunctorQSM[T[_[_]]] = Functor[QSM[T, ?]]
  def DelayRenderTreeQSM[T[_[_]]: BirecursiveT: EqualT: ShowT: RenderTreeT] = implicitly[Delay[RenderTree, QSM[T, ?]]]
  def ExtractPathQSM[T[_[_]]: RecursiveT] = ExtractPath[QSM[T, ?], APath]
  def QSCoreInject[T[_[_]]] = implicitly[QScriptCore[T, ?] :<: QSM[T, ?]]
  def MonadM = ??? // Monad[M]
  def MonadFsErrM = ??? // MonadFsErr[M]
  def PhaseResultTellM = ??? // PhaseResultTell[M]
  def PhaseResultListenM = ??? // PhaseResultListen[M]
  def UnirewriteT[T[_[_]]: BirecursiveT: EqualT: ShowT: RenderTreeT] = implicitly[Unirewrite[T, QS[T]]]
  def UnicoalesceCap[T[_[_]]: BirecursiveT: EqualT: ShowT: RenderTreeT] = Unicoalesce.Capture[T, QS[T]]

  type Config = Unit

  def parseConfig[S[_]](uri: ConnectionUri)(
    implicit
      S0: Task :<: S,
      S1: PhysErr :<: S): EitherT[Free[S, ?], ErrorMessages, Config] = ???

  def compile[S[_]](cfg: Config)(
    implicit
      S0: Task :<: S,
      S1: PhysErr :<: S): FileSystemDef.DefErrT[Free[S, ?], (M ~> Free[S, ?], Free[S, Unit])] = ???

  val Type = FileSystemType("skeleton")

  def plan[T[_[_]]: BirecursiveT: EqualT: ShowT: RenderTreeT](
      cp: T[QSM[T, ?]]): M[Repr] = ???

  object QueryFileModule extends QueryFileModule {
    import QueryFile._

    def executePlan(repr: Repr, out: AFile): Kleisli[M, Config, AFile] = ???
    def evaluatePlan(repr: Repr): Kleisli[M, Config, ResultHandle] = ???
    def more(h: ResultHandle): Kleisli[M, Config, Vector[Data]] = ???
    def close(h: ResultHandle): Kleisli[M, Config, Unit] = ???
    def explain(repr: Repr): Kleisli[M, Config, String] = ???
    def listContents(dir: ADir): Kleisli[M, Config, Set[PathSegment]] = ???
    def fileExists(file: AFile): Kleisli[M, Config, Boolean] = ???
  }

  object ReadFileModule extends ReadFileModule {
    import ReadFile._

    def open(file: AFile, offset: Natural, limit: Option[Positive]): Kleisli[M, Config, ReadHandle] = ???
    def read(h: ReadHandle): Kleisli[M, Config, Vector[Data]] = ???
    def close(h: ReadHandle): Kleisli[M, Config, Unit] = ???
  }

  object WriteFileModule extends WriteFileModule {
    import WriteFile._

    def open(file: AFile): Kleisli[M, Config, WriteHandle] = ???
    def write(h: WriteHandle, chunk: Vector[Data]): Kleisli[M, Config, Vector[FileSystemError]] = ???
    def close(h: WriteHandle): Kleisli[M, Config, Unit] = ???
  }

  object ManageFileModule extends ManageFileModule {
    import ManageFile._

    def move(scenario: MoveScenario, semantics: MoveSemantics): Kleisli[M, Config, Unit] = ???
    def delete(path: APath): Kleisli[M, Config, Unit] = ???
    def tempFile(near: APath): Kleisli[M, Config, AFile] = ???
  }
}