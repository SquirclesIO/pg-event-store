package eventstore.memory

import eventstore.EventRepositorySpec.Codecs
import eventstore.Scala3Spec
import eventstore.Scala3Spec.Union
import eventstore.memory.MemoryEventRepository.Id
import zio.Scope
import zio.durationInt
import zio.test.*

object MemoryEventRepositoryScala3Spec extends ZIOSpecDefault {

  given Codecs[Id, Id] = MemoryEventRepositorySpec.codecs
  given Id[Union] = ()

  override val spec: Spec[TestEnvironment with Scope, Any] = suite("memory raw")(
    Scala3Spec.spec(MemoryEventRepository.layer[Id, Id])
  ) @@ TestAspect.timeout(2.minute) @@ TestAspect.parallelN(2)

}
