package lilo

import org.specs2.mutable.Specification
import org.specs2.mock.Mockito
import com.twitter.util.Future
import scala.collection.mutable.ListBuffer
import org.specs2.specification.Scope
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import com.twitter.util.Promise

@RunWith(classOf[JUnitRunner])
class LiloExecutionSpec extends Spec {

  trait Context extends Scope {
    val source1Fetches = ListBuffer[List[Int]]()
    val source2Fetches = ListBuffer[List[Int]]()

    def fetchFunction(fetches: ListBuffer[List[Int]], inputs: List[Int]) = {
      fetches += inputs
      Future.value(inputs.map(i => i -> i * 10).toMap)
    }

    val source1 = Lilo.source((i: List[Int]) => fetchFunction(source1Fetches, i))
    val source2 = Lilo.source((i: List[Int]) => fetchFunction(source2Fetches, i))
  }

  "batches requests" >> {

    "for multiple lilos created from traversed inputs" in new Context {
      val lilo =
        Lilo.traverse(List(1, 2, 3, 4)) {
          i =>
            if (i <= 2)
              source1.get(i)
            else
              source2.get(i)
        }

      resultOf(lilo) mustEqual Some(List(10, 20, 30, 40))
      source1Fetches mustEqual List(List(1, 2))
      source2Fetches mustEqual List(List(3, 4))
    }

    "for multiple lilos collected into only one lilo" in new Context {
      val lilo = Lilo.collect(source1.get(1), source1.get(2), source2.get(3), source2.get(4))

      resultOf(lilo) mustEqual Some(List(10, 20, 30, 40))
      source1Fetches mustEqual List(List(1, 2))
      source2Fetches mustEqual List(List(3, 4))
    }

    "for lilos created inside nested flatmaps" in new Context {
      val lilo1 = Lilo.value(1).flatMap(source1.get(_))
      val lilo2 = Lilo.value(2).flatMap(source1.get(_))

      resultOf(Lilo.collect(lilo1, lilo2)) mustEqual Some(List(10, 20))
      source1Fetches mustEqual List(List(1, 2))
      source2Fetches must beEmpty
    }

    "for lilos composed using for comprehension" >> {

      "one level" in new Context {
        val lilo =
          for {
            int <- Lilo.collect(source1.get(1), source1.get(2), source2.get(3), source2.get(4))
          } yield int

        resultOf(lilo) mustEqual Some(List(10, 20, 30, 40))
        source1Fetches mustEqual List(List(1, 2))
        source2Fetches mustEqual List(List(3, 4))
      }

      "two levels" in new Context {
        val lilo =
          for {
            ints1 <- Lilo.collect(source1.get(1), source1.get(2))
            ints2 <- Lilo.collect(source2.get(3), source2.get(4))
          } yield (ints1, ints2)

        resultOf(lilo) mustEqual Some(List(10, 20), List(30, 40))
        source1Fetches mustEqual List(List(1, 2))
        source2Fetches mustEqual List(List(3, 4))
      }

      "with a filter condition" in new Context {
        val lilo =
          for {
            ints1 <- Lilo.collect(source1.get(1), source1.get(2))
            int2 <- source2.get(3) if (int2 != 999)
          } yield (ints1, int2)

        resultOf(lilo) mustEqual Some(List(10, 20), 30)
        source1Fetches mustEqual List(List(1, 2))
        source2Fetches mustEqual List(List(3))
      }

      "using a join" in new Context {
        val lilo =
          for {
            ints1 <- Lilo.collect(source1.get(1), source1.get(2))
            ints2 <- source2.get(3).join(source2.get(4))
          } yield (ints1, ints2)

        resultOf(lilo) mustEqual Some(List(10, 20), (30, 40))
        source1Fetches mustEqual List(List(1, 2))
        source2Fetches mustEqual List(List(3, 4))
      }

      "complex scenario" in new Context {
        val lilo =
          for {
            const1 <- Lilo.value(1)
            const2 <- Lilo.value(2)
            collect1 <- Lilo.collect(source1.get(const1), source2.get(const2))
            collect2 <- Lilo.collect(source1.get(const1), source2.get(const2)) if (true)
            join1 <- Lilo.value(4).join(Lilo.value(5))
            join2 <- source1.get(collect1).join(source2.get(join1._2))
          } yield (const1, const2, collect1, collect2, join1, join2)

        resultOf(lilo) mustEqual Some((1, 2, List(10, 20), List(10, 20), (4, 5), (List(100, 200), 50)))
        source1Fetches mustEqual List(List(1), List(10, 20))
        source2Fetches mustEqual List(List(2), List(5))
      }
    }
  }

  "executes joined lilos in parallel" in new Context {
    var promises = List[Promise[Map[Int, Int]]]()

    override def fetchFunction(fetches: ListBuffer[List[Int]], inputs: List[Int]) = {
      val promise = Promise[Map[Int, Int]]()
      promises :+= promise
      promise
    }

    source1.get(1).join(source2.get(2)).run

    promises.size mustEqual 2
  }

  "short-circuits the computation in case of a failure" in new Context {
    val lilo = Lilo.exception[Int](new IllegalStateException).map(_ => throw new NullPointerException)
    resultOf(lilo) must throwA[IllegalStateException]
  }
}