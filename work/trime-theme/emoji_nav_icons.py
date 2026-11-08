"""Compact, single-colour navigation icons in our own outline keyboard font."""


def build_nav_icons(glyph):
    # U+E011–U+E01A in order: recent, people, nature, food, activity,
    # travel, objects, symbols, flags, search. Coordinates are on a 24-unit grid.
    def shapes(draw):
        glyph=draw
        return [
        glyph(lines=[[(12,6),(12,12),(16,15)]], circles=[(12,12,9,False)]),
        glyph(lines=[[(6.5,13),(8,15.5),(10,17),(14,17),(16,15.5),(17.5,13)]],
              circles=[(12,12,9,False),(8.5,9,1,True),(15.5,9,1,True)]),
        glyph(lines=[[(3,18),(4,12),(8,7),(14,5),(21,3),(20,11),(18,16),(14,19),(8,20),(4,19)],
                     [(3,22),(16,9)]]),
        glyph(lines=[[(5,3),(5,9),(6,11),(10,11),(11,9),(11,3)],[(8,3),(8,21)],
                     [(18,21),(18,3),(15,6),(14,10),(14,13),(18,13)]]),
        glyph(lines=[[(3,12),(21,12)],[(12,3),(12,21)],[(5,5),(8,8),(9,12),(8,16),(5,19)],
                     [(19,5),(16,8),(15,12),(16,16),(19,19)]], circles=[(12,12,9,False)]),
        glyph(lines=[[(3,15),(3,11),(5,10),(7,5),(17,5),(19,10),(21,11),(21,16),(3,16),(3,15)],
                     [(5,10),(19,10)]], circles=[(7,17,2,False),(17,17,2,False)]),
        glyph(lines=[[(9,17),(9,15),(7,13),(6,10),(6.5,7),(8.5,4.5),(12,3.5),(15.5,4.5),(17.5,7),
                      (18,10),(17,13),(15,15),(15,17),(9,17)],[(9,19),(15,19)],[(10.5,21),(13.5,21)]]),
        glyph(lines=[[(12,20),(5,14),(3.5,11),(3.5,8),(5,5.5),(8,4.5),(10,5),(12,7),(14,5),
                      (16,4.5),(19,5.5),(20.5,8),(20.5,11),(19,14),(12,20)]]),
        glyph(lines=[[(5,22),(5,3),(8,3),(12,4.5),(15,5),(19,4),(19,14),(16,15),(12,14.5),
                      (8,13),(5,13)]]),
        glyph(lines=[[(15,15),(21,21)]], circles=[(10.5,10.5,6.7,False)]),
        ]
    icons=shapes(glyph)
    # U+E01B: use the same 1.3-unit stroke, advance and centering as the
    # main keyboard controls. No Android fallback font or text arrow.
    icons.append(glyph(lines=[[(11,5),(4,12),(11,19)],[(4,12),(21,12)]]))
    # U+E01C..E024: a small marker underneath the active category. Reusing
    # the exact outline avoids tinting an entire wide key in landscape.
    def selected(lines=(),circles=()):
        return glyph(lines=lines,circles=[*circles,(12,24.5,1.1,True)])
    icons.extend(shapes(selected)[:9])
    return icons
