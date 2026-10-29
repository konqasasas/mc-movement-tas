# Per-section ground ticks (feet on the ground before the next take-off) of REF and OTHER, from RunGap's --csv.
# usage: python3 ground_ticks.py run.csv MIN  (MIN: the same --min as RunGap, so the sections match)
import csv,sys,collections
rows=list(csv.DictReader(open(sys.argv[1]))); MIN=int(sys.argv[2])
R={'ref':[r for r in rows if r['run']=='ref'],'other':[r for r in rows if r['run']=='other']}
g={k:[int(r['ground']) for r in v] for k,v in R.items()}
match=[int(r['match']) for r in R['other']]
def gruns(G):
    out=[];c=0
    for x in G[1:]:
        if x: c+=1
        elif c: out.append(c); c=0
    if c: out.append(c)
    return out
for k in ('ref','other'):
    rs=gruns(g[k]); n=len(g[k])-1
    print(f"{k}: {n} ticks, ground {sum(g[k][1:])}, landings {len(rs)}, jumped next tick {sum(1 for x in rs if x==1)}, ground runs {sorted(collections.Counter(rs).items())}")
O=g['other']; cuts=[0]
for t in range(1,len(O)-1):
    if O[t] and not O[t-1] and t-cuts[-1]>=MIN: cuts.append(t)
cuts.append(len(O)-1)
tot=[0,0,0]; rowsout=[]
print(" other      | ref        | loss | ground other/ref | extra | where")
for a,b in zip(cuts,cuts[1:]):
    ra=match[a]; rb=match[b] if b<len(O)-1 else len(g['ref'])-1
    go=sum(O[a+1:b+1]); gr=sum(g['ref'][ra+1:rb+1]); loss=(b-a)-(rb-ra)
    r=R['other'][a]
    print(f" {a:4d}-{b:<4d} | {ra:4d}-{rb:<4d} | {loss:+4d} | {go:3d} / {gr:3d}       | {go-gr:+4d}  | ({float(r['x']):.1f},{float(r['y']):.1f},{float(r['z']):.1f})")
    rowsout.append((loss,go-gr)); tot[0]+=loss; tot[1]+=go; tot[2]+=gr
n=len(rowsout); mx=sum(x for x,_ in rowsout)/n; my=sum(y for _,y in rowsout)/n
cov=sum((x-mx)*(y-my) for x,y in rowsout); vx=sum((x-mx)**2 for x,_ in rowsout); vy=sum((y-my)**2 for _,y in rowsout)
print(f"total loss {tot[0]:+d}, ground other {tot[1]} ref {tot[2]} (extra {tot[1]-tot[2]:+d}), corr(loss, extra ground) {cov/((vx*vy)**0.5+1e-9):.2f}")
pos=[(x,y) for x,y in rowsout if y>0]; print(f"sections with extra ground: {len(pos)}/{n}, their loss {sum(x for x,_ in pos):+d}; others {sum(x for x,y in rowsout if y<=0):+d}")
