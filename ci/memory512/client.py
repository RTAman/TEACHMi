"""Synthetic HTTP driver; no provider adapter, external network, or response-body logging."""
import concurrent.futures
import http.cookies
import json
import re
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

STATE=Path("/state")
FIXTURES=Path("/fixtures")
BASE="http://app:8080"
CHECKS=[]
REQUESTS=[]
OBSERVATIONS=[]

def check(name,ok):
    CHECKS.append({"check":name,"passed":bool(ok)})
    if not ok: raise RuntimeError(name)

class Client:
    def __init__(self,state=None):
        self.data=state or {"email":uuid.uuid4().hex+"@example.test","password":"Test-"+uuid.uuid4().hex}
        self.http=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    def request(self,method,path,body=None,key=None,csrf=True,content_type="application/json"):
        headers={"Accept":"application/json"}
        if self.data.get("cookie"): headers["Cookie"]=self.data["cookie"]
        if csrf and self.data.get("csrf"): headers["X-CSRF-Token"]=self.data["csrf"]
        if key: headers["Idempotency-Key"]=key
        if body is not None:
            body=body if isinstance(body,bytes) else json.dumps(body).encode()
            headers["Content-Type"]=content_type
        start=time.monotonic()
        try: reply=self.http.open(urllib.request.Request(BASE+path,data=body,headers=headers,method=method),timeout=120)
        except urllib.error.HTTPError as error: reply=error
        with reply:
            raw=reply.read()
            result=json.loads(raw) if raw else None
            REQUESTS.append({"method":method,"route":re.sub(r"[0-9a-f]{8}-[0-9a-f-]{27,}","{id}",path),
                "status":reply.status,"seconds":round(time.monotonic()-start,3),
                "code":result.get("code") if isinstance(result,dict) else None})
            return reply.status,result,reply.headers
    def call(self,method,path,body=None,key=None,status=200,csrf=True):
        code,data,headers=self.request(method,path,body,key,csrf)
        check(method+" "+re.sub(r"[0-9a-f]{8}-[0-9a-f-]{27,}","{id}",path)+" expected "+str(status),code==status)
        return data,headers
    def login(self,create=False):
        credentials={k:self.data[k] for k in ("email","password")}
        if create: self.call("POST","/api/v1/auth/signup",credentials,status=201)
        data,headers=self.call("POST","/api/v1/auth/login",credentials,csrf=False)
        self.data["csrf"]=data.get("csrfToken")
        cookie=headers.get("Set-Cookie","")
        parsed=http.cookies.SimpleCookie(cookie)
        self.data["cookie"]="; ".join(k+"="+v.value for k,v in parsed.items())
        check("Secure HttpOnly SameSite=Lax cookie and CSRF issued",
              "Secure" in cookie and "HttpOnly" in cookie and "SameSite=Lax" in cookie and bool(self.data["csrf"]))
        # Explicit header on a private HTTP network; this does not claim browser HTTPS testing.
        self.call("GET","/api/v1/auth/me")
    def upload(self,filename):
        boundary="teachmi"+uuid.uuid4().hex
        data=(FIXTURES/filename).read_bytes()
        body=('--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="'+filename+'"\r\nContent-Type: application/octet-stream\r\n\r\n').encode()+data+('\r\n--'+boundary+'--\r\n').encode()
        code,result,_=self.request("POST","/api/v1/materials",body,uuid.uuid4().hex,
            content_type="multipart/form-data; boundary="+boundary)
        check("Upload accepted "+filename,code==202)
        return result["materialId"]
    def analysis(self,material_id):
        deadline=time.monotonic()+120
        while time.monotonic()<deadline:
            code,data,_=self.request("GET","/api/v1/materials/"+material_id)
            if code==200 and data.get("status")!="analyzing":
                check("File analyzed with source chunks",data.get("status")=="done" and bool(data.get("sources")))
                return data
            time.sleep(1)
        raise RuntimeError("File analysis deadline")

def phase1():
    member,other=Client(),Client()
    member.login(True);other.login(True)
    member.call("POST","/api/v1/materials",{"text":"CSRF fixture"},uuid.uuid4().hex,status=403,csrf=False)
    body={"title":"Synthetic memory fixture","text":"Depreciation allocates cost over useful life. Annual straight line expense equals cost minus residual value divided by useful life.",
          "concept":{"name":"Depreciation","scope":"Allocation and useful life"}}
    key=uuid.uuid4().hex
    material,_=member.call("POST","/api/v1/materials",body,key,201)
    repeat,_=member.call("POST","/api/v1/materials",body,key,201)
    check("Duplicate material is replayed",repeat==material)
    files=[]
    for filename in ("small.pdf","small.ppt","small.pptx"):
        files.append(member.analysis(member.upload(filename)))
    pdf=files[0]
    concept,_=member.call("POST","/api/v1/materials/"+pdf["materialId"]+"/concepts",
        {"name":"Depreciation","scope":"Allocation across useful life","sourceChunkIds":[pdf["sources"][0]["chunkId"]]},
        uuid.uuid4().hex,201)
    pdf,_=member.call("GET","/api/v1/materials/"+pdf["materialId"])
    files[0]=pdf
    body={"materialId":pdf["materialId"],"conceptId":concept["conceptId"]}
    key=uuid.uuid4().hex
    session,_=member.call("POST","/api/v1/sessions",body,key,201)
    repeat,_=member.call("POST","/api/v1/sessions",body,key,201)
    check("Duplicate session is replayed",repeat==session)
    sid=session["sessionId"]
    other.call("GET","/api/v1/materials/"+pdf["materialId"],status=404)
    other.call("GET","/api/v1/sessions/"+sid,status=404)
    unavailable,_=member.call("PUT","/api/v1/sessions/"+sid+"/mode",
        {"mode":"review","expectedVersion":session["version"]},uuid.uuid4().hex,503)
    check("Disabled AI is explicit",unavailable.get("code")=="LEARNING_CONTENT_UNAVAILABLE")
    saved,_=member.call("GET","/api/v1/sessions/"+sid)
    check("Disabled generation leaves session unchanged",saved==session)
    files.append(member.analysis(member.upload("large.pdf")))
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        futures=[pool.submit(client.upload,"large.pdf") for client in (member,other)]
        ids=[f.result() for f in futures]
    for client,mid in zip((member,other),ids):
        saved=client.analysis(mid)
        if client is member: files.append(saved)
    STATE.joinpath("private-state.json").write_text(json.dumps({"member":member.data,"other":other.data,
        "session":session,"files":files,"memberId":member.call("GET","/api/v1/auth/me")[0]["id"]}),encoding="utf-8")

def checkpoint():
    state=json.loads(STATE.joinpath("private-state.json").read_text())
    member=Client(state["member"])
    previous=state["session"]
    saved,_=member.call("GET","/api/v1/sessions/"+previous["sessionId"])
    core=lambda value:{k:v for k,v in value.items() if k not in ("recovery","availableActions")}
    check("Synthetic uncertain only changes recovery and available actions",core(saved)==core(previous))
    recovery=saved.get("recovery",{})
    check("Uncertain remains held while generation is disabled",recovery.get("action")=="review_required"
          and recovery.get("retryAllowed") is False and saved.get("availableActions")==["wait"])
    OBSERVATIONS.append({"recoveryAction":recovery.get("action"),"retryAllowed":recovery.get("retryAllowed"),
        "diagnosticCode":recovery.get("diagnosticCode"),"availableActions":saved.get("availableActions")})
    # Compare restart against the state AFTER inserting the deliberate preservation fixture.
    state["session"]=saved
    STATE.joinpath("private-state.json").write_text(json.dumps(state),encoding="utf-8")

def phase2():
    state=json.loads(STATE.joinpath("private-state.json").read_text())
    member,other=Client(state["member"]),Client(state["other"])
    session=state["session"];sid=session["sessionId"]
    member.call("GET","/api/v1/auth/me")
    saved,_=member.call("GET","/api/v1/sessions/"+sid)
    OBSERVATIONS.append({"changedSessionFields":[key for key in set(saved)|set(session) if saved.get(key)!=session.get(key)]})
    check("Authentication and session restore after restart",saved==session)
    check("Uncertain recovery hold survives restart",saved.get("recovery",{}).get("action")=="review_required"
          and saved.get("recovery",{}).get("retryAllowed") is False and saved.get("availableActions")==["wait"])
    member.login()
    saved,_=member.call("GET","/api/v1/sessions/active")
    check("Relogin restores active session",saved==session)
    member.call("GET","/api/v1/home")
    for material in state["files"]:
        saved,_=member.call("GET","/api/v1/materials/"+material["materialId"])
        check("Analysis status and evidence IDs survive restart",saved==material)
    other.call("GET","/api/v1/sessions/"+sid,status=404)

if __name__=="__main__":
    phase=sys.argv[1]
    report={"phase":phase,"checks":CHECKS,"requests":REQUESTS,"observations":OBSERVATIONS,"success":False}
    try:
        {"phase1":phase1,"checkpoint":checkpoint,"phase2":phase2}[phase]()
        report["success"]=True
    except Exception as error:
        report["failureType"]=type(error).__name__
        # Only fixed assertion labels, never network error text or response bodies.
        if isinstance(error,RuntimeError): report["failure"]=str(error)
    finally:
        STATE.joinpath(phase+"-summary.json").write_text(json.dumps(report,indent=2),encoding="utf-8")
    sys.exit(0 if report["success"] else 1)
