import sys
import time
from ble_water_node import run

time.sleep_ms(500)
try:
    run()
except Exception as exc:
    sys.print_exception(exc)
