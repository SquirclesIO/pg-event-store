package eventstore.playjson.pg
import eventstore.EventRepository
import eventstore.EventRepository.Error.Unexpected
import eventstore.RepositoryEvent
import eventstore.RepositoryWriteEvent
import eventstore.types.AggregateName
import eventstore.types.EventStoreVersion
import eventstore.types.EventStreamId
import org.typelevel.doobie._
import play.api.libs.json.Json
import play.api.libs.json.Reads
import play.api.libs.json.Writes
import zio.ZIO
import zio._
import zio.stream.Stream
import zio.stream.ZStream

import EventRepository.Direction
import EventRepository.Error
import EventRepository.FromVersion
import EventRepository.Subscription

private class PostgresPlayJsonEventRepository(
    postgresEventRepositoryLive: EventRepository[Get, Put]
) extends EventRepository[Reads, Writes] {

  private def getJson[A: Reads: Tag]: Get[A] =
    Get[String].temap(input =>
      Json
        .parse(input)
        .validate[A]
        .asEither
        .left
        .map(msg => s"trying to decode $input as ${implicitly[Tag[A]].tag}, error $msg")
    )

  private def putJson[A](implicit writes: Writes[A]): Put[A] =
    Put[String].contramap(obj => Json.stringify(writes.writes(obj)))

  override def getEventStream[EventType: Reads: Tag, Metadata: Reads: Tag](
      eventStreamId: EventStreamId,
      direction: Direction = Direction.Forward
  ): ZIO[Scope, Unexpected, Stream[Unexpected, RepositoryEvent[EventType, Metadata]]] = {
    implicit val getEventType: Get[EventType] = getJson[EventType]
    implicit val getMetadata: Get[Metadata] = getJson[Metadata]
    postgresEventRepositoryLive.getEventStream[EventType, Metadata](eventStreamId, direction)
  }

  override def saveEvents[EventType: Reads: Writes: Tag, Metadata: Reads: Writes: Tag](
      eventStreamId: EventStreamId,
      events: Seq[RepositoryWriteEvent[EventType, Metadata]]
  ): IO[EventRepository.SaveEventError, Seq[RepositoryEvent[EventType, Metadata]]] = {
    implicit val getEventType: Get[EventType] = getJson[EventType]
    implicit val putEventType: Put[EventType] = putJson[EventType]
    implicit val getMetadata: Get[Metadata] = getJson[Metadata]
    implicit val putMetadata: Put[Metadata] = putJson[Metadata]
    postgresEventRepositoryLive.saveEvents(eventStreamId, events)
  }

  override def listen[EventType: Reads: Tag, Metadata: Reads: Tag]: ZIO[
    Scope,
    Error.Unexpected,
    EventRepository.Subscription[EventType, Metadata]
  ] = {
    implicit val getEventType: Get[EventType] = getJson[EventType]
    implicit val getMetadata: Get[Metadata] = getJson[Metadata]
    postgresEventRepositoryLive.listen[EventType, Metadata]

  }

  override def listenFromVersion[EventType: Reads: Tag, Metadata: Reads: Tag](
      from: FromVersion
  ): ZIO[Scope, Unexpected, Subscription[EventType, Metadata]] = {
    implicit val getEventType: Get[EventType] = getJson[EventType]
    implicit val getMetadata: Get[Metadata] = getJson[Metadata]
    postgresEventRepositoryLive.listenFromVersion[EventType, Metadata](from)
  }

  override def getAllEvents[A: Reads: Tag, Metadata: Reads: Tag]: ZIO[Scope, Nothing, Stream[Unexpected, RepositoryEvent[A, Metadata]]] = {
    implicit val getEventType: Get[A] = getJson[A]
    implicit val getMetadata: Get[Metadata] = getJson[Metadata]
    postgresEventRepositoryLive.getAllEvents[A, Metadata]
  }

  override def getEventByStoreVersion[A: Reads: Tag, Metadata: Reads: Tag](
      version: EventStoreVersion
  ): IO[Unexpected, Option[RepositoryEvent[A, Metadata]]] = {
    implicit val getEventType: Get[A] = getJson[A]
    implicit val getMetadata: Get[Metadata] = getJson[Metadata]
    postgresEventRepositoryLive.getEventByStoreVersion[A, Metadata](version)
  }

  override def listEventStreamWithName(
      aggregateName: AggregateName,
      direction: Direction = Direction.Forward
  ): ZStream[Any, Error.Unexpected, EventStreamId] =
    postgresEventRepositoryLive.listEventStreamWithName(aggregateName, direction)

  override def getLastEventStoreVersion: IO[Unexpected, Option[EventStoreVersion]] =
    postgresEventRepositoryLive.getLastEventStoreVersion
}

object PostgresPlayJsonEventRepository {
  def layer: URLayer[EventRepository[Get, Put], EventRepository[Reads, Writes]] =
    ZLayer.derive[PostgresPlayJsonEventRepository]
}
