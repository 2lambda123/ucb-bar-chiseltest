// SPDX-License-Identifier: Apache-2.0

package chiseltest.simulator.jna

import chiseltest.simulator.TopmoduleInfo

/** Generates the Module specific verilator harness cpp file for verilator compilation.
  *  This version generates a harness that can be called into through the JNI.
  */
private[chiseltest] object VerilatorCppJNAHarnessGenerator {
  private val Dot = "__DOT__"
  def codeGen(
    toplevel:         TopmoduleInfo,
    vcdFilePath:      os.Path,
    targetDir:        os.Path,
    majorVersion:     Int,
    minorVersion:     Int,
    coverageCounters: List[String]
  ): String = {
    val pokeable = toplevel.inputs.zipWithIndex
    val peekable = (toplevel.inputs ++ toplevel.outputs).zipWithIndex
    def fitsIn64Bits(s:    ((String, Int), Int)): Boolean = s._1._2 <= 64
    def access(firrtlPath: String): String =
      "dut->" + toplevel.name + Dot + firrtlPath.split('.').mkString(Dot)

    val codeBuffer = new StringBuilder
    // generate Verilator specific "sim_state" class
    codeBuffer.append(s"""
struct sim_state {
  TOP_CLASS* dut;
  VERILATED_C* tfp;
  vluint64_t main_time;
  uint64_t* coverCounters;

  sim_state() :
    dut(new TOP_CLASS),
    tfp(nullptr),
    main_time(0),
    coverCounters(new uint64_t [${coverageCounters.length}])
  {
    // std::cout << "Allocating! " << ((long long) dut) << std::endl;
  }

  inline void step() { _step(tfp, dut, main_time); }
  inline void update() { dut->eval(); }
  inline void finish() {
    dut->eval();
    _finish(tfp, dut);
    delete[] coverCounters;
  }
  inline void resetCoverage() {
""")
    coverageCounters.foreach { name =>
      codeBuffer.append(s"    ${access(name)} = 0;\n")
    }
    codeBuffer.append(s"""
  }
  inline uint64_t* readCoverage() {
""")
    coverageCounters.zipWithIndex.foreach { case (name, i) =>
      codeBuffer.append(s"    coverCounters[$i] = ${access(name)};\n")
    }
    codeBuffer.append(s"""
    return coverCounters;
  }
  inline void poke(int32_t id, int64_t value) {
    const uint64_t u = value;
    // std::cout << "poking: " << std::hex << u << std::endl;
    switch(id) {
""")
    pokeable.filter(fitsIn64Bits).foreach { case ((name, _), id) =>
      codeBuffer.append(s"      case $id : dut->$name = u; break;\n")
    }
    codeBuffer.append(s"""
    default:
      std::cerr << "Cannot find the object of id = " << id << std::endl;
      finish();
      break;
    }
  }
  inline int64_t peek(int32_t id) {
    uint64_t value = 0;
    switch(id) {
""")
    peekable.filter(fitsIn64Bits).foreach { case ((name, _), id) =>
      codeBuffer.append(s"      case $id : value = dut->$name; break;\n")
    }
    codeBuffer.append(s"""
    default:
      std::cerr << "Cannot find the object of id = " << id << std::endl;
      finish();
      return -1;
    }
    // std::cout << "peeking: " << std::hex << value << std::endl;
    return value;
  }
  inline void poke_wide(int32_t id, int32_t offset, int64_t value) {
    const uint64_t u = value;
    WData* data = nullptr;
    size_t words = 0;
    switch(id) {
""")
    pokeable.filterNot(fitsIn64Bits).foreach { case ((name, width), id) =>
      val numWords = (width - 1) / 32 + 1
      codeBuffer.append(s"      case $id : data = dut->$name; words = $numWords; break;\n")
    }
    codeBuffer.append(s"""
    default:
      std::cerr << "Cannot find the object of id = " << id << std::endl;
      finish();
      break;
    }
    const size_t firstWord = offset * 2;
    const size_t secondWord = firstWord + 1;
    if(firstWord >= words || firstWord < 0) {
      std::cerr << "Out of bounds index for id = " << id << " index = " << offset << std::endl;
      finish();
    } else if(secondWord >= words) {
      data[firstWord] = u;
    } else {
      data[firstWord] = u & 0xffffffffu;
      data[secondWord] = (u >> 32) & 0xffffffffu;
    }
  }
  inline int64_t peek_wide(int32_t id, int32_t offset) {
    WData* data = nullptr;
    size_t words = 0;
    switch(id) {
""")
    peekable.filterNot(fitsIn64Bits).foreach { case ((name, width), id) =>
      val numWords = (width - 1) / 32 + 1
      codeBuffer.append(s"      case $id : data = dut->$name; words = $numWords; break;\n")
    }
    codeBuffer.append(s"""
    default:
      std::cerr << "Cannot find the object of id = " << id << std::endl;
      finish();
      return -1;
    }
    const size_t firstWord = offset * 2;
    const size_t secondWord = firstWord + 1;
    if(firstWord >= words || firstWord < 0) {
      std::cerr << "Out of bounds index for id = " << id << " index = " << offset << std::endl;
      finish();
      return -1;
    } else if(secondWord >= words) {
      return (uint64_t)data[firstWord];
    } else {
      return (((uint64_t)data[secondWord]) << 32) | ((uint64_t)data[firstWord]);
    }
  }
};

static sim_state* create_sim_state() {
  sim_state *s = new sim_state();
  std::string dumpfile = "${vcdFilePath}";
  _startCoverageAndDump(&s->tfp, dumpfile, s->dut);
  return s;
}
""")

    val jnaCode = codeBuffer.toString() + JNAUtils.interfaceCode
    commonCodeGen(toplevel, targetDir, majorVersion, minorVersion) + jnaCode
  }

  private def commonCodeGen(
    toplevel:     TopmoduleInfo,
    targetDir:    os.Path,
    majorVersion: Int,
    minorVersion: Int
  ): String = {
    val dutName = toplevel.name
    val dutVerilatorClassName = "V" + dutName

    require(toplevel.clocks.length <= 1, "Multi clock circuits are currently not supported!")
    val clockName = toplevel.clocks.headOption
    val clockLow = clockName.map("top->" + _ + " = 0;").getOrElse("")
    val clockHigh = clockName.map("top->" + _ + " = 1;").getOrElse("")

    val coverageInit =
      if (majorVersion >= 4 && minorVersion >= 202)
        """|#if VM_COVERAGE
           |    Verilated::defaultContextp()->coveragep()->forcePerInstance(true);
           |#endif
           |""".stripMargin
      else ""

    val verilatorRunFlushCallback = if (majorVersion >= 4 && minorVersion >= 38) {
      "Verilated::runFlushCallbacks();\n  Verilated::runExitCallbacks();"
    } else {
      "Verilated::flushCall();"
    }

    val codeBuffer = new StringBuilder
    codeBuffer.append(s"""#include "$dutVerilatorClassName.h"
                         |#include "verilated.h"
                         |
                         |#define TOP_CLASS $dutVerilatorClassName
                         |
                         |#ifndef VM_TRACE_FST
                         |#define VM_TRACE_FST 0
                         |#endif
                         |
                         |#if VM_TRACE
                         |#if VM_TRACE_FST
                         |  #include "verilated_fst_c.h"
                         |  #define VERILATED_C VerilatedFstC
                         |#else // !(VM_TRACE_FST)
                         |  #include "verilated_vcd_c.h"
                         |  #define VERILATED_C VerilatedVcdC
                         |#endif
                         |#else // !(VM_TRACE)
                         |  #define VERILATED_C VerilatedVcdC
                         |#endif
                         |#include <iostream>
                         |
                         |
                         |// Override Verilator definition so first $$finish ends simulation
                         |// Note: VL_USER_FINISH needs to be defined when compiling Verilator code
                         |void vl_finish(const char* filename, int linenum, const char* hier) {
                         |  $verilatorRunFlushCallback
                         |  exit(0);
                         |}
                         |
                         |static void _startCoverageAndDump(VERILATED_C** tfp, const std::string& dumpfile, TOP_CLASS* top) {
                         |$coverageInit
                         |#if VM_TRACE || VM_COVERAGE
                         |    Verilated::traceEverOn(true);
                         |#endif
                         |#if VM_TRACE
                         |    VL_PRINTF(\"Enabling waves..\\n\");
                         |    *tfp = new VERILATED_C;
                         |    top->trace(*tfp, 99);
                         |    (*tfp)->open(dumpfile.c_str());
                         |#endif
                         |}
                         |
                         |static void _step(VERILATED_C* tfp, TOP_CLASS* top, vluint64_t& main_time) {
                         |    $clockLow
                         |    top->eval();
                         |#if VM_TRACE
                         |    if (tfp) tfp->dump(main_time);
                         |#endif
                         |    main_time++;
                         |    $clockHigh
                         |    top->eval();
                         |#if VM_TRACE
                         |    if (tfp) tfp->dump(main_time);
                         |#endif
                         |    main_time++;
                         |}
                         |
                         |static void _finish(VERILATED_C* tfp, TOP_CLASS* top) {
                         |#if VM_TRACE
                         |  if (tfp) tfp->close();
                         |  delete tfp;
                         |#endif
                         |#if VM_COVERAGE
                         |  VerilatedCov::write("$targetDir/coverage.dat");
                         |#endif
                         |  // TODO: re-enable!
                         |  // delete top;
                         |}
                         |""".stripMargin)

    codeBuffer.toString()
  }
}
