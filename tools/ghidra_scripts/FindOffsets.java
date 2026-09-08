// FindOffsets.java -- re-derive KewlKlient's function RVAs after a game update.
//
// Run it with tools\ghidra_headless.ps1. It writes tools\offsets_found.txt.
//
// THE METHOD, which matters more than this script: anchor on something the client NAMES ITSELF, then
// walk from there. The game ships a hidden Lua binding layer that registers its own functions by name --
// "worldToScreenCoord", "npcCoord", "playerCoord" and dozens more. Those name strings are in the binary
// as plain text. Find the string, find who references it, and you are standing in the registration code
// for the exact function you want.
//
// DO NOT go looking for byte patterns instead. A byte pattern is a guess about code the compiler is free
// to rearrange; it breaks silently on the next build and hands you an address that decompiles to
// something plausible and wrong. The string is what the client itself calls the thing, and it survives.
//
//@category KewlKlient
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.Reference;
import ghidra.program.util.DefinedDataIterator;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public class FindOffsets extends GhidraScript {

    // The binding names worth looking for. Add to this list as you need more of the game.
    //
    // The block below the originals is for the RuneLite shim (Phase D of the port): each anchor is a
    // CANDIDATE spelling of a binding that should sit near the memory the shim needs. The script
    // reports "NOT FOUND" harmlessly for a wrong guess, so cast a wide net -- but verify whatever a
    // hit points at against an observable in-game before trusting it, exactly like every offset in
    // client/offsets.hpp. None of these are confirmed yet.
    private static final String[] ANCHORS = {
        "worldToScreenCoord",   // -> WORLD_TO_SCREEN
        "npcCoord",
        "playerCoord",
        "npcName",
        "getNpcObj",

        // -- varps: the int[] of varp values on the client object. getVarbit's registration should
        //    also lead to the varbit definition table (varp index, low bit, width).
        "getVarp",
        "getVarbit",

        // -- env: game state enum + current world id. If no string anchor exists, fall back to the
        //    plan's method: the state int walks a small known value set -- break on it during login.
        "getGameState",
        "getWorld",

        // -- containers: inventory/bank/worn item arrays.
        "getItemContainer",
        "getInv",
        "getContainer",

        // -- widgets: the widget tree. Worldmap.MAP_CONTAINER is the first target because its bounds
        //    are visually verifiable (resize the window, the bounds must track the map box).
        "getWidget",
        "widgetPos",

        // -- world map: centre (world tiles) + zoom. Verify by landing the shim's marker on a
        //    landmark you can see out the window too.
        "getMapCenter",
        "worldMapCenter",

        // -- menu: the struct DO_ACTION is fed from (open flag, entries, click record). The stronger
        //    anchor is DO_ACTION itself -- trace what writes its arguments -- but try these first.
        "getMenu",
        "menuEntry",

        // -- CONFIRMED PRESENT in client-240-6 (sha256 d6a43c08..., strings dump 2026-09-05):
        //    inventory container bindings -- the container native's anchors.
        "invGetObjId",
        "invGetNum",
        "invSize",
        "invTotal",

        // -- widget/interface access. "ifType" is the RTTI-visible accessor; "iftypes" is the
        //    definition-table name, whose xrefs build the widget definition array.
        "ifType",

        // -- world map: map coordinate/origin bindings for the centre+zoom reads.
        "getMapCoordinate",
        "getMapOrigin",
        "getMapTile",
        "drawOnWorldMap",

        // -- client state: "worldid" is the current-world field's Lua binding name.
        "worldid",

        // -- real tick counter, if the client exposes its own (kewl currently derives it cycle/30).
        "getTickCount",

        // -- entity coords beyond the originals: objCoord (items), locCoord (scenery), and the
        //    generic getCoord -- each walks to the same scene-entity arrays the others use.
        "objCoord",
        "locCoord",
        "getCoord",
    };

    @Override
    public void run() throws Exception {
        long base = currentProgram.getImageBase().getOffset();
        List<String> out = new ArrayList<>();
        out.add("# KewlKlient offsets, found by anchoring on the client's own binding names.");
        out.add("# image base 0x" + Long.toHexString(base));
        out.add("");

        for (String anchor : ANCHORS) {
            out.add("## " + anchor);
            List<Address> hits = findStrings(anchor);
            if (hits.isEmpty()) {
                out.add("   string NOT FOUND -- the client may have renamed it this build");
                out.add("");
                continue;
            }
            for (Address s : hits) {
                out.add("   string at " + s + "   (rva 0x" + Long.toHexString(s.getOffset() - base) + ")");
                for (Reference r : getReferencesTo(s)) {
                    Function f = getFunctionContaining(r.getFromAddress());
                    if (f == null) continue;
                    long rva = f.getEntryPoint().getOffset() - base;
                    out.add("      referenced from " + f.getName()
                            + "  entry rva 0x" + Long.toHexString(rva));
                }
            }
            out.add("");
        }

        out.add("# WHAT TO DO WITH THIS:");
        out.add("#   The referencing function is the REGISTRATION, not the thing itself. Open it and look");
        out.add("#   for the function pointer it registers next to the name -- that is your leaf. Put its");
        out.add("#   rva in client/offsets.hpp and re-check BUILD_ID at the same time.");

        // getSourceFile() is a ResourceFile (not a File) in current Ghidra, so rebuild the path.
        File f = new File(new File(getSourceFile().getAbsolutePath()).getParentFile().getParentFile(),
                          "offsets_found.txt");
        try (PrintWriter w = new PrintWriter(f)) {
            for (String line : out) w.println(line);
        }
        println("wrote " + f.getAbsolutePath());
    }

    /** Every defined string in the binary equal to `want`. */
    private List<Address> findStrings(String want) {
        List<Address> hits = new ArrayList<>();
        for (var data : DefinedDataIterator.byDataInstance(currentProgram, d -> d.hasStringValue())) {
            String v = data.getDefaultValueRepresentation();
            if (v != null && v.replaceAll("^\"|\"$", "").equals(want)) hits.add(data.getAddress());
        }
        // Some builds leave these as raw bytes rather than defined data. Fall back to a memory search.
        if (hits.isEmpty()) {
            byte[] pat = want.getBytes();
            for (MemoryBlock b : currentProgram.getMemory().getBlocks()) {
                if (!b.isInitialized()) continue;
                Address a = currentProgram.getMemory().findBytes(b.getStart(), b.getEnd(), pat, null, true, monitor);
                if (a != null) hits.add(a);
            }
        }
        return hits;
    }
}
