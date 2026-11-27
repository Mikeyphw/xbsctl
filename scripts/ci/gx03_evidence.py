#!/usr/bin/env python3
"""GX03 pure Android emulator evidence gates; no root/device/Gradle execution.

Does not treat a JUnit XML file as a successful app journey unless the expected
class AND exact named assertions appear with no skip/failure/duplicate.
"""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
import re
import struct
import xml.etree.ElementTree as ET
import zlib

class Refused(RuntimeError): pass

def need(ok: bool, why: str):
    if not ok: raise Refused(why)

def sha(data: bytes) -> str: return hashlib.sha256(data).hexdigest()

PKG='io.github.mikeceead.xbscompanion'
ORIGINAL_CASES=tuple(f'EM-{n:03d}' for n in list(range(1,19))+[22,23,24])
JOURNEYS={
  'Gx03JourneyInstrumentedTest': {
    'realMainActivityRendersAndNavigatesFiveTabs',
    'allFivePagesHaveRealNonemptyViewTrees',
    'hostilePageInputCannotOpenAnUnboundedPage',
  },
}
STAGE_A={'Gx03ProcessDeathInstrumentedTest':{'stageAWriteDurablePendingOwnership'}}
STAGE_B={'Gx03ProcessDeathInstrumentedTest':{'stageBInspectRecoveryAfterExternalForceStop'}}
COMPAT={
  26:{'R05cUiSmokeInstrumentedTest':{'launcherActivityReachesResumedWithoutTaskerOrRoot'},
      'R05cLifecycleInstrumentedTest':{'ownedPendingCommandSurvivesMemoryLossWithoutReplay',
          'corruptPendingCommandCannotBeReplayed','duplicateStartCheckSeesExistingOwner'}},
  34:{'R05cUiSmokeInstrumentedTest':{'launcherActivityReachesResumedWithoutTaskerOrRoot'},
      'R05cTaskerBoundaryInstrumentedTest':{'unsignedActionFailsWithoutStartingRoot',
          'unsignedConditionIsUnknownRatherThanSatisfied','policyDetectsModifiedSignedPayload',
          'exportedBoundaryIsLimitedToTaskerReceivers'}},
}

def junit_receipt(results: Path, expected: dict[str,set[str]]) -> dict:
    need(results.is_dir() and not results.is_symlink(),'RESULT_DIRECTORY_MISSING')
    xmls=sorted(results.rglob('*.xml'))
    need(bool(xmls),'NO_ANDROID_JUNIT_XML')
    actual={key:set() for key in expected}
    seen=set();all_cases=0
    for filename in xmls:
        need(filename.is_file() and not filename.is_symlink() and filename.stat().st_size<12_000_000,
             'UNSAFE_OR_OVERSIZED_XML')
        try: tree=ET.parse(filename).getroot()
        except (ET.ParseError,OSError) as e: raise Refused('MALFORMED_ANDROID_JUNIT_XML') from e
        if tree.tag not in ('testsuites','testsuite'): continue
        for row in tree.iter('testcase'):
            name=row.get('name','');cl=row.get('classname','').rsplit('.',1)[-1]
            need(name and cl,'UNNAMED_ANDROID_TEST')
            key=(cl,name)
            need(key not in seen,'DUPLICATE_ANDROID_TEST '+str(key))
            seen.add(key)
            need(row.find('failure') is None and row.find('error') is None,'FAILED_ANDROID_TEST '+str(key))
            need(row.find('skipped') is None,'SKIPPED_ANDROID_TEST '+str(key))
            all_cases+=1
            if cl in actual: actual[cl].add(name)
    for cl,methods in expected.items():
        missing=methods-actual[cl]
        need(not missing,'MISSING_ANDROID_ASSERTIONS '+cl+' '+','.join(sorted(missing)))
    need(all_cases>=sum(len(v) for v in expected.values()),'ZERO_TEST_OR_NO_ASSERTIONS')
    return {'total_testcases':all_cases,'required_testcases':sum(len(x) for x in expected.values()),
            'matched':{k:sorted(v) for k,v in actual.items()},
            'testcase_identifiers_verified':True,'skipped_cases':0,'failed_cases':0,
            'xml_files':len(xmls)}

def artifact_apk_pair(paths: dict[str,Path], apksigner: str, aapt: str, run) -> dict:
    """Verify fresh matching app/test APK identities and signatures (no device writes)."""
    outputs={}
    for kind,expected in [('app',PKG),('test',PKG+'.test')]:
        file=paths[kind]
        need(file.is_file() and not file.is_symlink() and file.stat().st_size>10000,'APK_MISSING_OR_EMPTY '+kind)
        cert=run([apksigner,'verify','--print-certs',str(file)])
        need(cert.returncode==0,'APK_SIGNER_INVALID '+kind)
        certs=re.findall(r'Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})',cert.stdout)
        need(len(certs)==1,'APK_SIGNER_AMBIGUOUS '+kind)
        badging=run([aapt,'dump','badging',str(file)])
        need(badging.returncode==0,'APK_BADGING_FAILED '+kind)
        m=re.search(r"(?m)^package: name='([^']+)'",badging.stdout)
        need(m is not None and m.group(1)==expected,'APK_PACKAGE_IDENTITY_MISMATCH '+kind)
        outputs[kind]={'path':file.name,'sha256':sha(file.read_bytes()),
                       'size_bytes':file.stat().st_size,'package':expected,'cert_sha256':certs[0].lower()}
    need(outputs['app']['sha256']!=outputs['test']['sha256'],'APP_TEST_APK_NOT_DISTINCT')
    need(outputs['app']['cert_sha256']==outputs['test']['cert_sha256'],'APP_TEST_CERT_MISMATCH')
    return outputs

PNG=b'\x89PNG\r\n\x1a\n'
def chunk(kind:bytes,payload:bytes)->bytes:
    return struct.pack('>I',len(payload))+kind+payload+struct.pack('>I',zlib.crc32(kind+payload)&0xffffffff)

def redact_png(raw:bytes, expected_width:int, band:int=100)->tuple[bytes,dict]:
    """Decode RGB/RGBA 8bpp PNG, strip ancillary metadata, mask status/navigation bands.

    We explicitly refuse unknown formats rather than output an unsanitized image.
    Does not inspect or claim to redact any secrets in the app content itself;
    fixture runs in a disposable emulator without accounts.
    """
    need(isinstance(raw,bytes) and len(raw)<=40_000_000 and raw.startswith(PNG),'PNG_SIGNATURE_INVALID')
    pos=8;chunks=[];found_end=False
    while pos<len(raw):
        need(pos+12<=len(raw),'PNG_CHUNK_TRUNCATED')
        size=struct.unpack_from('>I',raw,pos)[0];kind=raw[pos+4:pos+8];pos+=8
        need(size<=30_000_000 and pos+size+4<=len(raw),'PNG_CHUNK_SIZE_UNSAFE')
        data=raw[pos:pos+size];pos+=size
        crc=struct.unpack_from('>I',raw,pos)[0];pos+=4
        need(zlib.crc32(kind+data)&0xffffffff==crc,'PNG_CRC_MISMATCH')
        chunks.append((kind,data))
        if kind==b'IEND':found_end=True;break
    need(found_end and pos==len(raw),'PNG_EXTRA_DATA_OR_MISSING_IEND')
    hdr=[v for k,v in chunks if k==b'IHDR'];ids=[v for k,v in chunks if k==b'IDAT']
    need(len(hdr)==1 and ids,'PNG_IHDR_OR_IDAT_MISSING')
    ihdr=hdr[0];need(len(ihdr)==13,'PNG_BAD_IHDR')
    w,h,depth,mode,comp,filt,inter=struct.unpack('>IIBBBBB',ihdr)
    need(w==expected_width and 1000<=h<=3600 and depth==8 and mode in (2,6)
         and comp==filt==inter==0,'UNSUPPORTED_OR_WRONG_SCREENSHOT_DIMENSIONS')
    need(h>band*3,'PNG_TOO_SHORT_FOR_REDACTION')
    bpp=3 if mode==2 else 4;stride=w*bpp
    expected_size=h*(stride+1)
    try:
        decoder=zlib.decompressobj()
        encoded=decoder.decompress(b''.join(ids),expected_size+1)
    except zlib.error as exc: raise Refused('PNG_DECOMPRESSION_INVALID') from exc
    need(len(encoded)==expected_size and decoder.eof and not decoder.unconsumed_tail
         and not decoder.unused_data,'PNG_DECOMPRESSED_LENGTH_MISMATCH')
    prev=bytearray(stride);filtered=[];p=0;nonzero=0
    light_samples=dark_samples=content_samples=0
    for row in range(h):
        kind=encoded[p];p+=1;source=encoded[p:p+stride];p+=stride
        need(kind<=4,'PNG_FILTER_UNSUPPORTED')
        current=bytearray(stride)
        for i in range(stride):
            a=current[i-bpp] if i>=bpp else 0
            b=prev[i];c=prev[i-bpp] if i>=bpp else 0
            if kind==0:predict=0
            elif kind==1:predict=a
            elif kind==2:predict=b
            elif kind==3:predict=(a+b)//2
            else:
                v=a+b-c;da=abs(v-a);db=abs(v-b);dc=abs(v-c)
                predict=a if da<=db and da<=dc else b if db<=dc else c
            current[i]=(source[i]+predict)&0xff
        prev=current
        if row<band or row>=h-band:
            # Opaque black avoids transparent status/nav regions revealing stale imagery.
            current=bytearray((0,0,0,255) if bpp==4 else (0,0,0))*w
        else:
            nonzero += sum(x!=0 for x in current[:128])
            # Sample the decoded content, never the masked bars or compressed bytes.
            # Integer luma, conservative bounds; no claim about every individual pixel.
            if row%24==0:
                for column in range(24,w-24,24):
                    i=column*bpp
                    if bpp==4 and current[i+3]!=255: continue
                    luma=(2126*current[i]+7152*current[i+1]+722*current[i+2])//10000
                    content_samples+=1
                    light_samples+=luma>=180
                    dark_samples+=luma<=75
        filtered.append(b'\x00'+current)
    need(nonzero>0,'PNG_UNEXPECTEDLY_BLANK')
    output=PNG+chunk(b'IHDR',ihdr)+chunk(b'IDAT',zlib.compress(b''.join(filtered),6))+chunk(b'IEND',b'')
    meta={'width_px':w,'height_px':h,'density_dpi':480,'width_dp':w//3,
          'masked_top_px':band,'masked_bottom_px':band,'raw_sha256_local_only':sha(raw),
          'redacted_sha256':sha(output),'redacted_size_bytes':len(output),
          'ancillary_png_metadata_removed':True,
          'content_samples':content_samples,'light_samples':light_samples,'dark_samples':dark_samples}
    need(meta['width_dp'] in (360,393),'SCREENSHOT_DP_WIDTH_DRIFT')
    return output,meta

def validate_identity(head:str,runid:str,attempt:str,api:int,serial:str,qemu:str,abi:str):
    need(re.fullmatch('[0-9a-f]{40}',head or '') is not None,'HEAD_SHA_INVALID')
    need(str(runid).isdigit() and int(runid)>0 and str(attempt).isdigit() and int(attempt)>0,
         'GITHUB_RUN_ID_OR_ATTEMPT_INVALID')
    need(api in (26,34,36) and re.fullmatch(r'emulator-\d+',serial or '') and qemu=='1' and
         abi in ('x86_64','x86'), 'NON_EMULATOR_OR_WRONG_API_ABI_REFUSED')
    return {'source_sha':head,'github_run_id':str(runid),'github_run_attempt':str(attempt),
            'api_level':api,'serial':serial,'qemu':qemu,'abi':abi,'lane':'ROOTLESS_ANDROID_EMULATOR',
            'emulator_only':True,'physical_root_qualified':False,'kernel_su_qualified':False,
            'magisk_qualified':False,'signed_release':False,'battery_savings_measured':False}

def expected_inventory(matrix: dict)->dict:
    cases={x['id']:x for x in matrix['cases']}
    need(len(cases)==131 and len(matrix['cases'])==131,'ORIGINAL_131_CASES_CHANGED')
    need(all(i in cases and 'R10' in cases[i]['phases'] for i in ORIGINAL_CASES if i!='EM-019'),
         'GX03_EXPECTED_R10_CASES_MISSING')
    return {'original_case_ids':list(ORIGINAL_CASES),'original_case_count':len(ORIGINAL_CASES),
            'claim':'Inventory only; no individual original test accepted without mapped exact assertions.'}
