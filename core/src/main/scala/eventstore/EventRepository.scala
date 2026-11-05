package eventstore

import eventstore.EventRepository.Direction
import eventstore.EventRepository.Error.Unexpected
import eventstore.EventRepository.FromVersion
import eventstore.EventRepository.SaveEventError
import eventstore.EventRepository.Subscription
import eventstore.SwitchableZStream.Message
import eventstore.types.AggregateName
import eventstore.types.AggregateVersion
import eventstore.types.EventStoreVersion
import eventstore.types.EventStreamId
import zio.IO
import zio.Scope
import zio.Tag
import zio.ZIO
import zio.stream.Stream
import zio.stream.ZStream

object EventRepository {

  sealed trait Direction
  object Direction {
    case object Forward extends Direction
    case object Backward extends Direction
  }

  sealed trait LastEventToHandle
  object LastEventToHandle {
    case class Version(version: EventStoreVersion) extends LastEventToHandle
    case object LastEvent extends LastEventToHandle
  }

  trait Subscription[EventType, Metadata] {
    def restartFromFirstEvent(lastEventToHandle: LastEventToHandle = LastEventToHandle.LastEvent): IO[Unexpected, Unit]
    def stream: ZStream[Any, Unexpected, EventStoreEvent[EventType, Metadata]]
  }

  sealed trait FromVersion
  object FromVersion {
    case class Exclusive(version: EventStoreVersion) extends FromVersion
    case class Inclusive(version: EventStoreVersion) extends FromVersion
    case object Initial extends FromVersion
  }

  object Subscription {
    def fromSwitchableStream[EventType, Metadata](
        switchableStream: SwitchableZStream[Any, Unexpected, RepositoryEvent[EventType, Metadata]],
        maybeLastVersion: IO[Unexpected, Option[EventStoreVersion]]
    ): Subscription[EventType, Metadata] =
      new Subscription[EventType, Metadata] {

        override def restartFromFirstEvent(
            lastEventToHandle: LastEventToHandle = LastEventToHandle.LastEvent
        ): IO[Unexpected, Unit] =
          lastEventToHandle match {
            case LastEventToHandle.Version(version) =>
              switchableStream.switchToPastEvents(_.eventStoreVersion == version)
            case LastEventToHandle.LastEvent =>
              maybeLastVersion.some
                .flatMap(version => switchableStream.switchToPastEvents(_.eventStoreVersion == version))
                .unsome
                .someOrElseZIO(switchableStream.switchToEmptyPastEvents)
          }

        override def stream: ZStream[Any, Unexpected, EventStoreEvent[EventType, Metadata]] =
          switchableStream.stream.collect {
            case Message.SwitchedToPastEvents => Reset
            case Message.Event(a)             => a
            case Message.SwitchedToLive       => SwitchedToLive
          }

      }

  }

  sealed trait SaveEventError

  sealed trait Error
  object Error {
    case class VersionConflict(provided: AggregateVersion, required: AggregateVersion) extends Error with SaveEventError
    case class Unexpected(throwable: Throwable) extends Error with SaveEventError
  }

  implicit class EventsOps[E, Metadata](self: Seq[RepositoryWriteEvent[E, Metadata]]) {

    def checkVersionsAreContiguousIncrements: Either[Unexpected, Unit] = self match {
      case _ :: tail =>
        self.zip(tail).foldLeft[Either[Unexpected, Unit]](Right[Unexpected, Unit](())) {
          case (invalid @ Left(_), _)   => invalid
          case (valid, (current, next)) =>
            if (current.aggregateVersion.next == next.aggregateVersion) valid
            else
              Left(
                Unexpected(
                  new IllegalArgumentException(
                    s"Invalid version sequence current: ${current.aggregateVersion}, next: ${next.aggregateVersion}"
                  )
                )
              )
        }
      case _ => Right(())
    }
  }
}

trait EventRepository[Decoder[_], Encoder[_]] {

  def getLastEventStoreVersion: IO[Unexpected, Option[EventStoreVersion]]

  def getAllEvents[A: Decoder: Tag, Metadata: Decoder: Tag]: ZIO[Scope, Nothing, Stream[Unexpected, RepositoryEvent[A, Metadata]]]

  def getEventByStoreVersion[A: Decoder: Tag, Metadata: Decoder: Tag](
      version: EventStoreVersion
  ): IO[Unexpected, Option[RepositoryEvent[A, Metadata]]]

  def listEventStreamWithName(
      aggregateName: AggregateName,
      direction: Direction = Direction.Forward
  ): Stream[Unexpected, EventStreamId]

  def getEventStream[A: Decoder: Tag, Metadata: Decoder: Tag](
      eventStreamId: EventStreamId,
      direction: Direction = Direction.Forward
  ): ZIO[Scope, Unexpected, Stream[Unexpected, RepositoryEvent[A, Metadata]]]

  def saveEvents[A: Decoder: Encoder: Tag, Metadata: Decoder: Encoder: Tag](
      eventStreamId: EventStreamId,
      events: Seq[RepositoryWriteEvent[A, Metadata]]
  ): IO[SaveEventError, Seq[RepositoryEvent[A, Metadata]]]

  def listen[EventType: Decoder: Tag, Metadata: Decoder: Tag]: ZIO[Scope, Unexpected, Subscription[EventType, Metadata]]

  def listenFromVersion[EventType: Decoder: Tag, Metadata: Decoder: Tag](
      from: FromVersion
  ): ZIO[Scope, Unexpected, Subscription[EventType, Metadata]]

}
