import idaapi, idc, idautils, json
names = ['coord','playerCoord','playerName','npcCoord','npcName','ifType','getMapOrigin']
out = {}
for target in names:
    hits=[]
    for s in idautils.Strings():
        if str(s) != target: continue
        refs=[]
        for x in idautils.XrefsTo(s.ea, 0):
            f=idaapi.get_func(x.frm)
            start=f.start_ea if f else x.frm
            lines=[]
            for ea in range(max(start, x.frm-0x30), min(x.frm+0x100, x.frm+0x101)):
                if idaapi.is_code(idaapi.get_flags(ea)):
                    lines.append({'ea':hex(ea),'asm':idc.generate_disasm_line(ea,0) or ''})
            refs.append({'xref':hex(x.frm),'function':hex(start),'name':idaapi.get_name(start),'lines':lines[:80]})
        hits.append({'string':hex(s.ea),'refs':refs})
    out[target]=hits
print(json.dumps(out))
