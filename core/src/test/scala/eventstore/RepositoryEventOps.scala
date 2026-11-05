package eventstore

import eventstore.types.AggregateVersion
import eventstore.types.EventStoreVersion
import eventstore.types.EventStreamId
import eventstore.types.ProcessId
import zio.Tag
import zio.ZIO

import java.time.OffsetDateTime

object RepositoryEventOps {

  implicit class Ops[A: Tag](a: A) {
    def asRepositoryEvent(
        version: AggregateVersion = AggregateVersion.initial,
        eventStoreVersion: EventStoreVersion = EventStoreVersion.initial,
        streamId: EventStreamId
    ): ZIO[Any, Nothing, RepositoryEvent[A, Unit]] = {
      for {
        processId <- ProcessId.generate
      } yield RepositoryEvent[A, Unit](
        processId = processId,
        aggregateId = streamId.aggregateId,
        aggregateName = streamId.aggregateName,
        sentDate = OffsetDateTime.parse("2027-12-03T10:15:30+01:00"),
        aggregateVersion = version,
        event = a,
        eventStoreVersion = eventStoreVersion,
        metadata = ()
      )
    }
    def asRepositoryWriteEvent(
        version: AggregateVersion = AggregateVersion.initial,
        streamId: EventStreamId
    ): ZIO[Any, Nothing, RepositoryWriteEvent[A, Unit]] = {
      for {
        processId <- ProcessId.generate
      } yield RepositoryWriteEvent[A, Unit](
        processId = processId,
        aggregateId = streamId.aggregateId,
        aggregateName = streamId.aggregateName,
        sentDate = OffsetDateTime.parse("2027-12-03T10:15:30+01:00"),
        aggregateVersion = version,
        event = a,
        metadata = ()
      )
    }
    def asRepositoryWriteEventWithDoneBy[U](
        version: AggregateVersion = AggregateVersion.initial,
        streamId: EventStreamId,
        user: U
    ): ZIO[Any, Nothing, RepositoryWriteEvent[(A, U), Unit]] = {
      for {
        processId <- ProcessId.generate
      } yield RepositoryWriteEvent[(A, U), Unit](
        processId = processId,
        aggregateId = streamId.aggregateId,
        aggregateName = streamId.aggregateName,
        sentDate = OffsetDateTime.parse("2027-12-03T10:15:30+01:00"),
        aggregateVersion = version,
        event = (a, user),
        metadata = ()
      )
    }
  }

  implicit class WriteEventsOps[+E](
      events: Seq[RepositoryEvent[E, Unit]]
  ) {
    def asRepositoryWriteEvents: Seq[RepositoryWriteEvent[E, Unit]] =
      events.map(_.asRepositoryWriteEvent)
  }

  implicit class WriteEventOps[+E](
      event: RepositoryEvent[E, Unit]
  ) {
    def asRepositoryWriteEvent =
      RepositoryWriteEvent(
        processId = event.processId,
        aggregateId = event.aggregateId,
        aggregateName = event.aggregateName,
        aggregateVersion = event.aggregateVersion,
        sentDate = event.sentDate,
        event = event.event,
        metadata = ()
      )
  }

}
