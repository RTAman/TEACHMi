"""Opt-in disposable Linux containers. No cloud deployment, source documents, or real AI."""
from __future__ import annotations
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import uuid
import zipfile

HERE=Path(__file__).resolve().parent
REPO=HERE.parents[1]
BACKEND=REPO/"backend"
DEPLOY=BACKEND/"deploy"
LIMIT=512*1024*1024
ENV={k:v for k,v in os.environ.items() if k in ("PATH","HOME","LANG","LC_ALL","TMPDIR","DOCKER_CONFIG")}
spec=importlib.util.spec_from_file_location("teachmi_ops",DEPLOY/"ops.py")
ops=importlib.util.module_from_spec(spec)
spec.loader.exec_module(ops)
RESULT={"realGeminiCalls":0,"externalDeployment":False,"syntheticFixturesOnly":True,
        "checks":[],"phases":[],"resourceSamples":[],"complete":False,
        "limitations":["No real Gemini generation or grading","No browser HTTPS or remote Supabase validation",
                       "Synthetic files do not cover every PDF or PPTX complexity","No long-duration or many-user load test"]}

def check(name,value):
    RESULT["checks"].append({"check":name,"passed":bool(value)})
    if not value: raise RuntimeError(name)

def capture(args,*,cwd=None,input=None,timeout=120):
    result=subprocess.run(args,cwd=cwd,env=ENV,input=input,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
    if result.returncode:
        raise RuntimeError("Local container/tool operation failed: "+Path(args[0]).name)
    return result.stdout

def docker(args,**kwargs):
    return capture(["docker"]+args,**kwargs)

def validate_resources(host):
    return (host.get("Memory")==LIMIT and host.get("MemorySwap")==LIMIT
            and host.get("NanoCpus")==100_000_000 and not host.get("PortBindings")
            and host.get("RestartPolicy",{}).get("Name")=="no")

def validate_cgroup(sample):
    quota,period=sample["cpuMax"].split()
    return (sample["memoryMax"]==LIMIT and sample["swapMax"]==0
            and quota!="max" and int(quota)*10==int(period))

def validate_ownership(project,label):
    return bool(re.fullmatch(r"teachmi-memory512-[a-f0-9]{16}",project)) and project==label

def health_diagnostics(state):
    health=state.get("Health",{})
    codes=[]
    for entry in health.get("Log",[]):
        output=entry.get("Output","")
        if "curl: (28)" in output: code="HEALTH_TIMEOUT"
        elif "curl: (7)" in output: code="HEALTH_CONNECTION_REFUSED"
        elif "curl: (22)" in output: code="HEALTH_NON_2XX"
        elif entry.get("ExitCode")==0: code="HEALTH_OK"
        else: code="HEALTH_CHECK_FAILED"
        codes.append({"exitCode":entry.get("ExitCode"),"code":code})
    return {"status":health.get("Status"),"failingStreak":health.get("FailingStreak"),"checks":codes}

def fixtures(root):
    jar=BACKEND/"build/libs/teachmi-backend-0.0.1-SNAPSHOT.jar"
    deps=root/"deps";deps.mkdir()
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            if name.startswith("BOOT-INF/lib/") and name.endswith(".jar"):
                (deps/Path(name).name).write_bytes(archive.read(name))
    classes=root/"classes";classes.mkdir()
    capture(["javac","-cp",str(deps/"*"),"-d",str(classes),str(HERE/"Fixtures.java")],timeout=60)
    target=root/"fixtures"
    capture(["java","-Djava.awt.headless=true","-cp",str(classes)+os.pathsep+str(deps/"*"),"Fixtures",str(target)],timeout=120)
    RESULT["fixtures"]=[{"name":x.name,"bytes":x.stat().st_size,"sha256":hashlib.sha256(x.read_bytes()).hexdigest()}
                       for x in sorted(target.iterdir())]
    check("Large fixture remains below 20MiB",target.joinpath("large.pdf").stat().st_size<20*1024*1024)
    return target

def sample_resources(container,phase):
    script="""printf 'memoryMax '; cat /sys/fs/cgroup/memory.max
printf 'memoryCurrent '; cat /sys/fs/cgroup/memory.current
printf 'memoryPeak '; cat /sys/fs/cgroup/memory.peak
printf 'swapMax '; cat /sys/fs/cgroup/memory.swap.max
printf 'cpuMax '; cat /sys/fs/cgroup/cpu.max
cat /sys/fs/cgroup/memory.events
cat /sys/fs/cgroup/cpu.stat
"""
    raw=docker(["exec",container,"sh","-c",script],timeout=20).decode()
    sample={"phase":phase}
    for line in raw.splitlines():
        key,value=line.split(" ",1)
        sample[key]=value if key=="cpuMax" else int(value)
    RESULT["resourceSamples"].append(sample)
    check("cgroup memory, no-swap and CPU limits are enforced",validate_cgroup(sample))
    check("No cgroup OOM or OOM kill",sample.get("oom",0)==0 and sample.get("oom_kill",0)==0)
    return sample

def wait_ready(container,phase):
    deadline=time.monotonic()+300
    while time.monotonic()<deadline:
        state=json.loads(docker(["inspect",container,"--format","{{json .State}}"]))
        if not state["Running"]:
            raise RuntimeError("Application exited during "+phase)
        sample_resources(container,phase)
        if state.get("Health",{}).get("Status")=="healthy":
            RESULT.setdefault("health",{})[phase]=health_diagnostics(state)
            return
        time.sleep(8)
    raise RuntimeError("Application readiness deadline during "+phase)

def snapshot(operation,database):
    result={}
    for table in ("learning_ai_generation","learning_ai_call","assessment_attempt","assessment_problem",
                  "material","learning_session","source_chunk","concept","concept_source","learning_session_request"):
        data=operation.psql("BEGIN READ ONLY; SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text"
                            " FROM teachmi."+table+" t; COMMIT;",database=database)
        result[table]=hashlib.sha256(data).hexdigest()
    data=operation.psql("""BEGIN READ ONLY;
        SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY material_id),'[]'::jsonb)::text FROM
        (SELECT material_id, format, sha256, octet_length(content) bytes, encode(sha256(content),'hex') actual_sha256,
                created_at FROM teachmi.material_file) t; COMMIT;""",database=database)
    result["material_file"]=hashlib.sha256(data).hexdigest()
    return result

def seed_preservation_fixture(operation,database,state):
    # Synthetic receipts only, inserted into this brand-new CI database. Never an existing ledger.
    member=state["memberId"];session=state["session"]["sessionId"]
    variables={"member":member,"session":session,"problem":str(uuid.uuid4()),"attempt":str(uuid.uuid4()),"call":str(uuid.uuid4())}
    script="""
    INSERT INTO teachmi.assessment_problem(id,session_id,member_id,sequence,question,changed_conditions,source_chunk_ids,
        expected_answer,solution,grading_criteria,evidence_version,created_at)
    VALUES (:'problem',:'session',:'member',1,'Synthetic preservation question','Synthetic condition','[]',
        'synthetic answer','synthetic solution','synthetic criterion',0,now());
    INSERT INTO teachmi.assessment_attempt(id,problem_id,session_id,member_id,attempt_no,answer,work,request_key,request_hash,created_at)
    VALUES (:'attempt',:'problem',:'session',:'member',1,'saved synthetic answer','saved synthetic work','pending-fixture','fixture-hash',now());
    INSERT INTO teachmi.learning_ai_generation(generation_key,member_id,session_id,state,attempts,lease_until,retry_after,content_json,last_error_code,updated_at)
    VALUES ('cache-fixture',:'member',:'session','succeeded',1,now(),now(),'{"synthetic":"cached success"}',NULL,now()),
           ('uncertain-fixture',:'member',:'session','uncertain',1,now()-interval '1 minute',now(),NULL,'TRANSPORT_REQUEST_TIMEOUT',now());
    INSERT INTO teachmi.learning_ai_call(id,generation_key,member_id,attempted_at,input_bytes,max_output_tokens)
    VALUES (:'call','uncertain-fixture',:'member',now(),100,2048);
    """
    operation.psql(script,database=database,variables=variables)

def client_phase(operation,project,network,fixture_dir,state_dir,container,phase):
    name=project+"-"+phase
    started=time.monotonic()
    docker(["run","-d","--name",name,"--label","io.teachmi.memory512="+project,"--network",network,
            "--memory","512m","--read-only","--tmpfs","/tmp",
            "-v",str(HERE)+":/test:ro","-v",str(fixture_dir)+":/fixtures:ro","-v",str(state_dir)+":/state:rw",
            "python:3.13-alpine","python","/test/client.py",phase])
    try:
        deadline=time.monotonic()+420
        while time.monotonic()<deadline:
            sample_resources(container,phase)
            status=json.loads(docker(["inspect",name,"--format","{{json .State}}"]))
            if not status["Running"]:
                exit_code=status["ExitCode"]
                break
            time.sleep(8)
        else:
            raise RuntimeError("HTTP driver deadline")
        summary=json.loads(state_dir.joinpath(phase+"-summary.json").read_text())
        RESULT["phases"].append(summary)
        RESULT["checks"].extend(summary["checks"])
        check("HTTP driver exited successfully",exit_code==0)
        check("HTTP phase completed",summary["success"])
    finally:
        check("Only this run's HTTP driver is cleaned",validate_ownership(project,docker(
            ["inspect",name,"--format",'{{index .Config.Labels "io.teachmi.memory512"}}']).decode().strip()))
        docker(["rm","-f",name])
        RESULT.setdefault("phaseSeconds",{})[phase]=round(time.monotonic()-started,2)

def main():
    operation=None
    temporary=None
    project="teachmi-memory512-"+uuid.uuid4().hex[:16]
    container=None
    try:
        check("Explicit disposable GitHub Linux runner",
              os.environ.get("GITHUB_ACTIONS")=="true" and os.environ.get("TEACHMI_MEMORY_TEST")=="true"
              and os.name=="posix")
        revision=os.environ["GITHUB_SHA"]
        check("Full tested revision",bool(re.fullmatch("[0-9a-f]{40}",revision)))
        RESULT["revision"]=revision
        RESULT["baseRevision"]=capture(["git","merge-base","HEAD","origin/main"],cwd=REPO).decode().strip()
        check("Linux Docker engine",docker(["info","--format","{{.OSType}}"]).decode().strip()=="linux")
        image="teachmi-memory512:"+revision
        temporary=tempfile.TemporaryDirectory(prefix=project)
        if temporary:
            root=Path(temporary.name);root.chmod(0o700)
            fixture_dir=fixtures(root)
            state_dir=root/"state";state_dir.mkdir()
            docker(["buildx","build","--load","--build-arg","APP_REVISION="+revision,"-t",image,"-f","Dockerfile","."],
                   cwd=BACKEND,timeout=360)
            docker(["pull","python:3.13-alpine"],timeout=120)
            values={"APP_IMAGE":image,"APP_REVISION":revision,"TEACHMI_DOMAIN":"ci.localhost","ACME_EMAIL":"ci@localhost.test",
                    "DB_NAME":"teachmi_memory_ci","DB_PASSWORD":uuid.uuid4().hex+uuid.uuid4().hex,
                    "DB_ADMIN_PASSWORD":uuid.uuid4().hex+uuid.uuid4().hex,"GEMINI_ENABLED":"false","GEMINI_API_KEY":"",
                    "AUTH_COOKIE_SECURE":"true","AUTH_COOKIE_SAME_SITE":"Lax"}
            env_file=root/"test.env"
            env_file.write_text("".join(k+"="+v+"\n" for k,v in values.items()),encoding="utf-8");env_file.chmod(0o600)
            operation=ops.Operations(env_file,project)
            operation.environment=ENV
            operation.base += ["-f",str(HERE/"limits.yaml")]
            resolved=json.loads(operation.capture(["config","--format","json"]))
            check("Only private backend and DB services",set(resolved["services"])=={"app","db"})
            check("Every application network is internal",all(x.get("internal") for x in resolved["networks"].values()))
            check("Application and DB have no published ports",all(not x.get("ports") for x in resolved["services"].values()))
            check("Resolved AI disabled without a key",resolved["services"]["app"]["environment"]["GEMINI_ENABLED"]=="false"
                  and not resolved["services"]["app"]["environment"]["GEMINI_API_KEY"])
            for resource in ("container","volume","network"):
                args=(["ps","-a","-q"] if resource=="container" else [resource,"ls","-q"])
                check("New project has no existing "+resource+" resources",
                      not docker(args+["--filter","label=com.docker.compose.project="+project]).strip())
            started=time.monotonic()
            operation.capture(["up","-d"])
            container=operation.capture(["ps","-q","app"]).decode().strip()
            host=json.loads(docker(["inspect",container,"--format","{{json .HostConfig}}"]))
            check("Docker enforces 512MiB, no swap, 0.1 CPU and no automatic restart",validate_resources(host))
            RESULT["enforcedLimits"]={k:host.get(k) for k in ("Memory","MemorySwap","NanoCpus")}
            wait_ready(container,"startup_wait")
            RESULT["startupSeconds"]=round(time.monotonic()-started,2)
            sample_resources(container,"startup")
            database=values["DB_NAME"]
            check("Non-superuser application role",operation.psql("SELECT rolsuper FROM pg_roles WHERE rolname='teachmi_app';").strip()==b"f")
            network=resolved["networks"]["edge"]["name"]
            client_phase(operation,project,network,fixture_dir,state_dir,container,"phase1")
            check("AI disabled made zero call reservations",operation.psql("SELECT count(*) FROM teachmi.learning_ai_call;",database=database).strip()==b"0")
            check("Six uploaded originals stored",operation.psql("SELECT count(*) FROM teachmi.material_file;",database=database).strip()==b"6")
            large_hash=next(x["sha256"] for x in RESULT["fixtures"] if x["name"]=="large.pdf")
            count=operation.psql("SELECT count(*) FROM teachmi.material_file WHERE encode(sha256(content),'hex')=:'hash';",
                                 database=database,variables={"hash":large_hash}).strip()
            check("All three large original PDFs match SHA-256",count==b"3")
            state=json.loads(state_dir.joinpath("private-state.json").read_text())
            seed_preservation_fixture(operation,database,state)
            before=snapshot(operation,database)
            started=time.monotonic()
            operation.capture(["restart","--no-deps","app"])
            wait_ready(container,"restart_wait")
            RESULT["restartSeconds"]=round(time.monotonic()-started,2)
            sample_resources(container,"restart")
            client_phase(operation,project,network,fixture_dir,state_dir,container,"phase2")
            check("Originals, sources, session, synthetic cache, uncertain and pending answer unchanged",snapshot(operation,database)==before)
            RESULT["preservationFixtures"]={"syntheticUncertain":1,"syntheticSuccessCache":1,"syntheticPendingAnswer":1}
            check("Flyway V1-V12 applied",operation.psql("SELECT count(*) FROM public.flyway_schema_history WHERE success AND version IS NOT NULL;",database=database).strip()==b"12")
            RESULT["databaseSizeBytes"]=int(operation.psql("SELECT pg_database_size(current_database());",database=database).strip())
            final=json.loads(docker(["inspect",container,"--format","{{json .State}}"]))
            check("Application still healthy with no OOM kill",final["Running"] and not final["OOMKilled"] and final["Health"]["Status"]=="healthy")
            check("No automatic restart masked failure",docker(["inspect",container,"--format","{{.RestartCount}}"]).strip()==b"0")
            RESULT["complete"]=True
    except Exception as error:
        RESULT["failureType"]=type(error).__name__
        if isinstance(error,RuntimeError): RESULT["failure"]=str(error)
        if not container and operation:
            try: container=operation.capture(["ps","-aq","app"]).decode().strip()
            except Exception: pass
        if container:
            try:
                logs=docker(["logs",container]).decode(errors="replace")
                RESULT["safeDiagnostics"]=[code for code in ("OutOfMemoryError","Java heap space","Metaspace",
                    "Native memory allocation","insufficient memory") if code in logs]
                state=json.loads(docker(["inspect",container,"--format","{{json .State}}"]))
                RESULT["failedContainerState"]={k:state.get(k) for k in ("Running","OOMKilled","ExitCode")}
                RESULT["failedHealth"]=health_diagnostics(state)
                starts=re.findall(r"Started TeachmiApplication in ([0-9.]+) seconds",logs)
                RESULT["applicationStartupLogSeconds"]=[float(x) for x in starts]
                RESULT["startupMarkers"]={label:label in logs for label in (
                    "Starting TeachmiApplication","Tomcat started on port","HikariPool-1 - Start completed",
                    "Initialized JPA EntityManagerFactory")}
            except Exception: pass
    finally:
        if operation:
            try:
                ids=operation.capture(["ps","-aq"]).decode().split()
                for cid in ids:
                    label=docker(["inspect",cid,"--format",'{{index .Config.Labels "com.docker.compose.project"}}']).decode().strip()
                    check("Cleanup restricted to this run's Compose project",validate_ownership(project,label))
                volumes=docker(["volume","ls","-q","--filter","label=com.docker.compose.project="+project]).decode().split()
                for volume in volumes:
                    label=docker(["volume","inspect",volume,"--format",'{{index .Labels "com.docker.compose.project"}}']).decode().strip()
                    check("Only this run's volumes are cleaned",validate_ownership(project,label) and volume.startswith(project+"_"))
                operation.capture(["down","--volumes","--remove-orphans"])
            except Exception:
                RESULT["cleanupFailed"]=True
                RESULT["complete"]=False
        if temporary:
            temporary.cleanup()
        samples=RESULT["resourceSamples"]
        RESULT["peakCgroupMemoryBytes"]=max((x["memoryPeak"] for x in samples),default=0)
        RESULT["cpuWasThrottled"]=any(x.get("nr_throttled",0)>0 for x in samples)
        HERE.joinpath("result.json").write_text(json.dumps(RESULT,indent=2),encoding="utf-8")
        print(json.dumps({k:RESULT.get(k) for k in ("complete","revision","startupSeconds","restartSeconds",
            "peakCgroupMemoryBytes","cpuWasThrottled","realGeminiCalls","externalDeployment","failure","safeDiagnostics")}))
        print(json.dumps({"checks":len(RESULT["checks"]),"failedChecks":sum(not x["passed"] for x in RESULT["checks"])}))
    return 0 if RESULT["complete"] else 1

if __name__=="__main__":
    raise SystemExit(main())
