import idaapi,idc,json
addrs=[0x1403AFA10,0x1401EE2D0,0x1401EE430,0x1401EE910]
out=[]
for start in addrs:
 f=idaapi.get_func(start); end=f.end_ea if f else start+0x100
 lines=[]; ea=start
 while ea<end and len(lines)<120:
  ins=idaapi.insn_t(); n=idaapi.decode_insn(ins,ea)
  if not n: break
  lines.append({'ea':hex(ea),'asm':idc.generate_disasm_line(ea,0) or ''}); ea+=n
 out.append({'start':hex(start),'end':hex(end),'lines':lines})
print(json.dumps(out))
