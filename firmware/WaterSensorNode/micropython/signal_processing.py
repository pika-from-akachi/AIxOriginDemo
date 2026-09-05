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


DEPTH_POINTS_MM = (0, 5, 10, 15, 20, 25, 30)


def depth_curve_check(records):
    """Validate point captures and return a raw-to-depth lookup curve."""
    try:
        ordered = sorted(records, key=lambda item: item['depth_mm'])
        depths = tuple(item['depth_mm'] for item in ordered)
        if len(depths) < 2 or depths[0] != 0 or depths[-1] != DEPTH_POINTS_MM[-1]:
            return {'valid': False, 'reason': '0mm and 30mm endpoints required'}
        if len(set(depths)) != len(depths) or any(
                depth not in DEPTH_POINTS_MM for depth in depths):
            return {'valid': False, 'reason': 'invalid or duplicate depths'}
        points = []
        noises = []
        for item in ordered:
            center, noise = block_stats(item['samples'])
            points.append({'depth_mm': item['depth_mm'], 'raw': center})
            noises.append(noise)
    except (KeyError, TypeError, ValueError, ZeroDivisionError):
        return {'valid': False, 'reason': 'invalid point data'}

    span = points[-1]['raw'] - points[0]['raw']
    required = max(512, 3 * (noises[0] + noises[-1]))
    if abs(span) < required:
        return {'valid': False, 'reason': 'total span too small',
                'span': span, 'required': required}
    direction = 1 if span > 0 else -1
    for left, right in zip(points, points[1:]):
        if direction * (right['raw'] - left['raw']) <= 0:
            return {'valid': False, 'reason': 'points are not monotonic',
                    'points': points}
    return {'valid': True, 'points': points, 'span': span,
            'required': required, 'max_depth_mm': DEPTH_POINTS_MM[-1]}


def level_percent_curve(value, curve):
    """Invert a measured nonlinear raw curve into linear depth percent."""
    if not curve or not curve.get('valid'):
        return None
    points = curve['points']
    direction = 1 if points[-1]['raw'] > points[0]['raw'] else -1
    if direction * (value - points[0]['raw']) <= 0:
        return 0
    if direction * (value - points[-1]['raw']) >= 0:
        return 100
    for left, right in zip(points, points[1:]):
        if direction * (value - right['raw']) <= 0:
            fraction = (value - left['raw']) / (right['raw'] - left['raw'])
            depth = left['depth_mm'] + fraction * (
                right['depth_mm'] - left['depth_mm']
            )
            return max(0, min(100, round(
                100 * depth / curve['max_depth_mm']
            )))
    return None


def hysteresis_alarm(value, active, on_threshold, off_threshold):
    """Latch an alarm until the signal falls below a lower reset threshold."""
    if off_threshold >= on_threshold:
        raise ValueError('off threshold must be below on threshold')
    if active:
        return value >= off_threshold
    return value >= on_threshold


def select_buzzer_mode(percent, current='off'):
    """Select off/melody/high with small downward hysteresis."""
    if percent is None:
        return 'off'
    if current == 'high':
        if percent >= 58:
            return 'high'
        current = 'melody'
    if current == 'melody':
        if percent > 60:
            return 'high'
        return 'melody' if percent >= 18 else 'off'
    if percent > 60:
        return 'high'
    return 'melody' if percent >= 20 else 'off'
