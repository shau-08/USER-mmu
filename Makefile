project = explorerTL
TARGET ?= "Minimal"
-include cd.config
RTL_TARGET ?= rtl
# Toolchains and tools
MILL = ./../playground/mill
-include ../playground/Makefile.include
# Targets
rtl:check-firtool ## Generates Verilog code from Chisel sources (output to ./generated_sv_dir)
	$(MILL) $(project).runMain $(project).explorerTLMain $(TARGET)
lazyrtl:check-firtool ## Generates Verilog code from Chisel sources (output to ./generated_sv_dir)
	$(MILL) $(project).runMain $(project).lazyExplorerTLMain $(TARGET)
.PHONY: rtl-dispatch
rtl-dispatch:
	$(MAKE) $(RTL_TARGET) TARGET=$(TARGET)
check: test
.PHONY: test
test:check-firtool ## Run Chisel tests
	$(MILL) $(project).test.testOnly $(project).serdes.TestSerDesLoopBack
	@echo "If using WriteVcdAnnotation in your tests, the VCD files are generated in ./test_run_dir/testname directories."
