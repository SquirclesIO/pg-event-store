package eventstore.memory

import eventstore.{DoneBy, EventRepository, RepositoryEvent, RepositoryWriteEvent, SwitchableZStream}
import eventstore.memory.MemoryEventRepository.Storage
import eventstore.types.AggregateName
import eventstore.types.AggregateVersion
import eventstore.types.EventStoreVersion
import eventstore.types.EventStreamId
import zio.*
import zio.stm.TRef
import zio.stm.ZSTM
import zio.stream.Stream
import zio.stream.ZStream

import scala.collection.immutable.ListSet
import EventRepository.Error.Unexpected
import EventRepository.Error.VersionConflict
import EventRepository.{Direction, EventsOps, SaveEventError, Subscription}

class MemoryEventRepository[UnusedDecoder[_], UnusedEncoder[_]](
    storageRef: TRef[Storage],
    hub: Hub[RepositoryEvent[Any]]
) extends EventRepository[UnusedDecoder, UnusedEncoder] {

  override def getEventStream[A: UnusedDecoder: Tag: DoneBy](
      eventStreamId: EventStreamId
  ): IO[Unexpected, Seq[RepositoryEvent[A]]] = storageRef.get.map(_.getEvents(eventStreamId)).commit

  override def saveEvents[A: UnusedDecoder: UnusedEncoder: Tag: DoneBy](
      eventStreamId: EventStreamId,
      newEvents: Seq[RepositoryWriteEvent[A]]
  ): IO[SaveEventError, Seq[RepositoryEvent[A]]] = for {

    events <- (for {
      _ <- ZSTM.fromEither(newEvents.checkVersionsAreContiguousIncrements)

      storage <- storageRef.get

      result <- storage.appendEvents(eventStreamId, newEvents)
      (updatedStorage, newRepositoryEvents) = result

      _ <- storageRef.set(updatedStorage)
    } yield newRepositoryEvents).commit
    _ <- hub.publishAll(events)
  } yield events

  override def getAllEvents[EventType: UnusedDecoder: Tag: DoneBy]
      : ZIO[Scope, Nothing, Stream[Unexpected, RepositoryEvent[EventType]]] =
    for { events <- storageRef.get.map(_.events).commit } yield {
      ZStream
        .fromIterable(events)
        .map(_.asInstanceOf[RepositoryEvent[EventType]])
    }

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

  override def listen[EventType: UnusedDecoder: Tag: DoneBy]
      : ZIO[Scope, Unexpected, Subscription[EventType]] = {
    val fromDb = getAllEvents[EventType]

    val typeTag = implicitly[Tag[EventType]]
    val doneTag = implicitly[DoneBy[EventType]].tag

    for {
      live <- hub.subscribe.map { subscription =>
        ZStream
          .fromQueue(subscription)
          .collect {
            case event: RepositoryEvent[Any]
                if event.eventTag <:< typeTag.tag && event.doneByTag <:< doneTag =>
              event.asInstanceOf[RepositoryEvent[EventType]]
          }
      }

      switchableStream <- SwitchableZStream.from(live, fromDb)

    } yield Subscription.fromSwitchableStream(switchableStream, getLastEventVersion)
  }

  private def getLastEventVersion: IO[Unexpected, Option[EventStoreVersion]] =
    storageRef.get.map(_.events.lastOption.map(_.eventStoreVersion)).commit

}

object MemoryEventRepository {

  type Id[A] = Unit

  case class Storage(
      events: List[RepositoryEvent[?]],
      byAggregate: Map[EventStreamId, List[RepositoryEvent[?]]],
      aggregates: ListSet[EventStreamId]
  ) {

    def appendEvents[A: Tag: DoneBy](
        eventStreamId: EventStreamId,
        newEvents: Seq[RepositoryWriteEvent[A]]
    ): ZSTM[Any, SaveEventError, (Storage, Seq[RepositoryEvent[A]])] = {
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

    implicit class EventsOps[A: Tag: DoneBy](self: Seq[RepositoryWriteEvent[A]]) {
      def toRepositoryEvents(eventStoreVersion: EventStoreVersion): Seq[RepositoryEvent[A]] =
        self
          .zip(LazyList.iterate(eventStoreVersion.next)(v => v.next))
          .map { case (evt, version) =>
            RepositoryEvent(
              evt.processId,
              evt.aggregateId,
              evt.aggregateName,
              evt.aggregateVersion,
              evt.sentDate,
              version,
              evt.doneBy,
              evt.event
            )
          }
    }

    private def checkExpectedVersion(
        currentEvents: Seq[RepositoryEvent[?]],
        newEvents: Seq[RepositoryWriteEvent[?]]
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

    def getEvents[A, DoneBy](eventStreamId: EventStreamId): List[RepositoryEvent[A]] =
      byAggregate
        .getOrElse(key = eventStreamId, default = List.empty)
        .asInstanceOf[List[RepositoryEvent[A]]]

  }

  def layer[UnusedDecoder[_]: TagK, UnusedEncoder[_]: TagK]: ULayer[EventRepository[UnusedDecoder, UnusedEncoder]] = {
    ZLayer {
      for {
        map <- TRef.makeCommit(Storage(List.empty, Map.empty, ListSet.empty))
        hub <- Hub.unbounded[RepositoryEvent[Any]]
      } yield new MemoryEventRepository(map, hub)
    }
  }
}
