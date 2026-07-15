import java.io.File
import java.net.URI
import java.nio.file.Paths
import sbt._

object DocumentationWebsite {
  def generateStaticAssets(
    contributorsFile: File,
    mainFile: File,
    cssContentContributorsSourceBaseFile: File,
    cssSourceFileBase: File,
    baseDest: File
  ): Seq[File] = {


    val contributorsTestcasesDestinationFile = Paths.get("scaladoc-testcases", "docs", "_assets", "js", "contributors.js").toFile
    val contributorsDestinationFile = baseDest / "dotty_res" / "scripts" / "contributors.js"
    sbt.IO.copyFile(contributorsFile, contributorsTestcasesDestinationFile)
    sbt.IO.copyFile(contributorsFile, contributorsDestinationFile)

    val mainDestinationFile = baseDest / "dotty_res" / "scripts" / "scaladoc-scalajs.js"
    sbt.IO.copyFile(mainFile, mainDestinationFile)

    val cssContentContributorsTestcasesDestinationFile = Paths.get("scaladoc-testcases", "docs", "_assets", "css", "content-contributors.css").toFile
    val cssContentContributorsDestinationFile = baseDest / "dotty_res" / "styles" / "content-contributors.css"
    val cssContentContributorsSourceFile = cssContentContributorsSourceBaseFile / "content-contributors.css"
    sbt.IO.copyFile(cssContentContributorsSourceFile, cssContentContributorsTestcasesDestinationFile)
    sbt.IO.copyFile(cssContentContributorsSourceFile, cssContentContributorsDestinationFile)

    val dests = Seq("searchbar.css", "social-links.css", "versions-dropdown.css").map { file =>
      val cssDestinationFile = baseDest / "dotty_res" / "styles" / file
      val cssSourceFile = cssSourceFileBase / file
      sbt.IO.copyFile(cssSourceFile, cssDestinationFile)
      cssDestinationFile
    }

    import _root_.scala.sys.process._
    import _root_.scala.concurrent._
    import _root_.scala.concurrent.duration.Duration
    import ExecutionContext.Implicits.global
    val inkuireVersion = "v1.0.0-M9"
    val inkuireLink = s"https://github.com/VirtusLab/Inkuire/releases/download/$inkuireVersion/inkuire.js"
    val inkuireDestinationFile = baseDest / "dotty_res" / "scripts" / "inkuire.js"
    sbt.IO.touch(inkuireDestinationFile)

    def tryFetch(retries: Int, timeout: Duration): Unit = {
      val downloadProcess = (new URI(inkuireLink).toURL #> inkuireDestinationFile).run()
      val result: Future[Int] = Future(blocking(downloadProcess.exitValue()))
      try {
        Await.result(result, timeout) match {
          case 0 =>
          case res if retries > 0 =>
            println(s"Failed to fetch inkuire.js from $inkuireLink: Error code $res. $retries retries left")
            tryFetch(retries - 1, timeout)
          case res => throw new MessageOnlyException(s"Failed to fetch inkuire.js from $inkuireLink: Error code $res")
        }
      } catch {
        case e: TimeoutException =>
          downloadProcess.destroy()
          if (retries > 0) {
            println(s"Failed to fetch inkuire.js from $inkuireLink: Download timeout. $retries retries left")
            tryFetch(retries - 1, timeout)
          }
          else {
            throw new MessageOnlyException(s"Failed to fetch inkuire.js from $inkuireLink: Download timeout")
          }
      }
    }

    // Offline/sandbox-friendly inkuire.js provisioning.
    // inkuire.js is a GitHub *release asset* (not a repo file); in restricted
    // network environments the download 403s/times out. The asset only powers
    // scaladoc's search box, so failing to fetch it must NOT break publishLocal.
    //  - INKUIRE_JS=/path/to/inkuire.js  -> use a locally provided copy
    //  - committed project/inkuire.js    -> use the vendored copy (default, offline)
    //  - SKIP_INKUIRE_FETCH=1            -> skip the fetch, keep the empty stub
    //  - otherwise                       -> best-effort fetch, empty stub on failure
    val vendoredInkuire = new File("project/inkuire.js")
    sys.env.get("INKUIRE_JS").map(new File(_))
      .orElse(Some(vendoredInkuire))
      .filter(_.exists) match {
      case Some(local) =>
        println(s"Using local inkuire.js from $local")
        sbt.IO.copyFile(local, inkuireDestinationFile)
      case None if sys.env.get("SKIP_INKUIRE_FETCH").contains("1") =>
        println("SKIP_INKUIRE_FETCH=1: keeping empty inkuire.js stub (scaladoc search disabled)")
      case None =>
        try tryFetch(5, Duration(60, "s"))
        catch {
          case e: Throwable =>
            println(s"WARNING: could not fetch inkuire.js (${e.getMessage}); " +
              "keeping empty stub (scaladoc search disabled)")
        }
    }
    Seq(
      inkuireDestinationFile,
      mainDestinationFile,
      contributorsDestinationFile,
      cssContentContributorsDestinationFile,
    ) ++ dests
  }
}


