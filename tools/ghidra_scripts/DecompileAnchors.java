// DecompileAnchors.java -- decompile the functions behind FindOffsets' hits.
//
// FindOffsets tells you WHERE the registration lives; this prints the decompiled C of those
// registration functions (and, separately, the candidate leaf functions you spotted beside the name
// references), into tools/decompiled/<label>.c. Reading the registration is what turns "a function
// references this string" into "this is the function pointer the client binds to the name".
//
// Usage: analyzeHeadless ... -postScript DecompileAnchors.java f6440 47e4b8 fe8bc
// (bare hex RVAs, image-base-relative). With no arguments it decompiles the functions that contain
// every string-reference site listed below.
//
//@category KewlKlient
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.symbol.Reference;
import ghidra.program.util.DefinedDataIterator;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public class DecompileAnchors extends GhidraScript {

    /** label, string the client binds by. We decompile every function referencing the string. */
    private static final String[][] STRING_ANCHORS = {
        { "getVarp",            "getVarp" },
        { "getVarbit",          "getVarbit" },
        { "getTickCount",       "getTickCount" },
        { "invGetObjId",        "invGetObjId" },
        { "invGetNum",          "invGetNum" },
        { "invSize",            "invSize" },
        { "worldid",            "worldid" },
        { "worldToScreenCoord", "worldToScreenCoord" },
        { "playerCoord",        "playerCoord" },
        { "npcCoord",           "npcCoord" },
        { "getNpcObj",          "getNpcObj" },
        { "npcName",            "npcName" },
        { "ifType",             "ifType" },
        { "getMapCoordinate",   "getMapCoordinate" },
        { "getMapOrigin",       "getMapOrigin" },
        { "getMapTile",         "getMapTile" },
        { "drawOnWorldMap",     "drawOnWorldMap" },
        { "ClientOp",           "ClientOp" },
        { "getPlayerObj",       "getPlayerObj" },
        { "ocName",             "ocName" },
        { "minimenu_click",     "ON_MINIMENU_CLICK" },
        { "minimenu_rebuild",   "ON_MINIMENU_REBUILD" },
    };

    @Override
    public void run() throws Exception {
        long base = currentProgram.getImageBase().getOffset();
        File dir = new File(new File(getSourceFile().getAbsolutePath()).getParentFile().getParentFile(), "decompiled");
        dir.mkdirs();

        DecompInterface d = new DecompInterface();
        d.openProgram(currentProgram);

        // From the command line: bare hex RVAs to decompile directly.
        String[] args = getScriptArgs();
        for (String a : args) {
            long rva = Long.parseLong(a.replaceFirst("^0x", ""), 16);
            Address at = currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(base + rva);
            Function f = getFunctionContaining(at);
            if (f == null) {
                println("no function contains rva 0x" + Long.toHexString(rva));
                continue;
            }
            write(d, dir, f.getName() + "_" + Long.toHexString(rva), f, base);
        }
        if (args.length > 0) return;

        for (String[] anchor : STRING_ANCHORS) {
            List<Function> seen = new ArrayList<>();
            for (var data : DefinedDataIterator.byDataInstance(currentProgram, da -> da.hasStringValue())) {
                String v = data.getDefaultValueRepresentation();
                if (v == null || !anchor[1].equals(v.replaceAll("^\"|\"$", ""))) continue;
                for (Reference r : getReferencesTo(data.getAddress())) {
                    Function f = getFunctionContaining(r.getFromAddress());
                    if (f != null && !seen.contains(f)) seen.add(f);
                }
            }
            for (Function f : seen) {
                write(d, dir, anchor[0] + "_" + Long.toHexString(f.getEntryPoint().getOffset() - base), f, base);
            }
            println(anchor[0] + ": " + seen.size() + " function(s)");
        }
        d.dispose();
    }

    private void write(DecompInterface d, File dir, String name, Function f, long base) {
        DecompileResults r = d.decompileFunction(f, 60, monitor);
        String c = r.getDecompiledFunction() == null ? "// decompile failed" : r.getDecompiledFunction().getC();
        try (PrintWriter w = new PrintWriter(new File(dir, name + ".c"))) {
            w.println("// " + f.getName() + "  entry rva 0x" + Long.toHexString(f.getEntryPoint().getOffset() - base));
            w.print(c);
        } catch (Exception e) {
            println("write failed: " + e);
        }
        println("wrote " + name + ".c");
    }
}
