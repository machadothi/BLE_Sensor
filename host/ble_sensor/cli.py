"""Command-line interface: `ble-sensor <command>`. Run with --help for the list."""

from __future__ import annotations

import argparse
import asyncio
import struct
import sys
from dataclasses import fields
from typing import Awaitable, Callable, Optional

from .client import BleSensor
from .protocol import COMMANDS, DISPLAY_PAGE_TIME_S, DISPLAY_PAGES, LED_MODES, LIMITS, SENSOR_BITS, Button, Config, Env, Motion

STREAMS = ["env", "motion", "button"]

# --- printing ------------------------------------------------------------------


def _fmt(value: Optional[float], unit: str = "", digits: int = 2) -> str:
    return "n/a" if value is None else f"{value:.{digits}f}{unit}"


def print_env(env: Env) -> None:
    hall = _fmt(env.hall_mt, " mT", 3)
    if env.hall_alert:
        hall += " [ALERT]"
    if env.hall_tamper:
        hall += " [TAMPER]"
    print(f"[{env.uptime_ms / 1000:9.2f}s] "
          f"T={_fmt(env.temperature_c, '°C')} RH={_fmt(env.humidity_pct, '%')} "
          f"light={_fmt(env.lux, ' lx', 1)} UV={_fmt(env.uv_index)} hall={hall} "
          f"sound={_fmt(env.sound_db, ' dB', 1)} supply={_fmt(env.supply_v, ' V', 3)} "
          f"die={_fmt(env.die_temperature_c, '°C', 1)}")


def print_motion(motion: Motion) -> None:
    ax, ay, az = motion.accel_g
    gx, gy, gz = motion.gyro_dps
    roll, pitch, yaw = motion.orientation_deg
    print(f"[{motion.uptime_ms / 1000:9.2f}s] "
          f"acc=({ax:+.3f},{ay:+.3f},{az:+.3f}) g  "
          f"gyro=({gx:+7.2f},{gy:+7.2f},{gz:+7.2f}) °/s  "
          f"roll/pitch/yaw=({roll:+7.2f},{pitch:+7.2f},{yaw:+7.2f})°")


def print_button(button: Button) -> None:
    print(f"button {'PRESSED' if button.pressed else 'released'} (presses: {button.press_count})")


def print_config(config: Config) -> None:
    for field in fields(config):
        print(f"  {field.name:18} {getattr(config, field.name)}")
    print(f"  {'sensors':18} {', '.join(config.sensors) or 'none'}")


# --- commands ------------------------------------------------------------------
# Each takes the connected board and the parsed arguments.


async def cmd_info(board: BleSensor, args: argparse.Namespace) -> None:
    info = await board.read_info()
    led = await board.read_led()
    print(f"board:     {info.board}")
    print(f"protocol:  v{info.protocol_version}")
    print(f"sensors:   {', '.join(info.available)}")
    print("config:")
    print_config(await board.read_config())
    print(f"led:       {led.mode} (on {led.on_ms} ms / off {led.off_ms} ms)")


async def cmd_read(board: BleSensor, args: argparse.Namespace) -> None:
    print_env(await board.read_env())
    try:
        print_motion(await board.read_motion())
    except struct.error:   # empty value: IMU off or no sample yet
        print("motion: n/a")
    print_button(await board.read_button())


async def cmd_monitor(board: BleSensor, args: argparse.Namespace) -> None:
    streams = set(args.streams or STREAMS)
    unknown = streams - set(STREAMS)
    if unknown:
        raise SystemExit(f"unknown streams: {', '.join(sorted(unknown))}")

    if "env" in streams:
        await board.on_env(print_env)
    if "motion" in streams:
        await board.on_motion(print_motion)
    if "button" in streams:
        await board.on_button(print_button)

    print("Monitoring, Ctrl+C to stop.", file=sys.stderr)
    if args.duration:
        await asyncio.sleep(args.duration)
    else:
        await asyncio.Event().wait()   # forever


async def cmd_led(board: BleSensor, args: argparse.Namespace) -> None:
    await board.set_led(args.mode, args.on_ms, args.off_ms)


async def cmd_config(board: BleSensor, args: argparse.Namespace) -> None:
    config_fields = {field.name for field in fields(Config)}
    changes = {k: v for k, v in vars(args).items() if k in config_fields and v is not None}

    sensors = None
    if args.sensors is not None:
        sensors = [] if args.sensors == "none" else args.sensors.split(",")

    if changes or sensors is not None:
        try:
            await board.set_config(sensors=sensors, **changes)
        except ValueError as e:
            raise SystemExit(str(e))
    print_config(await board.read_config())


async def cmd_display(board: BleSensor, args: argparse.Namespace) -> None:
    pages = None
    if args.pages is not None:
        pages = DISPLAY_PAGES if args.pages == "all" else [] if args.pages == "none" else args.pages.split(",")
    if pages is not None or args.page_time is not None:
        try:
            await board.set_display(pages=pages, page_time_s=args.page_time)
        except ValueError as e:
            raise SystemExit(str(e))
    display = await board.read_display()
    print(f"display:  {'connected' if display.present else 'not connected'}")
    print(f"each reading shown for {display.page_time_s:g} s")
    for name in DISPLAY_PAGES:
        print(f"  [{'x' if name in display.pages else ' '}] {name}")


async def cmd_name(board: BleSensor, args: argparse.Namespace) -> None:
    await board.set_name(args.new_name)
    print("Name saved; it is advertised after this disconnect.")


async def cmd_board_command(board: BleSensor, args: argparse.Namespace) -> None:
    if args.command == "calibrate":
        print("Keep the board still...", file=sys.stderr)
    await board.command(args.command)


Handler = Callable[[BleSensor, argparse.Namespace], Awaitable[None]]


# --- argument parsing ------------------------------------------------------------


def _range_help(field: str, unit: str, note: str = "") -> str:
    low, high = LIMITS[field]
    return f"{unit}, {low:g} to {high:g}{note}"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="ble-sensor", description="Thunderboard BG22 BLE Sensor client")
    parser.add_argument("-a", "--address", help="board Bluetooth address (default: first board found)")
    parser.add_argument("-n", "--name", help="connect by advertised name instead")
    sub = parser.add_subparsers(dest="command", required=True)

    def add(name: str, handler: Optional[Handler], help: str) -> argparse.ArgumentParser:
        command = sub.add_parser(name, help=help)
        command.set_defaults(handler=handler)
        return command

    scan = add("scan", None, "list boards in range")
    scan.add_argument("-t", "--timeout", type=float, default=6.0, help="seconds (default 6)")

    add("info", cmd_info, "board revision, sensors, config, LED")
    add("read", cmd_read, "read every sensor once")

    monitor = add("monitor", cmd_monitor, "stream live sensor notifications")
    # No choices=: Python 3.10's argparse rejects an empty nargs="*" list with choices.
    monitor.add_argument("streams", nargs="*", help=f"any of {', '.join(STREAMS)} (default: all)")
    monitor.add_argument("-d", "--duration", type=float, help="stop after N seconds")

    led = add("led", cmd_led, "control the LED")
    led.add_argument("mode", choices=list(LED_MODES))
    led.add_argument("--on-ms", type=int, default=500)
    led.add_argument("--off-ms", type=int, default=500)

    config = add("config", cmd_config, "show or change the stored configuration")
    config.add_argument("--sensors", help=f"comma list of {','.join(SENSOR_BITS)}, or 'none'")
    config.add_argument("--env-period", dest="env_period_ms", type=int,
                        help=_range_help("env_period_ms", "ms"))
    config.add_argument("--motion-period", dest="motion_period_ms", type=int,
                        help=_range_help("motion_period_ms", "ms"))
    config.add_argument("--tx-power", dest="tx_power_dbm", type=float,
                        help=_range_help("tx_power_dbm", "dBm", "; applied after disconnect"))
    config.add_argument("--adv-interval", dest="adv_interval_ms", type=int,
                        help=_range_help("adv_interval_ms", "ms", "; applied after disconnect"))
    config.add_argument("--hall-threshold", dest="hall_threshold_mt", type=float,
                        help=_range_help("hall_threshold_mt", "mT"))

    display = add("display", cmd_display, "show or choose what the OLED display shows")
    display.add_argument("--pages", help=f"comma list of {','.join(DISPLAY_PAGES)}, or 'all' / 'none'")
    display.add_argument("--page-time", type=float,
                         help=f"seconds per reading, {DISPLAY_PAGE_TIME_S[0]:g} to {DISPLAY_PAGE_TIME_S[1]:g}")

    name = add("name", cmd_name, "rename the board (stored)")
    name.add_argument("new_name")

    add("calibrate", cmd_board_command, "gyro calibration; keep the board still")
    add("reset-orientation", cmd_board_command, "zero roll/pitch/yaw")
    add("identify", cmd_board_command, "blink the LED fast for 3 s")
    add("factory-reset", cmd_board_command, "restore default config and name")
    add("reboot", cmd_board_command, "reboot the board")
    assert set(COMMANDS) <= set(sub.choices), "every protocol command needs a CLI entry"
    return parser


async def run(args: argparse.Namespace) -> None:
    if args.command == "scan":
        boards = await BleSensor.scan(timeout=args.timeout)
        if not boards:
            print("No BLE Sensor boards found.")
        for device, rssi, revision in boards:
            print(f"{device.address}  {rssi:4d} dBm  {revision}  {device.name}")
        return

    async with await BleSensor.connect(address=args.address, name=args.name) as board:
        print(f"Connected to {await board.read_name()} ({board.client.address})", file=sys.stderr)
        await args.handler(board, args)


def main() -> None:
    args = build_parser().parse_args()
    try:
        asyncio.run(run(args))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
