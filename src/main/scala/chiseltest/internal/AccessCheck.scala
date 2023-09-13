// Copyright 2018-2023 The Regents of the University of California
// released under BSD 3-Clause License
// author: Kevin Laeufer <laeufer@cs.berkeley.edu>

package chiseltest.internal

import chisel3.experimental.Direction
import chisel3.reflect.DataMirror
import chisel3.{Clock, Data}
import chiseltest.internal.AccessCheck.populateSignals
import chiseltest.{
  ChiselAssertionError,
  StopException,
  ThreadOrderDependentException,
  TimeoutException,
  UnpeekableException,
  UnpokeableException
}
import chiseltest.simulator.{SimulatorContext, StepInterrupted, StepOk}

/** Meta data about a signal that can be peeked or poked. */
private class SignalInfo(
  /** Name used by the simulator. */
  val name: String,
  /** Unique ID. */
  val id: Int,
  /** Indicates that the signal cannot be poked. */
  val readOnly: Boolean,
  /** Lists any signals that this signal depends on. */
  val dependsOn: Seq[Int],
  /** Lists any signals that are depended on by this signal. */
  val dependedOnBy: Seq[Int],
  /** Value of the last poke. Used in order to avoid unnecessary calls to the simulator. */
  var lastPokeValue: BigInt,
  /** Time of the last peek or poke. */
  var lastAccessAt: Int = -1,
  /** Id of the thread that performed the last peek or poke. */
  var lastAccessFrom: Int = -1,
  /** Type of the last access */
  var lastAccessWasPoke: Boolean = false) {}

/** Regulates I/O access from different threads. Keeps track of timeout. */
private class AccessCheck(design: DesignInfo, tester: SimulatorContext) {
  private var timeout:    Int = SimController.DefaultTimeout
  private var idleCycles: Int = 0
  private val signals = populateSignals(design, tester).toMap
  private val idToSignal = {
    val sorted = signals.values.toSeq.sortBy(_.id)
    assert(sorted.zipWithIndex.forall(t => t._1.id == t._2))
    sorted.toIndexedSeq
  }
  private def lookupSignal(signal: Data, isPeekNotPoke: Boolean): SignalInfo = signals.getOrElse(
    signal, {
      val msg = s"Signal $signal not found. Perhaps you're peeking a non-IO signal.\n " +
        s"If so, consider using the chiseltest.experimental.expose API."
      if (isPeekNotPoke) {
        throw new UnpeekableException(msg)
      } else {
        throw new UnpokeableException(msg)
      }
    }
  )

  def pokeBits(threadInfo: ThreadInfoProvider, signal: Data, value: BigInt): Unit = {
    val info = lookupSignal(signal, isPeekNotPoke = false)
    assert(!info.readOnly, "can only poke input! This should have been detected earlier.")
    // check for conflicting pokes
    if (hasConflictingAccess(info, threadInfo)) {
      if (info.lastAccessWasPoke) {
        throw new ThreadOrderDependentException("Conflicting pokes!") // TODO: better message
      } else {
        throw new ThreadOrderDependentException("Conflicting peek!") // TODO: better message
      }
    }
    // have any of the signals that are influences by this input been peeked?
    info.dependedOnBy.foreach { id =>
      val info = idToSignal(id)
      if (hasConflictingAccess(info, threadInfo) && !info.lastAccessWasPoke) {
        throw new ThreadOrderDependentException(
          "Conflicting peek of a signal that may change with this poke!"
        ) // TODO: better message
      }
    }

    // check to see if the same value is already applied
    if (info.lastPokeValue == value) {
      // nothing to do!
    } else {
      tester.poke(info.name, value)
      info.lastPokeValue = value
      // only reset timeout if the poke has an effect
      idleCycles = 0
    }
    // record poke
    info.lastAccessFrom = threadInfo.getActiveThreadId
    info.lastAccessAt = threadInfo.getStepCount
    info.lastAccessWasPoke = true
  }

  /** Was there an access that crosses thread boundaries in this current time step. */
  private def hasConflictingAccess(info: SignalInfo, threadInfo: ThreadInfoProvider): Boolean =
    info.lastAccessAt == threadInfo.getStepCount &&
      info.lastAccessFrom != threadInfo.getActiveThreadId &&
      !threadInfo.isParentOf(info.lastAccessFrom, threadInfo.getActiveThreadId)

  def peekBits(threadInfo: ThreadInfoProvider, signal: Data): BigInt = {
    val info = lookupSignal(signal, isPeekNotPoke = true)
    // has this signal been poked?
    if (hasConflictingAccess(info, threadInfo) && info.lastAccessWasPoke) {
      throw new ThreadOrderDependentException(
        "Conflicting poke on signal that is being peeked!"
      ) // TODO: better message
    }

    // check to see if there have been any dependent pokes from another thread
    info.dependsOn.foreach { id =>
      val info = idToSignal(id)
      if (hasConflictingAccess(info, threadInfo) && info.lastAccessWasPoke) {
        throw new ThreadOrderDependentException("Conflicting poke that influences peek!") // TODO: better message
      }
    }

    // record peek
    info.lastAccessFrom = threadInfo.getActiveThreadId
    info.lastAccessAt = threadInfo.getStepCount
    info.lastAccessWasPoke = false

    tester.peek(info.name)
  }

  def setTimeout(signal: Clock, cycles: Int): Unit = {
    require(signal == design.clock, "timeout currently only supports master clock")
    require(cycles >= 0, s"Negative timeout $cycles is not supported! Use 0 to disable the timeout.")
    timeout = cycles
    idleCycles = 0
  }

  /** Performs a step on the actual simulation (as opposed to a "virtual" thread step) */
  def simulationStep(from: Int, cycles: Int): Int = {
    // throw any available exceptions before stepping
    Context().env.checkpoint()
    val delta = if (timeout == 0) cycles else Seq(cycles, timeout - idleCycles).min
    tester.step(delta) match {
      case StepOk =>
        // update and check timeout
        idleCycles += delta
        if (timeout > 0 && idleCycles == timeout) {
          throw new TimeoutException(s"timeout on ${design.clock} at $timeout idle cycles")
        }
        delta
      case StepInterrupted(after, true, _) =>
        val msg = s"An assertion in ${design.name} failed.\n" +
          "Please consult the standard output for more details."
        throw new ChiselAssertionError(msg, from + after)
      case StepInterrupted(after, false, _) =>
        val msg = s"A stop() statement was triggered in ${design.name}."
        throw new StopException(msg, from + after)
    }
  }
}

private object AccessCheck {
  private def populateSignals(design: DesignInfo, tester: SimulatorContext): Seq[(Data, SignalInfo)] = {
    val sorted = design.dataNames.toSeq.sortBy(_._2)
    // we only care about leaf Signals
    val onlyIO = sorted.filter { case (signal, _) =>
      val direction = DataMirror.directionOf(signal)
      direction == Direction.Output || direction == Direction.Input
    }
    var count: Int = 0
    // precompute mapping
    val nameToIds: Map[String, Seq[Int]] =
      onlyIO
        .map(_._2)
        .map { name =>
          val id = count; count += 1; name -> id
        }
        .groupBy(_._1)
        .map { case (key, values) => key -> values.map(_._2) }
    val reverseDependencies = design.combinationalPaths.toSeq.flatMap { case (sink, sources) =>
      sources.map(src => src -> sink)
    }.groupBy(_._1).map { case (key, values) => key -> values.map(_._2) }
    count = 0
    onlyIO.map { case (signal, name) =>
      val id = count; count += 1
      // check direction
      val direction = DataMirror.directionOf(signal)
      val isOutput = direction == Direction.Output
      val isInput = direction == Direction.Input
      assert(isOutput || isInput, f"$direction")
      // find dependencies
      val dependsOn = design.combinationalPaths.getOrElse(name, Seq()).flatMap(nameToIds).sorted
      val dependedOn = reverseDependencies.getOrElse(name, Seq()).flatMap(nameToIds).sorted
      val info = new SignalInfo(
        name,
        id,
        readOnly = isOutput,
        dependsOn = dependsOn,
        dependedOnBy = dependedOn,
        lastPokeValue = 0
      )
      signal -> info
    }
  }
}
