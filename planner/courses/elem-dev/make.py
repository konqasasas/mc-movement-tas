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
