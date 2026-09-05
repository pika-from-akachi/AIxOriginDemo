# Hardware-independent processing; identical code used in simulation and on ESP32.
def block_stats(samples):
    s=sorted(samples)
    cut=len(s)//4
    middle=s[cut:len(s)-cut]
    return sum(middle)/len(middle), s[int(len(s)*0.9)]-s[int(len(s)*0.1)]

class AdaptiveFilter:
    def __init__(self):
        self.value=None
        self.direction=0
        self.streak=0
    def update(self, samples):
        estimate, noise=block_stats(samples)
        if self.value is None:
            self.value=estimate
        delta=estimate-self.value
        direction=1 if delta>0 else -1
        if abs(delta)>max(120,noise*2):
            self.streak=self.streak+1 if direction==self.direction else 1
            self.direction=direction
        else:
            self.streak=0
            self.direction=0
        alpha=0.5 if self.streak>=3 else 0.08
        self.value+=alpha*delta
        return round(self.value), round(estimate), noise

def calibration_check(dry,wet):
    d,dnoise=block_stats(dry)
    w,wnoise=block_stats(wet)
    # Conservative demo acceptance rule, not a sensor accuracy specification.
    required=max(512,3*(dnoise+wnoise))
    return {'valid':abs(w-d)>=required,'dry':d,'wet':w,'span':w-d,'required':required}

def level_percent(value,cal):
    if not cal['valid']:
        return None
    return max(0,min(100,round(100*(value-cal['dry'])/cal['span'])))
