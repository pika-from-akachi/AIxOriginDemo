from machine import ADC, Pin
import time
channels = {}
for gpio in (4, 5):
    Pin(gpio, Pin.IN, pull=None)
    channels[gpio] = ADC(Pin(gpio), atten=ADC.ATTN_11DB)
    print('CHANNEL', gpio, channels[gpio], channels[gpio].block())
time.sleep_ms(300)
for block in range(6):
    for gpio, adc in channels.items():
        raw = []
        volts = []
        for _ in range(64):
            raw.append(adc.read_u16())
            volts.append(adc.read_uv()//1000)
            time.sleep_ms(3)
        raw.sort()
        print('GROUND',block,'GPIO',gpio,'min/avg/max',raw[0],sum(raw)//64,raw[-1],'median',raw[32],'mv_avg',sum(volts)//64)
