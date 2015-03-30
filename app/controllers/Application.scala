package controllers

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject

import akka.pattern.{AskTimeoutException, ask}
import doc.DocRenderer
import modules.ConductRModule.ConductRDocRendererProvider
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.iteratee.Iteratee
import play.api.mvc._
import play.twirl.api.Html
import settings.Settings

object Application {
  object Project extends Enumeration {
    val ConductR = Value
  }

  private[controllers] object MacBodyParser {
    def apply(hmacHeader: String, secret: SecretKeySpec, algorithm: String) =
      new MacBodyParser(hmacHeader, secret, algorithm)
  }

  private[controllers] class MacBodyParser(
    hmacHeader: String,
    secret: SecretKeySpec,
    algorithm: String) extends BodyParser[Unit] {

    def hex2bytes(hex: String): Array[Byte] =
      hex.replaceAll("[^0-9A-Fa-f]", "").sliding(2, 2).toArray.map(Integer.parseInt(_, 16).toByte)

    override def apply(request: RequestHeader): Iteratee[Array[Byte], Either[Result, Unit]] = {
      val signature = hex2bytes(request.headers.get(hmacHeader).getOrElse(""))
      Iteratee.fold[Array[Byte], Mac] {
        val mac = Mac.getInstance(algorithm)
        mac.init(secret)
        mac
      } { (mac, bytes) =>
        mac.update(bytes)
        mac
      }.map {
        case mac if signature.isEmpty                     => Left(Results.BadRequest(s"No $hmacHeader header present"))
        case mac if mac.doFinal().sameElements(signature) => Right(())
        case mac                                          => Left(Results.Unauthorized("Bad signature"))
      }
    }
  }
}

class Application @Inject() (conductrDocRendererProvider: ConductRDocRendererProvider, settings: Settings)
  extends Controller {

  import Application._

  private final val MacAlgorithm = "HmacSHA1"
  private final val GitHubSignature = "X-Hub-Signature"

  private val conductrDocRenderer = conductrDocRendererProvider.get

  private val secret = new SecretKeySpec(settings.play.crypto.secret.getBytes, MacAlgorithm)

  def render(project: Project.Value, path: String) = Action.async {
    project match {
      case Project.ConductR =>
        conductrDocRenderer.actorRef
          .ask(DocRenderer.Render(path))(settings.doc.renderer.timeout)
          .map {
            case html: Html              => Ok(html)
            case DocRenderer.NotFound(p) => NotFound(s"Cannot find $p")
            case DocRenderer.NotReady    => ServiceUnavailable("Initializing documentation. Please try again in a minute.")
          }
          .recover {
            case _: AskTimeoutException => InternalServerError
          }
    }
  }

  def update(project: Project.Value) = Action(MacBodyParser(GitHubSignature, secret, MacAlgorithm)) { _ =>
    project match {
      case Project.ConductR =>
        conductrDocRenderer.actorRef ! DocRenderer.GetSite
        Ok("Site update requested")
    }
  }
}