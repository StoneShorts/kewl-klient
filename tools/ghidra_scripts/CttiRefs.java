// CttiRefs.java -- find the sol2 usertype registration for a binding CLASS (not a method).
//
// sol2 keys each usertype by ctti_get_type_name<T>()'s address. The usertype's registration
// function therefore references that ctti FUNCTION. Give this script the address of any string
// inside the ctti function (e.g. the MSVC type-name string) and it prints every function that
// references the ctti function's entry -- i.e. the class's registration site(s).
//
// Usage: -postScript CttiRefs.java bb1e50   (string rva inside the ctti function)
//
//@category KewlKlient
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.symbol.Reference;

import java.util.LinkedHashSet;
import java.util.Set;

public class CttiRefs extends GhidraScript {
    @Override
    public void run() throws Exception {
        long base = currentProgram.getImageBase().getOffset();
        for (String a : getScriptArgs()) {
            long rva = Long.parseLong(a.replaceFirst("^0x", ""), 16);
            Address at = currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(base + rva);

            // The address may BE the ctti function, or a string inside it, or a string the ctti
            // function references. Resolve all three to the ctti function.
            Function ctti = getFunctionContaining(at);
            if (ctti == null) {
                for (Reference r : getReferencesTo(at)) {
                    Function f = getFunctionContaining(r.getFromAddress());
                    if (f != null) { ctti = f; break; }
                }
            }
            if (ctti == null) {
                println("no function contains or references 0x" + Long.toHexString(rva));
                continue;
            }
            println("ctti function: " + ctti.getName() + " entry rva 0x"
                    + Long.toHexString(ctti.getEntryPoint().getOffset() - base));
            Set<Function> refs = new LinkedHashSet<>();
            for (Reference r : getReferencesTo(ctti.getEntryPoint())) {
                Function f = getFunctionContaining(r.getFromAddress());
                if (f != null) refs.add(f);
            }
            for (Function f : refs) {
                println("  referenced from " + f.getName() + " entry rva 0x"
                        + Long.toHexString(f.getEntryPoint().getOffset() - base));
            }
        }
    }
}
