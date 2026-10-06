// DeriveOffsets.java -- derive 0xClient's offsets from a client build, mechanically, with evidence.
//
// Run by tools/update/update.py through Ghidra headless:
//
//   analyzeHeadless <proj> osrs -import osclient.exe -scriptPath tools/ghidra_scripts \
//       -postScript DeriveOffsets.java <out.json>
//
// THE METHOD is the one every number in client/offsets.hpp was found with by hand: anchor on a name
// the client gives one of its own Lua bindings ("getVarp", "npcCoord", "worldToScreenCoord"...),
// walk from the string to the registration that binds it, from the registration to the LEAF the
// binding calls, and read the displacement off the instruction that touches the field. No byte
// patterns: a string survives a rebuild; a byte pattern does not.
//
// Three registration shapes carry the leaf (seen identical on client-240-6 and client-241-3):
//
//   ClientState thunks   LEA RDX,[name]; CALL assign; LEA R8,[leaf]; CALL T1(wrapper, state, leaf)
//   specialised T1       LEA RDX,[name]; CALL assign; CALL T1 -- and inside T1: MOV [wrapper+0x10],leaf
//   ScriptOps            LEA RDX,[name]; CALL assign; LEA RDX,[leaf]; CALL register
//   closure before name  LEA RAX,[leaf]; MOV [stack],RAX; ... LEA RAX,[name]        (getMapCoordinate)
//
// Every value written carries the anchor, the leaf address and the instruction it was read from, so
// a wrong number can be argued with. Anything a rule cannot establish is simply absent from the
// output: update.py then carries the previous build's value and LABELS it carried. Never guess here.
//
// A debug listing of every leaf this script looked at goes next to the output (<out>.debug.txt);
// when a build changes a shape, that listing is what to read to extend the rules below.
//
//@category 0xClient
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.Reference;
import ghidra.program.util.DefinedDataIterator;

import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DeriveOffsets extends GhidraScript {

    private long base;
    private MemoryBlock text;
    private final Map<String, Map<String, Object>> out = new LinkedHashMap<>();
    private final StringBuilder debug = new StringBuilder();
    private final List<String> notes = new ArrayList<>();

    // --- a memory operand, parsed from Ghidra's text: [BASE + INDEX*SCALE + DISP]
    static final class Mem {
        String base = "", index = "";
        long scale = 1, disp = 0;
        boolean hasDisp;
        Address absolute; // RIP-relative / absolute, resolved by Ghidra
    }

    @Override
    public void run() throws Exception {
        base = currentProgram.getImageBase().getOffset();
        text = currentProgram.getMemory().getBlock(".text");
        String[] args = getScriptArgs();
        File outFile = args.length > 0 ? new File(args[0])
                : new File(new File(getSourceFile().getAbsolutePath()).getParentFile().getParentFile(), "derived.json");

        try { derive(); } catch (Exception e) { notes.add("derivation aborted: " + e); printerr("" + e); }

        String sha = sha256(currentProgram.getExecutablePath());
        try (PrintWriter w = new PrintWriter(outFile, "UTF-8")) {
            w.println("{");
            w.println("  \"source\": \"DeriveOffsets.java\",");
            w.println("  \"sha256\": \"" + sha + "\",");
            w.println("  \"image_base\": " + base + ",");
            w.println("  \"notes\": \"" + esc(String.join(" | ", notes)) + "\",");
            w.println("  \"offsets\": {");
            int i = 0;
            for (Map.Entry<String, Map<String, Object>> e : out.entrySet()) {
                Object v = e.getValue().get("value");
                w.print("    \"" + e.getKey() + "\": {\"value\": " + v + ", \"evidence\": \"" + esc("" + e.getValue().get("evidence")) + "\"}");
                w.println(++i < out.size() ? "," : "");
            }
            w.println("  }");
            w.println("}");
        }
        try (PrintWriter w = new PrintWriter(new File(outFile.getPath() + ".debug.txt"), "UTF-8")) {
            w.print(debug);
        }
        println("wrote " + outFile.getAbsolutePath() + " (" + out.size() + " offsets)");
    }

    // ===========================================================================================
    // The rules. Each one is a few lines because the work is in the helpers below.
    // ===========================================================================================
    private void derive() {
        // ---- the registration that carries "getVarp" IS the build fingerprint
        Instruction varpRef = firstRef("getVarp");
        if (varpRef != null) {
            Function f = getFunctionContaining(varpRef.getAddress());
            if (f != null) put("BUILD_ID", rva(f.getEntryPoint()), "entry of the ClientState registration, the function that references \"getVarp\"");
        }

        // ---- varps: getVarp leaf is `mov rax,[rip+VARP]; movsxd rcx,edx; mov eax,[rax+rcx*4]`
        List<Instruction> varp = leaf("getVarp");
        Instruction ld = firstRipLoad(varp);
        if (ld != null) put("VARP_ARRAY_PTR", rva(ripTarget(ld)), "getVarp leaf: " + at(ld));

        // ---- the varbit decoder: getVarbit leaf is a thunk `mov ecx,edx; jmp DECODER`
        List<Instruction> varbit = leaf("getVarbit");
        Address jmp = thunkTarget(varbit);
        if (jmp != null) put("GET_VARBIT", rva(jmp), "getVarbit leaf is a thunk; its jump target: " + at(varbit.get(varbit.size() - 1)));

        // ---- the client object and the three skill arrays
        for (String[] s : new String[][]{{"getStatEffectiveLevel", "SKILL_EFFECTIVE"}, {"getStatBaseLevel", "SKILL_BASE"}, {"getStatXP", "SKILL_XP"}}) {
            List<Instruction> l = leaf(s[0]);
            Instruction c = firstRipLoad(l);
            if (c != null && !out.containsKey("CLIENT_OBJ_PTR"))
                put("CLIENT_OBJ_PTR", rva(ripTarget(c)), s[0] + " leaf loads the client object: " + at(c));
            Instruction idx = firstScaledLoad(l, 4);
            if (idx != null) put(s[1], mem(idx).disp, s[0] + " leaf reads the array: " + at(idx));
        }
        long client = val("CLIENT_OBJ_PTR");

        // ---- game state: isLoggedIn leaf compares the state dword to 30
        List<Instruction> logged = leaf("isLoggedIn");
        Instruction cmp30 = firstMatch(logged, "CMP", m -> m.hasDisp && m.disp > 0x100, "0x1e");
        if (cmp30 != null) {
            put("GAME_STATE", mem(cmp30).disp, "isLoggedIn leaf compares it to 30: " + at(cmp30));
            long cycle = mem(cmp30).disp + 4;
            Instruction inc = findInc(cycle);
            if (inc != null) put("CYCLE", cycle, "GAME_STATE+4, confirmed by the per-frame increment " + at(inc));
            else notes.add("CYCLE: no `inc dword ptr [reg+0x" + Long.toHexString(cycle) + "]` found; not derived");
        }

        // ---- the scene pointer: getMapCoordinate leaf is `mov rax,[rip+client]; mov r8,[rax+SCENE]; mov eax,[r8+0x18]`
        List<Instruction> mapc = leaf("getMapCoordinate");
        Instruction scene = loadAfterClient(mapc, client, 0x1000);
        if (scene != null) put("SCENE", mem(scene).disp, "getMapCoordinate leaf: " + at(scene));

        // ---- the world map object and its origin
        List<Instruction> origin = leaf("getMapOrigin");
        Instruction wm = loadAfterClient(origin, client, 0x1000);
        if (wm != null) {
            put("WORLD_MAP", mem(wm).disp, "getMapOrigin leaf: " + at(wm));
            Instruction movsd = firstMatch(origin, "MOVSD", m -> m.hasDisp && m.disp > 0x1000, null);
            if (movsd == null) movsd = firstMatch(origin, "MOV", m -> m.hasDisp && m.disp > 0x1000 && !m.base.isEmpty() && !m.base.equals(mem(wm).base), null);
            if (movsd != null) {
                long o = mem(movsd).disp;
                put("WM_ORIGIN_LEVEL", o, "getMapOrigin leaf reads the MapCoord: " + at(movsd));
                put("WM_ORIGIN_X", o + 4, "MapCoord {level, x, z}: origin + 4");
                put("WM_ORIGIN_Z", o + 8, "MapCoord {level, x, z}: origin + 8");
                put("WM_CENTRE_X", o + 0xC, "the centre pair sits right after the MapCoord (origin + 0xC), same layout as 240-6");
                put("WM_CENTRE_Z", o + 0x10, "origin + 0x10");
            }
        }

        // ---- world->screen: the Graphics usertype's worldToScreenCoord leaf is the projection itself
        Address w2s = leafAddress("worldToScreenCoord");
        if (w2s != null) {
            put("WORLD_TO_SCREEN", rva(w2s), "Graphics.worldToScreenCoord leaf (stored at wrapper+0x10 by its T1)");
            // The camera: three consecutive ints the projection reads off the client object and
            // subtracts from the fine input. The view object is the first qword it takes off the
            // client (a small displacement).
            List<Instruction> body = listing(w2s, 600);
            dump("worldToScreenCoord body", body);
            TreeMap<Long, Instruction> reads = new TreeMap<>();
            Instruction view = null;
            for (Instruction i : body) {
                if (!i.getMnemonicString().equals("MOV")) continue;
                Mem m = memOf(i);
                if (m == null || !m.hasDisp || !m.index.isEmpty() || !isClientBase(body, m, i)) continue;
                if (i.toString().contains("dword ptr") && m.disp > 0x1000) reads.put(m.disp, i);
                if (view == null && i.toString().contains("qword ptr") && m.disp < 0x1000) view = i;
            }
            for (Long d : reads.keySet()) {
                if (reads.containsKey(d + 4) && reads.containsKey(d + 8)) {
                    put("CAMERA_FINE_X", d, "three consecutive ints the projection reads off the client: " + at(reads.get(d)));
                    put("CAMERA_FINE_H", d + 4, at(reads.get(d + 4)));
                    put("CAMERA_FINE_Y", d + 8, at(reads.get(d + 8)));
                    break;
                }
            }
            if (view != null) put("VIEW_OBJ", mem(view).disp, "the projection's view object, first qword it takes off the client: " + at(view));
        }

        // ---- entities: npcCoord / playerCoord / coord
        List<Instruction> npcCoord = leaf("npcCoord", 260);
        List<Instruction> coord = leaf("coord", 260);
        Instruction reg = firstMatch(npcCoord, "LEA", m -> m.hasDisp && m.disp > 0x1000 && m.index.isEmpty(), null);
        if (reg != null && isClientReg(npcCoord, reg)) {
            long r = mem(reg).disp;
            put("REGISTRY_MAP", r, "npcCoord leaf hands the registry map (client+disp) to the lookup: " + at(reg));
            put("REGISTRY_GROUPS", r + 0x20, "map+0x20 (group head array), layout as on 240-6");
            put("REGISTRY_GROUP_COUNT", r + 0x28, "map+0x28 (group count), layout as on 240-6");
        }
        // npcCoord returns {scene+0x18, entity+X, entity+Y}: the X read is stored to result+4 and
        // the Y read to result+8, so the store that follows each load names the field.
        for (int k = 0; k + 1 < npcCoord.size(); k++) {
            Instruction rd = npcCoord.get(k), st = npcCoord.get(k + 1);
            Mem a = memOf(rd), b = memOf(st);
            if (a == null || b == null || !rd.getMnemonicString().equals("MOV") || !st.getMnemonicString().equals("MOV")) continue;
            if (!rd.toString().contains("dword ptr") || !a.hasDisp || a.disp < 0x100 || !st.getDefaultOperandRepresentation(0).contains("[")) continue;
            if (b.hasDisp && b.disp == 4 && !out.containsKey("ENTITY_SCENE_X")) put("ENTITY_SCENE_X", a.disp, "npcCoord leaf reads it into the result's x slot: " + at(rd));
            if (b.hasDisp && b.disp == 8 && !out.containsKey("ENTITY_SCENE_Y")) put("ENTITY_SCENE_Y", a.disp, "npcCoord leaf reads it into the result's y slot: " + at(rd));
        }
        Set<Long> npcFields = dwordFields(npcCoord, 0x100, 0x1000);
        Set<Long> coordFields = dwordFields(coord, 0x100, 0x1000);
        long ex = val("ENTITY_SCENE_X"), ey = val("ENTITY_SCENE_Y");
        for (Long d : coordFields) if (!npcFields.contains(d) && d != ex && d != ey) { put("ENTITY_PLANE_COORD", d, "the one dword the coord leaf reads off the entity that npcCoord does not (its level)"); break; }
        for (Long d : npcFields) if (coordFields.contains(d) && d != ex && d != ey && d < ex) { put("ENTITY_FINE_H", d, "dword both coordinate leaves read below the scene coords: the render height (fine x/y follow at +4/+8)"); put("ENTITY_FINE_X", d + 4, "render position triple {height, x, y}: +4"); put("ENTITY_FINE_Y", d + 8, "render position triple {height, x, y}: +8"); break; }
        if (!out.containsKey("ENTITY_SCENE_X")) notes.add("entity coords: npcCoord fields " + hex(npcFields) + ", coord fields " + hex(coordFields) + " -- no load/store pair found");

        // ---- getNpcIdAll walks the registry (client+GROUPS / client+GROUP_COUNT) and, per group,
        //      reads the NPC uid array (table+UIDS, count at +8)
        List<Instruction> uids = leaf("getNpcIdAll", 300);
        TreeMap<Long, Instruction> creads = new TreeMap<>();
        for (Instruction i : uids) {
            Mem m = memOf(i);
            if (m != null && m.hasDisp && m.index.isEmpty() && m.disp > 0x1000 && i.getMnemonicString().equals("MOV") && isClientBase(uids, m)) creads.put(m.disp, i);
        }
        if (creads.size() >= 2) {
            long g = creads.firstKey(), c = creads.lastKey();
            if (c == g + 8) {
                put("REGISTRY_GROUPS", g, "getNpcIdAll leaf walks the group heads: " + at(creads.get(g)));
                put("REGISTRY_GROUP_COUNT", c, "getNpcIdAll leaf bounds the walk: " + at(creads.get(c)));
                if (!out.containsKey("REGISTRY_MAP")) put("REGISTRY_MAP", g - 0x20, "group heads sit at map+0x20 (layout as on 240-6)");
            }
        }
        TreeMap<Long, Instruction> pairs = new TreeMap<>();
        for (Instruction i : uids) {
            Mem m = memOf(i);
            if (m != null && m.hasDisp && m.index.isEmpty() && m.disp >= 0x10 && m.disp < 0x400 && !m.base.equals("RSP") && !m.base.equals("RBP") || (m != null && m.hasDisp && m.base.equals("RBP") && m.disp > 0x10 && m.disp < 0x400)) pairs.put(m.disp, i);
        }
        for (Long d : pairs.keySet()) {
            Instruction a = pairs.get(d), b = pairs.get(d + 8);
            if (b == null) continue;
            if (a.toString().contains("qword ptr") && b.toString().contains("dword ptr") && b.getMnemonicString().equals("CMP")) {
                put("SCENE_NPC_UIDS", d, "getNpcIdAll leaf reads the uid array: " + at(a));
                put("SCENE_NPC_UID_COUNT", d + 8, "and its count: " + at(b));
                break;
            }
        }

        // ---- the local player handle: playerFindSelf gates on client+LOCAL_PLAYER_IDX
        List<Instruction> self = leaf("playerFindSelf", 120);
        Instruction gate = firstMatch(self, "CMP", m -> m.hasDisp && m.disp > 0x1000, "-0x1");
        if (gate == null) gate = firstMatch(self, "MOV", m -> m.hasDisp && m.disp > 0x1000 && isClientBase(self, m), null);
        if (gate != null && isClientBase(self, mem(gate))) put("LOCAL_PLAYER_IDX", mem(gate).disp, "playerFindSelf leaf: " + at(gate));

        // ---- names. playerName reads the player's name pointer itself; npcName hands the entity to
        //      a resolver, which reads the inline override (entity+OVERRIDE, flag byte at +0x17) and
        //      otherwise the definition (entity+DEF, name NxtString at def+8).
        List<Instruction> npcName = leaf("npcName", 300);
        List<Instruction> playerName = leaf("playerName", 300);
        Instruction pn = firstMatch(playerName, "MOV", m -> m.hasDisp && m.index.isEmpty() && m.disp >= 0x400 && m.disp < 0x1000, null);
        if (pn != null) put("PLAYER_NAME_PTR", mem(pn).disp, "playerName leaf: " + at(pn));
        for (Address callee : callees(npcName)) {
            List<Instruction> body = listing(callee, 400);
            TreeMap<Long, Instruction> fields = new TreeMap<>();
            for (Instruction i : body) {
                Mem m = memOf(i);
                if (m != null && m.hasDisp && m.index.isEmpty() && m.disp >= 0x400 && m.disp < 0x1000 && !m.base.equals("RSP") && !m.base.equals("RBP")) fields.put(m.disp, i);
            }
            if (fields.isEmpty()) continue;
            dump("npcName resolver rva 0x" + Long.toHexString(rva(callee)), body);
            // the definition pointer is loaded as a qword and then dereferenced
            Long def = null;
            for (Map.Entry<Long, Instruction> e : fields.entrySet())
                if (e.getValue().toString().contains("qword ptr") && e.getValue().getMnemonicString().equals("MOV")) def = e.getKey();
            if (def == null) continue;
            put("ENTITY_DEF_PTR", def, "npcName's resolver loads the definition: " + at(fields.get(def)));
            // the inline override: either its data (LEA/MOV at +0) or its SSO flag byte (+0x17) is touched
            for (Map.Entry<Long, Instruction> e : fields.entrySet()) {
                long d = e.getKey();
                if (d == def) continue;
                long start = e.getValue().toString().contains("byte ptr") ? d - 0x17 : d;
                if (start < def && start >= def - 0x40) { put("ENTITY_NAME_OVERRIDE", start, "npcName's resolver checks the inline override: " + at(e.getValue())); break; }
            }
            break;
        }

        // ---- interface manager: ifType's chain starts with a one-instruction getter `mov rax,[rcx+IFACE_MANAGER]`
        List<Instruction> ift = leaf("ifType", 200);
        for (Instruction i : ift) {
            if (!i.getMnemonicString().equals("CALL")) continue;
            Address t = callTarget(i);
            if (t == null) continue;
            List<Instruction> g = listing(t, 4);
            if (g.size() >= 2 && g.get(0).getMnemonicString().equals("MOV") && g.get(1).getMnemonicString().equals("RET")) {
                Mem m = memOf(g.get(0));
                if (m != null && m.hasDisp && m.disp > 0x10000) { put("IFACE_MANAGER", m.disp, "one-instruction getter called from the ifType leaf: " + at(g.get(0))); break; }
            }
        }

        // ---- item containers: the invGetObjId leaf is a Lua trampoline; the one function it calls
        //      directly is the implementation, which walks a global bucket table (count, then array)
        //      The lookup: `mov r10,[COUNT]; ... div r8; mov rdx,[BUCKETS]; mov rax,[rdx+rax*8]`, then
        //      a chain walk `cmp ecx,[rax]; mov rax,[rax+NEXT]`, then `mov rdx,[rax+IDS]; mov rcx,[rax+IDS_END];
        //      sub rcx,rdx`. invGetNum has the same shape over the quantity arrays.
        for (String[] s : new String[][]{{"invGetObjId", "CONTAINER_NODE_IDS", "CONTAINER_NODE_IDS_END"}, {"invGetNum", "CONTAINER_NODE_QTYS", "CONTAINER_NODE_QTYS_END"}}) {
            List<Instruction> inv = leaf(s[0], 200);
            for (Address callee : callees(inv, 2)) {
                List<Instruction> body = listing(callee, 300);
                List<Instruction> globals = new ArrayList<>();
                for (Instruction i : body) {
                    Mem m = memOf(i);
                    if (m != null && m.absolute != null && i.getMnemonicString().equals("MOV") && m.index.isEmpty()) globals.add(i);
                }
                boolean hasDiv = false;
                for (Instruction i : body) if (i.getMnemonicString().equals("DIV") || i.getMnemonicString().equals("IDIV")) hasDiv = true;
                if (globals.size() < 2 || !hasDiv) continue;
                dump(s[0] + " implementation rva 0x" + Long.toHexString(rva(callee)), body);
                if (!out.containsKey("CONTAINER_MASK")) {
                    put("CONTAINER_MASK", rva(mem(globals.get(0)).absolute), "container lookup reads the bucket count first: " + at(globals.get(0)));
                    put("CONTAINER_BUCKETS", rva(mem(globals.get(1)).absolute), "then the bucket array pointer: " + at(globals.get(1)));
                }
                for (int k = 0; k + 2 < body.size(); k++) {
                    Instruction a = body.get(k), b = body.get(k + 1), c = body.get(k + 2);
                    Mem ma = memOf(a), mb = memOf(b);
                    if (ma != null && mb != null && a.getMnemonicString().equals("MOV") && b.getMnemonicString().equals("MOV") && c.getMnemonicString().equals("SUB")
                            && ma.hasDisp && mb.hasDisp && ma.base.equals(mb.base) && mb.disp == ma.disp + 8 && a.toString().contains("qword ptr")) {
                        put(s[1], ma.disp, s[0] + ": the array's first-entry pointer on the node: " + at(a));
                        put(s[2], mb.disp, s[0] + ": one-past-last pointer on the node: " + at(b));
                        break;
                    }
                    if (!out.containsKey("CONTAINER_NODE_NEXT") && a.getMnemonicString().equals("MOV") && ma != null && ma.hasDisp && ma.disp > 0x10
                            && a.getDefaultOperandRepresentation(0).equals(ma.base) && a.toString().contains("qword ptr"))
                        put("CONTAINER_NODE_NEXT", ma.disp, "the chain walk advances through this field: " + at(a));
                }
                break;
            }
        }
    }

    // ===========================================================================================
    // Leaf discovery
    // ===========================================================================================
    private Address leafAddress(String name) {
        for (Address s : findStrings(name)) {
            for (Reference r : getReferencesTo(s)) {
                Instruction ref = getInstructionAt(r.getFromAddress());
                if (ref == null || !ref.getMnemonicString().equals("LEA")) continue;
                // 1. after the name's assign call: ClientState thunks (LEA R8,[leaf]) and ScriptOps
                //    (LEA RDX,[leaf]) hand the leaf to the very next call
                Instruction cur = ref.getNext();
                Instruction assign = null, t1call = null;
                for (int i = 0; i < 12 && cur != null; i++, cur = cur.getNext()) if (cur.getMnemonicString().equals("CALL")) { assign = cur; break; }
                if (assign != null) {
                    cur = assign.getNext();
                    for (int i = 0; i < 10 && cur != null; i++, cur = cur.getNext()) {
                        Address t = textLea(cur);
                        if (t != null) return t;
                        if (cur.getMnemonicString().equals("CALL")) { t1call = cur; break; }
                    }
                }
                // 2. a closure built just before the name: LEA RDX,[leaf]; LEA RCX,[slot]; CALL ctor
                //    (ScriptOps npcName/playerName) or LEA RAX,[leaf]; MOV [slot],RAX (getMapCoordinate)
                //    The closure may be built a few argument-shape setups earlier, so look back up to
                //    40 instructions, but never past another binding name (a string LEA in .rdata).
                Instruction p = ref.getPrevious();
                for (int i = 0; i < 40 && p != null; i++, p = p.getPrevious()) {
                    Address t = textLea(p);
                    if (t != null) return t;
                    if (i > 0 && p.getMnemonicString().equals("LEA") && refersToString(p)) break;
                }
                // 3. a per-binding wrapper constructor that stores the leaf itself at wrapper+0x10
                if (t1call != null) {
                    Address t1 = callTarget(t1call);
                    if (t1 != null) {
                        Address inside = leafStoredByT1(t1);
                        if (inside != null) return inside;
                        debug.append("!! T1 for ").append(name).append(" at rva 0x").append(Long.toHexString(rva(t1))).append(" stores no leaf:\n");
                        for (Instruction i : listing(t1, 40)) debug.append("      ").append(at(i)).append('\n');
                    }
                }
            }
        }
        debug.append("!! no leaf for ").append(name).append('\n');
        return null;
    }

    /**
     * Inside a per-binding wrapper constructor. The leaf is the only code address the constructor
     * takes: `LEA RAX,[leaf]` then either `MOV [RCX+0x10],RAX` or a 16-byte copy through XMM0 into
     * wrapper+0x8..0x18 (both seen). The vtable it also loads lives in .rdata, so "the first .text
     * LEA in a short function" is exactly the leaf. Long functions are not constructors: refuse.
     */
    private Address leafStoredByT1(Address t1) {
        List<Instruction> body = listing(t1, 40);
        if (body.size() >= 40) return null;
        for (Instruction i : body) {
            Address t = textLea(i);
            if (t != null) return t;
        }
        return null;
    }

    private List<Instruction> leaf(String name) { return leaf(name, 64); }

    private List<Instruction> leaf(String name, int max) {
        Address a = leafAddress(name);
        List<Instruction> l = a == null ? new ArrayList<>() : listing(a, max);
        debug.append("\n==== ").append(name).append(a == null ? "  (no leaf)" : "  leaf rva 0x" + Long.toHexString(rva(a))).append('\n');
        for (Instruction i : l) debug.append("   ").append(at(i)).append('\n');
        return l;
    }

    /**
     * The instructions at `a`. When `a` is the entry of a function Ghidra knows, this is the whole
     * function body in address order (a leaf's interesting reads often sit in a branch after its
     * first RET). Otherwise (a label thunk) it is the linear run from `a` to the first RET.
     */
    private List<Instruction> listing(Address a, int max) {
        // A flow walk: fallthrough plus every jump target (never calls), in address order, so a
        // leaf's reads in a branch past its first RET -- or in a block Ghidra split off into its
        // own function -- are still part of the listing. Bounded by `max` instructions.
        TreeMap<Long, Instruction> seen = new TreeMap<>();
        List<Address> work = new ArrayList<>();
        work.add(a);
        while (!work.isEmpty() && seen.size() < max) {
            Address at = work.remove(work.size() - 1);
            Instruction cur = getInstructionAt(at);
            if (cur == null) { disassemble(at); cur = getInstructionAt(at); }
            while (cur != null && seen.size() < max) {
                if (seen.containsKey(cur.getAddress().getOffset())) break;
                seen.put(cur.getAddress().getOffset(), cur);
                String mn = cur.getMnemonicString();
                if (mn.equals("RET") || mn.equals("INT3")) break;
                for (Address t : cur.getFlows()) {
                    if (text.contains(t) && !seen.containsKey(t.getOffset())) {
                        if (mn.equals("CALL")) continue;
                        work.add(t);
                    }
                }
                if (mn.equals("JMP") || mn.startsWith("RET")) break;
                cur = cur.getNext();
            }
        }
        return new ArrayList<>(seen.values());
    }

    /** Direct call targets (in .text, by address) of a listing, in order of first appearance. */
    private List<Address> callees(List<Instruction> l) {
        List<Address> out = new ArrayList<>();
        for (Instruction i : l) {
            if (!i.getMnemonicString().equals("CALL") || i.toString().contains("ptr")) continue;
            Address t = callTarget(i);
            if (t != null && !out.contains(t)) out.add(t);
        }
        return out;
    }

    /** Callees to a depth: depth 1 = direct, depth 2 = also what those call. */
    private List<Address> callees(List<Instruction> l, int depth) {
        List<Address> out = callees(l);
        if (depth > 1) {
            for (Address c : new ArrayList<>(out))
                for (Address d : callees(listing(c, 300), depth - 1)) if (!out.contains(d)) out.add(d);
        }
        return out;
    }

    private void dump(String title, List<Instruction> l) {
        debug.append("\n---- ").append(title).append('\n');
        for (Instruction i : l) debug.append("   ").append(at(i)).append('\n');
    }

    // ===========================================================================================
    // Instruction helpers
    // ===========================================================================================
    private static final Pattern MEM = Pattern.compile("\\[([A-Za-z0-9]+)?(?:\\s*\\+\\s*([A-Z0-9]+)\\*(0x[0-9a-f]+))?(?:\\s*\\+\\s*(-?0x[0-9a-f]+))?\\]");

    private Mem memOf(Instruction i) {
        for (int op = 0; op < i.getNumOperands(); op++) {
            String s = i.getDefaultOperandRepresentation(op);
            if (!s.contains("[")) continue;
            Mem m = mem(i, op, s);
            if (m != null) return m;
        }
        return null;
    }

    private Mem mem(Instruction i) { return memOf(i); }

    private Mem mem(Instruction i, int op, String s) {
        Mem m = new Mem();
        Matcher mt = MEM.matcher(s);
        if (!mt.find()) return null;
        String b = mt.group(1);
        if (b != null && b.startsWith("0x")) {           // absolute / RIP-resolved
            m.absolute = toAddr(Long.parseLong(b.substring(2), 16));
            return m;
        }
        m.base = b == null ? "" : b;
        if (mt.group(2) != null) { m.index = mt.group(2); m.scale = Long.parseLong(mt.group(3).substring(2), 16); }
        if (mt.group(4) != null) {
            String d = mt.group(4);
            boolean neg = d.startsWith("-");
            m.disp = Long.parseLong(d.substring(neg ? 3 : 2), 16) * (neg ? -1 : 1);
            m.hasDisp = true;
        }
        // Ghidra prints `[RAX + 0x3360]` but an index without displacement as `[RAX + RCX*0x4]`
        if (m.base.equals("RSP") || m.base.equals("RBP") && !m.hasDisp) return m;
        return m;
    }

    private interface MemTest { boolean ok(Mem m); }

    private Instruction firstMatch(List<Instruction> l, String mnemonic, MemTest t, String immediate) {
        for (Instruction i : l) {
            if (!i.getMnemonicString().equals(mnemonic)) continue;
            Mem m = memOf(i);
            if (m == null || !t.ok(m)) continue;
            if (immediate != null && !i.toString().endsWith("," + immediate)) continue;
            return i;
        }
        return null;
    }

    private Instruction firstRipLoad(List<Instruction> l) {
        for (Instruction i : l) {
            if (!i.getMnemonicString().equals("MOV")) continue;
            Mem m = memOf(i);
            if (m != null && m.absolute != null && !i.getDefaultOperandRepresentation(0).contains("[")) return i;
        }
        return null;
    }

    private Address ripTarget(Instruction i) { return memOf(i).absolute; }

    private Instruction firstScaledLoad(List<Instruction> l, long scale) {
        for (Instruction i : l) {
            Mem m = memOf(i);
            if (m != null && m.scale == scale && !m.index.isEmpty() && m.hasDisp && i.getMnemonicString().equals("MOV")) return i;
        }
        return null;
    }

    /** The first `MOV reg,[clientReg + disp]` after the instruction that loaded the client pointer. */
    private Instruction loadAfterClient(List<Instruction> l, long clientRva, long minDisp) {
        String creg = null;
        for (Instruction i : l) {
            Mem m = memOf(i);
            if (m == null) continue;
            if (creg == null) {
                if (m.absolute != null && rva(m.absolute) == clientRva && i.getMnemonicString().equals("MOV")) creg = destReg(i);
                continue;
            }
            if (i.getMnemonicString().equals("MOV") && m.base.equals(creg) && m.hasDisp && m.disp >= minDisp && i.getDefaultOperandRepresentation(1).contains("[")) return i;
        }
        return null;
    }

    private boolean isClientReg(List<Instruction> l, Instruction use) {
        Mem m = memOf(use);
        return m != null && isClientBase(l, m);
    }

    /**
     * Does the base register of `m` hold the client object pointer? True when SOME instruction in the
     * listing loads that register from the client global and nothing between that load and `use`
     * writes the register again (calls clobber the volatile ones). `use` null = anywhere.
     */
    private boolean isClientBase(List<Instruction> l, Mem m) { return isClientBase(l, m, null); }

    private boolean isClientBase(List<Instruction> l, Mem m, Instruction use) {
        long client = val("CLIENT_OBJ_PTR");
        int end = use == null ? l.size() : l.indexOf(use);
        if (end < 0) end = l.size();
        boolean live = false;
        for (int k = 0; k < end; k++) {
            Instruction i = l.get(k);
            String mn = i.getMnemonicString();
            Mem x = memOf(i);
            boolean loadsClient = x != null && x.absolute != null && rva(x.absolute) == client && mn.equals("MOV") && destReg(i).equals(m.base);
            if (loadsClient) { live = true; continue; }
            if (use == null) continue;
            if (mn.equals("CALL") && isVolatile(m.base)) live = false;
            else if (!mn.equals("CMP") && !mn.equals("TEST") && !mn.startsWith("J") && i.getNumOperands() > 0
                    && destReg(i).equals(m.base) && !i.getDefaultOperandRepresentation(0).contains("[")) live = false;
        }
        return use == null ? live : live;
    }

    private static boolean isVolatile(String r) {
        return r.equals("RAX") || r.equals("RCX") || r.equals("RDX") || r.equals("R8") || r.equals("R9") || r.equals("R10") || r.equals("R11");
    }

    private Set<Long> dwordFields(List<Instruction> l, long min, long max) {
        Set<Long> s = new LinkedHashSet<>();
        for (Instruction i : l) {
            if (!i.getMnemonicString().equals("MOV")) continue;
            String src = i.getNumOperands() > 1 ? i.getDefaultOperandRepresentation(1) : "";
            if (!src.contains("[") || !i.toString().contains("dword ptr")) continue;
            Mem m = memOf(i);
            if (m != null && m.hasDisp && m.index.isEmpty() && m.disp >= min && m.disp < max && !m.base.equals("RSP") && !m.base.equals("RBP")) s.add(m.disp);
        }
        List<Long> sorted = new ArrayList<>(s);
        java.util.Collections.sort(sorted);
        return new LinkedHashSet<>(sorted);
    }

    private Instruction findInc(long disp) {
        String needle = "0x" + Long.toHexString(disp) + "]";
        Instruction cur = getFirstInstruction();
        while (cur != null) {
            if (cur.getMnemonicString().equals("INC") && cur.toString().contains("dword ptr") && cur.toString().endsWith(needle)) return cur;
            cur = cur.getNext();
        }
        return null;
    }

    private Address thunkTarget(List<Instruction> l) {
        for (int i = 0; i < Math.min(3, l.size()); i++) {
            Instruction ins = l.get(i);
            if (ins.getMnemonicString().equals("JMP")) {
                for (Reference r : ins.getReferencesFrom()) if (text.contains(r.getToAddress())) return r.getToAddress();
            }
        }
        return null;
    }

    /** True when the instruction references defined string data (another binding's name). */
    private boolean refersToString(Instruction i) {
        for (Reference r : i.getReferencesFrom()) {
            var d = getDataAt(r.getToAddress());
            if (d != null && d.hasStringValue()) return true;
        }
        return false;
    }

    private Address textLea(Instruction i) {
        if (!i.getMnemonicString().equals("LEA")) return null;
        for (Reference r : i.getReferencesFrom()) if (text != null && text.contains(r.getToAddress())) return r.getToAddress();
        return null;
    }

    private Address callTarget(Instruction i) {
        for (Reference r : i.getReferencesFrom()) if (r.getReferenceType().isCall() && text.contains(r.getToAddress())) return r.getToAddress();
        return null;
    }

    private String destReg(Instruction i) { return i.getNumOperands() > 0 ? i.getDefaultOperandRepresentation(0) : ""; }

    private Instruction firstRef(String name) {
        for (Address s : findStrings(name)) for (Reference r : getReferencesTo(s)) {
            Instruction i = getInstructionAt(r.getFromAddress());
            if (i != null) return i;
        }
        return null;
    }

    private List<Address> findStrings(String want) {
        List<Address> hits = new ArrayList<>();
        for (var data : DefinedDataIterator.byDataInstance(currentProgram, d -> d.hasStringValue())) {
            String v = data.getDefaultValueRepresentation();
            if (v != null && v.replaceAll("^\"|\"$", "").equals(want)) hits.add(data.getAddress());
        }
        if (hits.isEmpty()) {
            byte[] pat = (want + "\0").getBytes();
            for (MemoryBlock b : currentProgram.getMemory().getBlocks()) {
                if (!b.isInitialized() || b.isExecute()) continue;
                Address a = currentProgram.getMemory().findBytes(b.getStart(), b.getEnd(), pat, null, true, monitor);
                while (a != null) {
                    // only a match at a string START counts (previous byte is NUL or the block start)
                    try { if (a.equals(b.getStart()) || currentProgram.getMemory().getByte(a.subtract(1)) == 0) hits.add(a); } catch (Exception ignored) { }
                    a = currentProgram.getMemory().findBytes(a.add(1), b.getEnd(), pat, null, true, monitor);
                }
            }
        }
        return hits;
    }

    // ===========================================================================================
    // Output helpers
    // ===========================================================================================
    private void put(String name, long value, String evidence) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("value", value);
        e.put("evidence", evidence);
        out.put(name, e);
        println(String.format("  %-24s = 0x%-10x %s", name, value, evidence));
    }

    private long val(String name) {
        Map<String, Object> e = out.get(name);
        return e == null ? -1 : (Long) e.get("value");
    }

    private long rva(Address a) { return a.getOffset() - base; }

    private String at(Instruction i) { return String.format("%06x  %s", rva(i.getAddress()), i.toString()); }

    private String hex(Set<Long> s) { StringBuilder b = new StringBuilder("["); for (Long v : s) b.append("0x").append(Long.toHexString(v)).append(' '); return b.append(']').toString(); }

    private static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " "); }

    private static String sha256(String path) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(Files.readAllBytes(new File(path).toPath()));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
