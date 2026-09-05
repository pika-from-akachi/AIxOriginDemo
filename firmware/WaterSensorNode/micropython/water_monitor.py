from machine import Pin, SPI, ADC
from signal_processing import (
    AdaptiveFilter,
    DEPTH_POINTS_MM,
    block_stats,
    calibration_check,
    depth_curve_check,
    level_percent,
    level_percent_curve,
)
import time
import json

CALIBRATION_FILE = 'water_calibration_gpio5_3cm.json'
CAPTURE_FILE = 'water_capture_gpio5_3cm.json'
CURVE_FILE = 'water_depth_curve_gpio5_3cm.json'
CURVE_CAPTURE_FILE = 'water_depth_curve_capture_gpio5_3cm.json'

class WaterMonitor:
    def __init__(self):
        self.oled=None
        try:
            from ssd1306 import SSD1309_SPI
            spi=SPI(1,baudrate=10000000,polarity=0,phase=0,sck=Pin(12),mosi=Pin(11))
            self.oled=SSD1309_SPI(128,64,spi,dc=Pin(7),res=Pin(8),cs=Pin(10))
            self.oled.contrast(80)
        except Exception as exc:
            print('OLED_DISABLED',repr(exc))
        Pin(5, Pin.IN, pull=None)
        self.adc=ADC(Pin(5),atten=ADC.ATTN_11DB)
        self.filter=AdaptiveFilter()
        self.divider_gain=1.0 # Direct signal: software provides NO overvoltage protection.
        self.last={}
        self.cal=None
        self.curve=None
        try:
            with open(CALIBRATION_FILE) as f: data=json.load(f)
            if data.get('gpio')==5 and data.get('atten')=='11DB':
                self.cal=calibration_check(data['dry'],data['wet'])
        except (OSError,ValueError,KeyError,TypeError): pass
        try:
            with open(CURVE_FILE) as f: data=json.load(f)
            if data.get('gpio')==5 and data.get('atten')=='11DB':
                checked=depth_curve_check(data['records'])
                if checked['valid']: self.curve=checked
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
        if self.curve:
            percent=level_percent_curve(value,self.curve)
        else:
            percent=level_percent(value,self.cal) if self.cal else None
        saturated=estimate>=65000
        if saturated: percent=None
        status='CURVE LEVEL' if self.curve else (
            'NO CAL' if self.cal is None else
            ('WEAK SIGNAL' if not self.cal['valid'] else 'REL LEVEL')
        )
        if saturated: status='ADC SATURATED'
        self.last={'filtered':value,'raw':estimate,'noise':noise,'mv':round(self.voltage),'sensor_mv_est':round(self.voltage*self.divider_gain),'percent':percent,'status':status}
        o=self.oled
        if o is not None:
            o.fill(0)
            o.text('ADC5 / 3CM REF',0,0)
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
            os.remove(CALIBRATION_FILE)
        except OSError: pass
        data={'gpio':5,'atten':'11DB','dry':values}
    else:
        with open(CAPTURE_FILE) as f: data=json.load(f)
        data['wet']=values
    with open(CAPTURE_FILE,'w') as f: json.dump(data,f)
    if 'dry' in data and 'wet' in data:
        result=calibration_check(data['dry'],data['wet'])
        with open(CALIBRATION_FILE,'w') as f: json.dump(data,f)
        print('CALIBRATION',result)
    else: print('DRY_CAPTURE_SAVED',min(values),max(values))


def capture_depth(depth_mm):
    """Capture one known-depth point without replacing the active curve early."""
    if depth_mm not in DEPTH_POINTS_MM:
        raise ValueError('depth must be one of %s mm' % (DEPTH_POINTS_MM,))
    monitor=WaterMonitor()
    values=[]
    for _ in range(40):
        monitor.update()
        values.append(monitor.last['raw'])
        time.sleep_ms(150)

    if depth_mm == 0:
        data={'gpio':5,'atten':'11DB','records':[]}
        try:
            import os
            os.remove(CURVE_FILE)
        except OSError: pass
    else:
        with open(CURVE_CAPTURE_FILE) as f: data=json.load(f)

    data['records']=[
        item for item in data['records'] if item['depth_mm'] != depth_mm
    ]
    data['records'].append({'depth_mm':depth_mm,'samples':values})
    data['records'].sort(key=lambda item:item['depth_mm'])
    with open(CURVE_CAPTURE_FILE,'w') as f: json.dump(data,f)

    captured=tuple(item['depth_mm'] for item in data['records'])
    result=depth_curve_check(data['records'])
    if result['valid']:
        with open(CURVE_FILE,'w') as f: json.dump(data,f)
        print('DEPTH_CURVE',result)
    else:
        center,noise=block_stats(values)
        print('DEPTH_POINT_SAVED depth_mm=%d raw=%s noise=%s captured=%s'
              %(depth_mm,center,noise,captured))


def activate_provisional_curve():
    """Combine captured 0/5mm points with the existing measured 30mm endpoint."""
    with open(CURVE_CAPTURE_FILE) as f: data=json.load(f)
    with open(CALIBRATION_FILE) as f: endpoint=json.load(f)
    data['records']=[
        item for item in data['records'] if item['depth_mm'] != 30
    ]
    data['records'].append({'depth_mm':30,'samples':endpoint['wet']})
    data['records'].sort(key=lambda item:item['depth_mm'])
    result=depth_curve_check(data['records'])
    if not result['valid']:
        raise ValueError(result)
    with open(CURVE_CAPTURE_FILE,'w') as f: json.dump(data,f)
    with open(CURVE_FILE,'w') as f: json.dump(data,f)
    print('PROVISIONAL_DEPTH_CURVE',result)

def run():
    m=WaterMonitor(); count=0
    while True:
        m.update()
        if count%4==0: print('WATER',m.last)
        count+=1
        time.sleep_ms(150)
