"""Seven small-size punctuation drawings for keycaps only; popup and commit use Unicode."""
from math import cos, sin, tau
from fontTools.pens.ttGlyphPen import TTGlyphPen


def build_hints():
    def dot(p, x, y, r, hole=False):
        sign = 1 if hole else -1
        points = [(round(x+r*cos(sign*tau*i/32)), round(y+r*sin(sign*tau*i/32))) for i in range(32)]
        p.moveTo(points[0])
        for point in points[1:]: p.lineTo(point)
        p.closePath()

    def path(p, steps):
        p.moveTo(steps[0])
        for value in steps[1:]:
            if len(value)==2: p.lineTo(value)
            else: p.qCurveTo(value[:2],value[2:])
        p.closePath()

    def tail(p,x=0,y=0, flip=False):
        # A comma with a strong round head and a clearly tapered tail.
        def pt(v):
            return tuple(((506-a if flip else a)+x if i%2==0 else (500-a if flip else a)+y) for i,a in enumerate(v))
        path(p, [pt(v) for v in ((180,49),(267,145,275,227),(199,222,176,277),
                (145,348,195,392),(244,438,307,414),(375,391,369,309),
                (364,173,224,28))])

    p=TTGlyphPen(None)
    path(p,[(442,646),(534,646),(523,256),(455,256)])
    dot(p,489,130,53); exclamation=p.glyph()
    p=TTGlyphPen(None)
    path(p,[(231,494),(239,681,460,697),(650,710,670,555),(691,440,565,343),
            (511,301,505,242),(408,242),(400,343,477,414),(579,502,553,555),
            (530,616,449,598),(365,590,350,487)])
    dot(p,456,119,52); question=p.glyph()
    p=TTGlyphPen(None)
    path(p,[(271,504),(312,550,364,518),(509,427,602,306),(630,268,587,233),
            (553,203,517,241),(399,370,286,442),(248,469,271,504)])
    enumeration=p.glyph()
    p=TTGlyphPen(None);dot(p,480,472,58);dot(p,480,206,58);colon=p.glyph()
    p=TTGlyphPen(None); tail(p,0,0,True);tail(p,215,0,True);quotation=p.glyph()
    p=TTGlyphPen(None);tail(p);comma=p.glyph()
    p=TTGlyphPen(None);dot(p,480,350,176);dot(p,480,350,111,True);period=p.glyph()
    return [exclamation,question,enumeration,colon,quotation,comma,period]
