import org.scalatest.FlatSpec
import org.scalatest.concurrent.Timeouts.failAfter
import org.scalatest.time.{Span,Millis}
import org.scalatest.concurrent.TimeLimitedTests
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent._
import scala.concurrent.duration._
import core_async.Chan
import core_async.Chan._
import ExecutionContext.Implicits.global
import scala.async.Async.{ async, await }
import scala.concurrent.duration._



class SendAndReceive extends FlatSpec with TimeLimitedTests {
  override val timeLimit = Span(100,Millis)
	val answer = 42
	"What is sent" should "be received" in {
		val c = Chan[Int]
		async {
			val i = await(c.read)
			assert(answer === i)
		}
		async {
			c.write(answer)
		}
	}
}

class ListenAndRespond extends FlatSpec with TimeLimitedTests with LazyLogging {
    override val timeLimit = Span(100,Millis)
    "We" should "get a response" in {
  val cListen = Chan[Int]("cListen")
  val cRespond = Chan[Int]("cRespond")
  async {
      logger.debug("Waiting on cListen")
      val i = await(cListen)
      logger.debug(s"Got $i; responding with ${i+1}")
      await(cRespond <-- (i + 1))
  }

  async {
    logger.debug("Sending 3")
    cListen <-- 3
    logger.debug("Sent 3")
    val i = await(cRespond)
    assert(3 === i)
  }
      
    }
  
}

class Alts extends FlatSpec with TimeLimitedTests with LazyLogging{
  
  val n = 100000
  val t1 = 10
  val t2 = 15
  
  override val timeLimit = Span(n*t1,Millis)
  import scala.util.Random

    val c1 = Chan[Int]("c1")
    val c2 = Chan[Long]("c2")

  "Counts" should "match" in {
    
  async {
      var i = 0
      while(true) {
        i = i + 1
        logger.debug(s"Sending $i") 
        await{c1 <-- i}
        logger.debug(s"Sent $i") 
        //await{timeout((Random.nextInt(10)),"timeout1")}
      }
  }

  async {
    var j : Long = 0
      while(true) {
        j = j + 1
        logger.debug(s"Sending ${j}L")
        await{c2 <-- j}
        logger.debug(s"Sent ${j}L")
        // await{timeout((Random.nextInt(15)),"timeout2")}
      }
  }
  
    val finished = Promise[Unit]
    var k = n
    var jPrev = 0L
    var iPrev = 0
    var iTo = 0
    var t = timeout(2)
    async {
      while(k>0) {
        logger.debug("Running alts")
        await(Chan.alts(c1, c2, t)) match {
          case c1(i) => {logger.debug(s"Got $i");assert(i.isInstanceOf[Int]);  assert(i===iPrev+1); iPrev = i; k=k-1}
          case c2(j) => {logger.debug(s"Got ${j}");assert(j.isInstanceOf[Long]);assert(j===jPrev+1); jPrev = j; k=k-1}
          case to =>
            logger.debug("Got timeout!")
            iTo = iTo +1
            t = timeout(2)  
        }
      }
      logger.info(s"$iPrev $jPrev $iTo")
      assert(n === iPrev+jPrev)
      finished.success(Unit)
    }
    
    Await.ready(finished.future,Duration.Inf)
    

  }
  


}
