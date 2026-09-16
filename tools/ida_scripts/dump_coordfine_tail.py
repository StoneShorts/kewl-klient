import idaapi,idc,json
start=0x1403AF3D0; end=0x1403AF449
out=[]
ea=start
while ea<end:
 ins=idaapi.insn_t(); n=idaapi.decode_insn(ins,ea)
 if not n: break
 out.append({'ea':hex(ea),'asm':idc.generate_disasm_line(ea,0) or ''}); ea+=n
print(json.dumps(out))
