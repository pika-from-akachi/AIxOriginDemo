from machine import Pin, SoftI2C, ADC
from ssd1306 import SSD1306_I2C
from signal_processing import AdaptiveFilter, calibration_check, level_percent
import time
import json

class WaterMonitor:
    def __init__(self):
        bus=SoftI2C(sda=Pin(9),scl=Pin(8),freq=100000)
        address=next((a for a in bus.scan() if a in (0x3c,0x3d)),None)
        if address is None: raise RuntimeError('OLED missing SDA9 SCL8')
        self.oled=SSD1306_I2C(128,64,bus,addr=address)
        self.oled.contrast(80)
        Pin(5, Pin.IN, pull=None)
        self.adc=ADC(Pin(5),atten=ADC.ATTN_11DB)
        self.filter=AdaptiveFilter()
        self.divider_gain=1.0 # Direct signal: software provides NO overvoltage protection.
        self.last={}
        self.cal=None
        try:
            with open('water_calibration_5v_direct_gpio5.json') as f: data=json.load(f)
            if data.get('gpio')==5 and data.get('atten')=='11DB':
                self.cal=calibration_check(data['dry'],data['wet'])
        except (OSError,ValueError,KeyError,TypeError): pass

    def update(self):
        raw=[]; mv=[]
        for _ in range(31):
            raw.append(self.adc.read_u16())
            mv.append(self.adc.read_uv()//1000)
            time.sleep_ms(3)
        value,estimate,noise=self.filter.update(raw)
        # Voltage uses robust block averaging and a fixed EMA, in millivolts.
        m=sorted(mv)[7:-7]
        voltage=sum(m)/len(m)
        if not hasattr(self,'voltage'): self.voltage=voltage
        self.voltage+=.18*(voltage-self.voltage)
        percent=level_percent(value,self.cal) if self.cal else None
        saturated=estimate>=65000
        if saturated: percent=None
        status='NO CAL' if self.cal is None else ('WEAK SIGNAL' if not self.cal['valid'] else 'REL LEVEL')
        if saturated: status='ADC SATURATED'
        self.last={'filtered':value,'raw':estimate,'noise':noise,'mv':round(self.voltage),'sensor_mv_est':round(self.voltage*self.divider_gain),'percent':percent,'status':status}
        o=self.oled; o.fill(0)
        o.text('ADC5 / DIRECT',0,0)
        o.text('FILT:%6d'%value,0,12)
        o.text('OUT:%5dmV'%round(self.voltage*self.divider_gain),0,24)
        o.text('mV:%4d N:%4d'%(round(self.voltage),noise),0,36)
        if percent is None:
            o.text(status,0,53)
        else:
            o.text('%3d%%'%percent,0,53)
            o.rect(38,53,90,10,1)
            o.fill_rect(39,54,round(percent*88/100),8,1)
        o.show()
        return value,round(self.voltage)

# Only call after the user confirms the physical state; no automatic dry assumption.
def capture(label):
    if label not in ('dry','wet'): raise ValueError('dry or wet required')
    monitor=WaterMonitor()
    values=[]
    for _ in range(40):
        monitor.update()
        values.append(monitor.last['raw'])
        time.sleep_ms(150)
    if label=='dry':
        try:
            import os
            os.remove('water_calibration_5v_direct_gpio5.json')
        except OSError: pass
        data={'gpio':5,'atten':'11DB','dry':values}
    else:
        with open('water_capture_5v_direct_gpio5.json') as f: data=json.load(f)
        data['wet']=values
    with open('water_capture_5v_direct_gpio5.json','w') as f: json.dump(data,f)
    if 'dry' in data and 'wet' in data:
        result=calibration_check(data['dry'],data['wet'])
        with open('water_calibration_5v_direct_gpio5.json','w') as f: json.dump(data,f)
        print('CALIBRATION',result)
    else: print('DRY_CAPTURE_SAVED',min(values),max(values))

def run():
    m=WaterMonitor(); count=0
    while True:
        m.update()
        if count%4==0: print('WATER',m.last)
        count+=1
        time.sleep_ms(150)
