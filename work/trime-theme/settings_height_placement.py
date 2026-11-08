"""Two five-column settings pages with transparent tiles and a dot-only paging strip."""

def height_icon(glyph):
    return glyph(lines=[[(4,2.7),(20,2.7)],[(4,21.3),(20,21.3)],
                        [(12,6),(12,18)],[(8.7,9.3),(12,6),(15.3,9.3)],
                        [(8.7,14.7),(12,18),(15.3,14.7)]])

def settings_icons(glyph):
    return [
        # E026 globe / language
        glyph(circles=[(12,12,9,False)],lines=[[(3,12),(21,12)],[(12,3),(9,6),(8,12),(9,18),(12,21),(15,18),(16,12),(15,6),(12,3)]]),
        # E027 numeric keypad
        glyph(circles=[(x,y,1.45,True) for x in (6,12,18) for y in (5,11,17)]+[(12,21,.8,True)]),
        # E028 symbol brackets
        glyph(lines=[[(8,6),(3,12),(8,18)],[(16,6),(21,12),(16,18)],[(14,4),(10,20)]]),
        # E029 translate (文 + a small A); fixed mark, independent of switch state
        glyph(lines=[[(2,6),(14,6)],[(8,3),(8,6)],[(4,9),(6,12),(12,16)],[(12,7),(10,11),(7,14),(2,17)],
                     [(12,20),(17.5,8),(23,20)],[(14.5,15.5),(20.5,15.5)]]),
        # E02A quotation marks
        glyph(lines=[[(9,5),(6,8),(4.5,12),(4.5,17),(9.5,17),(9.5,12),(4.5,12)],
                     [(19,5),(16,8),(14.5,12),(14.5,17),(19.5,17),(19.5,12),(14.5,12)]]),
        # E02B emoji suggestion bubble
        glyph(lines=[[(5,3.5),(19,3.5),(21,5.5),(21,15),(19,17),(12,17),(6,21),(6,17),(4,17),(3,15),(3,6),(5,3.5)],
                     [(8,11),(10,13),(13,13.5),(16,11)]],circles=[(8.8,8.2,.8,True),(15.4,8.2,.8,True)]),
        # E02C one focused candidate
        glyph(lines=[[(3,8),(3,3),(8,3)],[(16,3),(21,3),(21,8)],[(3,16),(3,21),(8,21)],[(16,21),(21,21),(21,16)],
                     [(8,8),(16,8),(16,16),(8,16),(8,8)]]),
        # E02D full/half width: a measurement between two edges
        glyph(lines=[[(3,5),(3,19)],[(21,5),(21,19)],[(6,12),(18,12)],[(9,9),(6,12),(9,15)],[(15,9),(18,12),(15,15)]]),
    ]

PAGES=(
    (('CleanEmojiPanel','表情',0xE000),('CleanClipboard','剪贴板',0xE002),('CleanPanelCursor','光标',0xE004),
     ('CleanRulesHub','本地规则',0xE00E),('CleanPhrases','常用语',0xE003),
     ('CleanHeightOpen','键盘高度',0xE025),('CleanKeyboardChoice','键盘选择',0xE001),
     ('CleanMode','输入语言',0xE026),('CleanScript','简繁转换',0xE029),('CleanSingle','单字优先',0xE02C)),
    (('CleanPanelTheme','主题',0xE00A),('CleanPanelColor','配色',0xE00B),('CleanPanelSound','按键音效',0xE00C),
     ('CleanPanelSchema','输入方案',0xE00D),('CleanHalf','全角半角',0xE02D)),
)
NOOP='CleanPanelNoop'
GESTURES=dict(swipe_left='CleanSettingsPage2',swipe_right='CleanSettingsPage1',
              swipe_up=NOOP,swipe_down=NOOP)

def install_settings_height_placement(theme):
    actions,boards=theme['preset_keys'],theme['preset_keyboards']
    for palette in theme['preset_color_schemes'].values():
        palette.update(settings_card='0x00000000',settings_pressed=palette['hilited_key_back_color'],
                       settings_ink=palette['panel_foreground'],settings_caption=palette['panel_caption'],
                       settings_empty='0x00000000')
    for page in PAGES:
        for action,caption,cp in page:
            # Keep the original action (including language/switch semantics) independent
            # of the tile's graphic. Trime otherwise replaces the icon with states[0/1].
            assert action in actions,action
            actual={k:v for k,v in actions[action].items() if k!='states'}
            actions['Tile'+action]={**actual,'label':chr(cp),'functional':False,'preview':''}
    for compact,stem in ((False,''),(True,'compact_')):
        tile_height=50 if compact else 60
        spacer_height=16 if compact else 20
        for page_no,(page,suffix) in enumerate(zip(PAGES,('settings_clean','settings_clean_more'))):
            board=boards[stem+suffix]
            tiles=[]
            for action,caption,cp in page:
                tiles.append(dict(click='Tile'+action,width=20,hint=caption,label_symbol=' ',
                    long_click='CleanUserDictionary' if action=='CleanRulesHub' else NOOP,
                    key_text_size=20 if compact else 23,symbol_text_size=10 if compact else 12,
                    key_text_offset_y=-7 if compact else -9,key_hint_offset_y=-9 if compact else -14,
                    key_back_color='settings_card',hilited_key_back_color='settings_pressed',
                    key_text_color='settings_ink',hilited_key_text_color='settings_ink',
                    key_symbol_color='settings_caption',hilited_key_symbol_color='settings_caption',
                    round_corner=9,**GESTURES))
            # Keep the second row and paging rail at the same coordinates on
            # both pages. These transparent slots have no tap/hold/swipe action.
            for _ in range(10-len(tiles)):
                tiles.append(dict(click=NOOP,width=20,label=' ',label_symbol=' ',
                    long_click=NOOP,key_back_color='settings_empty',
                    hilited_key_back_color='settings_empty',key_text_color='settings_empty',
                    hilited_key_text_color='settings_empty',
                    **{gesture:NOOP for gesture in GESTURES}))
            # Preserve the old total height and paging-rail position: the space
            # removed from the two tile rows is shared above and below the grid.
            # The second page keeps its empty second row in the same position.
            spacer=dict(click=NOOP,width=100,height=spacer_height,label=' ',label_symbol=' ',
                long_click=NOOP,key_back_color='settings_empty',
                hilited_key_back_color='settings_empty',key_text_color='settings_empty',
                hilited_key_text_color='settings_empty',**GESTURES)
            tiles.insert(0,dict(spacer))
            tiles.append(dict(spacer))
            # No setting/action is reachable from this whole strip, even if a
            # below-threshold drag is delivered as CLICK or LONG_CLICK.
            for target,width,label in ((NOOP,100,'○  ●' if page_no else '●  ○'),):
                token=f'SettingsRail{page_no}_{width}_{len(tiles)}'
                actions[token]={**actions[target],'label':label,'functional':False,'preview':''}
                tiles.append(dict(click=token,label_symbol=' ',width=width,height=32 if compact else 36,
                    key_text_size=8,long_click=NOOP,key_back_color='keyboard_back_color',
                    hilited_key_back_color='keyboard_back_color',key_text_color='panel_foreground',
                    hilited_key_text_color='panel_foreground',**GESTURES))
            board.update(keys=tiles,height=tile_height,horizontal_gap=5 if compact else 6,
                         vertical_gap=4 if compact else 6,round_corner=9)
