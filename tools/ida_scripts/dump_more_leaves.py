import idaapi,idc,json
addrs=[0x1403BCB70,0x1402276B0]
out=[]
for start in addrs:
 f=idaapi.get_func(start); end=f.end_ea if f else start+0x300
 lines=[]; ea=start
 while ea<end and len(lines)<180:
  ins=idaapi.insn_t(); n=idaapi.decode_insn(ins,ea)
  if not n: break
  lines.append({'ea':hex(ea),'asm':idc.generate_disasm_line(ea,0) or ''}); ea+=n
 out.append({'start':hex(start),'end':hex(end),'lines':lines})
print(json.dumps(out))
