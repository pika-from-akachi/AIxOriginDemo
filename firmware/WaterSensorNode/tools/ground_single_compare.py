from machine import ADC, Pin
import time
for gpio in (5, 4, 5):
    Pin(gpio, Pin.IN, pull=None)
    adc=ADC(Pin(gpio),atten=ADC.ATTN_11DB)
    for _ in range(128):
        adc.read_u16()
        time.sleep_ms(2)
    for block in range(4):
        samples=[]
        for _ in range(128):
            samples.append(adc.read_u16())
            time.sleep_ms(3)
        samples.sort()
        print('SINGLE_GPIO',gpio,'BLOCK',block,'min/avg/median/p90/max',samples[0],sum(samples)//128,samples[64],samples[115],samples[-1])
