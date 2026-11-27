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
    validate_capture, validate_matrix, validate_presentation_source, validate_handoff, validate_layout_repair)
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
    def test_layout_repair_source(self):
        validate_layout_repair(ROOT)
    def test_layout_repair_negative(self):
        from shutil import copytree
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            base='android-app/app/src/'
            for kind in ('main/java','androidTest/java'):
                rel=base+kind+'/io/github/mikeceead/xbscompanion'
                copytree(ROOT/rel,root/rel)
            prod=root/(base+'main/java/io/github/mikeceead/xbscompanion')
            inst=root/(base+'androidTest/java/io/github/mikeceead/xbscompanion/Gx10PresentationInstrumentedTest.java')
            for path,old,new in [(prod/'MainActivity.java','row.setBaselineAligned(false)','row.setBaselineAligned(true)'),
                (prod/'XbsTileSettingsActivity.java','setXbsContentView(frame)','setXbsContentView(scroll)'),
                (inst,'view.getHeight(),actual.height()','view.getHeight()-1,actual.height()'),
                (inst,'assertTextFits(reset)','assertNotNull(reset)'),
                (inst,'assertEquals(before,RunStore.all(app).size())','assertTrue(true)')]:
                source=path.read_text();self.assertIn(old,source)
                path.write_text(source.replace(old,new))
                try:
                    with self.subTest(old=old),self.assertRaises(Refused):validate_layout_repair(root)
                finally:path.write_text(source)
    def test_arabic_window_real_labels_and_mirrored_order(self):
        from gx10_presentation import NAV_AR
        root=ET.fromstring(xml_fixture())
        for i,node in enumerate(root.findall('node')):
            name=node.get('resource-id').rsplit('/',1)[-1]
            if name=='xbs_main_title':node.set('text',NAV_AR['overview'])
            else:
                node.set('content-desc',NAV_AR[name.removeprefix('xbs_nav_')]);node.set('focusable','true')
                node.set('bounds',f'[{(5-i)*200},2100][{(5-i)*200+180},2300]')
        data=ET.tostring(root)
        self.assertEqual(validate_window_xml(data,1080,2400,require_focus=True,locale='ar')['window_nodes_verified'],6)
        with self.assertRaises(Refused):validate_window_xml(data,1080,2400,locale='en')
        root.findall('node')[1].set('bounds','[0,2100][180,2300]')
        with self.assertRaises(Refused):validate_window_xml(ET.tostring(root),1080,2400,locale='ar')

    def test_adaptive_rail_geometry_and_negative_controls(self):
        from gx10_presentation import NAV_AR
        def window(locale, side):
            root=ET.fromstring(xml_fixture())
            x=1920 if side=='right' else 30
            for i,node in enumerate(root.findall('node')):
                name=node.get('resource-id').rsplit('/',1)[-1]
                if name=='xbs_main_title':
                    node.set('text',(NAV_AR if locale=='ar' else NAV)['overview'])
                    node.set('bounds','[30,50][1850,150]' if side=='right' else '[550,50][2350,150]')
                else:
                    node.set('content-desc',(NAV_AR if locale=='ar' else NAV)[name.removeprefix('xbs_nav_')]);node.set('focusable','true')
                    y=30+(i-1)*195
                    node.set('bounds',f'[{x},{y}][{x+420},{y+180}]')
            return root
        for locale,side in [('en','left'),('ar','right')]:
            root=window(locale,side)
            data=ET.tostring(root)
            result=validate_window_xml(data,2400,1080,require_focus=True,locale=locale,navigation_layout='rail')
            self.assertEqual(result['navigation_layout'],'rail')
            self.assertIs(result['rtl_navigation_verified'],locale=='ar')
            with self.assertRaises(Refused):validate_window_xml(data,2400,1080,locale=locale,navigation_layout='row')
            with self.assertRaises(Refused):validate_window_xml(ET.tostring(window(locale,'right' if side=='left' else 'left')),2400,1080,locale=locale,navigation_layout='rail')
            nodes=root.findall('node'); first,last=nodes[1].get('bounds'),nodes[-1].get('bounds')
            nodes[1].set('bounds',last);nodes[-1].set('bounds',first)
            with self.assertRaisesRegex(Refused,'RAIL_ORDER_DRIFT'):validate_window_xml(ET.tostring(root),2400,1080,locale=locale,navigation_layout='rail')
        # The original endpoint-only check accepted a scrambled middle tab.
        root=ET.fromstring(xml_fixture());nodes=root.findall('node')
        nodes[2].set('bounds',nodes[3].get('bounds'))
        with self.assertRaises(Refused):validate_window_xml(ET.tostring(root),1080,2400)

    def test_landscape_matrix_and_strict_negatives(self):
        from gx10_landscape import VARIANTS,validate_landscape_matrix
        rows=[]
        for i,(short,theme,font) in enumerate(sorted(VARIANTS)):
            row=capture(short,theme)
            h,w=WIDTHS[short]
            row.update(width_px=w,height_px=h,width_dp=w//3,short_edge_dp=short,
                orientation='landscape',font_scale_percent=font,font_scale_observed_percent=font,
                masked_left_px=180,masked_right_px=180,navigation_focusability_verified=True,
                redacted_sha256=format(i+10000,'064x'))
            row.update(locale_observed='en',locale_requested='en',navigation_layout='rail',
                       navigation_geometry_verified=True,rtl_navigation_verified=False)
            row['navigation_bounds']={'xbs_main_title':[550,40,w-30,140],
                **{'xbs_nav_'+key:[30,30+j*195,450,210+j*195] for j,key in enumerate(NAV)}}
            rows.append(row)
        self.assertEqual(validate_landscape_matrix(rows)['verified_landscape_variants'],5)
        for key,value in [('orientation','portrait'),('masked_left_px',0),('font_scale_observed_percent',0),
                          ('navigation_focusability_verified',False),('width_px',1080),('height_px',2400),
                          ('redacted_sha256','bad'),('foreground_verified',False),('navigation_layout','row'),
                          ('locale_observed','ar'),('navigation_geometry_verified',False),('rtl_navigation_verified',True)]:
            bad=copy.deepcopy(rows);bad[0][key]=value
            with self.subTest(key=key),self.assertRaises(Refused):validate_landscape_matrix(bad)
        with self.assertRaises(Refused):validate_landscape_matrix(rows[:-1])
        with self.assertRaises(Refused):validate_landscape_matrix(rows+[rows[0]])
    def test_landscape_actual_decoded_pixels_and_side_redaction(self):
        w,h=2400,1080
        raw=PNG+chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,8,2,0,0,0))+chunk(b'tEXt',b'private\x00fixture')+chunk(b'IDAT',zlib.compress((b'\0'+bytes([240])*w*3)*h))+chunk(b'IEND',b'')
        redacted,meta=redact_png(raw,w,180,expected_height=h,side_band=180)
        self.assertEqual(meta['light_samples'],meta['content_samples'])
        self.assertEqual(meta['short_edge_dp'],360)
        self.assertNotIn(b'tEXt',redacted)
        pos=8; data=[]
        while pos<len(redacted):
            size=struct.unpack_from('>I',redacted,pos)[0];kind=redacted[pos+4:pos+8]
            if kind==b'IDAT':data.append(redacted[pos+8:pos+8+size])
            pos+=12+size
        pixels=zlib.decompress(b''.join(data));stride=w*3+1
        self.assertEqual(set(pixels[:stride*180]),{0})
        row=pixels[200*stride:201*stride]
        self.assertEqual(set(row[:1+180*3]),{0})
        self.assertEqual(set(row[-180*3:]),{0})
        self.assertEqual(set(row[1+180*3:-180*3]),{240})
        with self.assertRaises(Refused):redact_png(raw,w,180,expected_height=h+1,side_band=180)
        with self.assertRaises(Refused):redact_png(raw,w,180,side_band=180)
    def test_window_focusability_is_actual_evidence(self):
        raw=xml_fixture()
        with self.assertRaises(Refused):validate_window_xml(raw,1080,2400,require_focus=True)
        root=ET.fromstring(raw)
        for node in root.iter('node'):
            if 'xbs_nav_' in node.get('resource-id',''):node.set('focusable','true')
        self.assertEqual(validate_window_xml(ET.tostring(root),1080,2400,require_focus=True)['window_nodes_verified'],6)
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
