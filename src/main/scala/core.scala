import scala.concurrent._
import com.typesafe.scalalogging.LazyLogging
//import shapeless._

import ExecutionContext.Implicits.global
import scala.async.Async.{ async, await }
import scala.concurrent.duration._

import java.util.{ Timer, TimerTask }

import scala.util.{ Try, Success, Failure }

package core_async {

  object Timeout {
    val timer = new Timer()
    def timeout(d: Duration): Future[Unit] = timeout(d.toMillis)
    def timeout(d: Long): Future[Unit] = {
      val p = Promise[Unit]()
      val f = p.future
      val tt = new TimerTask() {
        def run {
          p.success(())
        }
      }
      timer.schedule(tt, d)
      f
    }
  }
  
  object TentativeOfferResult extends Enumeration {
    type TentativeOfferResult = Value
    val Refused,    // The promise had already been completed by someone else 
        Accepted,   // Our offer succeeded; we completed the promise.
        Retracted = // Our offer failed; promise is still uncompleted.
          Value
  }
  import TentativeOfferResult._
  
  /** Promise that might not be fulfilled.
    */
  class TentativePromise[T] {
    val p = Promise[T]
    def future: scala.concurrent.Future[T] = p.future
    /** A normal Promise.tryComplete might fail if the promise is already completed; this
     *  one can also fail because the lazy offer value returns None.
     *  The result is a TentativeOfferResult
     */
    def tryComplete(o: => Option[T]):  TentativeOfferResult = this.synchronized {
      if(!p.isCompleted)  o match {
        case Some(t) =>
          p.success(t)
          Accepted
        case None =>
          Retracted
      }
      else Refused
     }
   }
   object TentativePromise {
    def apply[T] =    new TentativePromise[T]
   }
  

  /** Promise that fulfills tentative promises.
   */
  class ReadyPromise[T,U] extends Promise[U] {
    type TP = TentativePromise[T]
	  val p = Promise[U]
		val h: scala.collection.mutable.HashMap[TP, TP => Unit] = new scala.collection.mutable.HashMap()

    def future  = p.future
    def isCompleted: Boolean = p.isCompleted
    
    def tryComplete(result: scala.util.Try[U]): Boolean = this.synchronized {
       val ret = if(p.tryComplete(result)) {  // fires any standard listeners
          // Notify all clients.  
          h.foreach {case (pDeliver,deliverTo) => deliverTo(pDeliver) }
          true
       } else false
       h.clear()
       ret
    }

    /** 
     * When this ReadyPromise is complete, attempt to complete
     * the TentativePromise pDeliver by passing it to f.
     */
    def tryDeliver(recipient : TP)(deliverTo:TP=>Unit): Unit = this.synchronized {
      if(p.isCompleted) {
        deliverTo(recipient)
      } else {
        h += ((recipient, deliverTo))
        recipient.future.map {_ => this.synchronized{h -= recipient}}
      }
    }
  }

  object ReadyPromise {
    def apply[T,U] =         new ReadyPromise[T,U]() 
    def successful[T,U](u:U) : ReadyPromise[T,U] = {
      val p = new ReadyPromise[T,U]()
      p.trySuccess(u)
      p
    }
  }
  
  case class BufferResult[T](v : T,
        noLongerEmpty:Boolean=false,
        noLongerFull:Boolean=false,
        nowEmpty:Boolean=false,
        nowFull:Boolean=false)
  
  
  // The only thing exciting about a ChanBuffer is that you pass its put/take methods
  // a promise to fulfill should that operation render the buffer no longer empty/full.
  abstract class ChanBuffer[T]() {
    def put(v: T) : Option[BufferResult[T]]
    def take : Option[BufferResult[T]]
  }
  


  class NormalBuffer[T](n: Int, dropping: Boolean, sliding: Boolean) extends ChanBuffer[T] {
    val b = scala.collection.mutable.Buffer.empty[T]
    
    override def toString = s"NormalBuffer($n, $dropping, $sliding, $b"
    
    def put(v:T) : Option[BufferResult[T]] = this.synchronized {
      val s = b.size
      var noLongerEmpty = false
      var nowFull = false
      if (s == n) {
        if (dropping) {
          b.update(n - 1, v)
        } else if (sliding) {
          b.remove(0)
          b += v
        } else {
          return None
        }
      }
      else if (s == (n - 1)) {
        b += v
        nowFull = true
        if (s == 0) {
          noLongerEmpty = true
        }
      } else if (s == 0) {
        b += v
        noLongerEmpty=true
      }
      Some(BufferResult(v,noLongerEmpty=noLongerEmpty,nowFull=nowFull))
    }

    def take : Option[BufferResult[T]]= this.synchronized {
      val s = b.size
      var noLongerFull = false
      var nowEmpty = false
      if (s > 0) {
        if (s == n) {
          noLongerFull = true
          if (s == 1) { nowEmpty=true}
        }
        Some(BufferResult(b.remove(0),nowEmpty=nowEmpty,noLongerFull=noLongerFull))
      } else {
        None
      }
    }
  }
  

  
  sealed trait ChanHolder[T] {
    def chan : Chan[T]
  }
  
  case class CV[T](val c: Chan[T], val v: T) extends ChanHolder[T] {
    def chan = c
  }

  class Chan[T](val b: ChanBuffer[T], val name: String) extends ChanHolder[T] with LazyLogging {
    
    def chan = this

    private [this] var pReadyForWrite =   ReadyPromise.successful[CV[T],Unit](Unit)
    private [this ] var pReadyForRead =   ReadyPromise[CV[T],Unit]
    


    override def toString = s"Chan($b,$name) rfw=${pReadyForWrite.isCompleted} rfr=${pReadyForRead.isCompleted}"
    
    // Extract the value in a chan-value pair, properly cast.  Note we explicitly match
    // CV[Chan.Pretender], because Chan is invariant.
    def unapply(cv: CV[Chan.Pretender]): Option[T] =
      if (cv.c eq this) {
        Some(cv.v.asInstanceOf[T])
      } else None


    // Only reschedule if we failed to write to the buffer, not if the promise was already completed.
    private[this] def tryWrite(v: T, pClient: TentativePromise[CV[T]]) : Unit = this.synchronized {
      logger.debug(s"tryWrite $this $v $pClient")
      var trigger = false
      pClient.tryComplete(b.put(v).map { br => 
                                             if (br.noLongerEmpty) {logger.debug(s"${this} nle $v"); trigger = true}
                                             if (br.nowFull)       {logger.debug(s"${this} nf $v");  pReadyForWrite = ReadyPromise[CV[T],Unit]}
                                             CV(this,v)
                                           }) match {
        case Retracted => {logger.debug(s"${this} wdnc $v"); pReadyForWrite.tryDeliver(pClient){tryWrite(v,_)}}
        case Accepted => {logger.debug(s"${this} wdc $v")}
        case Refused => {logger.debug(s"${this} ac $v")}
      }
      if(trigger) pReadyForRead.trySuccess(Unit)
    }


      private[this] def tryRead(pClient: TentativePromise[CV[T]]): Unit = this.synchronized {
        logger.debug(s"tryRead $this $pClient")
        var trigger = false
        pClient.tryComplete(b.take.map {br =>
          if(br.noLongerFull) {logger.debug(s"${this} nlf ${br.v}"); trigger = true}
          if(br.nowEmpty)     {logger.debug(s"${this} ne ${br.v}");  pReadyForRead = ReadyPromise[CV[T],Unit]}
          CV(this,br.v)
        }) match {
          case Retracted => {logger.debug(s"${this} dnc");pReadyForRead.future map {_ => tryRead(pClient)}}
          case Accepted           => logger.debug(s"${this} rdc");
          case Refused      => logger.debug(s"${this} rac");
        }
        if(trigger) pReadyForWrite.trySuccess(Unit)
      }
    
    


    /** Return a future that completes on successful write to the channel. 
     */
    def write(v: T): Future[Unit] = this.synchronized {
      val p = TentativePromise[CV[T]]
      logger.debug(s"$this write $v $p")
      pReadyForWrite.tryDeliver(p)(tryWrite(v,_))
      p.future.map(_ => Unit)
    }
    def <-- (v: T)= write(v)

    def read: Future[T] = this.synchronized {
      val p = TentativePromise[CV[T]]
      logger.debug(s"$this read $p")
      pReadyForRead.tryDeliver(p)(tryRead(_))
      p.future.map(_.v)
    }

    
    def read(pNotify: TentativePromise[CV[T]]): Unit = this.synchronized {
      logger.debug(s"$this read $pNotify")
     pReadyForRead.tryDeliver(pNotify)(tryRead(_))}

    def write(v: T, pNotify: TentativePromise[CV[T]] ) : Unit = this.synchronized {
      logger.debug(s"$this write $pNotify")
      pReadyForWrite.tryDeliver(pNotify)(tryWrite(v,_))}
      
  }

  

  import java.util.UUID
  object Chan extends LazyLogging {
    
    def unary_~[T](c: Chan[T]) = c.read
    
    def apply[T](name: String) = new Chan[T](new NormalBuffer(1, false, false), name)
    def apply[T] = new Chan[T](new NormalBuffer(1, false, false), UUID.randomUUID().toString())
    def apply[T](n: Int) = new Chan[T](new NormalBuffer(n, false, false), UUID.randomUUID.toString())
    def timeout[T](d: Duration, v: T, name: String): Chan[T] = {
      val c = Chan[T](name)
      logger.debug(s"Creating timeout channel ${d}")
      Timeout.timeout(d) flatMap {println("Timeout fired"); _ => c.write(v) }
      c
    }
    def timeout[T](d: Duration, v: T): Chan[T] = timeout[T](d, v, UUID.randomUUID.toString())
    def timeout(d: Duration): Chan[Unit] = timeout(d, Unit)
    
    type Pretender

    def alts(cs: ChanHolder[Pretender]*): Future[CV[Pretender]] = {
      val p = TentativePromise[CV[Pretender]]
      cs.foreach { _ match {
        case c  : Chan[Pretender] => c.chan.read(p)
        case CV(c,v) => c.chan.write(v,p)
      }}

      p.future
    }

    implicit def ghastly[T](c: Chan[T]): Chan[Pretender] = c.asInstanceOf[Chan[Pretender]]
    implicit def ghastly2[T](p : Promise[CV[T]]) = p.asInstanceOf[Promise[Unit]]


  }
}


