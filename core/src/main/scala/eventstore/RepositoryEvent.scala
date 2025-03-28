package eventstore

import eventstore.types._
import izumi.reflect.Tag
import izumi.reflect.macrortti.LightTypeTag

import java.time.OffsetDateTime

case class RepositoryWriteEvent[+EventType: DoneBy](
    processId: ProcessId,
    aggregateId: AggregateId,
    aggregateName: AggregateName,
    aggregateVersion: AggregateVersion,
    sentDate: OffsetDateTime,
    doneBy: DoneBy[EventType]#DoneByT,
    event: EventType
)

trait DoneBy[+EventType] {
  type DoneByT
  val tag: LightTypeTag
}

sealed trait EventStoreEvent[+EventType]
case class RepositoryEvent[+EventType: Tag: DoneBy](
    processId: ProcessId,
    aggregateId: AggregateId,
    aggregateName: AggregateName,
    aggregateVersion: AggregateVersion,
    sentDate: OffsetDateTime,
    eventStoreVersion: EventStoreVersion,
    doneBy: DoneBy[EventType]#DoneByT,
    event: EventType
) extends EventStoreEvent[EventType] {
  private[eventstore] def eventTag: LightTypeTag = implicitly[Tag[EventType]].tag

  private[eventstore] def doneByTag: LightTypeTag = implicitly[DoneBy[EventType]].tag
}

case class Reset[+EventType]() extends EventStoreEvent[EventType]
