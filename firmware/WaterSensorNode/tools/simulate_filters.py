from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "micropython"))
import random, math, json
from signal_processing import AdaptiveFilter,block_stats,calibration_check,level_percent
class Old:
    def __init__(self): self.value=None; self.shown=None
    def update(self,s):
        v,_=block_stats(s)
        self.value=v if self.value is None else self.value+.18*(v-self.value)
        if self.shown is None or abs(self.value-self.shown)>=48: self.shown=round(self.value)
        return self.shown
report={'note':'SYNTHETIC DATA ONLY. Not evidence of physical water response.','cases':{}}
for name,start,end in [('rising',2800,18000),('falling',18000,2800),('overlap',2800,2850)]:
    rng=random.Random(42)
    old,new=Old(),AdaptiveFilter()
    rows=[]
    for frame in range(160):
        truth=start if frame<80 else end
        block=[]
        for k in range(31):
            v=truth+rng.gauss(0,180)
            if rng.random()<.05: v+=rng.choice([-14000,14000])
            block.append(round(max(0,min(65535,v))))
        a=old.update(block); b,raw,n=new.update(block)
        rows.append((truth,a,b))
    stable=list(range(20,80))+list(range(120,160))
    metrics={}
    for label,index in [('old',1),('adaptive',2)]:
        rmse=math.sqrt(sum((rows[i][index]-rows[i][0])**2 for i in stable)/len(stable))
        settle=next((i for i in range(80,155) if all(abs(rows[j][index]-end)<=max(100,abs(end-start)*.1) for j in range(i,i+5))),None)
        metrics[label]={'steady_rmse':round(rmse,1),'step_90pct_seconds':None if settle is None else round((settle-80)*.25,2)}
    report['cases'][name]=metrics
rng=random.Random(5)
dry=[round(rng.gauss(2800,100)) for _ in range(80)]
near=[round(rng.gauss(2850,100)) for _ in range(80)]
wet=[round(rng.gauss(18000,100)) for _ in range(80)]
cal=calibration_check(dry,wet)
assert cal['valid'] and not calibration_check(dry,near)['valid']
assert level_percent(cal['dry'],cal)==0 and level_percent(cal['wet'],cal)==100
reverse=calibration_check(wet,dry)
assert reverse['valid'] and level_percent(reverse['wet'],reverse)==100
assert level_percent(2800,calibration_check(dry,near)) is None
report['calibration_checks']='PASS: clear/reverse response accepted, overlapping states rejected'
for name in ('rising','falling'):
    assert report['cases'][name]['adaptive']['step_90pct_seconds'] < report['cases'][name]['old']['step_90pct_seconds']
print(json.dumps(report,indent=2))
