import idaapi, idc, json
addrs=[0x1403AF120,0x1403AF230]
out=[]
for start in addrs:
    f=idaapi.get_func(start)
    end=f.end_ea if f else start+0x100
    lines=[]
    ea=start
    while ea<end and len(lines)<100:
        lines.append({'ea':hex(ea),'asm':idc.generate_disasm_line(ea,0) or ''})
        n=idaapi.decode_insn(idaapi.insn_t(),ea)
        if not n: break
        ea += n
    out.append({'start':hex(start),'end':hex(end),'name':idaapi.get_name(start),'lines':lines})
print(json.dumps(out))
