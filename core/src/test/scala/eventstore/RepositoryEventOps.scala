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
    def asRepositoryEvent[Metadata](
        version: AggregateVersion = AggregateVersion.initial,
        eventStoreVersion: EventStoreVersion = EventStoreVersion.initial,
        streamId: EventStreamId,
        metadata: Metadata
    ): ZIO[Any, Nothing, RepositoryEvent[A, Metadata]] = {
      for {
        processId <- ProcessId.generate
      } yield RepositoryEvent[A, Metadata](
        processId = processId,
        aggregateId = streamId.aggregateId,
        aggregateName = streamId.aggregateName,
        sentDate = OffsetDateTime.parse("2027-12-03T10:15:30+01:00"),
        aggregateVersion = version,
        event = a,
        eventStoreVersion = eventStoreVersion,
        metadata = metadata
      )
    }
    def asRepositoryWriteEvent[Metadata](
        version: AggregateVersion = AggregateVersion.initial,
        streamId: EventStreamId,
        metadata: Metadata
    ): ZIO[Any, Nothing, RepositoryWriteEvent[A, Metadata]] = {
      for {
        processId <- ProcessId.generate
      } yield RepositoryWriteEvent[A, Metadata](
        processId = processId,
        aggregateId = streamId.aggregateId,
        aggregateName = streamId.aggregateName,
        sentDate = OffsetDateTime.parse("2027-12-03T10:15:30+01:00"),
        aggregateVersion = version,
        event = a,
        metadata = metadata
      )
    }
    def asRepositoryWriteEventWithDoneBy[U, Metadata](
        version: AggregateVersion = AggregateVersion.initial,
        streamId: EventStreamId,
        user: U,
        metadata: Metadata
    ): ZIO[Any, Nothing, RepositoryWriteEvent[(A, U), Metadata]] = {
      for {
        processId <- ProcessId.generate
      } yield RepositoryWriteEvent[(A, U), Metadata](
        processId = processId,
        aggregateId = streamId.aggregateId,
        aggregateName = streamId.aggregateName,
        sentDate = OffsetDateTime.parse("2027-12-03T10:15:30+01:00"),
        aggregateVersion = version,
        event = (a, user),
        metadata = metadata
      )
    }
  }

  implicit class WriteEventsOps[+E, Metadata](
      events: Seq[RepositoryEvent[E, Metadata]]
  ) {
    def asRepositoryWriteEvents: Seq[RepositoryWriteEvent[E, Metadata]] =
      events.map(_.asRepositoryWriteEvent)
  }

  implicit class WriteEventOps[+E, +Metadata](
      event: RepositoryEvent[E, Metadata]
  ) {
    def asRepositoryWriteEvent =
      RepositoryWriteEvent(
        processId = event.processId,
        aggregateId = event.aggregateId,
        aggregateName = event.aggregateName,
        aggregateVersion = event.aggregateVersion,
        sentDate = event.sentDate,
        event = event.event,
        metadata = event.metadata
      )
  }

}
