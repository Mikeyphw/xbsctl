#!/usr/bin/env python3
"""Independent negative evidence fixtures and real JDK palette qualification."""
from pathlib import Path
import copy
import subprocess
import tempfile
import unittest
import struct
import zlib
import xml.etree.ElementTree as ET
from gx03_evidence import PKG, Refused, PNG, chunk, redact_png
from gx10_presentation import (NAV, WIDTHS, validate_window_xml, validate_foreground,
    validate_capture, validate_matrix, validate_presentation_source, validate_handoff)
ROOT=Path(__file__).resolve().parents[2]

def xml_fixture():
    root=ET.Element('hierarchy')
    ET.SubElement(root,'node',{'package':PKG,'resource-id':PKG+':id/xbs_main_title','text':'Overview','bounds':'[30,200][450,300]'})
    for i,(name,label) in enumerate(NAV.items()):
        ET.SubElement(root,'node',{'package':PKG,'resource-id':PKG+':id/xbs_nav_'+name,
            'content-desc':label,'clickable':'true','enabled':'true','bounds':f'[{i*200},2100][{i*200+180},2300]'})
    return ET.tostring(root)

def capture(width,theme):
    w,h=WIDTHS[width]
    return {**validate_window_xml(xml_fixture(),w,h),'width_px':w,'height_px':h,'width_dp':width,'density_dpi':480,
        'theme_requested':theme,'theme_observed':theme,'content_samples':1000,'light_samples':900 if theme=='light' else 50,
        'dark_samples':900 if theme=='dark' else 50,'foreground_verified':True,'masked_top_px':180,'masked_bottom_px':180,
        'ancillary_png_metadata_removed':True,'redacted_sha256':format(width+(1 if theme=='light' else 0),'064x')}

class PresentationTests(unittest.TestCase):
    def test_real_window_structure(self): self.assertEqual(validate_window_xml(xml_fixture(),1080,2400)['window_nodes_verified'],6)
    def test_foreground(self):
        validate_foreground('topResumedActivity=ActivityRecord{aaa u0 '+PKG+'/.MainActivity t1}')
        for bad in ('history '+PKG+'/.MainActivity', 'topResumedActivity=ActivityRecord{a u0 other/.MainActivity}', 'topResumedActivity=ActivityRecord{a u0 '+PKG+'/.MainActivity}\nmResumedActivity: ActivityRecord{b u0 other/.Other}'):
            with self.subTest(bad=bad),self.assertRaises(Refused):validate_foreground(bad)
    def test_window_negative(self):
        for old,new in [(b'Overview',b'Other'),(b'clickable="true"',b'clickable="false"'),(b'[0,2100][180,2300]',b'[0,2100][90,2300]'),(b'[0,2100][180,2300]',b'[0,2100][180,2500]'),(b'content-desc="Events"',b'content-desc=""')]:
            with self.subTest(old=old,new=new),self.assertRaises(Refused):validate_window_xml(xml_fixture().replace(old,new),1080,2400)
        for raw in (b'<bad',b'<!DOCTYPE hierarchy><hierarchy/>',b'<hierarchy/>',xml_fixture().replace(b'</hierarchy>',xml_fixture().split(b'<hierarchy>')[1])):
            with self.assertRaises(Refused):validate_window_xml(raw,1080,2400)
    def test_matrix(self):
        rows=[capture(w,t) for w in WIDTHS for t in ('light','dark')]
        self.assertEqual(validate_matrix(rows)['distinct_redacted_digests'],4)
        for key,value in [('theme_observed','dark'),('light_samples',10),('dark_samples',1000),('foreground_verified',False),('window_xml_sha256','x'),('navigation_bounds',{}),('masked_top_px',100),('width_px',0)]:
            d=copy.deepcopy(rows);d[0][key]=value
            with self.subTest(key=key),self.assertRaises(Refused):validate_matrix(d)
        d=copy.deepcopy(rows);d[1]['redacted_sha256']=d[0]['redacted_sha256']
        with self.assertRaises(Refused):validate_matrix(d)
        with self.assertRaises(Refused):validate_matrix(rows[:-1])
    def test_actual_decoded_pixels_and_redaction(self):
        w,h=1080,1000
        for color,theme in [(240,'light'),(20,'dark')]:
            raw=PNG+chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,8,2,0,0,0))+chunk(b'tEXt',b'private\x00fixture')+chunk(b'IDAT',zlib.compress((b'\0'+bytes([color])*w*3)*h))+chunk(b'IEND',b'')
            redacted,meta=redact_png(raw,w,180)
            self.assertEqual(meta[theme+'_samples'],meta['content_samples']);self.assertNotIn(b'tEXt',redacted)
            self.assertNotEqual(raw,redacted)
        broken=PNG+chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(b'\0'*(h*(w*3+1)+1)))+chunk(b'IEND',b'')
        with self.assertRaises(Refused):redact_png(broken,w)
    def test_source_and_historical_handoff(self):
        validate_presentation_source(ROOT);validate_handoff(ROOT)
    def test_real_java_policy(self):
        # No Android SDK or Gradle: exercise the actual Java policy with the JDK.
        source=ROOT/'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/ThemePalettePolicy.java'
        harness='''package io.github.mikeceead.xbscompanion;
        public class PaletteHarness { public static void main(String[] args) {
          for(boolean dark:new boolean[]{false,true}) {
            int[] base=ThemePalettePolicy.fallback(dark);
            if(!ThemePalettePolicy.usable(dark,base)) throw new AssertionError("fallback");
            int[] wrong=ThemePalettePolicy.fallback(!dark);
            if(ThemePalettePolicy.usable(dark,wrong)) throw new AssertionError("inverted");
            int[] copy=ThemePalettePolicy.choose(dark,base); copy[0]=0;
            if(!ThemePalettePolicy.usable(dark,base)) throw new AssertionError("alias");
            base[3]=base[0]; if(ThemePalettePolicy.usable(dark,base)) throw new AssertionError("contrast");
            if(!ThemePalettePolicy.usable(dark,ThemePalettePolicy.choose(dark,base))) throw new AssertionError("fallback choice");
          }
        }}'''
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'PaletteHarness.java';path.write_text(harness)
            subprocess.run(['javac','-d',d,str(source),str(path)],check=True,timeout=60)
            subprocess.run(['java','-cp',d,'io.github.mikeceead.xbscompanion.PaletteHarness'],check=True,timeout=30)

if __name__=='__main__':unittest.main(verbosity=2)
