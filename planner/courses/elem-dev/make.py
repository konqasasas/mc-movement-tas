# Writes the element dev courses (stairs, slabs, skulls, posts, a slalom between panes, ladders) into the current folder.
# Run from planner/courses/elem-dev: python3 make.py
import json,sys
def course(id, fills, goal, start_y=65, maxTicks=800):
    base=[{"from":[-2,64,-10],"to":[2,64,0],"block":"minecraft:stone"}]
    d={"format":"mctas-problem","version":1,"id":"elem-dev/"+id,"mcVersion":"1.12.2",
       "world":{"source":"inline","fills":base+fills},
       "spawn":{"pos":[0.5,start_y,-5.5],"yaw":0.0,"pitch":0.0},
       "start":{"min":[-2.0,start_y,-3.0],"max":[3.0,start_y+6,-2.5]},
       "goal":goal,"limits":{"maxTicks":maxTicks,"voidY":50.0}}
    s=json.dumps(d,indent=None)
    s=s.replace('{"from"','\n    {"from"')
    open(id+".problem.json","w").write(s+"\n")
def F(a,b,blk): return {"from":a,"to":b,"block":"minecraft:"+blk}
def G(x0,y,z0,x1,z1): return {"min":[x0,y,z0],"max":[x1,y,z1]}
# e01: a staircase of bottom stairs up 6, each step one stair + full block columns under it
f=[]
for i in range(6):
    z=2+i; y=65+i
    if i: f.append(F([-1,65,z],[1,y-1,z],"stone"))
    f.append(F([-1,y,z],[1,y,z],"stone_brick_stairs[facing=north,half=bottom]"))
f.append(F([-1,64,8],[1,70,11],"stone"))
course("e01-stairs",f,G(-1,71,10,2,12))
# e02: rising slabs with gaps, each slab 0.5 higher
f=[]
y=65.0; z=2
for i in range(8):
    by=int(y); half="bottom" if y==by else "top"
    f.append(F([0,by,z],[0,by,z],"stone_slab[half=%s]"%half))
    y+=0.5; z+=3
f.append(F([-1,int(y)-1,z],[1,int(y)-1,z+3],"stone"))
course("e02-slabs",f,G(-1,int(y),z+1,2,z+4))
# e03: single skulls (8x8 px) on pillars, climbing 1 per jump
f=[]
for i in range(5):
    z=2+3*i; top=65+i
    if top>65: f.append(F([0,65,z],[0,top-1,z],"stone"))
    f.append(F([0,top,z],[0,top,z],"skull[facing=up]"))
zf=2+3*5
f.append(F([-1,65,zf],[1,69,zf+3],"stone"))
course("e03-skulls",f,G(-1,70,zf+1,2,zf+4))
# e04: isolated wall and fence posts (1.5 high) in a line, zigzag
f=[]
for i,(x,blk) in enumerate([(0,"cobblestone_wall"),(1,"spruce_fence"),(0,"cobblestone_wall"),(-1,"spruce_fence"),(0,"cobblestone_wall"),(1,"spruce_fence")]):
    z=2+3*i
    f.append(F([x,64,z],[x,64,z],blk))
zf=2+3*6
f.append(F([-1,64,zf],[1,64,zf+3],"stone"))
course("e04-posts",f,G(-1,65,zf+1,2,zf+4))
# e05: corridor floor with pane / bar / wall posts forming a slalom, roof at 3 high
f=[F([-3,64,1],[3,64,24],"stone"),F([-3,65,1],[-3,67,24],"barrier"),F([3,65,1],[3,67,24],"barrier")]
for i,z in enumerate(range(3,22,3)):
    gap = -2 if i%2==0 else 2
    blk=["stained_glass_pane","iron_bars","cobblestone_wall"][i%3]
    for x in range(-2,3):
        if x!=gap: f.append(F([x,65,z],[x,66,z],blk))
course("e05-slalom",f,G(-2,65,22,3,25))
# e06: ladder up a barrier tower to a stair top, then onto the goal
f=[F([-1,65,3],[1,72,4],"barrier"),F([0,65,2],[0,72,2],"ladder[facing=north]"),
   F([-1,73,3],[1,73,3],"stone_brick_stairs[facing=north,half=bottom]"),
   F([-1,64,6],[1,73,9],"stone")]
course("e06-barrier-ladder",f,G(-1,74,7,2,10))
# e07: ladder in a one-wide slot between walls, then fence posts to the goal
f=[F([-2,65,3],[2,71,4],"stone"),F([-1,65,2],[-1,71,2],"stone"),F([1,65,2],[1,71,2],"stone"),
   F([0,65,2],[0,71,2],"ladder[facing=north]")]
for i,x in enumerate([0,1,0]):
    z=7+3*i; f.append(F([x,64,z],[x,70,z],"stone")); f.append(F([x,71,z],[x,71,z],"spruce_fence"))
f.append(F([-1,64,16],[1,72,19],"stone"))
course("e07-slot-ladder-posts",f,G(-1,73,17,2,20))
# e08: a planks block with two anvils on it: the only foothold is the planks' edge beside the anvils
# (an anvil facing south is 0.75 wide in x), then up onto a block
f=[F([0,65,3],[0,65,3],"planks"),F([0,66,3],[0,67,3],"anvil[facing=south]"),
   F([-1,64,6],[1,66,9],"stone")]
course("e08-anvil-edge",f,G(-1,67,7,2,10))
# e09: floating water sources rising 1 up and 2 on in a one-wide lane between walls, a ladder up to
# the first: swum up and out against the wall from one to the next
f=[F([-1,65,1],[-1,72,10],"wool"),F([1,65,1],[1,72,10],"wool"),F([0,65,2],[0,67,2],"planks"),
   F([0,65,1],[0,67,1],"ladder[facing=north]")]
for i in range(4):
    f.append(F([0,68+i,3+2*i],[0,68+i,3+2*i],"water[level=0]"))
f.append(F([-1,64,11],[1,71,14],"stone"))
course("e09-water-steps",f,G(-1,72,12,2,15))
# e10: water at head height one block past the edge, then a step two on and one up, one on diagonally,
# and out onto a floor level with the last water; structure voids keep the sources apart in game
f=[F([-1,65,1],[-1,71,5],"wool"),F([1,65,1],[1,71,4],"wool"),F([2,65,5],[2,71,5],"wool"),
   F([0,67,5],[0,68,5],"structure_void"),
   F([0,66,2],[0,66,2],"water[level=0]"),F([0,66,3],[0,67,3],"structure_void"),
   F([0,67,4],[0,67,4],"water[level=0]"),F([0,66,4],[0,66,4],"structure_void"),
   F([1,68,5],[1,68,5],"water[level=0]"),F([1,67,5],[1,67,5],"structure_void"),
   F([-1,64,6],[2,67,9],"stone")]
course("e10-water-steps-out",f,G(-1,68,7,3,10))
# e11: a one-wide lane of one-block pillars five apart with a ladder on the near face of each, and a
# floating water source at head height between them under a row of fences; hopped pillar to pillar
f=[F([-1,64,1],[-1,68,19],"wool"),F([1,64,1],[1,68,19],"wool"),F([0,69,1],[0,69,19],"fence")]
for z in (5,10,15):
    f+=[F([0,64,z],[0,64,z],"wool"),F([0,64,z-1],[0,64,z-1],"ladder[facing=north]")]
for z in (2,7,12,17):
    f+=[F([0,67,z],[0,67,z],"water[level=0]"),F([0,66,z],[0,66,z],"structure_void"),
        F([0,67,z-1],[0,67,z-1],"structure_void"),F([0,67,z+1],[0,67,z+1],"structure_void")]
f.append(F([-1,64,20],[1,64,23],"stone"))
course("e11-water-hops",f,G(-1,65,21,2,24))
# e12, e13: hard posts rows drawn by devgen (three posts modules each, seeds 512 and 513), not copied
# from any report-only course
sys.path.insert(0,"../../../tools/devgen")
import devgen
devgen.HARD=True
for id,seed in (("e12-posts-gen",512),("e13-posts-gen",513)):
    d,_,_=devgen.generate(seed,40,80,["posts"])
    d={"format":d["format"],"version":d["version"],"id":"elem-dev/"+id,"title":d["title"],"mcVersion":d["mcVersion"],
       "world":d["world"],"spawn":d["spawn"],"start":d["start"],"goal":d["goal"],"limits":d["limits"]}
    open(id+".problem.json","w").write(json.dumps(d,indent=1)+"\n")
