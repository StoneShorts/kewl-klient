import idautils,json
for s in idautils.Strings():
 t=str(s)
 if 'coord' in t.lower() and ('fine' in t.lower() or t in ('coord','npcCoord','playerCoord')):
  print(json.dumps({'ea':hex(s.ea),'text':t}))
