#!/usr/bin/env python3
"""Read-only cache-error counts while the runner-owned containers still exist.

Use only as a diagnostic supplement: it does not alter the measured workload.
Console lines, credentials, request IDs, and tokens are never retained.
"""
import argparse
from collections import Counter
import datetime
import json
from pathlib import Path
import re
import subprocess


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory',type=Path)
    parser.add_argument('--project',required=True)
    args=parser.parse_args()
    assert re.fullmatch(r'tb11-[a-f0-9]+',args.project), 'Use the disposable runner project'
    report=json.loads((args.directory/'report.json').read_text())
    pattern=re.compile(r'^(\S+)\s+WARN.*Catalogue cache (\w+) (\w+) failed \((\w+)\);')
    observations=[]
    for name in ['app-a','app-b']:
        result=subprocess.run(['docker','logs',args.project+'-'+name+'-1'],capture_output=True,text=True,check=True)
        for line in (result.stdout+'\n'+result.stderr).splitlines():
            match=pattern.search(line)
            if match:
                stamp,cache,operation,error=match.groups()
                observations.append((datetime.datetime.fromisoformat(stamp),name,cache,operation,error))
    path=args.directory/'cache-diagnostics.json'
    output=json.loads(path.read_text()) if path.exists() else dict(runs={},source='Docker CatalogueCacheErrors warning counts; only measured observation windows included')
    for run in report['runs']:
        if run['job']['id'] in output['runs']:
            continue  # Preserve earlier observations if container logs rotate later.
        start=datetime.datetime.fromisoformat(run['before']['db']['observed_at'])
        end=datetime.datetime.fromisoformat(run['after']['db']['observed_at'])
        counts=Counter((name,cache,operation,error) for stamp,name,cache,operation,error in observations if start<=stamp<=end)
        output['runs'][run['job']['id']]=dict(start=start.isoformat(),end=end.isoformat(),
            errors=[dict(instance=n,cache=c,operation=o,error=e,count=v) for (n,c,o,e),v in sorted(counts.items())],
            count=sum(counts.values()))
    output['observed_utc']=datetime.datetime.now(datetime.timezone.utc).isoformat()
    path.write_text(json.dumps(output,indent=2)+'\n')
    print('Retained aggregate cache diagnostics for',len(report['runs']),'completed runs')


if __name__=='__main__':main()
