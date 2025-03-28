package eventstore

import eventstore.types._
import izumi.reflect.Tag
import izumi.reflect.macrortti.LightTypeTag

import java.time.OffsetDateTime

case class RepositoryWriteEvent[+EventType, +DoneBy](
    processId: ProcessId,
    aggregateId: AggregateId,
    aggregateName: AggregateName,
    aggregateVersion: AggregateVersion,
    sentDate: OffsetDateTime,
    doneBy: DoneBy,
    event: EventType
)

trait Tuple2Accessor[A] {
  type EvenType
  type DoneBy
}
object Toto {
  implicit def toto[A, B]: Tuple2Accessor[(A, B)] = new Tuple2Accessor[(A, B)] {
    override type EvenType = A
    override type DoneBy = B
  }
}

import Toto.*

sealed trait EventStoreEvent[+E <: (?, ?)]
case class RepositoryEvent[E <: (?, ?) : Tag : Tuple2Accessor](
    processId: ProcessId,
    aggregateId: AggregateId,
    aggregateName: AggregateName,
    aggregateVersion: AggregateVersion,
    sentDate: OffsetDateTime,
    eventStoreVersion: EventStoreVersion,
    doneBy: Tuple2Accessor[E]#EvenType,
    event: Tuple2Accessor[E]#DoneBy
) extends EventStoreEvent[E] {
  private[eventstore] def eventTag: LightTypeTag = implicitly[Tag[EventType]].tag

  private[eventstore] def doneByTag: LightTypeTag = implicitly[Tag[DoneBy]].tag
}

case class Reset[+EventType, +DoneBy]() extends EventStoreEvent[EventType, DoneBy]
