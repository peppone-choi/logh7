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

public class ExportDestinationHitRegionOwner extends GhidraScript {
    private PrintWriter out;
    private DecompInterface decompiler;
    private final Set<Address> functions = new LinkedHashSet<>();

    private void add(long raw) {
        Function function = getFunctionContaining(toAddr(raw));
        if (function != null) functions.add(function.getEntryPoint());
    }

    private void references(long raw, String label) {
        Address target = toAddr(raw);
        out.printf("===== REFERENCES %s target=%s =====%n", label, target);
        int count = 0;
        for (Reference ref : getReferencesTo(target)) {
            Function function = getFunctionContaining(ref.getFromAddress());
            Instruction instruction = getInstructionAt(ref.getFromAddress());
            out.printf("REF %s type=%s function=%s instruction=%s%n", ref.getFromAddress(), ref.getReferenceType(),
                function == null ? "<none>" : function.getName() + "@" + function.getEntryPoint(),
                instruction == null ? "<none>" : instruction.toString());
            if (function != null) functions.add(function.getEntryPoint());
            count++;
        }
        out.println("REF_COUNT=" + count);
    }

    private void rawWindow(long raw, int before, int after, String label) {
        Instruction center = getInstructionAt(toAddr(raw));
        out.printf("===== RAW WINDOW %s center=%s =====%n", label, toAddr(raw));
        if (center == null) { out.println("NO_INSTRUCTION"); return; }
        Instruction cursor = center;
        for (int i = 0; cursor != null && i < before; i++) cursor = cursor.getPrevious();
        for (int i = 0; cursor != null && i < before + after + 1; i++, cursor = cursor.getNext()) {
            Function function = getFunctionContaining(cursor.getAddress());
            out.printf("%s function=%s  %s%n", cursor.getAddress(),
                function == null ? "<none>" : function.getName() + "@" + function.getEntryPoint(), cursor);
        }
        add(raw);
    }

    private void memoryFloat(long raw, String label) throws Exception {
        byte[] bytes = new byte[4];
        currentProgram.getMemory().getBytes(toAddr(raw), bytes);
        int bits = (bytes[0] & 0xff) | ((bytes[1] & 0xff) << 8) | ((bytes[2] & 0xff) << 16) | ((bytes[3] & 0xff) << 24);
        out.printf("MEMORY_FLOAT %s address=%s bits=%08x value=%s%n", label, toAddr(raw), bits, Float.intBitsToFloat(bits));
    }

    private void exportFunctions() {
        for (Address entry : functions) {
            Function function = getFunctionAt(entry);
            if (function == null) continue;
            out.printf("===== FUNCTION %s@%s =====%n", function.getName(), entry);
            DecompileResults result = decompiler.decompileFunction(function, 300, monitor);
            out.println(result.decompileCompleted() ? result.getDecompiledFunction().getC()
                : "DECOMPILE_FAILED " + result.getErrorMessage());
        }
    }

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length != 1) throw new IllegalArgumentException("Expected one output path");
        File output = new File(args[0]);
        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("Cannot create output parent");
        decompiler = new DecompInterface();
        decompiler.openProgram(currentProgram);
        try (PrintWriter writer = new PrintWriter(output, "UTF-8")) {
            out = writer;
            out.printf("PROGRAM %s imageBase=%s language=%s%n", currentProgram.getName(), currentProgram.getImageBase(), currentProgram.getLanguageID());
            references(0x022143dcL, "mouse client x");
            references(0x022143e0L, "mouse client y");
            references(0x022142dbL, "VK_LBUTTON synthesized state byte; bit 0x40 press edge");
            references(0x022142dcL, "VK_RBUTTON synthesized state byte; bit 0x40 press edge");
            references(0x009d2a48L, "selected grid x");
            references(0x009d2a4cL, "selected grid y");
            references(0x009d1368L, "D3DTS_VIEW matrix");
            references(0x009d13a8L, "D3DTS_PROJECTION matrix");
            references(0x009d13e8L, "D3DTS_WORLD matrix");
            references(0x009d1428L, "D3D viewport");
            rawWindow(0x004d79deL, 36, 80, "mouse to hover-grid and click promotion");
            rawWindow(0x004d3580L, 4, 90, "world point to grid conversion");
            rawWindow(0x004b25a0L, 4, 120, "client point to world ray or plane intersection");
            rawWindow(0x004d2fe0L, 4, 100, "world to screen projection");
            rawWindow(0x004d3540L, 4, 30, "grid to world conversion");
            rawWindow(0x004d2f80L, 4, 90, "D3D transform and viewport refresh");
            rawWindow(0x005a557dL, 4, 120, "project helper implementation entry");
            rawWindow(0x004c8b70L, 4, 120, "grid record lookup used by cell-type validator");
            rawWindow(0x004d35e0L, 4, 60, "fixed grid render-record lookup");
            memoryFloat(0x0066e244L, "grid x rounding bias");
            memoryFloat(0x0066e61cL, "grid y origin");
            memoryFloat(0x0066e620L, "grid z origin");
            memoryFloat(0x0066e624L, "grid x origin");
            memoryFloat(0x0066e664L, "target distance filter epsilon");
            add(0x004d6b70L);
            add(0x004d3580L);
            add(0x004b25a0L);
            add(0x004d2fe0L);
            add(0x004d3540L);
            add(0x004d6310L);
            add(0x00500b60L);
            add(0x004b22d0L);
            add(0x004b24f0L);
            add(0x005a556cL);
            add(0x004d2f80L);
            add(0x005009d0L);
            add(0x00500b70L);
            add(0x00500580L);
            add(0x004c8b70L);
            add(0x004d35b0L);
            add(0x004d35e0L);
            exportFunctions();
        } finally {
            decompiler.dispose();
        }
    }
}
