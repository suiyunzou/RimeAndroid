from pathlib import Path
from math import cos, sin, pi, hypot
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from letter_hint_glyphs import build_hints
from emoji_nav_icons import build_nav_icons

ROOT = Path(__file__).resolve().parent
OUT = ROOT.parents[1] / 'outputs'


def glyph(lines=(), circles=()):
    pen = TTGlyphPen(None)

    def polygon(points):
        points = [(round(x * 40), round(800 - y * 40)) for x, y in points]
        pen.moveTo(points[0])
        for point in points[1:]:
            pen.lineTo(point)
        pen.closePath()

    for points in lines:
        for (x1, y1), (x2, y2) in zip(points, points[1:]):
            length = hypot(x2-x1, y2-y1)
            dx, dy = (y2-y1)/length*.65, (x2-x1)/length*.65
            polygon([(x1-dx, y1+dy), (x2-dx, y2+dy), (x2+dx, y2-dy), (x1+dx, y1-dy)])
    for x, y, radius, filled in circles:
        polygon([(x+radius*cos(a*pi/24), y+radius*sin(a*pi/24)) for a in range(48)])
        if not filled:
            polygon([(x+(radius-1.3)*cos(a*pi/24), y+(radius-1.3)*sin(a*pi/24)) for a in reversed(range(48))])
    return pen.glyph()


icons = [
    glyph(lines=[[(7,14),(9,16),(12,17),(15,16),(17,14)]], circles=[(12,12,9,False),(8.5,9,1,True),(15.5,9,1,True)]),
    glyph(lines=[[(6,19),(18,19)]], circles=[(x,y,.9,True) for y in (6,10,14) for x in (5,9,13,17)]),
    glyph(lines=[[(8,5),(5,5),(5,21),(19,21),(19,5),(16,5)],[(8,3),(16,3),(16,7),(8,7),(8,3)],[(8,11),(16,11)],[(8,15),(16,15)],[(8,18),(13,18)]]),
    glyph(lines=[[(12,2),(21,7),(21,17),(12,22),(3,17),(3,7),(12,2)],[(3,7),(12,12),(21,7)],[(12,12),(12,22)]]),
    glyph(lines=[[(12,2),(12,22)],[(2,12),(22,12)],[(8,6),(12,2),(16,6)],[(8,18),(12,22),(16,18)],[(6,8),(2,12),(6,16)],[(18,8),(22,12),(18,16)]]),
    glyph(lines=[[(3,5),(21,5)],[(3,12),(21,12)],[(3,19),(21,19)],[(8,2),(8,8)],[(16,9),(16,15)],[(10,16),(10,22)]]),
    glyph(lines=[[(5,15),(12,8),(19,15)]]),
    glyph(lines=[[(5,9),(12,16),(19,9)]]),
    glyph(lines=[[(3,11),(12,2),(21,11),(16,11),(16,22),(8,22),(8,11),(3,11)]]),
    glyph(lines=[[(2,12),(8,5),(22,5),(22,19),(8,19),(2,12)],[(11,8),(18,16)],[(18,8),(11,16)]]),
    # Theme, color, sound, schema, and dictionary share the existing 1.3-unit stroke.
    glyph(lines=[[(12,3),(7,4),(3,8),(2,13),(4,18),(8,21),(12,21),(14,19),(13,16),(15,14),(19,14),(22,11),(21,7),(17,4),(12,3)]],
          circles=[(7,10,.9,True),(10,6.5,.9,True),(15,7,.9,True),(6,15,.9,True)]),
    glyph(lines=[[(12,3),(12,21)],[(15,5),(15,19)],[(18,7),(18,17)]], circles=[(12,12,9,False)]),
    glyph(lines=[[(3,9),(7,9),(12,5),(12,19),(7,15),(3,15),(3,9)],
                 [(16,8),(18,10),(18,14),(16,16)],[(19,4),(22,8),(23,12),(22,16),(19,20)]]),
    glyph(lines=[[(9,6),(21,6)],[(9,12),(21,12)],[(9,18),(21,18)]],
          circles=[(4,6,.8,True),(4,12,.8,True),(4,18,.8,True)]),
    glyph(lines=[[(12,6),(9,4),(3,4),(3,20),(9,20),(12,22),(15,20),(21,20),(21,4),(15,4),(12,6),(12,22)],
                 [(6,9),(9,9)],[(6,13),(9,13)],[(15,9),(18,9)],[(15,13),(18,13)]]),
]
def centered_comma():
    # A label only: sending the comma to Rime still uses the original action.
    pen = TTGlyphPen(None)
    pen.moveTo((415, 208))
    pen.qCurveTo((469, 283), (473, 324))
    pen.qCurveTo((401, 316), (397, 382))
    pen.qCurveTo((395, 457), (473, 461))
    pen.qCurveTo((561, 465), (558, 371))
    pen.qCurveTo((550, 277), (451, 195))
    pen.closePath()
    return pen.glyph()

icons.append(centered_comma())

def rounded_gear():
    """Narrow rounded teeth, a concentric hub, three short radial webs."""
    pen = TTGlyphPen(None)
    def contour(points):
        def midway(a,b): return tuple(round((u+v)/2) for u,v in zip(a,b))
        pen.moveTo(midway(points[-1],points[0]))
        for i,point in enumerate(points):
            pen.qCurveTo(point,midway(point,points[(i+1)%len(points)]))
        pen.closePath()
    def polar(radius,degrees):
        angle=degrees*pi/180
        return (round(480+radius*40*cos(angle)),round(350+radius*40*sin(angle)))
    teeth=[(-13.5,8.7),(-7.5,8.7),(-6.0,9),(-5.0,10.6),(-3.8,10.85),
           (3.8,10.85),(5.0,10.6),(6.0,9),(7.5,8.7),(13.5,8.7)]
    contour([polar(r,90-30*i-d) for i in range(12) for d,r in teeth])
    # Cut arcs out instead of overdrawing rings: one consistent winding,
    # three 2dp-scale spokes closing the space between outer rim and hub.
    for base in (90,210,330):
        arc=[base+8+i*104/18 for i in range(19)]
        contour([polar(6.9,a) for a in arc]+[polar(4.8,a) for a in reversed(arc)])
    contour([polar(2.95,360*i/48) for i in range(48)])
    return pen.glyph()

icons.append(rounded_gear())  # U+E010; preserve all older private-use codepoints.
icons.extend(build_nav_icons(glyph))
from settings_height_placement import height_icon, settings_icons
assert 0xE000+len(icons)==0xE025
icons.append(height_icon(glyph))
icons.extend(settings_icons(glyph))
def filled_shift():
    """Keep the existing outline and fill only its enclosed arrow interior."""
    pen = TTGlyphPen(None)
    icons[8].draw(pen, None)
    vertices = [(3,11),(12,2),(21,11),(16,11),(16,22),(8,22),(8,11)]
    points = [(round(x*40), round(800-y*40)) for x,y in reversed(vertices)]
    pen.moveTo(points[0])
    for point in points[1:]:
        pen.lineTo(point)
    pen.closePath()
    return pen.glyph()


hint_names = [f'letterHint{i}' for i in range(7)]
names = ['.notdef'] + [f'icon{i}' for i in range(len(icons))] + hint_names + ['shiftFilled']
def build(target):
    fb = FontBuilder(1000, isTTF=True)
    fb.setupGlyphOrder(names)
    cmap = {0xE000+i: names[i+1] for i in range(len(icons))}
    cmap.update({0xE110+i: n for i,n in enumerate(hint_names)})
    # Unicode case conversion in Trime switches this pair when Shift is on.
    # U+E008 remains the original outline; none of the existing mappings move.
    cmap.update({0x0250: 'icon8', 0x2C6F: 'shiftFilled'})
    fb.setupCharacterMap(cmap)
    fb.setupGlyf(dict(zip(names, [glyph()] + icons + build_hints() + [filled_shift()])))
    # Text is centered by advance width in Android. A zero LSB for all icons
    # shifted the actual Shift and Backspace strokes toward the left of the cap.
    metrics = {'.notdef': (960, 0)}
    for name in names[1:]:
        item = fb.font['glyf'][name]
        item.recalcBounds(fb.font['glyf'])
        dx = round((960-item.xMin-item.xMax)/2)
        dy = round((700-item.yMin-item.yMax)/2)
        item.coordinates.translate((dx,dy))
        item.recalcBounds(fb.font['glyf'])
        metrics[name] = (960, item.xMin)
    fb.setupHorizontalMetrics(metrics)
    fb.setupHorizontalHeader(ascent=900, descent=-200)
    fb.setupNameTable({'familyName':'Clean Outline', 'styleName':'Regular', 'uniqueFontIdentifier':'CleanOutline-2.3', 'fullName':'Clean Outline', 'psName':'CleanOutline'})
    fb.setupOS2(sTypoAscender=900, sTypoDescender=-200, usWinAscent=1000, usWinDescent=250)
    fb.setupPost()
    fb.setupMaxp()
    from emoji_name_hints import add_name_glyphs
    add_name_glyphs(fb.font)
    fb.save(target)


if __name__ == '__main__':
    build(OUT / 'wechat-outline.ttf')
