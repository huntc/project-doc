package doc

import java.io.{FileNotFoundException, File, FileOutputStream}
import java.net.URI
import java.nio.file.{Path, Files}

import akka.actor.{ActorLogging, Actor, Props}
import akka.pattern.pipe
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.io.{FileUtils, IOUtils}
import play.api.libs.iteratee.{Enumerator, Iteratee}
import play.api.libs.ws.{WSResponseHeaders, WSClient}
import play.doc.{PlayDoc, FilesystemRepository}
import play.twirl.api.Html
import spray.caching.{Cache, LruCache}
import views.html.conductr.{body, index}

import scala.collection.immutable
import scala.collection.JavaConverters._
import scala.concurrent.{Future, blocking, ExecutionContext}

object DocRenderer {

  /**
   * Render a path. Either returns the rendered Html object or NotFound/NotReady
   */
  case class Render(path: String)

  /**
   * Path is not found
   */
  case class NotFound(path: String)

  /**
   * The renderer is not ready
   */
  case object NotReady

  /**
   * Request that the site be retrieved. This is done initially on the establishment of the renderer,
   * but can be re-requested at any other time e.g. when needing to update the site due to the
   * documentation source having been updated.
   */
  case object GetSite
  
  private[doc] sealed trait Entry
  private[doc] case class Folder(name: String, documents: immutable.Seq[Entry]) extends Entry
  private[doc] case class Document(name: String, ref: URI) extends Entry

  final private val CopyBufferSize = 8192
  final private val HtmlExt = "html"
  final private val TocFilename = "index.toc"

  def props(
    docArchive: URI,
    removeRootSegment: Boolean,
    docRoot: Path,
    siteContext: URI,
    version: String,
    wsClient: WSClient): Props =
    Props(new DocRenderer(docArchive, removeRootSegment, docRoot, siteContext, version, wsClient))
  
  private[doc] def unzip(input: Enumerator[Array[Byte]], removeRootSegment: Boolean)(implicit ec: ExecutionContext): Future[Path] = {
    val archive = Files.createTempFile(null, null)
    val outputDir = Files.createTempDirectory("zip")
    FileUtils.forceDeleteOnExit(outputDir.toFile)

    val fos = new FileOutputStream(archive.toFile)

    val writeFile = Iteratee.foreach[Array[Byte]] { bytes =>
      fos.write(bytes)
    }

    (input |>>> writeFile).andThen {
      case _ =>
        blocking {
          fos.close()
          writeZip(archive, removeRootSegment, outputDir)
          archive.toFile.delete()
        }
    }.map(_ => outputDir)
  }

  private def writeZip(archive: Path, removeRootSegment: Boolean, outputDir: Path): Unit = {
    val zipFile = new ZipFile(archive.toFile)
    try {
      import scala.collection.JavaConversions._
      for (entry <- zipFile.getEntries if !entry.isDirectory) {
        val path = if (removeRootSegment)
          outputDir.resolve(entry.getName.dropWhile(_ != File.separatorChar).drop(1))
        else
          outputDir.resolve(entry.getName)
        Files.createDirectories(path.getParent)
        val out = Files.newOutputStream(path)
        try {
          IOUtils.copyLarge(zipFile.getInputStream(entry), out, 0, CopyBufferSize)
        } finally {
          out.close()
        }
      }
    } finally {
      zipFile.close()
    }
  }

  private[doc] def aggregateToc(docDir: Path, siteContext: URI): Html = {
    import HtmlPrettyPrinter._

    val folder = createEntries(docDir, siteContext, Folder("", List.empty))

    def toDoc(documents: immutable.Seq[Entry]): Doc =
      ul(documents.map {
        case document: Document =>
          li(a(document.name, document.ref))
        case folder: Folder =>
          li(folder.name, toDoc(folder.documents))
      })

    val markup = aside(toDoc(folder.documents))

    Html(HtmlPrettyPrinter.pretty(markup))
  }

  private def createEntries(docDir: Path, targetUri: URI, folder: Folder): Folder = {
    val tocEntryLines = FileUtils.readLines(docDir.resolve(TocFilename).toFile, "UTF-8")
    val newDocuments = tocEntryLines.asScala.map { entry =>
      val entries = entry.split(":")
      val (filename, name) = if (entries.size == 2) (entries(0), entries(1)) else ("", "")
      if (filename.charAt(0).isLower) {
        val subDocDir = docDir.resolve(filename)
        val subTargetUri = new URI(s"$targetUri/$filename")
        createEntries(subDocDir, subTargetUri, Folder(name, List.empty))
      } else {
        Document(name, new URI(s"$targetUri/$filename.$HtmlExt"))
      }
    }
    folder.copy(documents = folder.documents ++ newDocuments)
  }

  private def getName(path: String): String =
    path.drop(1).reverse.dropWhile(_ != '.').drop(1).takeWhile(_ != '/').reverse.dropWhile(_ == '/')
}

/**
 * Renders documentation for a given URI representing an archive of documentation.
 * Performs blocking IO and thus requires a blocking dispatcher.
 */
class DocRenderer(
  docArchive: URI,
  removeRootSegment: Boolean,
  docRoot: Path,
  siteContext: URI,
  version: String,
  wsClient: WSClient) extends Actor with ActorLogging {

  import DocRenderer._
  import context.dispatcher

  override def preStart(): Unit =
    self ! GetSite
  
  override def receive: Receive =
    handleSiteRetrieval.orElse(handleUnready)

  private def handleSiteRetrieval: Receive = {
    case GetSite =>
      log.info(s"Retrieving doc for $docArchive")
      wsClient.url(docArchive.toString).getStream().pipeTo(self)

    case (_: WSResponseHeaders, body: Enumerator[Array[Byte]] @unchecked) =>
      unzip(body, removeRootSegment).pipeTo(self)

    case docDir: Path =>
      log.info(s"Doc retrieved for $docArchive")

      val docSources = docDir.resolve(docRoot)
      val toc = aggregateToc(docSources, siteContext)

      val repo = new FilesystemRepository(docSources.toFile)
      val mdRenderer = new PlayDoc(repo, repo, "resources", version)

      context.become(handleSiteRetrieval.orElse(handleRendering(docSources, mdRenderer, toc, LruCache[Html]())))
  }
  
  private def handleUnready: Receive = {
    case _ => sender() ! NotReady
  }

  private def handleRendering(docSources: Path, mdRenderer: PlayDoc, toc: Html, cache: Cache[Html]): Receive = {
    case Render(path) if path.isEmpty || path == "/" || path == s"/Home.$HtmlExt" =>
      cache("/") {
        index(toc)
      }.pipeTo(sender())

    case Render(path) if path.endsWith(HtmlExt)=>
      cache(path) {
        val mdFilename = getName(path)
        mdRenderer.renderPage(mdFilename) match {
          case Some(renderedPage) => body(Html(renderedPage.html), toc)
          case None               => throw new FileNotFoundException(mdFilename)
        }
      }.recover {
        case _: FileNotFoundException => NotFound(path)
      }.pipeTo(sender())

    case Render(path) =>
      val resource = docSources.resolve(path.drop(1)).toFile
      sender() ! (if (resource.exists()) resource else NotFound(path))
  }
}
