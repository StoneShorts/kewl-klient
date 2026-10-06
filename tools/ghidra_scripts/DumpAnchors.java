// DumpAnchors.java -- print the instruction context behind every binding-name anchor.
//
// For each name the client registers (see ANCHORS), this prints:
//   * every instruction that references the name string, with the 48 instructions after it (the
//     registration's argument setup and the call that binds the leaf);
//   * for every code address those instructions load with LEA (the leaf candidates), the first 40
//     instructions at that address.
//
// That is exactly what a person reads to derive an offset, written to one file so it can be diffed
// between builds. DeriveOffsets.java encodes the patterns found this way; when a build changes a
// shape, run this, read the new shape, extend DeriveOffsets. Output: <scriptDir>/../anchors_dump.txt,
// or the path given as the first script argument.
//
//@category 0xClient
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.Reference;
import ghidra.program.util.DefinedDataIterator;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class DumpAnchors extends GhidraScript {

    private static final String[] ANCHORS = {
        "getVarp", "getVarbit", "getTickCount", "getStatEffectiveLevel", "getStatBaseLevel", "getStatXP",
        "isLoggedIn", "getMapCoordinate", "getMapOrigin", "worldToScreenCoord",
        "npcCoord", "playerCoord", "npcName", "playerName", "getNpcObj", "getNpcIdAll", "playerFindSelf",
        "invGetObjId", "invGetNum", "invSize", "ifType", "coord", "getCoord", "worldid",
        "ON_RUN_ENERGY_TRANSMIT", "ON_MINIMENU_CLICK",
    };

    @Override
    public void run() throws Exception {
        long base = currentProgram.getImageBase().getOffset();
        String[] args = getScriptArgs();
        File out = args.length > 0 ? new File(args[0])
                : new File(new File(getSourceFile().getAbsolutePath()).getParentFile().getParentFile(), "anchors_dump.txt");
        MemoryBlock text = currentProgram.getMemory().getBlock(".text");
        try (PrintWriter w = new PrintWriter(out)) {
            w.println("# anchors dump, image base 0x" + Long.toHexString(base));
            for (String anchor : ANCHORS) {
                w.println();
                w.println("######## " + anchor);
                List<Address> strings = findStrings(anchor);
                if (strings.isEmpty()) { w.println("   string NOT FOUND"); continue; }
                for (Address s : strings) {
                    w.println("   string at rva 0x" + Long.toHexString(s.getOffset() - base));
                    Set<Address> leaves = new LinkedHashSet<>();
                    for (Reference r : getReferencesTo(s)) {
                        Instruction ins = getInstructionAt(r.getFromAddress());
                        if (ins == null) continue;
                        w.println("   --- reference at rva 0x" + Long.toHexString(ins.getAddress().getOffset() - base));
                        Instruction cur = ins;
                        for (int i = 0; i < 48 && cur != null; i++) {
                            w.println("      " + fmt(cur, base));
                            for (Reference ref : cur.getReferencesFrom()) {
                                Address to = ref.getToAddress();
                                if (text != null && text.contains(to) && cur.getMnemonicString().equals("LEA")) leaves.add(to);
                            }
                            cur = cur.getNext();
                        }
                    }
                    for (Address leaf : leaves) {
                        w.println("   === leaf candidate rva 0x" + Long.toHexString(leaf.getOffset() - base));
                        Instruction cur = getInstructionAt(leaf);
                        if (cur == null) { disassemble(leaf); cur = getInstructionAt(leaf); }
                        for (int i = 0; i < 40 && cur != null; i++) {
                            w.println("      " + fmt(cur, base));
                            if (cur.getMnemonicString().equals("RET")) break;
                            cur = cur.getNext();
                        }
                    }
                }
            }
        }
        println("wrote " + out.getAbsolutePath());
    }

    private String fmt(Instruction ins, long base) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%08x  ", ins.getAddress().getOffset() - base));
        sb.append(ins.toString());
        for (Reference r : ins.getReferencesFrom()) {
            Address to = r.getToAddress();
            if (to.isMemoryAddress())
                sb.append("   ; -> rva 0x").append(Long.toHexString(to.getOffset() - base));
        }
        return sb.toString();
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
                if (!b.isInitialized()) continue;
                Address a = currentProgram.getMemory().findBytes(b.getStart(), b.getEnd(), pat, null, true, monitor);
                while (a != null) {
                    hits.add(a);
                    a = currentProgram.getMemory().findBytes(a.add(1), b.getEnd(), pat, null, true, monitor);
                }
            }
        }
        return hits;
    }
}
