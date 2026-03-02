package eventstore

import eventstore.types.AggregateVersion
import eventstore.types.EventStoreVersion
import eventstore.types.EventStreamId
import eventstore.types.ProcessId
import zio.Tag

import java.time.OffsetDateTime

object RepositoryEventOps {

  implicit class Ops[A: Tag](a: A) {
    def asRepositoryEvent(
        version: AggregateVersion = AggregateVersion.initial,
        eventStoreVersion: EventStoreVersion = EventStoreVersion.initial,
        streamId: EventStreamId
    ) = {
      for {
        processId <- ProcessId.generate
      } yield RepositoryEvent[A](
        processId = processId,
        aggregateId = streamId.aggregateId,
        aggregateName = streamId.aggregateName,
        sentDate = OffsetDateTime.parse("2027-12-03T10:15:30+01:00"),
        aggregateVersion = version,
        event = a,
        eventStoreVersion = eventStoreVersion
      )
    }
    def asRepositoryWriteEvent(
        version: AggregateVersion = AggregateVersion.initial,
        streamId: EventStreamId
    ) = {
      for {
        processId <- ProcessId.generate
      } yield RepositoryWriteEvent[A](
        processId = processId,
        aggregateId = streamId.aggregateId,
        aggregateName = streamId.aggregateName,
        sentDate = OffsetDateTime.parse("2027-12-03T10:15:30+01:00"),
        aggregateVersion = version,
        event = a
      )
    }
    def asRepositoryWriteEventWithDoneBy[U](
        version: AggregateVersion = AggregateVersion.initial,
        streamId: EventStreamId,
        user: U
    ) = {
      for {
        processId <- ProcessId.generate
      } yield RepositoryWriteEvent[(A, U)](
        processId = processId,
        aggregateId = streamId.aggregateId,
        aggregateName = streamId.aggregateName,
        sentDate = OffsetDateTime.parse("2027-12-03T10:15:30+01:00"),
        aggregateVersion = version,
        event = (a, user)
      )
    }
  }

}
