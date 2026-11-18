package eventstore

import eventstore.types._
import izumi.reflect.Tag
import izumi.reflect.macrortti.LightTypeTag

import java.time.OffsetDateTime

case class RepositoryWriteEvent[+EventType, +Metadata](
    processId: ProcessId,
    aggregateId: AggregateId,
    aggregateName: AggregateName,
    aggregateVersion: AggregateVersion,
    sentDate: OffsetDateTime,
    event: EventType,
    metadata: Metadata
)

sealed trait EventStoreEvent[+EventType, +Metadata]
case class RepositoryEvent[+EventType: Tag, +Metadata](
    processId: ProcessId,
    aggregateId: AggregateId,
    aggregateName: AggregateName,
    aggregateVersion: AggregateVersion,
    sentDate: OffsetDateTime,
    eventStoreVersion: EventStoreVersion,
    event: EventType,
    metadata: Metadata
) extends EventStoreEvent[EventType, Metadata] {
  private[eventstore] def eventTag: LightTypeTag = implicitly[Tag[EventType]].tag

  def isEventSubtypeOf[A: Tag]: Boolean = eventTag <:< implicitly[Tag[A]].tag
}

case object Reset extends EventStoreEvent[Nothing, Nothing]

case object SwitchedToLive extends EventStoreEvent[Nothing, Nothing]
