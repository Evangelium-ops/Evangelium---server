"""Scheduled-tick correctness load for the packed ticks verify mode (CT190 /srv/perf/ticks, untimed: nice 19, cores 24-31, port 25653).
usage: python3 ticktest.py <jar> <seconds> <world-dir-name> [jvm args...]
Builds (on a flat world, spread over 4 regions around the region corner 0,0 and a far site) observer clocks, repeater/comparator lines
driven by them, water + lava sources that are placed and removed again and again (flow, retract, cobble/obsidian), sand + concrete powder
rain into water, and pistons pushing blocks; every 30 s some forceloaded chunks are dropped and re-added (save + reload of ticks while
they are pending). Afterwards prints the [packedTicksVerify] lines, error counts, and stops cleanly (save-all)."""
import os, random, re, subprocess, sys, threading, time
jar, secs, wname = sys.argv[1], int(sys.argv[2]), sys.argv[3]
extra = sys.argv[4:]
ROOT = "/srv/perf/ticks"; d = f"{ROOT}/tt"; os.makedirs(d, exist_ok=True)
open(f"{d}/eula.txt", "w").write("eula=true\n")
open(f"{d}/server.properties", "w").write(f"server-port=25653\nonline-mode=false\nlevel-name={wname}\nlevel-type=minecraft\\:flat\n"
    'generator-settings={"layers"\\:[{"block"\\:"minecraft\\:bedrock","height"\\:1},{"block"\\:"minecraft\\:stone","height"\\:2},{"block"\\:"minecraft\\:grass_block","height"\\:1}],"biome"\\:"minecraft\\:plains"}\n'
    "spawn-protection=0\nview-distance=4\nsimulation-distance=4\ngenerate-structures=false\nspawn-monsters=false\nspawn-animals=false\n"
    "max-chained-neighbor-updates=1000000\n")
log = []
p = subprocess.Popen(["nice", "-n", "19", "taskset", "-c", "24-31", "/opt/jdk25/bin/java", "-Xms1G", "-Xmx3G", "-XX:ActiveProcessorCount=8",
                      f"-DbundlerRepoDir={ROOT}/cache-tt", "-Dshreddedpaper.timings=0"] + extra + ["-jar", jar, "nogui"],
                     cwd=d, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1, errors="replace")
threading.Thread(target=lambda: [log.append(l.rstrip()) for l in p.stdout], daemon=True).start()
def cmd(c):
    p.stdin.write(c + "\n"); p.stdin.flush()
def wait(marker, t=900):
    end = time.time() + t
    while time.time() < end:
        if any(marker in l for l in log): return
        if p.poll() is not None: raise SystemExit("server died: " + "\n".join(log[-20:]))
        time.sleep(0.5)
    raise SystemExit("timeout " + marker)
wait("Done (")
for g in ["advance_time false", "advance_weather false", "spawn_mobs false", "random_tick_speed 3"]: cmd("gamerule " + g)
# sites: 4 around the region corner (regions are 8x8 chunks = 128 blocks in this fork? any corner of 0 is a border) + one far away
SITES = [(-48, -48), (16, -48), (-48, 16), (16, 16), (2000, 2000)]
for (x, z) in SITES:
    cmd(f"forceload add {x} {z} {x + 31} {z + 31}")
time.sleep(10)
Y = -60  # flat world surface is -61
for (x, z) in SITES:
    cmd(f"fill {x} {Y} {z} {x + 31} {Y + 12} {z + 31} air")
    cmd(f"fill {x} {Y - 1} {z} {x + 31} {Y - 1} {z + 31} stone")
    # observer clocks: pairs facing each other, every 4 blocks on the west half, each drives a repeater + comparator line
    for i in range(0, 16, 4):
        for j in range(0, 32, 4):
            bx, bz = x + i, z + j
            cmd(f"setblock {bx} {Y} {bz} observer[facing=east]")
            cmd(f"setblock {bx + 1} {Y} {bz} observer[facing=west]")
            cmd(f"setblock {bx + 2} {Y} {bz} repeater[facing=west,delay={1 + (j // 4) % 4}]")
            cmd(f"setblock {bx + 3} {Y} {bz} comparator[facing=west]")
            cmd(f"setblock {bx + 3} {Y} {bz + 1} redstone_wire")
            cmd(f"setblock {bx + 3} {Y} {bz + 2} piston[facing=up]")
            cmd(f"setblock {bx + 3} {Y + 1} {bz + 2} sand")
    # east half: water basin for falling blocks
    cmd(f"fill {x + 18} {Y} {z} {x + 31} {Y + 3} {z + 31} stone hollow")
    cmd(f"fill {x + 19} {Y} {z + 1} {x + 30} {Y + 2} {z + 30} water")
time.sleep(5)
cmd("say ticktest built")
t0 = time.time(); k = 0
rnd = random.Random(7)
while time.time() - t0 < secs:
    k += 1
    for (x, z) in SITES:
        # fluids on the clock half's roof: place sources, later remove them (flow + retract, lava meets water)
        for _ in range(6):
            fx, fz = x + rnd.randrange(0, 16), z + rnd.randrange(0, 32)
            cmd(f"setblock {fx} {Y + 6} {fz} {rnd.choice(['water', 'lava'])}")
        if k % 3 == 0:
            cmd(f"fill {x} {Y + 4} {z} {x + 16} {Y + 9} {z + 31} air replace water")
            cmd(f"fill {x} {Y + 4} {z} {x + 16} {Y + 9} {z + 31} air replace lava")
            cmd(f"fill {x} {Y + 4} {z} {x + 16} {Y + 9} {z + 31} air replace cobblestone")
            cmd(f"fill {x} {Y + 4} {z} {x + 16} {Y + 9} {z + 31} air replace obsidian")
            cmd(f"fill {x} {Y + 4} {z} {x + 16} {Y + 9} {z + 31} air replace stone")
        # sand / concrete powder rain into the basin
        cmd(f"fill {x + 19} {Y + 10} {z + 1} {x + 30} {Y + 10} {z + 30} {rnd.choice(['sand', 'red_concrete_powder', 'gravel'])} replace air")
        if k % 4 == 0:
            cmd(f"fill {x + 19} {Y} {z + 1} {x + 30} {Y + 9} {z + 30} water replace")
    if k % 6 == 0:
        # drop and re-add a forceloaded site: the chunks unload with pending ticks (save) and load again
        x, z = SITES[(k // 6) % len(SITES)]
        cmd(f"forceload remove {x} {z} {x + 31} {z + 31}")
        time.sleep(4)
        cmd(f"forceload add {x} {z} {x + 31} {z + 31}")
    time.sleep(5)
cmd("save-all flush"); time.sleep(10)
cmd("stop")
try: p.wait(180)
except Exception: p.kill()
v = [l for l in log if "packedTicksVerify" in l]
print("\n".join(v[-5:]))
errs = [l for l in log if re.search(r"ERROR|Exception", l)]
print("error lines:", len(errs))
for l in errs[:15]: print("  ", l[:300])
print("stopped cleanly:", any("Saving worlds" in l or "Closing Server" in l for l in log[-80:]), "exit", p.returncode)
