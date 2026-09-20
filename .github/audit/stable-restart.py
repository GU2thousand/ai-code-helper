import http.cookiejar
import json
import os
import pathlib
import secrets
import subprocess
import time
import urllib.request

out = pathlib.Path('output/playwright')
base = 'http://127.0.0.1:8082'
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
env = {**os.environ, 'SERVER_PORT': '8082', 'APP_AUTH_TOKEN_SECRET': secrets.token_hex(32)}
process = None
def request(path, body=None):
    data = None if body is None else json.dumps(body).encode()
    r = urllib.request.Request(base+path,data=data,headers={'Content-Type':'application/json'})
    with client.open(r,timeout=15) as response:
        return json.load(response)
def start(name):
    global process
    with out.joinpath(name).open('w') as log:
        process = subprocess.Popen(['java','-jar','backend/target/backend-0.0.1-SNAPSHOT.jar'],env=env,stdout=log,stderr=subprocess.STDOUT)
    for _ in range(60):
        try:
            request('/api/health')
            return
        except Exception:
            time.sleep(1)
    raise RuntimeError('Configured backend did not start')
def stop():
    if process and process.poll() is None:
        process.terminate()
        process.wait(timeout=30)
try:
    start('stable-backend-before.log')
    user = request('/api/users/guest',{})
    marker = 'Java stable-history-marker-0920'
    request('/api/ai/chat',{'memoryId':'stable','message':marker})
    before = request('/api/ai/chat',{'memoryId':'stable','message':'what did i just say'})
    stop()
    start('stable-backend-after.log')
    renewed = request('/api/users/guest',{})
    after = request('/api/ai/chat',{'memoryId':'stable','message':'what did i just say'})
    results = [
        {'name':'configured stable secret preserves visitor identity across restart','passed':user['userId']==renewed['userId']},
        {'name':'server conversation memory survives restart with stable identity','passed':marker in after['answer'],'detail':{'before':before['answer'],'after':after['answer'],'markerRememberedBefore':marker in before['answer']}},
    ]
    out.joinpath('stable-restart.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
    print(json.dumps(results,ensure_ascii=False))
finally:
    stop()
