#!/usr/bin/env python3
"""Paired queue-off/on validation. Dry-run by default; --execute requires frozen source."""
from __future__ import annotations
import argparse, hashlib, json, math, os, platform, re, secrets, socket, subprocess, sys, threading, time, urllib.request
from pathlib import Path
from datetime import datetime, timezone

HERE=Path(__file__).resolve().parent
REPO=HERE.parent/"ai-code-helper"
DOCKER="/Applications/Docker.app/Contents/Resources/bin/docker"
K6=str(HERE.parent/"load-test-tools/k6-v2.2.0-macos-arm64/k6")
BASE="http://127.0.0.1:28082"
PORTS=dict(BACKEND_PORT="28082",FRONTEND_PORT="25174",POSTGRES_PORT="5434",PROMETHEUS_PORT="9091",GRAFANA_PORT="3001",TEMPO_PORT="3201")
SCENARIOS=("chat10","chat50","chat100","rag","sse10","sse50","sse100")
GAUGES=("ai_provider_in_flight","ai_provider_queued","active_sse_streams")
METRICS=GAUGES+("process_cpu_usage","process_cpu_time_ns_total","process_resident_memory_bytes","jvm_memory_used_bytes","jvm_gc_pause_seconds_count","jvm_gc_pause_seconds_sum","jvm_threads_live_threads","ai_provider_admission_rejected_total","ai_provider_queue_wait_seconds_count","ai_provider_queue_wait_seconds_sum","ai_provider_queue_wait_seconds_max")
CONTROLS=dict(AI_PROVIDER_MAX_IN_FLIGHT="16",AI_PROVIDER_QUEUE_TIMEOUT="2s",AI_PROVIDER_CHAT_TIMEOUT="45s",AI_PROVIDER_EMBEDDING_TIMEOUT="20s",AI_PROVIDER_FIRST_TOKEN_TIMEOUT="15s",AI_PROVIDER_STREAM_TIMEOUT="90s",AI_MAX_CONCURRENT_REQUESTS="64",AI_MAX_CONCURRENT_REQUESTS_PER_OWNER="2",AI_MAX_STARTS_PER_MINUTE="300",AI_MAX_STARTS_PER_MINUTE_PER_OWNER="20",AI_MAX_CONVERSATIONS="2000",APP_STORAGE_ENABLED="true",APP_TRACING_ENABLED="true",MANAGEMENT_TRACING_SAMPLING_PROBABILITY="1.0",APP_EVALUATION_ENABLED="true",RETRIEVAL_MODE="lexical")
ENV_ALLOWED=set(CONTROLS)|{"AI_PROVIDER_MAX_QUEUED","SPRING_PROFILES_ACTIVE","APP_DATA_DIR","APP_RETRIEVAL_BACKEND","MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE","MANAGEMENT_OTLP_TRACING_ENDPOINT"}
INTERVAL=.1

def now(): return datetime.now(timezone.utc).isoformat()
def save(path,data): Path(path).write_text(json.dumps(data,indent=2,ensure_ascii=False,allow_nan=False)+"\n")
def cmd(args,env=None,check=True,timeout=600):
    r=subprocess.run([str(x) for x in args],cwd=REPO,env=env,text=True,capture_output=True,timeout=timeout)
    if check and r.returncode: raise RuntimeError(f"Command failed ({r.returncode}): {args[:5]}; {r.stderr[-2000:]}")
    return r
def get(path,key=None):
    headers={"X-Evaluation-Key":key} if key else {}
    with urllib.request.urlopen(urllib.request.Request(BASE+path,headers=headers),timeout=3) as r: return r.read().decode()
def prom(body):
    result={}
    for line in body.splitlines():
        if line and not line.startswith("#") and re.split(r"[ {]",line,1)[0] in METRICS:
            key,val=line.rsplit(" ",1)
            parsed=float(val); result[key]=parsed if math.isfinite(parsed) else None
    return result
def gauges(metrics): return {name:metrics.get(name) for name in GAUGES}
def healthy():
    deadline=time.monotonic()+180
    while time.monotonic()<deadline:
        try:
            h=json.loads(get("/api/health"))
            if json.loads(get("/actuator/health")).get("status")=="UP" and h.get("status")=="UP": return h
        except Exception: pass
        time.sleep(.25)
    raise RuntimeError("Backend health timeout")
def verify_runtime(runtime,queued):
    expected={
        "providerConfig":dict(maxInFlight=16,maxQueued=queued,queueTimeoutMs=2000,chatTimeoutMs=45000,embeddingTimeoutMs=20000,firstTokenTimeoutMs=15000,streamTimeoutMs=90000),
        "admissionConfig":dict(maxConcurrentRequests=64,maxConcurrentRequestsPerOwner=2,maxStartsPerMinute=300,maxStartsPerMinutePerOwner=20,maxConversations=2000,persistenceEnabled=True)}
    for key,value in expected.items():
        if runtime.get(key)!=value: raise RuntimeError(f"Bound {key} differs: {runtime.get(key)!r}")
    if runtime["retrievalConfig"]["mode"]!="lexical" or runtime["retrievalConfig"]["backend"]!="pgvector": raise RuntimeError("Unexpected runtime retrieval configuration")
def source_frozen(revision):
    if cmd(["git","rev-parse","HEAD"]).stdout.strip()!=revision or cmd(["git","status","--porcelain","--untracked-files=no"]).stdout.strip():
        raise RuntimeError("Source is not the requested clean frozen revision")
def cpu_seconds(text):
    first,separator,rest=text.partition("-")
    days,value=(int(first),rest) if separator else (0,first)
    seconds=0.
    for part in value.split(":"): seconds=seconds*60+float(part)
    return days*86400+seconds

class Sampler:
    def __init__(self,path,scenario,container):
        self.path,self.scenario,self.container=path,scenario,container
        self.stop=threading.Event(); self.pid=None; self.threads=[]; self.proc=None
        self.rows={k:[] for k in ("metrics","container","client")}; self.errors=[]
    def append(self,kind,row,file):
        self.rows[kind].append(row); file.write(json.dumps(row,allow_nan=False)+"\n"); file.flush()
    def loop(self,kind,read):
        with (self.path/f"{self.scenario}-{kind}.jsonl").open("w") as file:
            deadline=time.monotonic()
            while not self.stop.is_set():
                row=dict(at=now(),monotonic_seconds=time.monotonic())
                try: row.update(read())
                except Exception as error:
                    row["sample_error"]=type(error).__name__; self.errors.append(dict(kind=kind,at=row["at"],error=type(error).__name__))
                self.append(kind,row,file)
                deadline+=INTERVAL
                if deadline<time.monotonic(): deadline=time.monotonic()+INTERVAL
                self.stop.wait(max(0,deadline-time.monotonic()))
    def read_client(self):
        if self.pid is None: return dict(state="not_started")
        fields=cmd(["ps","-o","time=,rss=,pcpu=","-p",self.pid],check=False,timeout=3).stdout.split()
        if len(fields)!=3: return dict(state="exited",pid=self.pid)
        return dict(state="running",pid=self.pid,cpu_seconds=cpu_seconds(fields[0]),rss_bytes=int(fields[1])*1024,lifetime_cpu_percent=float(fields[2]))
    def container_loop(self):
        script=r'''while read sample; do
awk 'FILENAME=="/proc/uptime" { t=$1 }
FILENAME=="/sys/fs/cgroup/cpu.stat" && $1=="usage_usec" { cpu=$2 }
FILENAME=="/sys/fs/cgroup/memory.current" { mem=$1 }
FILENAME=="/sys/fs/cgroup/memory.stat" && $1=="anon" { anon=$2 }
FILENAME=="/sys/fs/cgroup/memory.stat" && $1=="inactive_file" { cache=$2 }
FILENAME=="/proc/1/status" && $1=="VmRSS:" { rss=$2*1024 }
END { printf "%.2f %.0f %.0f %.0f %.0f %.0f\n",t,cpu,mem,anon,cache,rss }' \
/proc/uptime /sys/fs/cgroup/cpu.stat /sys/fs/cgroup/memory.current /sys/fs/cgroup/memory.stat /proc/1/status
done'''
        self.proc=subprocess.Popen([DOCKER,"exec","-i",self.container,"sh","-c",script],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,bufsize=1)
        previous=None
        try:
            with (self.path/f"{self.scenario}-container.jsonl").open("w") as file:
                while not self.stop.is_set():
                    started=time.monotonic()
                    self.proc.stdin.write("sample\n"); self.proc.stdin.flush()
                    line=self.proc.stdout.readline()
                    if not line: break
                    try:
                        uptime,cpu,mem,anon,cache,rss=map(float,line.split())
                        row=dict(at=now(),monotonic_seconds=time.monotonic(),vm_uptime_seconds=uptime,cgroup_cpu_usage_usec=int(cpu),cgroup_memory_current_bytes=int(mem),cgroup_memory_anon_bytes=int(anon),cgroup_inactive_file_bytes=int(cache),java_rss_bytes=int(rss))
                        if previous and uptime>previous[0]: row["cpu_percent"]=100*(cpu-previous[1])/((uptime-previous[0])*1e6)
                        previous=uptime,cpu
                        self.append("container",row,file)
                    except Exception as error: self.errors.append(dict(kind="container",at=now(),error=type(error).__name__))
                    self.stop.wait(max(0,INTERVAL-(time.monotonic()-started)))
        finally:
            self.proc.stdin.close()
            if self.proc.poll() is None:
                try: self.proc.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    self.proc.terminate()
                    self.proc.wait(timeout=3)
    def start(self):
        targets=[(self.loop,("metrics",lambda:dict(values=prom(get("/actuator/prometheus"))))),(self.loop,("client",self.read_client)),(self.container_loop,())]
        self.threads=[threading.Thread(target=target,args=args,daemon=True) for target,args in targets]
        for thread in self.threads: thread.start()
    def finish(self):
        self.stop.set()
        for thread in self.threads: thread.join(timeout=5)
        if any(t.is_alive() for t in self.threads): self.errors.append(dict(kind="sampler",error="thread_failed_to_stop"))
        result=dict(target_interval_seconds=INTERVAL,errors=self.errors,observed_peak_gauges={},sampling={})
        for gauge in GAUGES:
            values=[r["values"][gauge] for r in self.rows["metrics"] if gauge in r.get("values",{})]
            result["observed_peak_gauges"][gauge]=max(values) if values else None
        for kind,rows in self.rows.items():
            times=[r["monotonic_seconds"] for r in rows]; intervals=[b-a for a,b in zip(times,times[1:])]
            result["sampling"][kind]=dict(count=len(rows),mean_interval_seconds=sum(intervals)/len(intervals) if intervals else None,max_interval_seconds=max(intervals) if intervals else None)
        for kind,name in (("container","cpu_percent"),("container","java_rss_bytes"),("container","cgroup_memory_current_bytes"),("client","rss_bytes")):
            vals=[r[name] for r in self.rows[kind] if name in r]
            result[f"peak_{kind}_{name}"]=max(vals) if vals else None
        rows=[r for r in self.rows["client"] if "cpu_seconds" in r]
        vals=[100*(b["cpu_seconds"]-a["cpu_seconds"])/(b["monotonic_seconds"]-a["monotonic_seconds"]) for a,b in zip(rows,rows[1:])]
        result["peak_client_interval_cpu_percent"]=max(vals) if vals else None
        return result

def safe_container(container):
    raw=json.loads(cmd([DOCKER,"inspect",container]).stdout)[0]
    env=dict(e.split("=",1) for e in raw["Config"].get("Env",[]) if "=" in e)
    return dict(container_id=raw["Id"],image_id=raw["Image"],image_revision=raw["Config"].get("Labels",{}).get("org.opencontainers.image.revision"),compose_project=raw["Config"].get("Labels",{}).get("com.docker.compose.project"),allowlisted_environment={k:env[k] for k in sorted(ENV_ALLOWED) if k in env},mounts=[dict(type=m["Type"],name=m.get("Name"),destination=m["Destination"]) for m in raw["Mounts"]])
def make_compose(project,override,env):
    def compose(*args,check=True,timeout=600):
        return cmd([DOCKER,"compose","--project-name",project,"-f",REPO/"docker-compose.yml","-f",override,*args],env=env,check=check,timeout=timeout)
    return compose

def sample(path,scenario,compose,env,container,key,revision,queued):
    source_frozen(revision); restart=compose("restart","backend"); health=healthy()
    if health["chatModel"]!="local-mock" or health["embeddingModel"]!="local-hash-embedding" or health["mcpConfigured"]: raise RuntimeError("Refusing nonlocal model/MCP load")
    runtime=json.loads(get("/api/evaluation/status",key)); verify_runtime(runtime,queued)
    health=json.loads(get("/api/health"))
    save(path/f"{scenario}-health-before.json",health); save(path/f"{scenario}-runtime-before.json",runtime)
    before=get("/actuator/prometheus"); (path/f"{scenario}-metrics-before.prom").write_text(before)
    if gauges(prom(before))!=dict.fromkeys(GAUGES,0): raise RuntimeError("Missing or nonzero pre-sample gauges")
    output=path/f"{scenario}.json"
    if scenario.startswith("sse"):
        overrides={}
        args=[sys.executable,str(REPO/"load-tests/sse_load.py"),"--base-url",BASE,"--concurrency",scenario[3:],"--duration","10","--timeout","30","--think-seconds","3.1","--output",str(output)]
    else:
        overrides=dict(BASE_URL=BASE,SCENARIO=scenario,DURATION="10s",THINK_SECONDS="3.1",REQUEST_TIMEOUT="120s",GRACEFUL_STOP="130s",MAX_SATURATION_RATE="1",SUMMARY_PATH=str(output))
        args=[K6,"run","--quiet",str(REPO/"load-tests/http.js")]
    started,clock=now(),time.monotonic(); observer=Sampler(path,scenario,container); observer.start()
    process=None; zeros=0; end={}
    try:
        with (path/f"{scenario}-client.log").open("w") as log:
            process=subprocess.Popen(args,cwd=REPO,env={**env,**overrides},stdout=log,stderr=subprocess.STDOUT,text=True)
            observer.pid=process.pid; exit_code=process.wait(timeout=170)
        elapsed=time.monotonic()-clock; deadline=time.monotonic()+10
        while time.monotonic()<deadline and zeros<3:
            end=gauges(prom(get("/actuator/prometheus"))); zeros=zeros+1 if end==dict.fromkeys(GAUGES,0) else 0; time.sleep(INTERVAL)
        (path/f"{scenario}-metrics-after.prom").write_text(get("/actuator/prometheus"))
    finally:
        if process and process.poll() is None:
            process.terminate()
            try: process.wait(timeout=3)
            except subprocess.TimeoutExpired: process.kill()
        resources=observer.finish()
        resources.update(end_gauges=end,drained_to_zero=zeros>=3,peak_is_sampled_lower_bound=True)
        save(path/f"{scenario}-resources-summary.json",resources)
    result=dict(scenario=scenario,started_at=started,completed_at=now(),elapsed_seconds=elapsed,exit_code=exit_code,command=args,environment_overrides=overrides,backend_restart_exit_code=restart.returncode,raw_result=output.name,resources_summary=f"{scenario}-resources-summary.json",drained_to_zero=zeros>=3)
    if not output.exists(): raise RuntimeError(f"{scenario} produced no raw report")
    if zeros<3: raise RuntimeError(f"{scenario} did not drain")
    if any(resources["sampling"][kind]["count"]<5 for kind in ("metrics","container","client")): raise RuntimeError("Resource sampler produced insufficient evidence")
    if resources["observed_peak_gauges"]["ai_provider_in_flight"] is None: raise RuntimeError("No observed provider metrics")
    if resources["observed_peak_gauges"]["ai_provider_in_flight"]>16 or resources["observed_peak_gauges"]["ai_provider_queued"]>queued: raise RuntimeError("Observed provider bound exceeded")
    print(json.dumps(dict(event="sample_complete",profile=path.name,scenario=scenario,exit_code=exit_code,peaks=resources["observed_peak_gauges"],end_gauges=end)),flush=True)
    return result

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--execute",action="store_true"); p.add_argument("--revision")
    p.add_argument("--backend-image",default="ai-code-helper-capacity-backend"); p.add_argument("--frontend-image",default="ai-code-helper-capacity-frontend")
    p.add_argument("--output",type=Path,default=HERE/"paired-results")
    a=p.parse_args(); a.output=a.output.resolve()
    if not a.execute:
        print(json.dumps(dict(prepared_only=True,repo=str(REPO),output=str(a.output),ports=PORTS,scenarios=SCENARIOS,profiles=dict(off=0,on=48),required="Frozen source, built images, explicit GO then --execute --revision SHA"),indent=2)); return 0
    if not a.revision: p.error("--revision required with --execute")
    source_frozen(a.revision)
    if a.output.exists(): raise RuntimeError("Output exists; refusing evidence overwrite")
    images={}
    for service,name in (("backend",a.backend_image),("frontend",a.frontend_image)):
        image=json.loads(cmd([DOCKER,"image","inspect",name]).stdout)[0]
        revision=image["Config"].get("Labels",{}).get("org.opencontainers.image.revision")
        if revision!=a.revision: raise RuntimeError(f"{service} image revision mismatch")
        images[service]=dict(id=image["Id"],revision=revision,reference=name,created=image["Created"])
    for port in PORTS.values():
        with socket.socket() as s:
            if s.connect_ex(("127.0.0.1",int(port)))==0: raise RuntimeError(f"Port {port} occupied; refusing interference")
    a.output.mkdir(parents=True); tag=datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S"); key=secrets.token_hex(32)
    env={**os.environ,**PORTS,"SOURCE_REVISION":a.revision,"CAPACITY_EVALUATION_KEY":key}
    info=json.loads(cmd([DOCKER,"info","--format","{{json .}}"]).stdout)
    manifest=dict(schema_version=1,source_revision=a.revision,git_clean_before=True,started_at=now(),base_url=BASE,images=images,
        host=dict(platform=platform.platform(),machine=platform.machine(),logical_cpus=os.cpu_count(),memory_bytes=int(cmd(["sysctl","-n","hw.memsize"]).stdout)),
        python_version=platform.python_version(),k6_version=cmd([K6,"version"]).stdout.strip(),docker_version=cmd([DOCKER,"version","--format","{{.Server.Version}}"]).stdout.strip(),
        docker_info={k:info.get(k) for k in ("OSType","Architecture","NCPU","MemTotal","CgroupVersion","CgroupDriver","KernelVersion")},
        workload=dict(scenarios=SCENARIOS,launch_duration_seconds=10,think_seconds=3.1,http_timeout_seconds=120,sse_timeout_seconds=30,sampling_target_seconds=INTERVAL),
        harness_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        input_hashes={str(path.relative_to(REPO)):hashlib.sha256(path.read_bytes()).hexdigest() for path in (REPO/"load-tests/http.js",REPO/"load-tests/sse_load.py",REPO/"docker-compose.yml",REPO/"backend/src/main/resources/application.yml")},
        profiles={},notes=["Same source/image IDs; only provider queue size differs.","Fresh owned persistent/observability volumes per profile; same scenario order and backend restart before every sample.","No chat/SSE warmup; protected status preflight initializes retrieval index before each sample; ten-second runs retain request-path JIT effects and are not production capacity claims.","Local-mock and local-hash-embedding only; no external model or MCP calls.","HTTP 429 separately reported; HTTP 503 and SSE error events remain unexpected failures.","Resource and metric samples target 100ms; actual timestamps/cadence retained. Cgroup CPU includes lightweight in-container sampler and both profiles use identical observer work.","CPU percent is cgroup usage delta; >100% represents multiple cores. Java RSS is approximate PID1 VmRSS, distinct from cgroup charged/anonymous memory.","Client CPU percent is cumulative process CPU delta via ps; client RSS is current ps RSS. Host sampler overhead is separate.","Gauge peaks are sampled lower bounds; repeated final zero observations establish drain.","Only allowlisted environment and protected runtime status are exported; no keys, cookies or configprops."])
    save(a.output/"manifest.json",manifest); corpus=None
    try:
        for profile,queued in (("off",0),("on",48)):
            source_frozen(a.revision); project=f"ai-code-helper-capacity-{profile}-{tag}"
            for args in (["ps","-aq"],["volume","ls","-q"]):
                if cmd([DOCKER,*args,"--filter",f"label=com.docker.compose.project={project}"]).stdout.strip(): raise RuntimeError("Project or volumes already exist")
            path=a.output/profile; path.mkdir(); override=path/"compose.override.json"
            save(override,dict(services=dict(backend=dict(image=images["backend"]["id"],environment={**CONTROLS,"AI_PROVIDER_MAX_QUEUED":str(queued),"APP_EVALUATION_KEY":"$"+"{CAPACITY_EVALUATION_KEY}"}),frontend=dict(image=images["frontend"]["id"]))))
            compose=make_compose(project,override,env); record=dict(project=project,max_queued=queued,started_at=now(),runs=[])
            manifest["profiles"][profile]=record; save(a.output/"manifest.json",manifest)
            try:
                compose("up","-d","--no-build",timeout=300); health=healthy(); container=compose("ps","-q","backend").stdout.strip()
                record["backend"]=safe_container(container)
                if record["backend"]["image_id"]!=images["backend"]["id"]: raise RuntimeError("Runtime image mismatch")
                record["java_version"]=cmd([DOCKER,"exec",container,"java","-version"]).stderr.strip()
                record["jar_sha256"]=cmd([DOCKER,"exec",container,"sha256sum","/app/app.jar"]).stdout.split()[0]
                runtime=json.loads(get("/api/evaluation/status",key)); verify_runtime(runtime,queued); record["runtime_configuration"]=runtime
                # Status initializes the lazy corpus. Compare health after that same initialization in both profiles.
                health=json.loads(get("/api/health")); record["health_after_runtime_initialization"]=health
                if corpus is None: corpus=runtime["ingestion"]["corpusHash"]
                elif runtime["ingestion"]["corpusHash"]!=corpus: raise RuntimeError("Paired corpus hashes differ")
                if health["knowledgeSegments"]!=runtime["ingestion"]["chunkCount"]: raise RuntimeError("Health/corpus count mismatch")
                record["fresh_owned_volumes"]=cmd([DOCKER,"volume","ls","-q","--filter",f"label=com.docker.compose.project={project}"]).stdout.splitlines()
                for scenario in SCENARIOS:
                    record["runs"].append(sample(path,scenario,compose,env,container,key,a.revision,queued))
                    save(path/"runs.json",record["runs"]); save(a.output/"manifest.json",manifest)
                final_runtime=json.loads(get("/api/evaluation/status",key)); verify_runtime(final_runtime,queued)
                record["completed_at"]=now(); save(path/"runtime-after.json",final_runtime)
                record["final_ingestion_identity"]={k:final_runtime["ingestion"].get(k) for k in ("corpusHash","sourceCount","chunkCount","embeddingModel","embeddingVersion")}
                if profile=="on":
                    # Post-benchmark smoke is kept outside every workload/resource window.
                    compose("restart","backend"); healthy()
                    smoke_args=[sys.executable,str(REPO/"deployment/smoke.py"),"--output",str(path/"compose-smoke.json")]
                    smoke=cmd(smoke_args,env={**env,"APP_EVALUATION_KEY":key},check=False,timeout=240)
                    (path/"compose-smoke.log").write_text(smoke.stdout+smoke.stderr)
                    record["post_benchmark_smoke"]=dict(exit_code=smoke.returncode,command=smoke_args,result="compose-smoke.json")
                    if smoke.returncode: raise RuntimeError("Post-benchmark Compose smoke failed")
            finally:
                cleanup=compose("down","--timeout","25",check=False,timeout=120); record["compose_down_exit_code"]=cleanup.returncode
                save(a.output/"manifest.json",manifest)
                if cleanup.returncode: raise RuntimeError("Owned stack failed to stop; no next profile")
        if manifest["profiles"]["off"]["final_ingestion_identity"]!=manifest["profiles"]["on"]["final_ingestion_identity"]: raise RuntimeError("Final paired corpus identities differ")
        source_frozen(a.revision); manifest.update(git_clean_after=True,completed_at=now(),complete=True)
    except Exception as error:
        manifest.update(complete=False,failure=dict(type=type(error).__name__,message=str(error))); raise
    finally: save(a.output/"manifest.json",manifest)
    return 0

if __name__=="__main__": raise SystemExit(main())
