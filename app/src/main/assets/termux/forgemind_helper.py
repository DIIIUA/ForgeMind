#!/usr/bin/env python3
"""ForgeMind's loopback helper. Standard library only; owns a separate tmux server.

No Android privileges, shared UID APK, login scripts or existing server sessions.
Wire v1: one authenticated JSON request/reply per socket, with a dedicated raw
socket for each PTY. Stable task IDs make a lost start reply inspectable.
"""
import argparse, base64, fcntl, hashlib, hmac, json, os, pathlib, pty, re
import select, shlex, signal, socket, socketserver, struct, subprocess, termios
import threading, time, uuid

VERSION = '1.0.0'
HOME = pathlib.Path(os.environ['HOME']).resolve()
PREFIX = pathlib.Path(os.environ.get('PREFIX', '/data/data/com.termux/files/usr')).resolve()
BASH = str(PREFIX / 'bin/bash')
ROOT = None
OWNER = 'forgemind-helper'
LOCK = threading.RLock()
PTYS = {}
LIMIT = 1048576

def atomic(path, value):
    temp = path.with_suffix(path.suffix + '.tmp')
    temp.write_text(json.dumps(value), encoding='utf-8')
    os.chmod(temp, 0o600)
    os.replace(temp, path)

def env(extra=None):
    result = {'HOME': str(HOME), 'PREFIX': str(PREFIX), 'PATH': str(PREFIX/'bin')+':/system/bin',
              'TERM': 'xterm-256color', 'LANG': 'C.UTF-8', 'TMPDIR': str(PREFIX/'tmp')}
    for key, value in (extra or {}).items():
        if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', key) or key in ('BASH_ENV','ENV','HOME','PREFIX','PATH','TMUX','TMUX_PANE') or key.startswith('LD_'):
            raise ValueError('Unsupported environment variable')
        if not isinstance(value,str) or '\0' in value: raise ValueError('Invalid environment value')
        result[key] = value
    return result

def tmux(*args, check=True):
    command = [str(PREFIX/'bin/tmux'), '-L', SOCKET_NAME, '-f', '/dev/null', *args]
    result = subprocess.run(command, env=env(), cwd=HOME, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=8)
    if check and result.returncode: raise RuntimeError(result.stderr.decode(errors='replace')[:500])
    return result.returncode, result.stdout.decode(errors='replace').strip()

def sessions():
    rc, text = tmux('list-sessions','-F','#{session_id}\t#{session_name}\t#{@forgemind-owner}\t#{@forgemind-number}\t#{@forgemind-keep}',check=False)
    if rc: return []
    rows=[]
    for line in text.splitlines():
        p=line.split('\t')
        if len(p)==5 and p[2]==OWNER and re.fullmatch(r'forgemind-[a-f0-9-]{36}',p[1]):
            rows.append(dict(id=p[0], name=p[1], number=int(p[3]), keep=p[4]=='1'))
    return sorted(rows,key=lambda x:x['number'])

def owned(req):
    return next((s for s in sessions() if s['id']==req.get('id') and s['name']==req.get('name')),None) or fail('Session ownership changed')

def fail(message): raise ValueError(message)

def make_session(name,number=0):
    tmux('new-session','-d','-s',name,'-n','shell','-x','120','-y','32','-c',str(HOME),BASH+' --noprofile --norc',
         ';','set-option','-t','='+name+':','@forgemind-owner',OWNER,
         ';','set-option','-t','='+name+':','@forgemind-number',str(number),
         ';','set-option','-t','='+name+':','@forgemind-keep','0',
         ';','set-option','-t','='+name+':','detach-on-destroy','on')

def agent():
    rc,_=tmux('has-session','-t','='+AGENT,check=False)
    if rc: make_session(AGENT)
    _,value=tmux('display-message','-p','-t','='+AGENT+':','#{@forgemind-owner}')
    if value != OWNER: fail('Agent session ownership changed')
    return AGENT

def session_action(req):
    action=req['action']
    with LOCK:
        if action=='list': return {'sessions':sessions()}
        if action=='agent': return {'name':agent()}
        if action=='create':
            current=sessions()
            number=max([s['number'] for s in current],default=1)+1
            name='forgemind-'+str(uuid.uuid4()); make_session(name,number)
            return next(s for s in sessions() if s['name']==name)
        old=owned(req) if req.get('name')!=AGENT else {'name':agent(),'id':AGENT,'keep':True}
        if old['name']==AGENT and action!='select-shell': fail('The agent session is managed by tracked tasks')
        if action=='replace':
            fresh=session_action({'action':'create'})
            if not old['keep']: tmux('kill-session','-t',old['id'])
            return fresh
        if action=='close': tmux('kill-session','-t',old['id']); return {}
        if action=='keep':
            tmux('set-option','-t',old['id']+':','@forgemind-keep','1' if req.get('keep') else '0')
            return dict(old,keep=bool(req.get('keep')))
        if action=='select-shell': tmux('select-window','-t','='+old['name']+':^'); return {}
        fail('Unsupported session action')

def path(value,write=False):
    if not isinstance(value,str) or '\0' in value: fail('Invalid path')
    candidate=pathlib.Path(os.path.expanduser(value))
    if not candidate.is_absolute(): candidate=HOME/candidate
    resolved=candidate.resolve()
    if resolved==ROOT or ROOT in resolved.parents: fail('Helper private state is protected')
    if write:
        if resolved in (pathlib.Path('/'),HOME,PREFIX) or resolved in ROOT.parents: fail('Protected directory')
        for item in ('bin','lib','libexec','etc'):
            protected=PREFIX/item
            if resolved==protected or protected in resolved.parents or resolved in protected.parents: fail('Protected Termux installation')
    return resolved

def stat(p):
    exists=p.exists()
    data=p.stat() if exists else None
    return dict(name=p.name,path=str(p),exists=exists,directory=p.is_dir(),size=data.st_size if data else 0,modified=int(data.st_mtime*1000) if data else 0)

def files(req):
    action=req['action']; p=path(req['path'],action in ('mkdir','delete','move','write'))
    if action=='stat': return stat(p)
    if action=='list':
        items=sorted(p.iterdir(),key=lambda x:(not x.is_dir(),x.name.lower()))
        start=max(0,int(req.get('offset',0))); end=start+200
        return dict(entries=[stat(i) for i in items[start:end] if i.resolve()!=ROOT],nextOffset=end if end<len(items) else -1)
    if action=='mkdir': p.mkdir(parents=True,exist_ok=True); return {}
    if action=='delete':
        if not req.get('userConfirmed'): fail('Deletion requires explicit user confirmation')
        # Never recursively follow symlinks into protected directories.
        if p.is_dir():
            import shutil
            shutil.rmtree(p)
        else: p.unlink()
        return {}
    if action=='move':
        target=path(req['destination'],True)
        if target.exists() or p in target.parents: fail('Destination exists or is inside source')
        target.parent.mkdir(parents=True,exist_ok=True); p.rename(target); return {}
    if action=='write':
        data=base64.b64decode(req['data'],validate=True)
        if len(data)>LIMIT: fail('Write chunk exceeds limit')
        p.parent.mkdir(parents=True,exist_ok=True)
        with p.open('wb' if int(req.get('offset',0))==0 else 'r+b') as output:
            output.seek(int(req.get('offset',0))); output.write(data); output.flush()
        return {'written':len(data)}
    fail('Unsupported file action')

def task_dir(identifier):
    if str(uuid.UUID(identifier))!=identifier: fail('Invalid task ID')
    return ROOT/'tasks'/identifier

def pane_owned(folder,identifier):
    pane_file=folder/'pane'
    if not pane_file.exists(): return None
    pane=pane_file.read_text().strip()
    if not re.fullmatch(r'%[0-9]+',pane): return None
    rc,text=tmux('display-message','-p','-t',pane,'#{session_name}\t#{@forgemind-owner}\t#{@forgemind-task}\t#{pane_dead}',check=False)
    return (pane,text.endswith('\t0')) if rc==0 and text.startswith(AGENT+'\t'+OWNER+'\t'+identifier+'\t') else None

def snapshot(identifier):
    folder=task_dir(identifier)
    if not (folder/'request.json').exists(): return dict(id=identifier,state='UNKNOWN',error='No task record; never replay automatically')
    record=json.loads((folder/'request.json').read_text())
    status=folder/'status'; owned_pane=pane_owned(folder,identifier)
    # Sample exit again after the pane probe to avoid racing task completion.
    code=int(status.read_text()) if status.exists() else None
    cancelled=(folder/'cancelled').exists()
    state=('CANCELLED' if cancelled else 'COMPLETED') if code is not None else ('RUNNING' if owned_pane and owned_pane[1] else 'LOST')
    result=dict(id=identifier,state=state,cwd=record['cwd'],outputTruncated=False)
    if code is not None: result['exitCode']=code
    for kind in ('stdout','stderr'):
        capture=folder/kind
        if capture.exists():
            result['outputTruncated'] |= capture.stat().st_size >= LIMIT
            with capture.open('rb') as stream:
                stream.seek(max(0,capture.stat().st_size-32768)); result[kind]=stream.read().decode(errors='replace')
        else: result[kind]=''
    return result

def tasks(req):
    identifier=req['id']; folder=task_dir(identifier)
    with LOCK:
        if req['action']=='status': return snapshot(identifier)
        if req['action']=='cancel':
            state=snapshot(identifier)
            if state['state'] in ('COMPLETED','CANCELLED'): return state
            pane=pane_owned(folder,identifier)
            if not pane: fail('Cancellation not confirmed: task pane is unavailable')
            (folder/'cancelled').write_text('cancel')
            tmux('send-keys','-t',pane[0],'C-c')
            for _ in range(10):
                if (folder/'status').exists(): break
                time.sleep(.05)
            if not (folder/'status').exists():
                if pane_owned(folder,identifier)!=pane: fail('Task ownership changed')
                tmux('kill-pane','-t',pane[0]); (folder/'status').write_text('130')
            return snapshot(identifier)
        if req['action']!='start': fail('Unsupported task action')
        command=req['command']; cwd=path(req.get('cwd') or str(HOME)); extra=req.get('env') or {}
        if not command.strip() or len(command)>64000 or '\0' in command or not cwd.is_dir(): fail('Invalid command or working directory')
        environment=env(extra)
        fingerprint=hashlib.sha256(json.dumps([command,str(cwd),extra],sort_keys=True).encode()).hexdigest()
        if (folder/'request.json').exists():
            if json.loads((folder/'request.json').read_text())['fingerprint']!=fingerprint: fail('Task ID already used')
            return snapshot(identifier)
        if sum(snapshot(p.name)['state']=='RUNNING' for p in (ROOT/'tasks').iterdir() if p.is_dir())>=16: fail('Too many active tasks')
        agent(); folder.mkdir(mode=0o700)
        q=lambda item:shlex.quote(str(folder/item))
        (folder/'command.sh').write_text(command+'\n')
        exports='\n'.join('export '+k+'='+shlex.quote(v) for k,v in environment.items())
        finish='rc=$?; trap - EXIT; exec 1>&3 2>&4; wait; printf "%s" "$rc" > '+q('status.tmp')+'; mv '+q('status.tmp')+' '+q('status')
        driver='exec 3>&1 4>&2\ntrap '+shlex.quote(finish)+' EXIT\n'
        driver+='for ((i=0;i<300;i++)); do [[ -f '+q('ready')+' ]] && break; sleep 0.1; done\n[[ -f '+q('ready')+' ]] || exit 130\n'
        driver+=exports+'\nunset TMUX TMUX_PANE SHLVL OLDPWD\n'
        driver+='exec > >(tee /dev/fd/3 | { head -c 1048576 > '+q('stdout')+'; cat >/dev/null; }) 2> >(tee /dev/fd/4 | { head -c 1048576 > '+q('stderr')+'; cat >/dev/null; })\n'
        driver+='cd -- '+shlex.quote(str(cwd))+' || exit\n'+BASH+' --noprofile --norc '+q('command.sh')+'\n'
        (folder/'driver.sh').write_text(driver)
        atomic(folder/'request.json',dict(fingerprint=fingerprint,cwd=str(cwd),created=time.time()))
        window='task-'+identifier
        _,pane=tmux('new-window','-d','-P','-F','#{pane_id}','-t',AGENT+':','-n',window,BASH+' '+q('driver.sh'),
                    ';','set-option','-p','-t',AGENT+':'+window,'@forgemind-task',identifier,
                    ';','set-option','-w','-t',AGENT+':'+window,'remain-on-exit','on')
        (folder/'pane').write_text(pane.splitlines()[0])
        if not pane_owned(folder,identifier): fail('Task dispatch ownership not confirmed')
        (folder/'ready').write_text('ready'); tmux('select-window','-t',pane)
        return snapshot(identifier)

def open_pty(req):
    name=req['name']
    with LOCK:
        for identifier,entry in list(PTYS.items()):
            if len(PTYS)>64 and entry['fd']<0 and entry['process'].poll() is not None: PTYS.pop(identifier,None)
        if name==AGENT: agent()
        elif name not in [s['name'] for s in sessions()]: fail('Session is not owned')
        if len([p for p in PTYS.values() if p['process'].poll() is None])>=16: fail('Too many terminals')
        master,slave=pty.openpty()
        fcntl.ioctl(slave,termios.TIOCSWINSZ,struct.pack('HHHH',24,80,0,0))
        process=subprocess.Popen([str(PREFIX/'bin/tmux'),'-L',SOCKET_NAME,'attach-session','-t','='+name],
                                 stdin=slave,stdout=slave,stderr=slave,env=env(req.get('env')),cwd=HOME,start_new_session=True)
        os.close(slave); identifier=str(uuid.uuid4())
        PTYS[identifier]=dict(process=process,fd=master,streamed=False)
        def expire():
            entry=PTYS.get(identifier)
            if entry and not entry['streamed']: close_pty(identifier)
        timer=threading.Timer(30,expire); timer.daemon=True; timer.start()
        return dict(id=identifier,pid=process.pid)

def close_pty(identifier):
    with LOCK:
        entry=PTYS.get(identifier)
        if not entry: return {}
        if entry['process'].poll() is None:
            entry['process'].terminate()
            try: entry['process'].wait(2)
            except subprocess.TimeoutExpired: entry['process'].kill(); entry['process'].wait()
        if entry['fd']>=0:
            try: os.close(entry['fd'])
            except OSError: pass
            entry['fd']=-1
        return {}

class Handler(socketserver.StreamRequestHandler):
    def handle(self):
        self.request.settimeout(12)
        try:
            line=self.rfile.readline(1500000)
            if not line.endswith(b'\n'): fail('Request too large')
            req=json.loads(line)
            if not hmac.compare_digest(str(req.pop('token','')),TOKEN): fail('Unauthorized')
            method=req.pop('method')
            if method=='info': result=dict(protocol=1,version=VERSION,termuxReady=True,tmuxReady=(PREFIX/'bin/tmux').is_file(),debug=False,uid=os.getuid(),socket=SOCKET_NAME)
            elif method=='sessions': result=session_action(req)
            elif method=='files': result=files(req)
            elif method=='tasks': result=tasks(req)
            elif method=='openPty': result=open_pty(req)
            elif method=='resizePty':
                entry=PTYS[req['id']]
                fcntl.ioctl(entry['fd'],termios.TIOCSWINSZ,struct.pack('HHHH',max(1,min(500,int(req['rows']))),max(1,min(1000,int(req['columns']))),0,0))
                # Popen deliberately avoids preexec_fn in this threaded server. The
                # client has no controlling tty, so notify this owned client explicitly.
                if entry['process'].poll() is None: entry['process'].send_signal(signal.SIGWINCH)
                result={}
            elif method=='waitForPty':
                process=PTYS[req['id']]['process']
                try: process.wait(min(1,max(0,req.get('timeoutMs',1000)/1000)))
                except subprocess.TimeoutExpired: pass
                result=dict(running=process.poll() is None,exitCode=process.returncode if process.returncode is not None else -1)
            elif method=='closePty': result=close_pty(req['id'])
            elif method=='fileRead':
                with path(req['path']).open('rb') as source:
                    self.reply({}); self.request.settimeout(60)
                    while True:
                        chunk=source.read(65536)
                        if not chunk: break
                        self.wfile.write(chunk)
                return
            elif method=='ptyStream':
                entry=PTYS[req['id']]
                with LOCK:
                    if entry['streamed']: fail('PTY already attached')
                    entry['streamed']=True
                self.reply({}); self.request.settimeout(None)
                try:
                    while entry['process'].poll() is None:
                        readable,_,_=select.select([self.request,entry['fd']],[],[],1)
                        for channel in readable:
                            data=self.request.recv(16384) if channel is self.request else os.read(channel,16384)
                            if not data: return
                            if channel is self.request:
                                while data:
                                    written=os.write(entry['fd'],data); data=data[written:]
                            else: self.request.sendall(data)
                finally: close_pty(req['id'])
                return
            else: fail('Unsupported method')
            self.reply(result)
        except (BrokenPipeError,ConnectionResetError): pass
        except Exception as error:
            try: self.wfile.write((json.dumps({'ok':False,'error':str(error)[:500]})+'\n').encode())
            except OSError: pass
    def reply(self,result): self.wfile.write((json.dumps({'ok':True,'result':result},separators=(',',':'))+'\n').encode())

class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address=True
    daemon_threads=True
    slots=threading.BoundedSemaphore(32)
    def process_request(self,request,address):
        if not self.slots.acquire(False): request.close(); return
        try: super().process_request(request,address)
        except Exception: self.slots.release(); raise
    def process_request_thread(self,request,address):
        try: super().process_request_thread(request,address)
        finally: self.slots.release()

def main():
    global ROOT,TOKEN,SOCKET_NAME,AGENT
    parser=argparse.ArgumentParser(); parser.add_argument('--root',required=True); parser.add_argument('--port',type=int,required=True)
    args=parser.parse_args(); ROOT=pathlib.Path(args.root).resolve()
    if not (HOME in ROOT.parents and ROOT.name=='forgemind-helper'): fail('Invalid helper root')
    os.umask(0o077); ROOT.mkdir(parents=True,exist_ok=True); (ROOT/'tasks').mkdir(exist_ok=True)
    lock=(ROOT/'daemon.lock').open('a'); fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
    TOKEN=(ROOT/'token').read_text().strip()
    if not re.fullmatch(r'[a-f0-9]{64}',TOKEN): fail('Invalid token')
    SOCKET_NAME='fm-'+hashlib.sha256(str(ROOT).encode()).hexdigest()[:20]
    meta=ROOT/'identity.json'
    if not meta.exists(): atomic(meta,{'agent':'fm-agent-'+str(uuid.uuid4())})
    AGENT=json.loads(meta.read_text())['agent']
    with Server(('127.0.0.1',args.port),Handler) as server:
        atomic(ROOT/'running.json',dict(pid=os.getpid(),port=server.server_address[1],version=VERSION))
        server.serve_forever(poll_interval=.5)

if __name__=='__main__': main()
