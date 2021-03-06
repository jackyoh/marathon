package mesosphere.marathon
package core.base

import akka.actor.{ ActorRefFactory, ActorSystem }
import akka.stream.ActorMaterializer
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * Contains basic dependencies used throughout the application disregarding the concrete function.
  */
class ActorsModule(shutdownHooks: ShutdownHooks, actorSystem: ActorSystem = ActorSystem()) {
  private[this] val log = LoggerFactory.getLogger(getClass)

  def actorRefFactory: ActorRefFactory = actorSystem
  val materializer = ActorMaterializer()(actorRefFactory)

  shutdownHooks.onShutdown {
    log.info("Shutting down actor system {}", actorSystem)
    Await.result(actorSystem.terminate(), 10.seconds)
  }
}
