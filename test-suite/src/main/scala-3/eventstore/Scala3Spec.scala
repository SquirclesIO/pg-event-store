package eventstore

import eventstore.EventRepositorySpec.A
import eventstore.EventRepositorySpec.Bob
import eventstore.EventRepositorySpec.Codecs
import eventstore.EventRepositorySpec.DoneBy
import eventstore.EventRepositorySpec.DoneBy1
import eventstore.EventRepositorySpec.DoneBy2
import eventstore.EventRepositorySpec.Event
import eventstore.EventRepositorySpec.Event1
import eventstore.EventRepositorySpec.Event2
import eventstore.EventRepositorySpec.Metadata
import eventstore.EventRepositorySpec.doneBy1Gen
import eventstore.EventRepositorySpec.doneBy2Gen
import eventstore.EventRepositorySpec.event1Gen
import eventstore.EventRepositorySpec.event2Gen
import eventstore.EventRepositorySpec.eventsGen
import eventstore.RepositoryEventOps.*
import eventstore.types.AggregateId
import eventstore.types.AggregateName
import eventstore.types.AggregateVersion
import eventstore.types.EventStreamId
import zio.TagK
import zio.URLayer
import zio.ZIO
import zio.durationInt
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant

object Scala3Spec {

  type Union = (Event1, DoneBy1) | (Event2, DoneBy2)

  def spec[R, Decoder[_]: TagK, Encoder[_]: TagK](
      repository: URLayer[R, EventRepository[Decoder, Encoder]]
  )(implicit codecs: Codecs[Decoder, Encoder], unionDecoder: Decoder[Union], unionEncoder: Encoder[Union]) = {

    given Decoder[(Event, DoneBy)] = codecs.eventWithDoneByDecoder
    given Decoder[(Event1, DoneBy1)] = codecs.event1WithDoneBy1Decoder
    given Decoder[(Event2, DoneBy2)] = codecs.event2WithDoneBy2Decoder
    given Encoder[(Event1, DoneBy1)] = codecs.event1WithDoneBy1Encoder
    given Encoder[(Event2, DoneBy2)] = codecs.event2WithDoneBy2Encoder
    given Encoder[Metadata] = codecs.metadataEncoder
    given Decoder[Metadata] = codecs.metadataDecoder

    suite("scala 3 examples")(
      test("should dispatch events to all listeners with union type listen") {
        ZIO.scoped {
          Live.live {
            for {
              repository <- ZIO.service[EventRepository[Decoder, Encoder]]
              listener1 <- repository.listen[(Event1, DoneBy1), Metadata].map(_.events)
              listener2 <- repository.listen[Union, Metadata].map(_.events)
              listener3 <- repository.listen[(Event, DoneBy), Metadata].map(_.events)
              listener4 <- repository.listen[(Event2, DoneBy2), Metadata].map(_.events)
              streamId <- AggregateId.generate.map(aggregateId => {
                EventStreamId(
                  aggregateId = aggregateId,
                  aggregateName = AggregateName("Foo")
                )
              })
              metadata = Metadata(
                migratedAt = Instant.parse("2007-12-03T10:15:30.00Z"),
                sourceGlobalVersion = 12L)
              event <- (A: Event1).asRepositoryWriteEventWithDoneBy[DoneBy1, Metadata](
                streamId = streamId, 
                user = Bob,
                metadata = metadata)
              _ <- repository.saveEvents(streamId, Seq(event))
              events1 <- listener1.take(1).timeout(1.seconds).runCollect
              events2 <- listener2.take(1).timeout(1.seconds).runCollect
              events3 <- listener3.take(1).timeout(1.seconds).runCollect
              events4 <- listener4.take(1).timeout(1.seconds).runCollect
              expected <- (A: Event1).asRepositoryEvent(streamId = streamId, metadata = metadata)
            } yield assert(events1.asStrings)(hasSameElements(Seq(expected.asString))) &&
              assert(events2.asRepositoryWriteEvents.asStrings)(hasSameElements(Seq(expected.asString))) &&
              assert(events3.asStrings)(hasSameElements(Seq(expected.asString))) &&
              assert(events4.asStrings)(isEmpty)
          }
        }
      }.provideSome[R](repository),
      test("getEventStream should return appended events with user") {
        check {
          for
            (streamId, eventsA, _) <- eventsGen(event1Gen <*> doneBy1Gen, size1Gen = Gen.int(1, 20))
            version = eventsA.lastOption.map(_.aggregateVersion).getOrElse(AggregateVersion.initial)
            (_, eventsD, _) <- eventsGen(event2Gen <*> doneBy2Gen, fromVersion = version.next)
          yield (
            streamId,
            eventsA,
            eventsD.map(_.copy(aggregateId = streamId.aggregateId, aggregateName = streamId.aggregateName))
          )
        } { case (streamId, events1, events2) =>
          (ZIO
            .scoped(for {
              repository <- ZIO.service[EventRepository[Decoder, Encoder]]
              _ <- repository.saveEvents[(Event1, DoneBy1), Metadata](streamId, events1)
              _ <- repository.saveEvents[(Event2, DoneBy2), Metadata](streamId, events2)
              stream <- repository.getEventStream[Union, Metadata](streamId)
              result <- stream.runCollect
            } yield assert(result.asRepositoryWriteEvents)(equalTo(events1 ++ events2))))
            .provideSome[R](repository)
        }
      }
    ) @@ TestAspect.samples(10) @@ TestAspect.shrinks(0)
  }
}
