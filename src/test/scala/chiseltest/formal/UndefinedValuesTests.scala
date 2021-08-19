// SPDX-License-Identifier: Apache-2.0

package chiseltest.formal

import org.scalatest.flatspec.AnyFlatSpec
import chiseltest._
import chisel3._
import chisel3.experimental.verification

// most of the tests are inspired by the UndefinedFirrtlSpec in firrtl.backends.experimental.smt.end2end
class UndefinedValuesTests extends AnyFlatSpec with ChiselScalatestTester with Formal {
  "division by zero" should "result in an arbitrary value" taggedAs FormalTag in {
    // the SMTLib spec defines the result of division by zero to be all 1s
    // https://cs.nyu.edu/pipermail/smt-lib/2015/000977.html
    // However, we intentionally add a mechanism to allow the solver to choose an arbitrary
    // value instead of the SMTLib result when dividing by zero.
    // we try to assert that (d = a / 0) is any fixed value which should be false
    (0 until 4).foreach { ii =>
      val e = intercept[FailedBoundedCheckException] {
        verify(new DivisionByZeroIsEq(ii), Seq(BoundedCheck(1)))
      }
      assert(e.failAt == 0)
    }
  }

  "invalid signals" should "have an arbitrary values" taggedAs FormalTag in {
    (0 until 4).foreach { ii =>
      val e = intercept[FailedBoundedCheckException] {
        verify(new InvalidSignalIs(ii), Seq(BoundedCheck(1)))
      }
      assert(e.failAt == 0)
    }
  }
}

class DivisionByZeroIsEq(to: Int) extends Module {
  val a = IO(Input(UInt(2.W)))
  val b = IO(Input(UInt(2.W)))
  val d = a / b
  verification.assume(b === 0.U)
  verification.assert(d === to.U)
}

class InvalidSignalIs(value: Int) extends Module {
  val a = Wire(UInt(2.W))
  a := DontCare
  verification.assert(a === value.U)
}
