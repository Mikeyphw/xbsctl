"""Strict rootless presentation evidence; no device or Gradle commands in pure gates."""
from __future__ import annotations
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from gx03_evidence import need, sha, PKG, Refused

CLASS='Gx10PresentationInstrumentedTest'
METHODS=frozenset({
    'actualDayNightActivitiesUseDistinctReadablePalettes',
    'realRotationPreservesSelectedTabAndMeasuredInsets',
    'arabicMainNavigationMirrorsAndExposesLocalizedLabels',
    'tileSettingsOfflineSelectionAndResetCancellationDoNotDispatch',
    'allNavigationTabsHaveAccessibleActionsAndRealScroll',
})
NAV={'overview':'Overview','events':'Events','processes':'Processes','activity':'Activity','settings':'Settings'}
WIDTHS={360:(1080,2400),393:(1179,2620)}
NAV_AR={'overview':'نظرة عامة','events':'الأحداث','processes':'العمليات','activity':'النشاط','settings':'الإعدادات'}


def validate_foreground(output: str) -> None:
    # Match the active/resumed activity field, not a buried task or historical entry.
    matches=re.findall(r'(?m)^\s*(?:topResumedActivity|mResumedActivity)\s*[:=]\s*(.*)$',output)
    actual=re.compile(r'(?<![A-Za-z0-9_.])'+re.escape(PKG)+r'/'+r'(?:\.MainActivity|'+re.escape(PKG)+r'\.MainActivity)(?:[\s}]|$)')
    need(bool(matches) and all(actual.search(line) for line in matches),'GX10_MAIN_ACTIVITY_NOT_RESUMED')


def validate_navigation_geometry(bounds: dict, layout: str, locale: str) -> None:
    """Verify the actual layout's reading order, not just two endpoint positions.

    Compact navigation mirrors horizontally. Expanded navigation is a vertical
    rail whose start edge mirrors, while its semantic order remains top-to-bottom.
    Coordinates are the measured accessibility bounds, never requested geometry.
    """
    need(layout in ('row','rail') and locale in ('en','ar'),'GX10_NAVIGATION_GEOMETRY_INVALID')
    keys=['xbs_nav_'+k for k in NAV]
    need(isinstance(bounds,dict) and set(bounds)=={'xbs_main_title',*keys},'GX10_CAPTURE_BOUNDS_INVENTORY')
    for rect in bounds.values():
        need(type(rect) is list and len(rect)==4 and all(type(v) is int for v in rect)
             and 0<=rect[0]<rect[2] and 0<=rect[1]<rect[3],'GX10_NAVIGATION_GEOMETRY_INVALID')
    buttons=[bounds[k] for k in keys]
    title=bounds['xbs_main_title']
    if layout=='row':
        need(max(r[1] for r in buttons)<min(r[3] for r in buttons),'GX10_NAVIGATION_ROW_MISALIGNED')
        ordered=buttons if locale=='en' else list(reversed(buttons))
        need(all(a[2]<=b[0] for a,b in zip(ordered,ordered[1:])),
             'GX10_RTL_NAVIGATION_NOT_MIRRORED' if locale=='ar' else 'GX10_LTR_NAVIGATION_ORDER_DRIFT')
    else:
        need(max(r[0] for r in buttons)<min(r[2] for r in buttons),'GX10_NAVIGATION_RAIL_MISALIGNED')
        need(all(a[3]<=b[1] for a,b in zip(buttons,buttons[1:])),'GX10_NAVIGATION_RAIL_ORDER_DRIFT')
        correct_side=(min(r[0] for r in buttons)>=title[2] if locale=='ar'
                      else max(r[2] for r in buttons)<=title[0])
        need(correct_side,'GX10_RTL_NAVIGATION_NOT_MIRRORED' if locale=='ar' else 'GX10_LTR_RAIL_WRONG_SIDE')


def validate_window_xml(raw: bytes, width: int, height: int, *, require_focus: bool=False, locale: str='en', navigation_layout: str='row') -> dict:
    """Verify actual navigation/title nodes and fully in-window bounds; publish no raw XML."""
    need(isinstance(raw,bytes) and 0<len(raw)<=512_000 and b'<!DOCTYPE' not in raw.upper()
         and b'<!ENTITY' not in raw.upper(),'GX10_WINDOW_XML_UNSAFE')
    try: root=ET.fromstring(raw)
    except ET.ParseError as exc: raise Refused('GX10_WINDOW_XML_MALFORMED') from exc
    need(root.tag=='hierarchy','GX10_WINDOW_XML_NOT_HIERARCHY')
    need(locale in ('en','ar'),'GX10_UNREVIEWED_LOCALE')
    labels=NAV_AR if locale=='ar' else NAV
    expected={PKG+':id/xbs_main_title':labels['overview']}
    expected.update({PKG+':id/xbs_nav_'+k:v for k,v in labels.items()})
    observed={}
    for node in root.iter('node'):
        rid=node.get('resource-id','')
        if rid not in expected: continue
        need(rid not in observed and node.get('package')==PKG,'GX10_DUPLICATE_OR_FOREIGN_WINDOW_NODE')
        match=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',node.get('bounds',''))
        need(match is not None,'GX10_WINDOW_NODE_BOUNDS_MISSING')
        x1,y1,x2,y2=map(int,match.groups())
        need(0<=x1<x2<=width and 0<=y1<y2<=height,'GX10_WINDOW_NODE_OUT_OF_BOUNDS')
        if rid.endswith('/xbs_main_title'):
            need(node.get('text')==expected[rid],'GX10_OVERVIEW_TITLE_MISSING')
        else:
            need(node.get('content-desc')==expected[rid] and node.get('clickable')=='true'
                 and node.get('enabled')=='true','GX10_NAVIGATION_LABEL_OR_ACTION_MISSING')
            need(x2-x1>=144 and y2-y1>=144,'GX10_NAVIGATION_MEASURED_TARGET_TOO_SMALL')
            if require_focus:
                need(node.get('focusable')=='true','GX10_NAVIGATION_NOT_ACCESSIBILITY_FOCUSABLE')
        observed[rid]=[x1,y1,x2,y2]
    need(set(observed)==set(expected),'GX10_REQUIRED_WINDOW_NODES_MISSING')
    bounds={k.rsplit('/',1)[1]:v for k,v in observed.items()}
    validate_navigation_geometry(bounds,navigation_layout,locale)
    return {'window_nodes_verified':len(observed),'window_xml_sha256':sha(raw),
            'navigation_bounds':bounds,'navigation_layout':navigation_layout,
            'navigation_geometry_verified':True,'rtl_navigation_verified':locale=='ar',
            'raw_window_xml_published':False}


def validate_capture(meta: dict, width: int, theme: str) -> None:
    need(width in WIDTHS and theme in ('light','dark'),'GX10_CAPTURE_REQUEST_INVALID')
    w,h=WIDTHS[width]
    need((meta.get('width_px'),meta.get('height_px'),meta.get('density_dpi'),meta.get('width_dp'))
         ==(w,h,480,width),'GX10_CAPTURE_DIMENSIONS_DRIFT')
    need(meta.get('theme_requested')==theme and meta.get('theme_observed')==theme,
         'GX10_THEME_MODE_MISMATCH')
    total=meta.get('content_samples'); selected=meta.get(theme+'_samples')
    need(type(total) is int and total>=100 and type(selected) is int and 0<=selected<=total
         and selected*100>=total*65,'GX10_CAPTURE_BRIGHTNESS_CONTRADICTS_THEME')
    need(type(meta.get('light_samples')) is int and type(meta.get('dark_samples')) is int
         and 0<=meta['light_samples']<=total and 0<=meta['dark_samples']<=total
         and meta['light_samples']+meta['dark_samples']<=total,'GX10_CAPTURE_SAMPLE_COUNTS_INVALID')
    need(meta.get('window_nodes_verified')==6 and meta.get('raw_window_xml_published') is False
         and meta.get('foreground_verified') is True,'GX10_CAPTURE_APP_WINDOW_NOT_VERIFIED')
    need(isinstance(meta.get('window_xml_sha256'),str) and re.fullmatch('[0-9a-f]{64}',meta['window_xml_sha256']),
         'GX10_WINDOW_DIGEST_INVALID')
    bounds=meta.get('navigation_bounds')
    need(isinstance(bounds,dict) and set(bounds)=={'xbs_main_title',*('xbs_nav_'+k for k in NAV)},'GX10_CAPTURE_BOUNDS_INVENTORY')
    for k,rect in bounds.items():
        need(type(rect) is list and len(rect)==4 and all(type(x) is int for x in rect),'GX10_CAPTURE_BOUNDS_INVALID')
        x1,y1,x2,y2=rect
        need(0<=x1<x2<=w and 0<=y1<y2<=h and (k=='xbs_main_title' or (x2-x1>=144 and y2-y1>=144)), 'GX10_CAPTURE_BOUNDS_INVALID')
    need(meta.get('masked_top_px',0)>=180 and meta.get('masked_bottom_px',0)>=180
         and meta.get('ancillary_png_metadata_removed') is True,'GX10_CAPTURE_REDACTION_WEAKENED')
    need(isinstance(meta.get('redacted_sha256'),str) and re.fullmatch('[0-9a-f]{64}',meta['redacted_sha256']),
         'GX10_CAPTURE_DIGEST_INVALID')


def validate_matrix(captures: list[dict]) -> dict:
    expected={(w,t) for w in WIDTHS for t in ('light','dark')}
    seen=set();hashes=set()
    for meta in captures:
        key=(meta.get('width_dp'),meta.get('theme_requested'))
        need(key in expected and key not in seen,'GX10_CAPTURE_DUPLICATE_OR_UNKNOWN_VARIANT')
        validate_capture(meta,*key)
        digest=meta['redacted_sha256']
        need(digest not in hashes,'GX10_IDENTICAL_REDACTED_SCREENSHOTS')
        seen.add(key);hashes.add(digest)
    need(seen==expected,'GX10_CAPTURE_MATRIX_INCOMPLETE')
    return {'verified_portrait_variants':len(seen),'distinct_redacted_digests':len(hashes),
            'theme_pixels_verified':True,'physical_qualified':False,
            'full_locale_translation_qualified':False,'landscape_screenshot_qualified':False,
            'original_cases_accepted':[]}


def validate_presentation_source(root: Path) -> None:
    path=root/'android-app/app/src/androidTest/java/io/github/mikeceead/xbscompanion'/f'{CLASS}.java'
    java=path.read_text(encoding='utf8')
    methods=re.findall(r'@Test\s+public\s+void\s+(\w+)\s*\(',java)
    need(len(methods)==len(METHODS) and set(methods)==METHODS,'GX10_INSTRUMENTATION_INVENTORY_DRIFT')
    host=(root/'scripts/ci/gx03_emulator_host.py').read_text(encoding='utf8')
    need("gradle_test('io.github.mikeceead.xbscompanion.Gx10PresentationInstrumentedTest')" in host
         and "report['gx10_presentation_assertions']=junit_receipt(RESULTS" in host
         and "report['gx10_capture_matrix']=validate_matrix(captures)" in host,
         'GX10_ACTUAL_HOSTED_ASSERTIONS_NOT_REQUIRED')
    workflow=(root/'.github/workflows/devtool-xbs-ci.yml').read_text(encoding='utf8')
    need('python3 -B scripts/ci/test_gx10_presentation.py' in workflow,'GX10_HOSTED_NEGATIVE_TESTS_NOT_REQUIRED')


def validate_layout_repair(root: Path) -> None:
    """Retain strict instrumentation and verify the UI repair is connected to production."""
    base=root/'android-app/app/src/main/java/io/github/mikeceead/xbscompanion'
    activity=(base/'MainActivity.java').read_text(encoding='utf8')
    tile=(base/'XbsTileSettingsActivity.java').read_text(encoding='utf8')
    tests=(root/'android-app/app/src/androidTest/java/io/github/mikeceead/xbscompanion'/f'{CLASS}.java').read_text(encoding='utf8')
    # Real UI acceptance is still the hosted test; these source checks prevent
    # accidentally replacing the fix with tolerance or a programmatic action.
    need('row.setBaselineAligned(false)' in activity and 'new LinearLayout.LayoutParams(0,-1,1)' in activity,
         'GX10_HF01_NAV_LAYOUT_DRIFT')
    need('frame.addView(scroll,new FrameLayout.LayoutParams(-1,-1))' in tile and 'setXbsContentView(frame)' in tile
         and 'setXbsContentView(scroll)' not in tile,'GX10_HF01_INSET_VIEWPORT_DRIFT')
    need('view.getWidth(),actual.width()' in tests and 'view.getHeight(),actual.height()' in tests
         and 'assertTextFits(nav)' in tests and 'nav.getWidth()>=minimum && nav.getHeight()>=minimum' in tests,
         'GX10_HF01_STRICT_GEOMETRY_WEAKENED')
    need('onView(withId(R.id.xbs_tile_settings_scroll)).perform(swipeUp())' in tests
         and 'assertTextFits(reset)' in tests and 'scroll.canScrollVertically(1)' in tests
         and 'onView(withId(R.id.xbs_tile_settings_reset)).check(matches(withText("Review local tile shortcut reset"))).perform(click())' in tests,
         'GX10_HF01_REAL_SCROLL_OR_CLICK_MISSING')
    need('assertEquals(before,RunStore.all(app).size())' in tests and 'assertEquals(saver,XbsTileConfiguration.selected' in tests
         and 'assertEquals(profiles,XbsTileConfiguration.selected' in tests,'GX10_HF01_NO_DISPATCH_ASSERTION_WEAKENED')


def validate_handoff(root: Path) -> None:
    import json
    path=root/'docs/engineering/XBS_R08_R10_UI01_EVIDENCE.json'
    need(path.is_file() and not path.is_symlink() and path.stat().st_size<1_000_000,'GX10_HANDOFF_MISSING')
    raw=path.read_bytes()
    need(sha(raw)=='e6d135f783bbda024abcf687e0646552cbe8db8d533f7a7f552ea21ea749a83b','GX10_HISTORICAL_OBSERVATION_DRIFT')
    data=json.loads(raw)
    matrix=json.loads((root/'docs/engineering/XBS_TEST_MATRIX.json').read_bytes())
    original={x['id']:x for x in matrix['cases']}
    need(len(original)==131 and data['original_cases_accepted']==[] and
         all(original[k]['required_assertion']==v['required_assertion'] and
             v['acceptance']=='PARTIAL_CANDIDATE_ONLY' for k,v in data['case_links'].items()),'GX10_ORIGINAL_MAPPING_DRIFT')
