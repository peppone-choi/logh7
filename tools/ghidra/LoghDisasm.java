// Read-only disassembly listing of whole functions for LOGH7 static analysis. Author: Peppone Choi.
// usage: analyzeHeadless <proj> logh7/bin -process G7MTClient.exe -noanalysis -readOnly
//        -scriptPath tools/ghidra -postScript LoghDisasm.java <outFile> <addr+addr+...>
// Each address selects the function containing it; the program is never modified.
//@category LOGH7
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;

import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

public class LoghDisasm extends GhidraScript {
    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(args[0]), StandardCharsets.UTF_8))) {
            for (String text : args[1].split("\\+")) {
                Address at = toAddr(Long.decode(text));
                Function f = getFunctionContaining(at);
                out.println("== " + text + " " + (f == null ? "<no function>" : f.getName() + " " + f.getBody().getMinAddress() + "-" + f.getBody().getMaxAddress()));
                if (f == null) continue;
                InstructionIterator it = currentProgram.getListing().getInstructions(f.getBody(), true);
                while (it.hasNext()) {
                    Instruction i = it.next();
                    StringBuilder bytes = new StringBuilder();
                    for (byte b : i.getBytes()) bytes.append(String.format("%02x", b & 0xff));
                    out.println(i.getAddress() + "  " + String.format("%-20s", bytes) + "  " + i);
                }
            }
        }
    }
}
