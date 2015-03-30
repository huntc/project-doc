package doc

import java.net.URI

import akka.actor.{Actor, Props}
import play.twirl.api.Html
import views.html.conductr.index

object DocRenderer {
  case class Render(path: String)

  def props(githubUri: URI): Props =
    Props(new DocRenderer(githubUri))
}

class DocRenderer(githubUri: URI) extends Actor {

  import DocRenderer._

  override def receive: Receive = {
    case Render(path) if path.isEmpty || path == "/" =>
      // TODO: This TOC will be generated
      sender() ! Some(index(Html(
        """
          |<aside>
          |<h3><a href="/conductr">Toc Item One</a></h3>
          |<h3><a href="/conductr" class="active">Toc Item Two</a></h3>
          |<ul>
          |  <li><a href="/conductr/install.html">Toc Sub Item One</a></li>
          |  <li><a href="/conductr/quickstart.html">Toc Sub Item Two</a></li>
          |  <li><a href="/conductr/quickstart.html">Toc Sub Item Two</a></li>
          |</ul>
          |<h3><a href="/conductr">Toc Item Three</a></h3>
          |<h3><a href="/conductr">Toc Item Four</a></h3>
          |</aside>""".stripMargin)))
    case Render(_) =>
      sender() ! None
  }
}
