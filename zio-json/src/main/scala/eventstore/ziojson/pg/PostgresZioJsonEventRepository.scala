package eventstore.ziojson.pg

import eventstore.EventRepository
import eventstore.EventRepository.Direction
import eventstore.EventRepository.Error.Unexpected
import eventstore.EventRepository.FromVersion
import eventstore.EventRepository.Subscription
import eventstore.RepositoryEvent
import eventstore.RepositoryWriteEvent
import eventstore.types.AggregateName
import eventstore.types.EventStoreVersion
import eventstore.types.EventStreamId
import org.typelevel.doobie.Get
import org.typelevel.doobie.Put
import zio._
import zio.json._
import zio.stream.Stream
import zio.stream.ZStream

private class PostgresZioJsonEventRepository(
    postgresEventRepositoryLive: EventRepository[Get, Put]
) extends EventRepository[JsonDecoder, JsonEncoder] {

  def getJson[A: JsonDecoder: Tag]: Get[A] =
    Get[String].temap(input => {
      implicitly[JsonDecoder[A]]
        .decodeJson(input)
        .left
        .map(msg => s"trying to decode $input as ${implicitly[Tag[A]].tag}, error $msg")
    })

  def putJson[A](implicit encoder: JsonEncoder[A]): Put[A] =
    Put[String].contramap(obj => encoder.encodeJson(obj, indent = None).toString)

  override def getEventStream[A: JsonDecoder: Tag, Metadata: JsonDecoder: Tag](
      eventStreamId: EventStreamId,
      direction: Direction = Direction.Forward
  ): ZIO[Scope, Unexpected, Stream[Unexpected, RepositoryEvent[A, Metadata]]] = {
    implicit val getA: Get[A] = getJson[A]
    implicit val getMetadata: Get[Metadata] = getJson[Metadata]
    postgresEventRepositoryLive.getEventStream[A, Metadata](eventStreamId, direction)
  }

  override def saveEvents[A: JsonDecoder: JsonEncoder: Tag, Metadata: JsonDecoder: JsonEncoder: Tag](
      eventStreamId: EventStreamId,
      events: Seq[RepositoryWriteEvent[A, Metadata]]
  ): IO[EventRepository.SaveEventError, Seq[RepositoryEvent[A, Metadata]]] = {
    implicit val getA: Get[A] = getJson[A]
    implicit val putA: Put[A] = putJson[A]
    implicit val getMetadata: Get[Metadata] = getJson[Metadata]
    implicit val putMetadata: Put[Metadata] = putJson[Metadata]
    postgresEventRepositoryLive.saveEvents(eventStreamId, events)
  }

  override def listen[EventType: JsonDecoder: Tag, Metadata: JsonDecoder: Tag]: ZIO[
    Scope,
    Unexpected,
    EventRepository.Subscription[EventType, Metadata]
  ] = {
    implicit val getEventType: Get[EventType] = getJson[EventType]
    implicit val getMetadata: Get[Metadata] = getJson[Metadata]
    postgresEventRepositoryLive.listen[EventType, Metadata]

  }

  override def listenFromVersion[EventType: JsonDecoder: Tag, Metadata: JsonDecoder: Tag](
      from: FromVersion
  ): ZIO[Scope, Unexpected, Subscription[EventType, Metadata]] = {
    implicit val getEventType: Get[EventType] = getJson[EventType]
    implicit val getMetadata: Get[Metadata] = getJson[Metadata]
    postgresEventRepositoryLive.listenFromVersion[EventType, Metadata](from)
  }

  override def getAllEvents[A: JsonDecoder: Tag, Metadata: JsonDecoder: Tag]: ZIO[Scope, Nothing, Stream[Unexpected, RepositoryEvent[A, Metadata]]] = {
    implicit val getA: Get[A] = getJson[A]
    implicit val getMetadata: Get[Metadata] = getJson[Metadata]
    postgresEventRepositoryLive.getAllEvents[A, Metadata]
  }

  override def getEventByStoreVersion[A: JsonDecoder: Tag, Metadata: JsonDecoder: Tag](
      version: EventStoreVersion
  ): IO[Unexpected, Option[RepositoryEvent[A, Metadata]]] = {
    implicit val getA: Get[A] = getJson[A]
    implicit val getMetadata: Get[Metadata] = getJson[Metadata]
    postgresEventRepositoryLive.getEventByStoreVersion[A, Metadata](version)
  }

  override def listEventStreamWithName(
      aggregateName: AggregateName,
      direction: Direction = Direction.Forward
  ): ZStream[Any, Unexpected, EventStreamId] =
    postgresEventRepositoryLive.listEventStreamWithName(aggregateName, direction)

  override def getLastEventStoreVersion: IO[Unexpected, Option[EventStoreVersion]] =
    postgresEventRepositoryLive.getLastEventStoreVersion
}

object PostgresZioJsonEventRepository {

  def layer: URLayer[EventRepository[Get, Put], EventRepository[JsonDecoder, JsonEncoder]] =
    ZLayer.derive[PostgresZioJsonEventRepository]
}
