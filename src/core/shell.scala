/*
   ╔═══════════════════════════════════════════════════════════════════════════════════════════════════════════╗
   ║ Fury, version 0.6.7. Copyright 2018-19 Jon Pretty, Propensive OÜ.                                         ║
   ║                                                                                                           ║
   ║ The primary distribution site is: https://propensive.com/                                                 ║
   ║                                                                                                           ║
   ║ Licensed under  the Apache License,  Version 2.0 (the  "License"); you  may not use  this file  except in ║
   ║ compliance with the License. You may obtain a copy of the License at                                      ║
   ║                                                                                                           ║
   ║     http://www.apache.org/licenses/LICENSE-2.0                                                            ║
   ║                                                                                                           ║
   ║ Unless required  by applicable law  or agreed to in  writing, software  distributed under the  License is ║
   ║ distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. ║
   ║ See the License for the specific language governing permissions and limitations under the License.        ║
   ╚═══════════════════════════════════════════════════════════════════════════════════════════════════════════╝
*/
package fury.core

import fury.io._, fury.strings._, fury.model._

import guillotine._

import scala.util._
import scala.collection.mutable.HashMap

import java.io.FileOutputStream
import java.util.UUID
import java.util.jar.{JarOutputStream, Manifest => JManifest}

case class Shell(environment: Environment) {
  private val furyHome   = Path(System.getProperty("fury.home"))

  implicit private[this] val defaultEnvironment: Environment = environment

  def runJava(classpath: List[String],
              main: String,
              securePolicy: Boolean,
              env: Map[String, String],
              properties: Map[String, String],
              policy: Policy,
              layout: Layout,
              args: List[String])
             (output: String => Unit)
             : Running = {
    layout.sharedDir.mkdir()

    implicit val defaultEnvironment: Environment =
      Environment((environment.variables ++ env).updated("SHARED", layout.sharedDir.value), environment.workDir)

    val policyFile = layout.policyDir / UUID.randomUUID().toString
    policy.save(policyFile)

    val allProperties: Map[String, String] =
      properties.updated("java.security.manager", "").updated("java.security.policy", policyFile.value)
          .updated("fury.sharedDir", layout.sharedDir.value)

    val propArgs = allProperties.map { case (k, v) => if(v.isEmpty) str"-D$k" else str"-D$k=$v" }.to[List]

    val classpathStr = classpath.mkString(":")
    
    val cmd =
      if(securePolicy) sh"java $propArgs -cp $classpathStr $main $args"
      else sh"java -Dfury.sharedDir=${layout.sharedDir.value} -cp ${classpath.mkString(":")} $main $args"

    cmd.async(output(_), output(_))
  }

  def javac(classpath: List[String], dest: String, sources: List[String]) =
    sh"javac -cp ${classpath.mkString(":")} -d $dest $sources".exec[Try[String]]

  object git {
    def cloneBare(url: String, dir: Path): Try[String] = {
      implicit val defaultEnvironment: Environment = sshBatchEnv

      sh"git clone --mirror $url ${dir.value}".exec[Try[String]].map { out => (dir / ".done").touch(); out }
    }

    def sparseCheckout(from: Path,
                       dir: Path,
                       sources: List[Path],
                       refSpec: String,
                       commit: String)
                      : Try[String] = for {
      _   <- sh"git -C ${dir.value} init".exec[Try[String]]
      _   <- if(!sources.isEmpty) sh"git -C ${dir.value} config core.sparseCheckout true".exec[Try[String]]
             else Success(())
      _   <- ~(dir / ".git" / "info" / "sparse-checkout").writeSync(sources.map(_.value + "/*\n").mkString)
      _   <- sh"git -C ${dir.value} remote add origin ${from.value}".exec[Try[String]]
      str <- sh"git -C ${dir.value} fetch origin $refSpec".exec[Try[String]]
      _   <- sh"git -C ${dir.value} checkout $commit".exec[Try[String]]
      _   <- ~(dir / ".done").touch()
    } yield str

    def lsTree(dir: Path, commit: String): Try[List[Path]] = for {
      string <- sh"git -C ${dir.value} ls-tree -r --name-only $commit".exec[Try[String]]
      files  <- ~string.split("\n").to[List].map(Path(_))
    } yield files

    def showRefs(dir: Path): Try[List[String]] = for {
      refs  <- sh"git -C ${dir.value} show-ref --heads --tags".exec[Try[String]]
      lines <- ~refs.split("\n").to[List]
    } yield lines.map(_.split("/").last)

    private def sshBatchEnv: Environment = environment.append("GIT_SSH_COMMAND", "ssh -o BatchMode=yes")

    def lsRemote(url: String): Try[List[String]] = {
      implicit val defaultEnvironment: Environment = sshBatchEnv
      
      Cached.lsRemote.getOrElseUpdate(url, sh"git ls-remote --tags --heads $url".exec[Try[String]].map(_.split(
          "\n").to[List].map(_.split("/").last)))
    }

    def lsRemoteRefSpec(url: String, refSpec: String): Try[String] = {
      implicit val defaultEnvironment: Environment = sshBatchEnv
      
      Cached.lsRemoteRefSpec.getOrElseUpdate((url, refSpec), sh"git ls-remote $url $refSpec".exec[
          Try[String]].map(_.take(40)))
    }

    def fetch(dir: Path, refspec: Option[RefSpec]): Try[String] =
      sh"git -C ${dir.value} fetch origin ${refspec.to[List].map(_.id)}".exec[Try[String]]

    def showFile(dir: Path, file: String): Try[String] =
      sh"git -C ${dir.value} show HEAD:$file".exec[Try[String]]

    def getCommitFromTag(dir: Path, tag: String): Try[String] =
      sh"git -C ${dir.value} rev-parse $tag".exec[Try[String]]

    def getCommit(dir: Path): Try[String] = sh"git -C ${dir.value} rev-parse --short HEAD".exec[Try[String]]

    def getBranchHead(dir: Path, branch: String): Try[String] =
      sh"git -C ${dir.value} show-ref -s heads/$branch".exec[Try[String]]

    def getTag(dir: Path, tag: String): Try[String] =
      sh"git -C ${dir.value} show-ref -s tags/$tag".exec[Try[String]]
  }

  object java {
    def ensureIsGraalVM(): Try[Unit] =
      sh"sh -c 'java -version 2>&1'".exec[Try[String]].map(_.contains("GraalVM")).transform(
        if(_) Success(()) else Failure(GraalVMError("non-GraalVM java")),
        _ => Failure(GraalVMError("Could not check Java version"))
      )

    def ensureNativeImageInPath(): Try[Unit] =
      Try(sh"native-image --help".exec[Try[String]]).fold(
          _ => Failure(GraalVMError("native-image could not be executed")),
          _.map(_ => ())
      )
  }

  def jar(dest: Path, inputs: Set[Path], manifest: JManifest): Try[Unit] = Try {
    val out = new JarOutputStream(new FileOutputStream(dest.value), manifest)
    out.finish()
    out.close()

    inputs.foreach(Zipper.pack(_, dest))
  }

  def native(dest: Path, classpath: List[String], main: String): Try[Unit] = {
    implicit val defaultEnvironment: Environment = environment.copy(workDir = Some(dest.value))

    for {
      _  <- java.ensureNativeImageInPath
      _  <- java.ensureIsGraalVM()
      cp  = classpath.mkString(":")
      _  <- sh"native-image -cp $cp $main".exec[Try[String]].map(main.toLowerCase.waive)
    } yield ()
  }
}

object Cached {
  val lsRemote: HashMap[String, Try[List[String]]] = new HashMap()
  val lsRemoteRefSpec: HashMap[(String, String), Try[String]] = new HashMap()
}
