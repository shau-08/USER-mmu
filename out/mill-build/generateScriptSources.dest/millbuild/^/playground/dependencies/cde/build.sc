package millbuild.`^`.playground.dependencies.cde

import _root_.mill.runner.MillBuildRootModule

object MiscInfo_build {
  implicit lazy val millBuildRootModuleInfo: _root_.mill.runner.MillBuildRootModule.Info = _root_.mill.runner.MillBuildRootModule.Info(
    Vector("/home/shauryaps/USER-mmu/out/mill-launcher/0.11.6.jar").map(_root_.os.Path(_)),
    _root_.os.Path("/home/shauryaps/playground/dependencies/cde"),
    _root_.os.Path("/home/shauryaps/USER-mmu"),
    _root_.scala.Seq()
  )
  implicit lazy val millBaseModuleInfo: _root_.mill.main.RootModule.Info = _root_.mill.main.RootModule.Info(
    millBuildRootModuleInfo.projectRoot,
    _root_.mill.define.Discover[build]
  )
}
import MiscInfo_build.{millBuildRootModuleInfo, millBaseModuleInfo}
object build extends build
class build extends _root_.mill.main.RootModule.Foreign(Some(_root_.mill.define.Segments.labels("foreign-modules", "up-1", "playground", "dependencies", "cde", "build"))) {

//MILL_ORIGINAL_FILE_PATH=/home/shauryaps/playground/dependencies/cde/build.sc
//MILL_USER_CODE_START_MARKER
import mill._
import scalalib._
import scalafmt._
import publish._
import _root_._

import _root_._
import de.tobiasroeser.mill.vcs.version.VcsVersion

object v {
  val scala = "2.13.10"
  val utest = ivy"com.lihaoyi::utest:0.8.1"
}

object cde extends CDE

trait CDE
  extends common.CDEModule
    with ScalafmtModule
    with CDEPublishModule {
  override def scalaVersion = v.scala
}

object cdetest extends CDETest

trait CDETest
  extends common.CDETestModule
    with ScalafmtModule {

  override def scalaVersion = v.scala

  override def millSourcePath = cde.millSourcePath / "tests"

  def cdeModule = cde

  def utestIvy = v.utest
}

trait CDEPublishModule extends PublishModule {
  def publishVersion = de.tobiasroeser.mill.vcs.version.VcsVersion.vcsState().format()

  def pomSettings = PomSettings(
    description = artifactName(),
    organization = "org.chipsalliance",
    url = "https://www.github.com/chipsalliance/cde",
    licenses = Seq(License.`Apache-2.0`),
    versionControl = VersionControl.github("chipsalliance", "cde"),
    developers = Seq(
      Developer("terpstra", "Wesley W. Terpstra", "https://github.com/terpstra")
    )
  )
  // TODO: wait Chisel has mill-based release flow, let's copy&paste from it.
}

}