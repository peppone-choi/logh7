import java.io.File;
import java.io.PrintWriter;

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.Function;
import ghidra.program.model.symbol.Reference;

public class ExportBootFirstFlow extends GhidraScript {
    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length != 1) throw new IllegalArgumentException("Expected one output path");
        DecompInterface decompiler = new DecompInterface();
        decompiler.openProgram(currentProgram);
        try (PrintWriter out = new PrintWriter(new File(args[0]), "UTF-8")) {
            out.printf("PROGRAM %s imageBase=%s language=%s compiler=%s%n",
                currentProgram.getName(), currentProgram.getImageBase(),
                currentProgram.getLanguageID(), currentProgram.getCompilerSpec().getCompilerSpecID());
            out.println("===== DEFINED STRINGS AND XREFS =====");
            for (Data data : currentProgram.getListing().getDefinedData(true)) {
                Object value = data.getValue();
                if (!(value instanceof String)) continue;
                String text = (String)value;
                out.printf("STRING %s %s%n", data.getAddress(), text.replace("\r", "\\r").replace("\n", "\\n"));
                for (Reference ref : getReferencesTo(data.getAddress())) {
                    Function owner = getFunctionContaining(ref.getFromAddress());
                    out.printf("  XREF %s type=%s function=%s%n", ref.getFromAddress(), ref.getReferenceType(),
                        owner == null ? "<none>" : owner.getName() + "@" + owner.getEntryPoint());
                }
            }
            out.println("===== FUNCTIONS =====");
            int count = 0;
            for (Function function : currentProgram.getFunctionManager().getFunctions(true)) {
                if (function.isExternal()) continue;
                DecompileResults result = decompiler.decompileFunction(function, 120, monitor);
                out.printf("===== FUNCTION %s@%s =====%n", function.getName(), function.getEntryPoint());
                if (result.decompileCompleted()) out.println(result.getDecompiledFunction().getC());
                else out.println("DECOMPILE_FAILED " + result.getErrorMessage());
                count++;
            }
            out.println("FUNCTION_COUNT=" + count);
        } finally {
            decompiler.dispose();
        }
    }
}
