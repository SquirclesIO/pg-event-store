package eventstore.memory

import eventstore.EventRepository
import eventstore.RepositoryEvent
import eventstore.RepositoryWriteEvent
import eventstore.SwitchableZStream
import eventstore.memory.MemoryEventRepository.Storage
import eventstore.types.AggregateName
import eventstore.types.AggregateVersion
import eventstore.types.EventStoreVersion
import eventstore.types.EventStreamId
import zio._
import zio.stm.TRef
import zio.stm.ZSTM
import zio.stream.Stream
import zio.stream.ZStream

import scala.collection.immutable.ListSet
import scala.math.Ordered.orderingToOrdered

import EventRepository.Error.Unexpected
import EventRepository.Error.VersionConflict
import EventRepository.Direction
import EventRepository.EventsOps
import EventRepository.FromVersion
import EventRepository.SaveEventError
import EventRepository.Subscription

class MemoryEventRepository[UnusedDecoder[_], UnusedEncoder[_]](
    storageRef: TRef[Storage],
    hub: Hub[RepositoryEvent[Any, Any]]
) extends EventRepository[UnusedDecoder, UnusedEncoder] {

  override def getEventStream[A: UnusedDecoder: Tag, Metadata: UnusedDecoder: Tag](
      eventStreamId: EventStreamId,
      direction: Direction = Direction.Forward
  ): ZIO[Scope, Unexpected, Stream[Unexpected, RepositoryEvent[A, Metadata]]] =
    storageRef.get
      .map(_.getEvents(eventStreamId))
      .commit
      .map(events =>
        direction match {
          case Direction.Forward  => events
          case Direction.Backward => events.reverse
        }
      )
      .map(x => ZStream.fromIterable(x))

  override def saveEvents[A: UnusedDecoder: UnusedEncoder: Tag, Metadata: UnusedDecoder: UnusedEncoder: Tag](
      eventStreamId: EventStreamId,
      newEvents: Seq[RepositoryWriteEvent[A, Metadata]]
  ): IO[SaveEventError, Seq[RepositoryEvent[A, Metadata]]] = for {

    events <- (for {
      _ <- ZSTM.fromEither(newEvents.checkVersionsAreContiguousIncrements)

      storage <- storageRef.get

      result <- storage.appendEvents(eventStreamId, newEvents)
      (updatedStorage, newRepositoryEvents) = result

      _ <- storageRef.set(updatedStorage)
    } yield newRepositoryEvents).commit
    _ <- hub.publishAll(events)
  } yield events

  override def getAllEvents[EventType: UnusedDecoder: Tag, Metadata: UnusedDecoder: Tag]
      : ZIO[Scope, Nothing, Stream[Unexpected, RepositoryEvent[EventType, Metadata]]] =
    for { events <- storageRef.get.map(_.events).commit } yield {
      ZStream
        .fromIterable(events)
        .map(_.asInstanceOf[RepositoryEvent[EventType, Metadata]])
    }

  override def getEventByStoreVersion[EventType: UnusedDecoder: Tag, Metadata: UnusedDecoder: Tag](
      version: EventStoreVersion
  ): IO[Nothing, Option[RepositoryEvent[EventType, Metadata]]] =
    storageRef.get.map(_.getEventByStoreVersion(version)).commit

  override def listEventStreamWithName(
      aggregateName: AggregateName,
      direction: Direction = Direction.Forward
  ): Stream[Unexpected, EventStreamId] =
    ZStream.fromIterableZIO(
      for { aggregates <- storageRef.get.map(_.aggregates).commit } yield {
        val naturalyOrdered = aggregates.filter(_.aggregateName == aggregateName).toList
        direction match {
          case Direction.Forward  => naturalyOrdered
          case Direction.Backward => naturalyOrdered.reverse
        }
      }
    )

  override def listen[EventType: UnusedDecoder: Tag, Metadata: UnusedDecoder: Tag]: ZIO[Scope, Unexpected, Subscription[EventType, Metadata]] =
    listenImpl(live = fromHub[EventType, Metadata])

  override def listenFromVersion[EventType: UnusedDecoder: Tag, Metadata: UnusedDecoder: Tag](
      from: FromVersion
  ): ZIO[Scope, Unexpected, Subscription[EventType, Metadata]] = {
    val predicate: RepositoryEvent[EventType, Metadata] => Boolean = from match {
      case FromVersion.Exclusive(version) => _.eventStoreVersion <= version
      case FromVersion.Inclusive(version) => _.eventStoreVersion < version
      case FromVersion.Initial            => _ => false
    }
    val live = for {
      fromDb <- getAllEvents[EventType, Metadata]
      fromHub <- fromHub[EventType, Metadata]
    } yield fromDb.concat(fromHub).dropWhile(predicate)
    listenImpl(live = live)
  }

  private def listenImpl[EventType: UnusedDecoder: Tag, Metadata: UnusedDecoder: Tag](
      live: ZIO[Scope, Nothing, ZStream[Any, Unexpected, RepositoryEvent[EventType, Metadata]]]
  ): ZIO[Scope, Unexpected, Subscription[EventType, Metadata]] = {
    val fromDb = getAllEvents[EventType, Metadata]

    for {
      live <- live
      switchableStream <- SwitchableZStream.from(live, fromDb)
    } yield Subscription.fromSwitchableStream(switchableStream, getLastEventStoreVersion)
  }

  private def fromHub[EventType: UnusedDecoder: Tag, Metadata: UnusedDecoder: Tag]
      : ZIO[Scope, Nothing, ZStream[Any, Nothing, RepositoryEvent[EventType, Metadata]]] = {
    val typeTag = implicitly[Tag[EventType]]

    hub.subscribe.map { subscription =>
      ZStream
        .fromQueue(subscription)
        .collect {
          case event: RepositoryEvent[Any, Any] if event.eventTag <:< typeTag.tag =>
            event.asInstanceOf[RepositoryEvent[EventType, Metadata]]
        }
    }

  }

  def getLastEventStoreVersion: IO[Unexpected, Option[EventStoreVersion]] =
    storageRef.get.map(_.events.lastOption.map(_.eventStoreVersion)).commit

}

object MemoryEventRepository {

  type Id[A] = Unit

  case class Storage(
      events: List[RepositoryEvent[?, ?]],
      byAggregate: Map[EventStreamId, List[RepositoryEvent[?, ?]]],
      aggregates: ListSet[EventStreamId]
  ) {

    def appendEvents[A: Tag, Metadata: Tag](
        eventStreamId: EventStreamId,
        newEvents: Seq[RepositoryWriteEvent[A, Metadata]]
    ): ZSTM[Any, SaveEventError, (Storage, Seq[RepositoryEvent[A, Metadata]])] = {
      val currentEvents = getEvents(eventStreamId)
      for {
        _ <- checkExpectedVersion(currentEvents, newEvents)
        eventStoreVersion = currentEvents.lastOption.map(_.eventStoreVersion).getOrElse(EventStoreVersion.initial)
        newRepositoryEvents = newEvents.toRepositoryEvents(eventStoreVersion)
      } yield copy(
        byAggregate = byAggregate.updated(eventStreamId, currentEvents ++ newRepositoryEvents),
        events = events ++ newRepositoryEvents,
        aggregates = aggregates + eventStreamId
      ) -> newRepositoryEvents
    }

    implicit class EventsOps[A: Tag, Metadata: Tag](self: Seq[RepositoryWriteEvent[A, Metadata]]) {
      def toRepositoryEvents(eventStoreVersion: EventStoreVersion): Seq[RepositoryEvent[A, Metadata]] =
        self
          .zip(LazyList.iterate(eventStoreVersion.next)(v => v.next))
          .map { case (evt, version) =>
            RepositoryEvent(
              processId = evt.processId,
              aggregateId = evt.aggregateId,
              aggregateName = evt.aggregateName,
              aggregateVersion = evt.aggregateVersion,
              sentDate = evt.sentDate,
              eventStoreVersion = version,
              event = evt.event,
              metadata = evt.metadata
            )
          }
    }

    private def checkExpectedVersion(
        currentEvents: Seq[RepositoryEvent[?, ?]],
        newEvents: Seq[RepositoryWriteEvent[?, ?]]
    ) = {
      newEvents.headOption
        .map { headEvent =>
          val headVersion = headEvent.aggregateVersion
          val expectedVersion = {
            currentEvents.lastOption
              .map(_.aggregateVersion.next)
              .getOrElse(AggregateVersion.initial)
          }
          ZSTM
            .fail[SaveEventError](VersionConflict(headVersion, expectedVersion))
            .unless(headVersion == expectedVersion)
        }
        .getOrElse(ZSTM.unit)
    }

    def getEvents[A, Metadata](eventStreamId: EventStreamId): List[RepositoryEvent[A, Metadata]] =
      byAggregate
        .getOrElse(key = eventStreamId, default = List.empty)
        .asInstanceOf[List[RepositoryEvent[A, Metadata]]]

    def getEventByStoreVersion[A, Metadata](version: EventStoreVersion): Option[RepositoryEvent[A, Metadata]] =
      events
        .find(_.eventStoreVersion == version)
        .asInstanceOf[Option[RepositoryEvent[A, Metadata]]]

  }

  def layer[UnusedDecoder[_]: TagK, UnusedEncoder[_]: TagK]: ULayer[EventRepository[UnusedDecoder, UnusedEncoder]] = {
    ZLayer {
      for {
        map <- TRef.makeCommit(Storage(List.empty, Map.empty, ListSet.empty))
        hub <- Hub.unbounded[RepositoryEvent[Any, Any]]
      } yield new MemoryEventRepository(map, hub)
    }
  }
}
