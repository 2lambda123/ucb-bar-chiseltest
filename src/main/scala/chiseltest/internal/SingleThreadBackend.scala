package chiseltest.internal

import chisel3.{Clock, Data, Module}
import chiseltest._
import chiseltest.coverage.TestCoverage
import chiseltest.simulator.{SimulatorContext, StepInterrupted, StepOk}
import firrtl2.AnnotationSeq
import scala.collection.mutable

/** Chiseltest backend that does not support fork or timescope but is generally faster since it does not need to launch
  * any Java threads.
  */
class SingleThreadBackend[T <: Module](
  design:              DesignInfo,
  tester:              SimulatorContext,
  coverageAnnotations: AnnotationSeq)
    extends BackendInterface[T] {

  private val previousPokes = mutable.HashMap[String, BigInt]()
  override def pokeBits(signal: Data, value: BigInt): Unit = {
    val name = design.resolveName(signal)
    previousPokes.get(name) match {
      case Some(oldValue) if oldValue == value => // ignore
      case _ =>
        tester.poke(name, value)
        idleCycles = 0
        previousPokes(name) = value
    }
  }

  override def peekBits(signal: Data): BigInt = {
    val name = design
      .getName(signal)
      .getOrElse(
        throw new UnpeekableException(
          s"Signal $signal not found. Perhaps you're peeking a non-IO signal.\n  If so, consider using the chiseltest.experimental.expose API."
        )
      )
    tester.peek(name)
  }

  override def doTimescope(contents: () => Unit): Unit = {
    throw new NotImplementedError("This backend does not support timescopes!")
  }

  override def doFork(runnable: () => Unit, name: Option[String], region: Option[Region]): Nothing = {
    throw new NotImplementedError("This backend does not support threads!")
  }

  override def doJoin(threads: Seq[AbstractTesterThread], stepAfter: Option[Clock]): Unit = {
    throw new NotImplementedError("This backend does not support threads!")
  }

  private var timeout = 1000
  private var idleCycles = 0

  override def step(signal: Clock, cycles: Int): Unit = {
    require(signal == design.clock)
    // throw any available exceptions before stepping
    Context().env.checkpoint()
    val delta = if (timeout == 0) cycles else Seq(cycles, timeout - idleCycles).min
    tester.step(delta) match {
      case StepOk =>
        // update and check timeout
        idleCycles += delta
        stepCount += delta
        if (timeout > 0 && idleCycles == timeout) {
          throw new TimeoutException(s"timeout on $signal at $timeout idle cycles")
        }
      case StepInterrupted(after, true, _) =>
        val msg = s"An assertion in ${design.name} failed.\n" +
          "Please consult the standard output for more details."
        throw new ChiselAssertionError(msg, cycles + after)
      case StepInterrupted(after, false, _) =>
        val msg = s"A stop() statement was triggered in ${design.name}."
        throw new StopException(msg, cycles + after)
    }
  }

  private var stepCount: Long = 0

  override def getStepCount(signal: Clock): Long = {
    require(signal == design.clock)
    stepCount
  }

  override def setTimeout(signal: Clock, cycles: Int): Unit = {
    require(signal == design.clock, "timeout currently only supports master clock")
    require(cycles >= 0, s"Negative timeout $cycles is not supported! Use 0 to disable the timeout.")
    timeout = cycles
    idleCycles = 0
  }

  override def run(dut: T, testFn: T => Unit): AnnotationSeq = {
    try {
      // default reset
      tester.poke("reset", 1)
      tester.step(1)
      tester.poke("reset", 0)

      // we only count the user steps
      stepCount = 0

      // execute use code
      testFn(dut)

      // throw any exceptions that might be left over
      Context().env.checkpoint()
    } finally {
      tester.finish() // needed to dump VCDs + terminate any external process
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
