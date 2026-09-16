import idaapi,idc,idautils,json
for name in ['playerCoord','playerName','npcName','npcCoord','npcCoordFine','playerCoordFine']:
 for s in idautils.Strings():
  if str(s)!=name: continue
  for x in idautils.XrefsTo(s.ea,0):
   f=idaapi.get_func(x.frm)
   if not f: continue
   out=[]
   for ea in range(max(f.start_ea,x.frm-0x80),min(f.end_ea,x.frm+0x120)):
    ins=idaapi.insn_t(); n=idaapi.decode_insn(ins,ea)
    if not n: continue
    text=idc.generate_disasm_line(ea,0) or ''
    if 'lea' in text.lower() and 'sub_' in text:
     out.append({'ea':hex(ea),'asm':text})
   print(json.dumps({'name':name,'ref':hex(x.frm),'func':hex(f.start_ea),'targets':out}))
