# BLE Sensor firmware for the Thunderboard EFR32BG22.
#
#   make                      build for BOARD (default brd4184a)
#   make flash                build and flash over the on-board J-Link
#   make BOARD=brd4184b flash
#   make log                  show the firmware's UART log (VCOM, 115200 8N1)
#   make clean
#
# Toolchain (SDK, slc, GCC, JDK, Commander) lives in ../tools.

BOARD     ?= brd4184a
PROJECT   := ble_sensor_$(BOARD)
TOOLS     := $(abspath ../tools)
SDK       := $(TOOLS)/simplicity_sdk
BUILD_DIR := build/$(BOARD)
HEX       := $(BUILD_DIR)/build/debug/$(PROJECT).hex
SLC       := PATH="$(TOOLS)/jdk/bin:$$PATH" $(TOOLS)/slc_cli/slc
COMMANDER := $(TOOLS)/commander-cli/commander-cli
GCC_DIR   := $(TOOLS)/gcc
VCOM      ?= $(firstword $(wildcard /dev/serial/by-id/usb-Silicon_Labs_J-Link_OB_*-if00))
# Set JLINK_SN when more than one J-Link is plugged in.
JLINK_SN  ?=
SN_ARG    := $(if $(JLINK_SN),--serialno $(JLINK_SN),)

# Every source file, including subfolders (ble/, sensors/, home_assistant/, ...)
SOURCES := $(shell find src -name '*.[ch]') config/btconf/gatt_configuration.btconf $(PROJECT).slcp

.PHONY: all generate flash erase log clean

all: $(HEX)

# slc never overwrites config files already in the output directory, so
# regenerate from scratch whenever the project or GATT database changes.
# --sdk pins this checkout's own SDK; otherwise slc uses whatever SDK the
# per-user ~/.uc/cli/cli.config points at. "signature trust" is a one-time,
# idempotent step slc requires for each SDK path.
$(BUILD_DIR)/$(PROJECT).Makefile: $(PROJECT).slcp config/btconf/gatt_configuration.btconf
	rm -rf $(BUILD_DIR)
	$(SLC) signature trust --sdk $(SDK)
	$(SLC) generate --sdk $(SDK) -p $(PROJECT).slcp -d $(BUILD_DIR) -o makefile --with $(BOARD)

generate: $(BUILD_DIR)/$(PROJECT).Makefile

$(HEX): $(BUILD_DIR)/$(PROJECT).Makefile $(SOURCES)
	$(MAKE) -C $(BUILD_DIR) -f $(PROJECT).Makefile -j$$(nproc) ARM_GCC_DIR=$(GCC_DIR)

flash: $(HEX)
	$(COMMANDER) flash $(HEX) --device EFR32BG22C224F512IM40 $(SN_ARG)

# Wipes the whole chip, including stored config and any bootloader.
erase:
	$(COMMANDER) device masserase --device EFR32BG22C224F512IM40 $(SN_ARG)

log:
	../.venv/bin/python -m serial.tools.miniterm --raw $(VCOM) 115200

clean:
	rm -rf build
