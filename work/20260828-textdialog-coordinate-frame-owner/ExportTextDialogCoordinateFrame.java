import java.io.File;
import java.io.PrintWriter;
import java.util.LinkedHashSet;
import java.util.Set;

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.symbol.Reference;

public class ExportTextDialogCoordinateFrame extends GhidraScript {
    private PrintWriter out;
    private DecompInterface decompiler;
    private final Set<Address> functions = new LinkedHashSet<>();

    private void add(long raw) {
        Function f = getFunctionContaining(toAddr(raw));
        if (f != null) functions.add(f.getEntryPoint());
    }

    private void refs(long raw, String label) {
        Address target = toAddr(raw);
        out.printf("===== REFERENCES %s target=%s =====%n", label, target);
        int count = 0;
        for (Reference ref : getReferencesTo(target)) {
            Function f = getFunctionContaining(ref.getFromAddress());
            Instruction ins = getInstructionAt(ref.getFromAddress());
            out.printf("REF %s type=%s function=%s instruction=%s%n",
                ref.getFromAddress(), ref.getReferenceType(),
                f == null ? "<none>" : f.getName() + "@" + f.getEntryPoint(),
                ins == null ? "<none>" : ins.toString());
            if (f != null) functions.add(f.getEntryPoint());
            count++;
        }
        out.println("REFERENCE_COUNT=" + count);
    }

    private void dump() {
        for (Address entry : functions) {
            Function f = getFunctionAt(entry);
            if (f == null) continue;
            out.printf("===== FUNCTION %s@%s =====%n", f.getName(), entry);
            DecompileResults result = decompiler.decompileFunction(f, 300, monitor);
            out.println(result.decompileCompleted()
                ? result.getDecompiledFunction().getC()
                : "DECOMPILE_FAILED " + result.getErrorMessage());
            out.println("----- DISASSEMBLY -----");
            Instruction ins = getInstructionAt(entry);
            while (ins != null && f.getBody().contains(ins.getAddress())) {
                out.printf("%s  %s%n", ins.getAddress(), ins);
                ins = ins.getNext();
            }
        }
    }

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length != 1) throw new IllegalArgumentException("Expected one output path");
        decompiler = new DecompInterface();
        decompiler.openProgram(currentProgram);
        File output = new File(args[0]);
        File parent = output.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Could not create output directory: " + parent);
        }
        try (PrintWriter writer = new PrintWriter(output, "UTF-8")) {
            out = writer;
            out.printf("PROGRAM %s imageBase=%s language=%s%n",
                currentProgram.getName(), currentProgram.getImageBase(), currentProgram.getLanguageID());

            long[] anchors = {
                0x004ea460L, 0x004ea510L, 0x004ea570L, 0x004ea610L,
                0x00500820L, 0x00507a50L,
                0x0056f8e0L, 0x0056f960L, 0x0056fb40L,
                0x00570340L, 0x00570650L, 0x005706e0L, 0x00570740L,
                0x00501200L, 0x005015f0L, 0x00501d60L, 0x00501ed0L,
                0x00502220L, 0x005024e0L, 0x005025c0L, 0x005025f0L,
                0x00502940L, 0x00502980L, 0x005033b0L,
                0x00507090L, 0x00502760L, 0x0050c180L,
                0x00576990L, 0x00530bf0L, 0x00577050L,
                0x004fbe90L, 0x004fdde0L, 0x004fe890L, 0x004fe930L,
                0x00572170L, 0x0050cf40L, 0x0056ebf0L
            };
            for (long anchor : anchors) add(anchor);

            refs(0x0056fb40L, "TextDialog layout/animation owner");
            refs(0x00570740L, "TextDialog post-layout owner");
            refs(0x00507a50L, "manager origin setter");
            refs(0x005015f0L, "widget event hit-test");
            refs(0x005025f0L, "widget absolute rectangle resolver");
            refs(0x00502940L, "widget rectangle setter");
            refs(0x004ea510L, "client pixel to logical point transform");
            refs(0x00507090L, "recursive manager origin resolver");
            refs(0x004fbe90L, "TextDialog manager object constructor");
            refs(0x0056f8e0L, "TextDialog manager payload initializer");
            refs(0x00502220L, "widget rectangle/tooltip hit-test");
            refs(0x004ea610L, "client rectangle transform helper");
            dump();
        } finally {
            decompiler.dispose();
        }
    }
}
