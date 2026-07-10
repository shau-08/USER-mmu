project = mmu

TARGET = MMU

# Toolchains and tools
MILL = ./../playground/mill

-include ./../playground/Makefile.include

# Targets
rtl: check-firtool ## Generates Verilog code from Chisel sources (output to ./generated_sv_dir)
	$(MILL) $(project).runMain redefine.rrm.mmu.genRTLMain $(TARGET)


check: test
.PHONY: test
test: check-verilator ## Run Chisel tests
	$(MILL) $(project).test.testOnly redefine.rrm.mmu.smoke.MMUTest
	@echo "If using WriteVcdAnnotation in your tests, the VCD files are generated in ./test_run_dir/testname directories."
