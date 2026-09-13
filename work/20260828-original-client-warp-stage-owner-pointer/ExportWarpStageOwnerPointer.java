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
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.Reference;

public class ExportWarpStageOwnerPointer extends GhidraScript {
    private PrintWriter out;
    private DecompInterface decompiler;
    private final Set<Address> functions = new LinkedHashSet<>();

    private void add(long raw) {
        Function function = getFunctionContaining(toAddr(raw));
        if (function != null) functions.add(function.getEntryPoint());
    }

    private void refs(long raw, String label) {
        Address target = toAddr(raw);
        out.printf("===== REFERENCES %s target=%s =====%n", label, target);
        int count = 0;
        for (Reference reference : getReferencesTo(target)) {
            Function function = getFunctionContaining(reference.getFromAddress());
            Instruction instruction = getInstructionAt(reference.getFromAddress());
            out.printf("REF %s type=%s function=%s instruction=%s%n", reference.getFromAddress(), reference.getReferenceType(),
                function == null ? "<none>" : function.getName() + "@" + function.getEntryPoint(),
                instruction == null ? "<none>" : instruction.toString());
            if (function != null) functions.add(function.getEntryPoint());
            count++;
        }
        out.println("REF_COUNT=" + count);
    }

    private void scanImmediate(long value, String label) {
        out.printf("===== IMMEDIATE %s value=%08x =====%n", label, value);
        InstructionIterator iterator = currentProgram.getListing().getInstructions(true);
        int count = 0;
        while (iterator.hasNext()) {
            Instruction instruction = iterator.next();
            boolean match = false;
            for (int op = 0; op < instruction.getNumOperands() && !match; op++) {
                for (Object object : instruction.getOpObjects(op)) {
                    if (object instanceof Scalar && ((Scalar)object).getUnsignedValue() == value) { match = true; break; }
                    if (object instanceof Address && ((Address)object).getOffset() == value) { match = true; break; }
                }
            }
            if (!match) continue;
            Function function = getFunctionContaining(instruction.getAddress());
            out.printf("MATCH %s function=%s instruction=%s%n", instruction.getAddress(),
                function == null ? "<none>" : function.getName() + "@" + function.getEntryPoint(), instruction);
            if (function != null) functions.add(function.getEntryPoint());
            count++;
        }
        out.println("MATCH_COUNT=" + count);
    }

    private void dumpFunctions() {
        for (Address entry : functions) {
            Function function = getFunctionAt(entry);
            if (function == null) continue;
            out.printf("===== FUNCTION %s@%s =====%n", function.getName(), entry);
            DecompileResults result = decompiler.decompileFunction(function, 300, monitor);
            out.println(result.decompileCompleted() ? result.getDecompiledFunction().getC() : "DECOMPILE_FAILED " + result.getErrorMessage());
            out.println("----- DISASSEMBLY -----");
            Instruction instruction = getInstructionAt(entry);
            while (instruction != null && function.getBody().contains(instruction.getAddress())) {
                out.printf("%s  %s%n", instruction.getAddress(), instruction);
                instruction = instruction.getNext();
            }
        }
    }

    private void dumpPointerRange(long start, long end, String label) throws Exception {
        out.printf("===== POINTER RANGE %s %08x-%08x =====%n", label, start, end);
        for (long raw=start; raw<=end; raw+=4) {
            long value = Integer.toUnsignedLong(currentProgram.getMemory().getInt(toAddr(raw)));
            Function function = getFunctionContaining(toAddr(value));
            out.printf("SLOT %08x value=%08x function=%s%n", raw, value,
                function == null ? "<none>" : function.getName() + "@" + function.getEntryPoint());
            if (function != null) functions.add(function.getEntryPoint());
        }
    }

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length != 1) throw new IllegalArgumentException("Expected one output path");
        decompiler = new DecompInterface();
        decompiler.openProgram(currentProgram);
        try (PrintWriter writer = new PrintWriter(new File(args[0]), "UTF-8")) {
            out = writer;
            out.printf("PROGRAM %s imageBase=%s language=%s%n", currentProgram.getName(), currentProgram.getImageBase(), currentProgram.getLanguageID());
            long[] targets = {0x004f58c0L,0x004f93c0L,0x004f8990L,0x004034f0L,0x00581c80L,0x00572170L,0x0058c750L,0x004f98f0L};
            String[] labels = {"manager65 action dispatcher","flow dispatcher","flow owner constructor","flow child insertion","warp factory","TextDialog flow object constructor","factory table initializer","named flow child base constructor"};
            for (int i=0;i<targets.length;i++) { refs(targets[i], labels[i]); add(targets[i]); }
            refs(0x00c9e2e0L, "strategy flow dispatcher root");
            refs(0x00c9e2f8L, "active strategy flow owner pointer");
            refs(0x00c9e3a8L, "command 0x2B warp factory slot");
            refs(0x00675780L, "TextDialog object vtable");
            refs(0x00676b30L, "SelectGrid object vtable");
            scanImmediate(0x00c9e2e0L, "strategy flow dispatcher root");
            scanImmediate(0x00c9e2f8L, "active strategy flow owner pointer");
            scanImmediate(0x00c9e3a8L, "warp factory slot");
            scanImmediate(0x00581c80L, "warp factory address");
            scanImmediate(0x00572170L, "TextDialog constructor address");
            scanImmediate(0x00675780L, "TextDialog final vtable");
            dumpPointerRange(0x00675780L, 0x006757b8L, "TextDialog final vtable");
            dumpPointerRange(0x006702b8L, 0x006702e8L, "flow owner vtable");
            dumpFunctions();
        } finally {
            decompiler.dispose();
        }
    }
}
