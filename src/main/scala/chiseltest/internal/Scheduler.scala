// Copyright 2018-2023 The Regents of the University of California
// released under BSD 3-Clause License
// author: Kevin Laeufer <laeufer@cs.berkeley.edu>

package chiseltest.internal

import java.util.concurrent.Semaphore
import scala.collection.mutable

private class ThreadInfo(
  /** Unique thread id. */
  val id: Int,
  /** Human readable name of the thread */
  val name: String,
  /** Java thread. */
  var underlying: Option[Thread],
  /** Status of the thread. */
  var status: ThreadStatus,
  /** Semaphore that pauses thread. */
  val semaphore: Semaphore) {
  def serializeShort(currentStep: Int, activeThreadId: Int): String = {
    val isActive = id == activeThreadId
    val idStr = if (isActive) s"[${id}]" else id.toString
    val pausedStr = ThreadStatus.serialize(status, currentStep)
    idStr + ": " + pausedStr
  }

  override def toString = s"$name ($id)"
}

private sealed trait ThreadStatus {}
private case object ThreadActive extends ThreadStatus
private case class ThreadWaitingForJoin(otherThread: Int) extends ThreadStatus
private case class ThreadWaitingUntil(step: Int) extends ThreadStatus
private case object ThreadFinished extends ThreadStatus
private object ThreadStatus {
  @inline def serialize(status: ThreadStatus, currentStep: Int): String = status match {
    case ThreadActive                      => "R"
    case ThreadWaitingForJoin(otherThread) => s"J($otherThread)"
    case ThreadWaitingUntil(step)          => s"P(${step - currentStep})"
    case ThreadFinished                    => "F"
  }
}

/** Manages multiple Java threads that all interact with the same simulation and step synchronously. Currently only
  * supports a single global clock that all threads are synchronized to.
  */
private class Scheduler(simulationStep: Int => Int) {
  private val EnableDebug:         Boolean = true
  private val DebugThreadSwitches: Boolean = false
  private def debug(msg: => String): Unit = if (EnableDebug) println(s"$activeThreadId@$currentStep: $msg")
  private def debugSwitch(from: Int, to: Int, reason: => String): Unit = if (DebugThreadSwitches) {
    println(
      s"@$currentStep: ${threads(from).serializeShort(currentStep, activeThreadId)} ->" +
        s" ${threads(to).serializeShort(currentStep, activeThreadId)} $reason"
    )
    dbgThreadList(showAlways = true)
  }

  private val MainThreadId = 0

  /** current active thread */
  private var activeThreadId = MainThreadId

  /** all threads */
  private val threads = new mutable.ArrayBuffer[ThreadInfo]()
  threads.addOne(new ThreadInfo(MainThreadId, "main", None, ThreadActive, new Semaphore(0)))

  /** order in which threads are scheduled */
  private val threadOrder = new ThreadOrder
  private def threadsInSchedulerOrder = threadOrder.getOrder.map(threads(_))

  /** Keep track of global simulation time. */
  private var currentStep: Int = 0

  @inline def getStepCount: Long = currentStep.toLong

  @inline private def createThread(name: String, id: Int, runnable: () => Unit): (Thread, Semaphore) = {
    val semaphore = new Semaphore(0)
    val thread = new Thread(
      null,
      () => {
        // BLOCK #1: after being started
        semaphore.acquire() // wait until it is our turn
        onResumeThread(id)
        runnable() // execute user code
        finishThread(id) // finish thread execution
      },
      name
    )
    thread.start()
    (thread, semaphore)
  }

  /** Must be called by a thread right after it resumes execution. */
  @inline private def onResumeThread(id: Int): Unit = {
    activeThreadId = id
    threads(id).status = ThreadActive
  }

  /** Called by every thread right before it is done. */
  private def finishThread(id: Int): Unit = {
    val info = threads(id)
    assert(info.status == ThreadActive)
    debug(s"finishThread(id=$id (${info.name}))")
    threadOrder.finishThread(id)
    info.status = ThreadFinished
    // remove pointer to underlying thread so that it can be garbage collected
    info.underlying = None
    // display thread status for debugging
    dbgThreadList()
    // now that we are done, we need to schedule a new thread, if there are any unfinished threads left
    if (!allThreadsAreFinished) {
      val freeAfterJoin = threadsWithUnblockedJoin
      freeAfterJoin.headOption match {
        case Some(unblockedThread) =>
          // if there is a thread that just got unblocked by us finishing, we want to switch to that
          debugSwitch(id, unblockedThread.id, "finish unblocks join")
          wakeUpThread(unblockedThread)
        case None =>
          // otherwise we make sure a thread waiting on a step will be available and then execute that
          stepSimulationToNearestWait()
          val nextThread = findNextThread()
          debugSwitch(id, nextThread.id, "finish")
          wakeUpThread(nextThread)
      }
    }
  }

  def forkThread(runnable: () => Unit, name: Option[String]): SimThreadId = {
    // generate an ID, name and data structure for the new thread
    val id = threads.length
    val fullName = name.getOrElse(s"chiseltest_thread_$id")
    debug(s"forkThread($fullName ($id)) from ${activeThreadId}")
    // the new thread starts as paused
    val (newJavaThread, newSemaphore) = createThread(fullName, id, runnable)
    threads.addOne(new ThreadInfo(id, fullName, Some(newJavaThread), ThreadWaitingUntil(currentStep), newSemaphore))
    threadOrder.addThread(parent = activeThreadId, id = id)
    // yield to the new thread before returning
    yieldForStep(0, isFork = true)
    new SimThreadId(id)
  }

  private def dbgThreadList(showAlways: Boolean = false): Unit = if (EnableDebug || showAlways) {
    val msg = threadsInSchedulerOrder.map(_.serializeShort(currentStep, activeThreadId)).mkString(", ")
    println("  --> " + msg)
  }

  /** Suspends the active thread for `cycles` steps and schedules a new one to run. */
  @inline private def yieldForStep(cycles: Int, isFork: Boolean): Unit = {
    debug(s"yieldForStep(cycles = $cycles)"); dbgThreadList()
    // find a thread that is ready to run
    val nextThread = findNextThread()
    // set active thread status to paused
    val originalActiveThreadId = activeThreadId
    val activeThread = threads(originalActiveThreadId)
    activeThread.status = ThreadWaitingUntil(currentStep + cycles)
    // switch threads
    debugSwitch(activeThreadId, nextThread.id, if (isFork) s"fork" else s"yield for step ($cycles)")
    wakeUpThread(nextThread)
    // BLOCK #2: as part of a step or fork
    activeThread.semaphore.acquire()
    onResumeThread(originalActiveThreadId)
  }

  @inline private def wakeUpThread(nextThread: ThreadInfo): Unit = {
    // check thread (for debugging)
    val semaphoreNeedsToBeReleased = nextThread.status match {
      case ThreadActive => throw new RuntimeException(s"Cannot resume active thread! $nextThread")
      case ThreadWaitingForJoin(otherThreadId) =>
        val otherThread = threads(otherThreadId)
        assert(otherThread.status == ThreadFinished, s"Cannot resume thread $nextThread waiting on $otherThread")
        false
      case ThreadFinished => throw new RuntimeException(s"Cannot resume finished thread! $nextThread")
      case ThreadWaitingUntil(target) =>
        assert(target == currentStep, s"Cannot resume thread! $nextThread")
        true
    }
    // only threads that are blocked on a step (and not e.g. a join) need their semaphore to be release
    if (semaphoreNeedsToBeReleased) {
      nextThread.semaphore.release()
    }
  }

  @inline private def allThreadsAreFinished: Boolean = threads.forall(_.status == ThreadFinished)

  @inline private def canBeScheduled(status: ThreadStatus): Boolean = status match {
    case ThreadWaitingUntil(step)          => step == currentStep
    case ThreadWaitingForJoin(otherThread) =>
      // check if other thread is finished
      threads(otherThread).status == ThreadFinished
    case _ => false
  }

  /** Determines which thread needs to run next. */
  @inline private def findNextThread(): ThreadInfo = {
    val nextThreadOption = threadsInSchedulerOrder.find(t => canBeScheduled(t.status))
    val nextThread = nextThreadOption.getOrElse {
      debug("Deadlock condition: could not find any thread that can be executed.")
      dbgThreadList()
      throw new RuntimeException("Deadlock!")
    }
    // print debug info and check invariants
    debug(s"  --> nextThreadId = ${nextThread.id}")
    nextThread
  }

  @inline private def threadsWaitingUntil: Iterable[Int] =
    threads.map(_.status).collect { case ThreadWaitingUntil(step) => step }
  @inline private def threadsWithUnblockedJoin: Iterable[ThreadInfo] = threadsInSchedulerOrder.flatMap { t =>
    t.status match {
      case ThreadWaitingForJoin(otherThread) if threads(otherThread).status == ThreadFinished => Some(t)
      case _                                                                                  => None
    }
  }

  /** Steps the currently active thread. Needs to be called in the context of the active thread! */
  def stepThread(cycles: Int): Unit = {
    require(cycles > 0)
    // find all wait cycles
    val waitForSteps = threadsWaitingUntil
    if (waitForSteps.isEmpty) { // all other threads are either finished, or waiting for a join
      doStep(cycles)
    } else {
      val targetStep = currentStep + cycles
      // what is the furthest point in the future that we can step all paused threads?
      val nextWake = waitForSteps.min
      debug(s"stepThread(cycles = $cycles): minPause = ${nextWake - currentStep}");
      dbgThreadList()
      // if all threads are paused for more than we want to step, we do not need to context switch
      if (nextWake > targetStep) {
        doStep(cycles)
      }
      // otherwise we need to potentially first step some other threads
      else {
        // pretend that we are suspended
        val activeThread = threads(activeThreadId)
        activeThread.status = ThreadWaitingUntil(targetStep)
        // perform the biggest step we can
        val stepSize = stepSimulationToNearestWait()
        // yield to the scheduler
        yieldForStep(cycles - stepSize, isFork = false)
      }
    }
  }

  /** Advances the simulation to the closest step that a thread is waiting for.
    * @return
    *   size of the step taken
    */
  private def stepSimulationToNearestWait(): Int = {
    // find all wait cycles
    val waitForSteps = threads.map(_.status).collect { case ThreadWaitingUntil(step) => step }
    // if no thread is waiting, then there is nothing to do
    if (waitForSteps.isEmpty) {
      return 0
    }
    // what is the furthest point in the future that we can step all paused threads?
    val nextWake = waitForSteps.min
    // perform the biggest step we can
    val stepSize = nextWake - currentStep
    if (stepSize > 0) {
      doStep(stepSize)
    }
    stepSize
  }

  /** Performs the simulation step */
  @inline private def doStep(cycles: Int): Unit = {
    debug(s"doStep(cycles = $cycles)")
    val delta = simulationStep(cycles)
    currentStep += delta
  }

  def joinThreads(ids: Seq[SimThreadId]): Unit = joinThreadsImpl(ids.map(_.id))

  private def joinThreadsImpl(ids: Seq[Int]): Unit = {
    debug(s"joinThreads(ids = $ids)")
    // cache ID
    val joiningThreadId = activeThreadId
    // check to make sure we are doing something meaningful
    assert(!ids.contains(joiningThreadId), "cannot join on the active thread!")
    // join all threads that aren't stopped yet
    ids.map(threads(_)).foreach { other =>
      if (other.status != ThreadFinished) {
        // before we block on the join, we need to find another thread to start
        // this might _not_ be the thread we will be joining with!
        stepSimulationToNearestWait()
        val nextThread = findNextThread()
        // we remember which thread we are blocking on
        threads(joiningThreadId).status = ThreadWaitingForJoin(other.id)
        debugSwitch(joiningThreadId, nextThread.id, s"waiting for ${other.id}")
        wakeUpThread(nextThread)
        // BLOCK #3: waiting for other thread to finish
        other.underlying.get.join()
        onResumeThread(joiningThreadId)
      }
    }
  }

  /** Shuts down the main thread by waiting for all other threads to finish, */
  def finishMainThread(): Unit = {
    assert(activeThreadId == MainThreadId, "TODO: deal with exceptions inside of threads correctly!")
    joinThreadsImpl(threads.drop(1).toSeq.map(_.id))
    finishThread(MainThreadId)
  }
}

/** Maintains a tree of threads and uses it in order to derive in which order threads need to run. */
private class ThreadOrder {
  private class Node(var thread: Int, var children: Seq[Node] = null)
  private val root = new Node(0)
  private val idToNode = mutable.ArrayBuffer[Node](root)
  private var threadOrder: Iterable[Int] = Seq()

  /** Returns non-finished threads in the order in which they should be scheduled */
  def getOrder: Iterable[Int] = {
    // if the root thread is still alive, but no threads are in the current order
    if (threadOrder.isEmpty && root.thread == 0) { threadOrder = calculateOrder() }
    threadOrder
  }

  /** Add a new thread. */
  def addThread(parent: Int, id: Int): Unit = {
    // invalidate order
    threadOrder = Seq()
    // create new child node
    val childNode = new Node(id)
    assert(idToNode.length == id, "Expected ids to always increase by one...")
    idToNode.addOne(childNode)
    // insert pointer into parent node
    val parentNode = idToNode(parent)
    if (parentNode.children == null) {
      parentNode.children = List(childNode)
    } else {
      parentNode.children = parentNode.children :+ childNode
    }
  }

  /** Marks thread as finished. */
  def finishThread(id: Int): Unit = {
    // invalidate order
    threadOrder = Seq()
    // check to make sure there are no alive children
    val node = idToNode(id)
    if (node.children != null) {
      val lifeChildren = node.children.filter(_.thread > -1)
      assert(
        lifeChildren.isEmpty,
        f"Cannot finish thread $id since some of its children are still alive: ${lifeChildren.map(_.thread)}"
      )
    }
    // mark node as finished
    node.children = null
    node.thread = -1
  }

  private def calculateOrder(): Iterable[Int] = {
    assert(root.thread == 0, "We lost the main thread!")
    val order = mutable.ArrayBuffer[Int]()
    // threads need to be scheduled in depth first order
    val todo = mutable.Stack[Node]()
    todo.push(root)
    while (todo.nonEmpty) {
      val node = todo.pop()
      order.addOne(node.thread)
      if (node.children != null) {
        val lifeChildren = node.children.filter(_.thread > -1)
        node.children = lifeChildren
        todo.pushAll(lifeChildren.reverse)
      }
    }
    order
  }
}
