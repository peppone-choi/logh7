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

public class ExportDestinationConfirmOwners extends GhidraScript {
    private PrintWriter out;
    private DecompInterface decompiler;
    private final Set<Address> functions = new LinkedHashSet<>();

    private void addFunction(long raw) {
        Function function = getFunctionContaining(toAddr(raw));
        if (function != null) functions.add(function.getEntryPoint());
    }

    private void references(long raw, String label) {
        Address target = toAddr(raw);
        out.printf("===== REFERENCES %s target=%s =====%n", label, target);
        int count = 0;
        for (Reference reference : getReferencesTo(target)) {
            Function function = getFunctionContaining(reference.getFromAddress());
            Instruction instruction = getInstructionAt(reference.getFromAddress());
            out.printf("REF %s type=%s function=%s instruction=%s%n",
                reference.getFromAddress(), reference.getReferenceType(),
                function == null ? "<none>" : function.getName() + "@" + function.getEntryPoint(),
                instruction == null ? "<none>" : instruction.toString());
            if (function != null) functions.add(function.getEntryPoint());
            count++;
        }
        out.println("REFERENCE_COUNT=" + count);
    }

    private void pointerRange(long start, long end, String label) throws Exception {
        out.printf("===== POINTER RANGE %s %s-%s =====%n", label, toAddr(start), toAddr(end));
        for (long raw = start; raw <= end; raw += 4) {
            Address slot = toAddr(raw);
            long value = Integer.toUnsignedLong(currentProgram.getMemory().getInt(slot));
            Function function = getFunctionContaining(toAddr(value));
            out.printf("SLOT %s value=%08x function=%s%n", slot, value,
                function == null ? "<none>" : function.getName() + "@" + function.getEntryPoint());
            if (function != null) functions.add(function.getEntryPoint());
        }
    }

    private void dumpFunctions() {
        for (Address entry : functions) {
            Function function = getFunctionAt(entry);
            if (function == null) continue;
            out.printf("===== FUNCTION %s@%s =====%n", function.getName(), entry);
            DecompileResults result = decompiler.decompileFunction(function, 300, monitor);
            out.println(result.decompileCompleted() ? result.getDecompiledFunction().getC()
                : "DECOMPILE_FAILED " + result.getErrorMessage());
            out.println("----- DISASSEMBLY -----");
            Instruction instruction = getInstructionAt(entry);
            while (instruction != null && function.getBody().contains(instruction.getAddress())) {
                out.printf("%s  %s%n", instruction.getAddress(), instruction);
                instruction = instruction.getNext();
            }
        }
    }

    private void rawWindow(long raw, int count, String label) {
        out.printf("===== RAW WINDOW %s start=%s count=%d =====%n", label, toAddr(raw), count);
        Instruction instruction = getInstructionAt(toAddr(raw));
        if (instruction == null) {
            out.println("NO_INSTRUCTION_AT_START");
            return;
        }
        for (int i = 0; instruction != null && i < count; i++, instruction = instruction.getNext()) {
            Function function = getFunctionContaining(instruction.getAddress());
            out.printf("%s function=%s  %s%n", instruction.getAddress(),
                function == null ? "<none>" : function.getName() + "@" + function.getEntryPoint(), instruction);
        }
    }

    private void scanExactScalar(long wanted, String label) {
        out.printf("===== EXACT SCALAR %s value=0x%x =====%n", label, wanted);
        InstructionIterator iterator = currentProgram.getListing().getInstructions(true);
        int count = 0;
        while (iterator.hasNext()) {
            Instruction instruction = iterator.next();
            boolean match = false;
            for (int op = 0; op < instruction.getNumOperands() && !match; op++) {
                for (Object object : instruction.getOpObjects(op)) {
                    if (object instanceof Scalar && ((Scalar)object).getUnsignedValue() == wanted) {
                        match = true;
                        break;
                    }
                }
            }
            if (!match) continue;
            Function function = getFunctionContaining(instruction.getAddress());
            out.printf("MATCH %s function=%s instruction=%s%n", instruction.getAddress(),
                function == null ? "<none>" : function.getName() + "@" + function.getEntryPoint(), instruction);
            if (function != null) functions.add(function.getEntryPoint());
            count++;
        }
        out.println("EXACT_SCALAR_COUNT=" + count);
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

            references(0x00676b30L, "SelectGrid primary vtable");
            references(0x00676b74L, "TARGET_GRID subobject vtable");
            references(0x0078bf80L, "TARGET_GRID label");
            references(0x0078bf6cL, "TARGET_BASE_GRID label");
            references(0x0078c784L, "SelectGrid label");
            references(0x0078c76cL, "SendWarpCommand label");
            references(0x00676aecL, "SendWarpCommand vtable");
            references(0x00676af4L, "SendWarpCommand callback slot");
            references(0x009d2a34L, "selected grid status/global");
            references(0x009d2a3cL, "selection result state/global");
            references(0x009d2a40L, "selected grid value/global");

            pointerRange(0x00676aa8L, 0x00676bd8L, "warp selection callback neighborhood");
            pointerRange(0x00676d5cL, 0x00676da0L, "TARGET_BASE_GRID vtable neighborhood");
            pointerRange(0x00675780L, 0x006757e0L, "TextDialog vtable neighborhood");
            rawWindow(0x00573cd0L, 180, "TARGET_GRID callback");
            rawWindow(0x00573cf0L, 48, "TARGET_BASE_GRID callback");
            rawWindow(0x0058cc10L, 32, "TARGET_GRID destructor thunk");
            rawWindow(0x005725e0L, 120, "TextDialog stage callback");
            rawWindow(0x005727b3L, 260, "TextDialog stage callback tail");
            rawWindow(0x00572410L, 120, "TextDialog callback 0x0c");
            rawWindow(0x005724d0L, 80, "TextDialog callback 0x10");
            rawWindow(0x00572480L, 80, "TextDialog callback 0x14");
            rawWindow(0x00572460L, 80, "TextDialog callback 0x18");
            scanExactScalar(0x0de0L, "manager terminal state field");

            long[] anchors = {
                0x00581c80L, 0x00581f20L, 0x0058cc20L, 0x0058ce00L,
                0x005737d0L, 0x005736d0L, 0x004d5030L, 0x004d51d0L,
                0x004b49d0L, 0x004b49a0L,
                0x00572170L, 0x00572520L, 0x004fdde0L,
                0x004f8990L, 0x004034f0L
            };
            for (long anchor : anchors) addFunction(anchor);
            dumpFunctions();
        } finally {
            decompiler.dispose();
        }
    }
}
