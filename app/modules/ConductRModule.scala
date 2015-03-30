package modules

import java.net.URI
import java.nio.file.Paths
import javax.inject.{Provider, Inject, Singleton}

import akka.actor.{ActorRef, ActorSystem}
import doc.DocRenderer
import play.api.libs.ws.WSClient

object ConductRModule {

  @Singleton
  class ConductRDocRendererProvider @Inject()(actorSystem: ActorSystem, wsClient: WSClient) extends Provider[ActorRef] {

    override def get =
      actorSystem.actorOf(DocRenderer.props(
        new URI("https://github.com/huntc/conductr-doc/archive/master.zip"),
        Paths.get("src/main/play-doc"),
        new URI("/conductr"),
        removeRootSegment = true,
        wsClient), "conductr-doc-renderer")
  }
}
