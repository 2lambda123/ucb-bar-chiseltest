// SPDX-License-Identifier: Apache-2.0

package chiseltest.internal

import chiseltest._
import chisel3._
import chiseltest.coverage.TestCoverage
import chiseltest.simulator.{SimulatorContext, StepInterrupted, StepOk}
import firrtl2.AnnotationSeq

import chiseltest.simulator.ipc.TestApplicationException

import scala.collection.mutable

/** Chiseltest threaded backend using the generic SimulatorContext abstraction from [[chiseltest.simulator]] */
class GenericBackend[T <: Module](
  val design:          DesignInfo,
  tester:              SimulatorContext,
  coverageAnnotations: AnnotationSeq)
    extends BackendInterface[T]
    with ThreadedBackend[T] {

  //
  // Debug utility functions
  //
  val verbose: Boolean = false // hard-coded debug flag
  def debugLog(str: => String): Unit = {
    if (verbose) println(str)
  }

  override def pokeBits(signal: Data, value: BigInt): Unit = {
    doPoke(signal, value, new Throwable)
    if (tester.peek(design.resolveName(signal)) != value) {
      idleCycles.clear()
    }
    tester.poke(design.resolveName(signal), value)
    debugLog(s"${design.resolveName(signal)} <- $value")
  }

  override def peekBits(signal: Data): BigInt = {
    doPeek(signal, new Throwable)
    val name = design
      .getName(signal)
      .getOrElse(
        throw new UnpeekableException(
          s"Signal $signal not found. Perhaps you're peeking a non-IO signal.\n  If so, consider using the chiseltest.experimental.expose API."
        )
      )
    val a = tester.peek(name)
    debugLog(s"$name -> $a")
    a
  }

  protected val clockCounter: mutable.HashMap[Clock, Int] = mutable.HashMap()
  protected def getClockCycle(clk: Clock): Int = {
    clockCounter.getOrElse(clk, 0)
  }
  protected def getClock(clk: Clock): Boolean = tester.peek(design.resolveName(clk)).toInt match {
    case 0 => false
    case 1 => true
  }

  protected val lastClockValue: mutable.HashMap[Clock, Boolean] = mutable.HashMap()

  override def doTimescope(contents: () => Unit): Unit = {
    val createdTimescope = newTimescope()

    contents()

    closeTimescope(createdTimescope).foreach { case (data, valueOption) =>
      val signalName = design.resolveName(data)
      valueOption match {
        case Some(value) =>
          if (tester.peek(signalName) != value) {
            idleCycles.clear()
          }
          tester.poke(signalName, value)
          debugLog(s"${design.resolveName(data)} <- (revert) $value")
        case None =>
          idleCycles.clear()
          tester.poke(signalName, 0) // TODO: randomize or 4-state sim
          debugLog(s"${design.resolveName(data)} <- (revert) DC")
      }
    }
  }

  override def step(signal: Clock, cycles: Int): Unit = {
    // TODO: maybe a fast condition for when threading is not in use?
    for (_ <- 0 until cycles) {
      // If a new clock, record the current value so change detection is instantaneous
      if (signal != design.clock && !lastClockValue.contains(signal)) {
        lastClockValue.put(signal, getClock(signal))
      }

      val thisThread = currentThread.get
      thisThread.clockedOn = Some(signal)
      schedulerState.currentThreadIndex += 1
      scheduler()
      thisThread.waiting.acquire()
    }
  }

  override def getStepCount(signal: Clock): Long = {
    require(signal == design.clock, "step count is only implemented for a single clock right now")
    stepCount
  }

  private var stepCount: Long = 0

  private def stepMainClock(): Unit = {
    tester.step() match {
      case StepOk => // all right
        stepCount += 1
      case StepInterrupted(after, true, _) =>
        val msg = s"An assertion in ${design.name} failed.\n" +
          "Please consult the standard output for more details."
        throw new ChiselAssertionError(msg, stepCount + after)
      case StepInterrupted(after, false, _) =>
        val msg = s"A stop() statement was triggered in ${design.name}."
        throw new StopException(msg, stepCount + after)
    }
  }

  override def run(dut: T, testFn: T => Unit): AnnotationSeq = {
    rootTimescope = Some(new RootTimescope)
    val mainThread = new TesterThread(
      () => {
        tester.poke("reset", 1)
        stepMainClock()
        tester.poke("reset", 0)

        // we only count the user steps
        stepCount = 0

        testFn(dut)
      },
      TimeRegion(0, Region.default),
      rootTimescope.get,
      0,
      Region.default,
      None
    )
    mainThread.thread.start()
    require(allThreads.isEmpty)
    allThreads += mainThread

    try {
      while (!mainThread.done) { // iterate timesteps
        clockCounter.put(design.clock, getClockCycle(design.clock) + 1)

        debugLog(s"clock step")

        // TODO: allow dependent clocks to step based on test stimulus generator
        // TODO: remove multiple invocations of getClock
        // Unblock threads waiting on dependent clock
        val steppedClocks = Seq(design.clock) ++ lastClockValue.collect {
          case (clock, lastValue) if getClock(clock) != lastValue && getClock(clock) => clock
        }
        steppedClocks.foreach { clock =>
          clockCounter.put(dut.clock, getClockCycle(clock) + 1) // TODO: ignores cycles before a clock was stepped on
        }
        lastClockValue.foreach { case (clock, _) =>
          lastClockValue.put(clock, getClock(clock))
        }

        runThreads(steppedClocks.toSet)
        Context().env.checkpoint()

        idleLimits.foreach { case (clock, limit) =>
          idleCycles.put(clock, idleCycles.getOrElse(clock, -1) + 1)
          if (idleCycles(clock) >= limit) {
            throw new TimeoutException(s"timeout on $clock at $limit idle cycles.")
          }
        }

        if (!mainThread.done) {
          stepMainClock()
        }
      }
    } finally {
      rootTimescope = None

      for (thread <- allThreads.clone()) {
        // Kill the threads using an InterruptedException
        if (thread.thread.isAlive) {
          thread.thread.interrupt()
        }
      }
      try {
        tester.finish() // needed to dump VCDs + terminate any external process
      } catch {
        case e: TestApplicationException =>
          throw new ChiselAssertionError(
            s"Simulator exited sooner than expected. See logs for more information about what is assumed to be a Chisel Assertion which failed.",
            stepCount
          )
      }
    }
    if (tester.sim.supportsCoverage) {
      generateTestCoverageAnnotation() +: coverageAnnotations
    } else { Seq() }
  }

  /** Generates an annotation containing the map from coverage point names to coverage counts. */
  private def generateTestCoverageAnnotation(): TestCoverage = {
    TestCoverage(tester.getCoverage())
  }
}
