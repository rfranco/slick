package com.typesafe.slick.testkit.util

import scala.language.existentials

import scala.concurrent.{Promise, ExecutionContext, Await, Future, blocking}
import scala.concurrent.duration.Duration
import scala.reflect.ClassTag
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

import java.lang.reflect.Method
import java.util.concurrent.{LinkedBlockingQueue, ThreadPoolExecutor, ExecutionException, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import scala.slick.dbio._
import scala.slick.jdbc.JdbcBackend
import scala.slick.util.DumpInfo
import scala.slick.profile.{RelationalProfile, SqlProfile, Capability}
import scala.slick.driver.JdbcProfile

import org.junit.runner.Description
import org.junit.runner.notification.RunNotifier
import org.junit.runners.model._
import org.junit.Assert

import org.reactivestreams.{Subscription, Subscriber, Publisher}

/** JUnit runner for the Slick driver test kit. */
class Testkit(clazz: Class[_ <: DriverTest], runnerBuilder: RunnerBuilder) extends SimpleParentRunner[TestMethod](clazz) {

  val driverTest = clazz.newInstance
  var tdb: TestDB = driverTest.tdb

  def describeChild(ch: TestMethod) = ch.desc

  def getChildren = if(tdb.isEnabled) {
    driverTest.tests.flatMap { t =>
      val ms = t.getMethods.filter { m =>
        m.getName.startsWith("test") && m.getParameterTypes.length == 0
      }
      ms.map { m =>
        val tname = m.getName + '[' + tdb.confName + ']'
        new TestMethod(tname, Description.createTestDescription(t, tname), m, t)
      }
    }
  } else Nil

  override def runChildren(notifier: RunNotifier) = if(!children.isEmpty) {
    tdb.cleanUpBefore()
    try {
      val is = children.iterator.map(ch => (ch, ch.cl.newInstance()))
        .filter{ case (_, to) => to.setTestDB(tdb) }.zipWithIndex.toIndexedSeq
      val last = is.length - 1
      var previousTestObject: GenericTest[_ >: Null <: TestDB] = null
      for(((ch, preparedTestObject), idx) <- is) {
        val desc = describeChild(ch)
        notifier.fireTestStarted(desc)
        try {
          val testObject =
            if(previousTestObject ne null) previousTestObject
            else preparedTestObject
          previousTestObject = null
          try ch.run(testObject) finally {
            val skipCleanup = idx == last || (testObject.reuseInstance && (ch.cl eq is(idx+1)._1._1.cl))
            if(skipCleanup) {
              if(idx == last) testObject.closeKeepAlive()
              else previousTestObject = testObject
            }
            else testObject.cleanup()
          }
        } catch {
          case t: Throwable => addFailure(t, notifier, desc)
        } finally notifier.fireTestFinished(desc)
      }
    } finally tdb.cleanUpAfter()
  }
}

abstract class DriverTest(val tdb: TestDB) {
  def tests = TestkitConfig.testClasses
}

case class TestMethod(name: String, desc: Description, method: Method, cl: Class[_ <: GenericTest[_ >: Null <: TestDB]]) {
  private[this] def await[T](f: Future[T]): T =
    try Await.result(f, TestkitConfig.asyncTimeout)
    catch { case ex: ExecutionException => throw ex.getCause }

  def run(testObject: GenericTest[_]): Unit = {
    val r = method.getReturnType
    testObject match {
      case testObject: TestkitTest[_] =>
        if(r == Void.TYPE) method.invoke(testObject)
        else throw new RuntimeException(s"Illegal return type: '${r.getName}' in test method '$name' -- TestkitTest methods must return Unit")

      case testObject: AsyncTest[_] =>
        if(r == classOf[Future[_]]) await(method.invoke(testObject).asInstanceOf[Future[Any]])
        else if(r == classOf[DBIOAction[_, _, _]]) await(testObject.db.run(method.invoke(testObject).asInstanceOf[DBIO[Any]]))
        else throw new RuntimeException(s"Illegal return type: '${r.getName}' in test method '$name' -- AsyncTest methods must return Future or Action")
    }
  }
}

sealed abstract class GenericTest[TDB >: Null <: TestDB](implicit TdbClass: ClassTag[TDB]) {
  protected[this] var _tdb: TDB = null
  private[testkit] def setTestDB(tdb: TestDB): Boolean = {
    tdb match {
      case TdbClass(o) =>
        _tdb = o
        true
      case _ =>
        false
    }
  }
  lazy val tdb: TDB = _tdb

  private[testkit] var keepAliveSession: tdb.profile.Backend#Session = null

  private[this] var unique = new AtomicInteger

  val reuseInstance = false

  lazy val db = {
    val db = tdb.createDB()
    keepAliveSession = db.createSession()
    if(!tdb.isPersistent && tdb.isShared)
      keepAliveSession.force() // keep the database in memory with an extra connection
    db
  }

  final def cleanup() = if(keepAliveSession ne null) {
    try if(tdb.isPersistent) tdb.dropUserArtifacts(keepAliveSession)
    finally {
      try db.close() finally closeKeepAlive()
    }
  }

  final def closeKeepAlive() = {
    if(keepAliveSession ne null) keepAliveSession.close()
  }

  implicit class StringContextExtensionMethods(s: StringContext) {
    /** Generate a unique name suitable for a database entity */
    def u(args: Any*) = s.standardInterpolator(identity, args) + "_" + unique.incrementAndGet()
  }

  def rcap = RelationalProfile.capabilities
  def scap = SqlProfile.capabilities
  def jcap = JdbcProfile.capabilities
  def tcap = TestDB.capabilities
}

abstract class TestkitTest[TDB >: Null <: TestDB](implicit TdbClass: ClassTag[TDB]) extends GenericTest[TDB] {
  @deprecated("Use implicitSession instead of sharedSession", "3.0")
  protected final def sharedSession: tdb.profile.Backend#Session = implicitSession

  protected implicit def implicitSession: tdb.profile.Backend#Session = {
    db
    keepAliveSession
  }

  def ifCap[T](caps: Capability*)(f: => T): Unit =
    if(caps.forall(c => tdb.capabilities.contains(c))) f
  def ifNotCap[T](caps: Capability*)(f: => T): Unit =
    if(!caps.forall(c => tdb.capabilities.contains(c))) f

  def assertFail(f: =>Unit) = {
    var succeeded = false
    try {
      f
      succeeded = true
    } catch {
      case e: Exception if !scala.util.control.Exception.shouldRethrow(e) =>
    }
    if(succeeded) Assert.fail("Exception expected")
  }

  def assertAllMatch[T](t: TraversableOnce[T])(f: PartialFunction[T, _]) = t.foreach { x =>
    if(!f.isDefinedAt(x)) Assert.fail("Expected shape not matched by: "+x)
  }
}

abstract class AsyncTest[TDB >: Null <: TestDB](implicit TdbClass: ClassTag[TDB]) extends GenericTest[TDB] {
  final override val reuseInstance = true

  protected implicit def asyncTestExecutionContext = ExecutionContext.global

  /** Test Action: Get the current database session */
  object GetSession extends SynchronousDatabaseAction[TDB#Driver#Backend, Effect, TDB#Driver#Backend#Session, NoStream] {
    def run(context: TDB#Driver#Backend#Context) = context.session
    def getDumpInfo = DumpInfo(name = "<GetSession>")
  }

  /** Test Action: Check if the current database session is pinned */
  object IsPinned extends SynchronousDatabaseAction[TDB#Driver#Backend, Effect, Boolean, NoStream] {
    def run(context: TDB#Driver#Backend#Context) = context.isPinned
    def getDumpInfo = DumpInfo(name = "<IsPinned>")
  }

  /** Test Action: Get the current transactionality level and autoCommit flag */
  object GetTransactionality extends SynchronousDatabaseAction[JdbcBackend, Effect, (Int, Boolean), NoStream] {
    def run(context: JdbcBackend#Context) =
      context.session.asInstanceOf[JdbcBackend#BaseSession].getTransactionality
    def getDumpInfo = DumpInfo(name = "<GetTransactionality>")
  }

  /** Test Action: Get the current statement parameters, except for `statementInit` which is always set to null */
  object GetStatementParameters extends SynchronousDatabaseAction[JdbcBackend, Effect, JdbcBackend.StatementParameters, NoStream] {
    def run(context: JdbcBackend#Context) = {
      val s = context.session
      JdbcBackend.StatementParameters(s.resultSetType, s.resultSetConcurrency, s.resultSetHoldability, null, s.fetchSize)
    }
    def getDumpInfo = DumpInfo(name = "<GetStatementParameters>")
  }

  def ifCap[E <: Effect, R](caps: Capability*)(f: => DBIOAction[R, NoStream, E]): DBIOAction[Unit, NoStream, E] =
    if(caps.forall(c => tdb.capabilities.contains(c))) f.andThen(DBIO.successful(())) else DBIO.successful(())
  def ifNotCap[E <: Effect, R](caps: Capability*)(f: => DBIOAction[R, NoStream, E]): DBIOAction[Unit, NoStream, E] =
    if(!caps.forall(c => tdb.capabilities.contains(c))) f.andThen(DBIO.successful(())) else DBIO.successful(())

  def ifCapF[R](caps: Capability*)(f: => Future[R]): Future[Unit] =
    if(caps.forall(c => tdb.capabilities.contains(c))) f.map(_ => ()) else Future.successful(())
  def ifNotCapF[R](caps: Capability*)(f: => Future[R]): Future[Unit] =
    if(!caps.forall(c => tdb.capabilities.contains(c))) f.map(_ => ()) else Future.successful(())

  def asAction[R](f: tdb.profile.Backend#Session => R): DBIOAction[R, NoStream, Effect] =
    new SynchronousDatabaseAction[tdb.profile.Backend, Effect, R, NoStream] {
      def run(context: tdb.profile.Backend#Context): R = f(context.session)
      def getDumpInfo = DumpInfo(name = "<asAction>")
    }

  def seq[E <: Effect](actions: DBIOAction[_, NoStream, E]*): DBIOAction[Unit, NoStream, E] = DBIO.seq[E](actions: _*)

  /** Synchronously consume a Reactive Stream and materialize it as a Vector. */
  def materialize[T](p: Publisher[T]): Future[Vector[T]] = {
    val builder = Vector.newBuilder[T]
    val pr = Promise[Vector[T]]()
    try p.subscribe(new Subscriber[T] {
      def onSubscribe(s: Subscription): Unit = s.request(Long.MaxValue)
      def onComplete(): Unit = pr.success(builder.result())
      def onError(t: Throwable): Unit = pr.failure(t)
      def onNext(t: T): Unit = builder += t
    }) catch { case NonFatal(ex) => pr.failure(ex) }
    pr.future
  }

  /** Iterate synchronously over a Reactive Stream. */
  def foreach[T](p: Publisher[T])(f: T => Any): Future[Unit] = {
    val pr = Promise[Unit]()
    try p.subscribe(new Subscriber[T] {
      def onSubscribe(s: Subscription): Unit = s.request(Long.MaxValue)
      def onComplete(): Unit = pr.success(())
      def onError(t: Throwable): Unit = pr.failure(t)
      def onNext(t: T): Unit = f(t)
    }) catch { case NonFatal(ex) => pr.failure(ex) }
    pr.future
  }


  /** Asynchronously consume a Reactive Stream and materialize it as a Vector, requesting new
    * elements one by one and transforming them after the specified delay. This ensures that the
    * transformation does not run in the synchronous database context but still preserves
    * proper sequencing. */
  def materializeAsync[T, R](p: Publisher[T], tr: T => Future[R], delay: Duration = Duration(100L, TimeUnit.MILLISECONDS)): Future[Vector[R]] = {
    val exe = new ThreadPoolExecutor(1, 1, 1L, TimeUnit.SECONDS, new LinkedBlockingQueue[Runnable]())
    val ec = ExecutionContext.fromExecutor(exe)
    val builder = Vector.newBuilder[R]
    val pr = Promise[Vector[R]]()
    var sub: Subscription = null
    def async[T](thunk: => T): Future[T] = {
      val f = Future {
        Thread.sleep(delay.toMillis)
        thunk
      }(ec)
      f.onFailure { case t =>
        pr.tryFailure(t)
        sub.cancel()
      }
      f
    }
    try p.subscribe(new Subscriber[T] {
      def onSubscribe(s: Subscription): Unit = async {
        sub = s
        sub.request(1L)
      }
      def onComplete(): Unit = async(pr.trySuccess(builder.result()))
      def onError(t: Throwable): Unit = async(pr.tryFailure(t))
      def onNext(t: T): Unit = async {
        tr(t).onComplete {
          case Success(r) =>
            builder += r
            sub.request(1L)
          case Failure(t) =>
            pr.tryFailure(t)
            sub.cancel()
        }(ec)
      }
    }) catch { case NonFatal(ex) => pr.tryFailure(ex) }
    val f = pr.future
    f.onComplete(_ => exe.shutdown())
    f
  }

  implicit class AssertionExtensionMethods[T](v: T) {
    private[this] val cln = getClass.getName
    private[this] def fixStack(f: => Unit): Unit = try f catch {
      case ex: AssertionError =>
        ex.setStackTrace(ex.getStackTrace.iterator.filterNot(_.getClassName.startsWith(cln)).toArray)
        throw ex
    }

    def shouldBe(o: Any): Unit = fixStack(Assert.assertEquals(o, v))

    def shouldNotBe(o: Any): Unit = fixStack(Assert.assertNotSame(o, v))

    def should(f: T => Boolean): Unit = fixStack(Assert.assertTrue(f(v)))

    def shouldBeA[T](implicit ct: ClassTag[T]): Unit = {
      if(!ct.runtimeClass.isInstance(v))
        fixStack(Assert.fail("Expected value of type " + ct.runtimeClass.getName + ", got " + v.getClass.getName))
    }
  }

  implicit class CollectionAssertionExtensionMethods[T](v: TraversableOnce[T]) {
    private[this] val cln = getClass.getName
    private[this] def fixStack(f: => Unit): Unit = try f catch {
      case ex: AssertionError =>
        ex.setStackTrace(ex.getStackTrace.iterator.filterNot(_.getClassName.startsWith(cln)).toArray)
        throw ex
    }

    def shouldAllMatch(f: PartialFunction[T, _]) = v.foreach { x =>
      if(!f.isDefinedAt(x)) fixStack(Assert.fail("Value does not match expected shape: "+x))
    }
  }
}
