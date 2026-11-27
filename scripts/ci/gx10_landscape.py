"""Actual API36 landscape capture evidence, independent of portrait and physical acceptance."""
from __future__ import annotations
from gx03_evidence import need
from gx10_presentation import WIDTHS, validate_capture

VARIANTS=frozenset((w,t,100) for w in WIDTHS for t in ('light','dark')) | {(393,'light',130)}


def validate_landscape_capture(meta: dict, short_edge: int, theme: str, font_percent: int) -> None:
    need((short_edge,theme,font_percent) in VARIANTS,'GX10_LANDSCAPE_UNKNOWN_VARIANT')
    h,w=WIDTHS[short_edge]
    need((meta.get('width_px'),meta.get('height_px'),meta.get('density_dpi'),meta.get('short_edge_dp'))
         ==(w,h,480,short_edge),'GX10_LANDSCAPE_DIMENSION_DRIFT')
    need(meta.get('orientation')=='landscape' and meta.get('font_scale_percent')==font_percent
         and meta.get('font_scale_observed_percent')==font_percent,'GX10_LANDSCAPE_CONFIGURATION_DRIFT')
    need(meta.get('masked_left_px',0)>=180 and meta.get('masked_right_px',0)>=180
         and meta.get('navigation_focusability_verified') is True,'GX10_LANDSCAPE_SAFETY_OR_ACCESSIBILITY_MISSING')
    # Reuse all portrait evidence predicates on transposed geometry. No predicate
    # is waived; transpose coordinates and dimensions, retaining the original digest.
    checked=dict(meta,width_px=h,height_px=w,width_dp=short_edge)
    bounds=meta.get('navigation_bounds')
    need(isinstance(bounds,dict),'GX10_LANDSCAPE_BOUNDS_MISSING')
    rotated={}
    for key,rect in bounds.items():
        need(type(rect) is list and len(rect)==4,'GX10_LANDSCAPE_BOUNDS_INVALID')
        rotated[key]=[rect[1],rect[0],rect[3],rect[2]]
    checked['navigation_bounds']=rotated
    validate_capture(checked,short_edge,theme)


def validate_landscape_matrix(captures: list[dict]) -> dict:
    seen=set(); digests=set()
    for meta in captures:
        key=(meta.get('short_edge_dp'),meta.get('theme_requested'),meta.get('font_scale_percent'))
        need(key in VARIANTS and key not in seen,'GX10_LANDSCAPE_DUPLICATE_OR_UNKNOWN_VARIANT')
        validate_landscape_capture(meta,*key)
        digest=meta['redacted_sha256']
        need(digest not in digests,'GX10_LANDSCAPE_IDENTICAL_SCREENSHOTS')
        seen.add(key);digests.add(digest)
    need(seen==VARIANTS,'GX10_LANDSCAPE_MATRIX_INCOMPLETE')
    return {'verified_landscape_variants':len(seen),'distinct_redacted_digests':len(digests),
            'measured_accessible_navigation':True,'large_font_variant_verified':True,
            'full_talkback_qualified':False,'physical_qualified':False,'original_cases_accepted':[]}
