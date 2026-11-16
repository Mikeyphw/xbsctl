#!/usr/bin/env python3
"""GX05 disposable emulator startup/footprint sampler, called only by gx03_emulator_host.

Uses the parent's already verified sole x86 API36 emulator and ADB wrapper.
Never attempts root, installs, permission changes, or privileged power effects.
"""
from __future__ import annotations
import re
from gx05_preflight import Refused,require,measured_android

TOTAL=re.compile(r'(?m)^\s*TotalTime:\s*(\d{1,6})\s*$')
PSS1=re.compile(r'(?m)^\s*TOTAL\s+PSS:\s*([0-9,]{1,10})\b')
PSS2=re.compile(r'(?m)^\s*TOTAL\s+([0-9,]{1,10})\s+[0-9,]+\s+[0-9,]+\s+')

def parse_total_time(output):
    require(isinstance(output,str) and len(output)<8192,'STARTUP_LOG_OVERSIZED')
    m=TOTAL.findall(output)
    require(len(m)==1,'MISSING_OR_AMBIGUOUS_REAL_AM_TOTAL_TIME')
    value=int(m[0]);require(0<value<120_000,'STARTUP_TIME_INVALID')
    return value

def parse_pss(output):
    require(isinstance(output,str) and len(output)<350_000,'MEMINFO_UNTRUSTED_SIZE')
    matches=PSS1.findall(output)
    if not matches:matches=PSS2.findall(output)
    require(len(matches)==1,'MISSING_OR_AMBIGUOUS_REAL_TOTAL_PSS')
    raw=matches[0].replace(',','')
    require(raw.isdecimal() and 0<int(raw)<=1_000_000,'TOTAL_PSS_INVALID')
    return int(raw)

def collect(serial,adb,package,identity,*,samples=7):
    require(samples==7,'GX05_SAMPLE_COUNT_MUST_BE_SEVEN')
    require(identity.get('api_level')==36 and identity.get('abi') in ('x86_64','x86') and
            identity.get('emulator_only') is True,'GX05_DISPOSABLE_API36_IDENTITY_REQUIRED')
    require(re.fullmatch(r'[a-z][a-z0-9_.]{3,180}',package),'PACKAGE_NAME_UNSAFE')
    metrics=[];memory=[]
    for iteration in range(samples):
        adb(serial,'shell','am','force-stop',package)
        start=adb(serial,'shell','am','start','-W','-n',package+'/.MainActivity')
        elapsed=parse_total_time(start.stdout)
        mem=adb(serial,'shell','dumpsys','meminfo',package)
        pss=parse_pss(mem.stdout)
        metrics.append(elapsed);memory.append(pss)
        print('GX05_REAL_EMULATOR_STARTUP_SAMPLE '+str(iteration+1)+' total_time_ms='+str(elapsed)+
              ' pss_kib='+str(pss),flush=True)
    report={'cold_launch_ms':metrics,'pss_kib':memory,'sample_source':'adb-am-start-W-TotalTime',
            'api':36,'abi':identity['abi'],'emulator':True,
            'physical_qualified':False,'battery_savings_qualified':False}
    measured_android(report)
    return report
