import time
import sys
from water_monitor import run

time.sleep_ms(500)
try:
    run()
except Exception as exc:
    sys.print_exception(exc)
