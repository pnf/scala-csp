import org.scalatest.FlatSpec
import org.scalatest.concurrent.Timeouts.failAfter
import org.scalatest.time.{Span,Millis}
import org.scalatest.concurrent.TimeLimitedTests

import scala.concurrent._
import core_async._
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




object AsyncApp extends App {

  import Chan._
  import scala.util.Random
  
/*

  val f = Future {Thread.sleep(100);2}
  val f2 = f.map {_+1}
  val f3 = Future {Thread.sleep(1000); 3.14}
  val f4 = async {await(f3).round == await(f2)}
  async {
    println(s"Flort ${await(f4)}")
  }
  

  val cListen = Chan[Int]("c1")
  val cRespond = Chan[Int]("c2")
  async {
    while (true) {
      println("Waiting")
      val i = await(cListen.read)
      println(s"Got $i")
      cRespond.write(i + 1)
    }
  }

  async {
    println("Sending 3")
    await(cListen.write(3))
    println("Sent")
    val i = await(cRespond.read)
    println(s"Got $i")
  }
*/
  {
    
    val c1 = Chan[Int]("c1")
    val c2 = Chan[String]("c2")
    
  
  async {
      while(true) {
        val i = Random.nextInt(10)
        println(s"Sending $i") 
        await{c1.write(i)}
        println(s"Sent $i") 
        await{timeout((Random.nextInt(1000)) milliseconds,"timeout1").read}
        println("About to send another integer")
      }
  }

  async {
      while(true) {
        val s = s"I am a string: ${Random.nextInt(10)}"
        println(s"Sending $s")
        await{c2.write(s)}
        println(s"Sent $s")
        await{timeout((Random.nextInt(1000)) milliseconds,"timeout2").read}
        println("About to send another string")
      }
    }
  
    var n = 100
    async {
      while(n>0) {
        n=n-1
        println("Running alts")
        await(Chan.alts(c1, c2)) match {
          case c1(i) => println(s"Plus one is ${i + 1}")
          case c2(s) => println(s + " flushing")
        }
      }
    }

  }
  
 
  
  Await.ready(Promise[Unit].future, Duration.Inf)

}
