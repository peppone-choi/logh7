// LOGH7 static-analysis export for Ghidra headless (read-only on program; no execution of target).
//
// Produces, in <outDir>:
//   <prog>.functions.tsv     entry, name, body size, #callers, #callees, thunk
//   <prog>.imports_xref.tsv  dll, api, call-site VA, containing function   (WS2_32/WSOCK32 + extra APIs)
//   <prog>.string_xref.tsv   label, target VA, ref VA, ref type, containing function
//   <prog>.decomp.txt        decompiled C of seed functions + callers up to <depth> levels
//
// usage:
//   analyzeHeadless <projDir> <proj> -process <prog> -noanalysis -scriptPath E:\logh7\tools\ghidra
//       -postScript LoghExport.java <outDir> [targets:<file>] [depth:2] [max:600] [apis:a+b+c]
//       [decomp:<addr+addr+...>] [tag:<suffix>] [nolists]
// NOTE: analyzeHeadless.bat (cmd) splits arguments on "=" "," ";" so use ":" and "+".
//
// targets file lines:
//   0x0076ee2c ginei00        -> references to this VA
//   s:47900                   -> search memory for the ASCII/SJIS bytes and take references
//   f:0x00401000              -> seed function directly (decompile)
//@category LOGH7
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.symbol.ExternalLocation;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.program.model.symbol.ReferenceManager;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;
import ghidra.program.model.symbol.SymbolTable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class LoghExport extends GhidraScript {

    private static final Set<String> NET_DLLS = new HashSet<>(Arrays.asList("WS2_32.DLL", "WSOCK32.DLL"));

    private PrintWriter open(File f) throws Exception {
        return new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8));
    }

    private String fname(Function f) {
        return f == null ? "<none>" : f.getName() + "@" + f.getEntryPoint();
    }

    @Override
    protected void run() throws Exception {
        String[] args = getScriptArgs();
        File outDir = new File(args.length > 0 ? args[0] : "ghidra-out");
        outDir.mkdirs();
        String targetsFile = null;
        int depth = 2;
        int max = 600;
        String tag = "";
        Set<String> extraApis = new HashSet<>();
        List<String> directDecomp = new ArrayList<>();
        boolean skipLists = false;
        boolean mkfunc = false;
        List<String> ranges = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("targets:")) targetsFile = a.substring(8);
            else if (a.startsWith("depth:")) depth = Integer.parseInt(a.substring(6));
            else if (a.startsWith("max:")) max = Integer.parseInt(a.substring(4));
            else if (a.startsWith("tag:")) tag = "." + a.substring(4);
            else if (a.startsWith("apis:")) extraApis.addAll(Arrays.asList(a.substring(5).split("\\+")));
            else if (a.startsWith("decomp:")) directDecomp.addAll(Arrays.asList(a.substring(7).split("\\+")));
            else if (a.equals("nolists")) skipLists = true;
            else if (a.startsWith("range:")) ranges.add(a.substring(6));
            else if (a.equals("mkfunc")) mkfunc = true;
        }
        String prog = currentProgram.getName().replaceAll("[^A-Za-z0-9_.-]", "_");
        FunctionManager fm = currentProgram.getFunctionManager();
        ReferenceManager rm = currentProgram.getReferenceManager();
        SymbolTable st = currentProgram.getSymbolTable();
        Memory mem = currentProgram.getMemory();

        // seeds: function -> reasons
        Map<Function, Set<String>> seeds = new LinkedHashMap<>();

        // (a) function list
        if (!skipLists) {
            try (PrintWriter pw = open(new File(outDir, prog + ".functions.tsv"))) {
                pw.println("entry\tname\tsize\tcallers\tcallees\tthunk");
                FunctionIterator it = fm.getFunctions(true);
                while (it.hasNext() && !monitor.isCancelled()) {
                    Function f = it.next();
                    pw.printf("%s\t%s\t%d\t%d\t%d\t%s%n", f.getEntryPoint(), f.getName(),
                        f.getBody().getNumAddresses(), f.getCallingFunctions(monitor).size(),
                        f.getCalledFunctions(monitor).size(), f.isThunk());
                }
            }
        }

        // (b) network (and extra) import call sites
        try (PrintWriter pw = open(new File(outDir, prog + tag + ".imports_xref.tsv"))) {
            pw.println("dll\tapi\tref_va\tref_type\tfunction");
            SymbolIterator sit = st.getExternalSymbols();
            while (sit.hasNext()) {
                Symbol s = sit.next();
                ExternalLocation el = currentProgram.getExternalManager().getExternalLocation(s);
                String lib = el == null ? "?" : el.getLibraryName().toUpperCase();
                boolean want = NET_DLLS.contains(lib) || extraApis.contains(s.getName());
                if (!want) continue;
                // references to the external symbol itself and to its IAT pointer / thunks
                Set<Address> refSources = new LinkedHashSet<>();
                for (Reference r : s.getReferences()) refSources.add(r.getFromAddress());
                // thunk functions that point to this external
                Set<Address> expanded = new LinkedHashSet<>();
                for (Address from : refSources) {
                    Function f = fm.getFunctionContaining(from);
                    boolean isData = f == null;
                    if (isData) {
                        // IAT slot: take references to the slot
                        ReferenceIterator ri = rm.getReferencesTo(from);
                        while (ri.hasNext()) expanded.add(ri.next().getFromAddress());
                    } else if (f.isThunk()) {
                        for (Reference r2 : st.getPrimarySymbol(f.getEntryPoint()).getReferences()) expanded.add(r2.getFromAddress());
                    } else {
                        expanded.add(from);
                    }
                }
                for (Address from : expanded) {
                    Function f = fm.getFunctionContaining(from);
                    pw.printf("%s\t%s\t%s\t%s\t%s%n", lib, s.getName(), from, "call", fname(f));
                    if (f != null) seeds.computeIfAbsent(f, k -> new LinkedHashSet<>()).add("api:" + s.getName());
                }
            }
        }

        // (c) string / address references
        if (targetsFile != null) {
            try (PrintWriter pw = open(new File(outDir, prog + tag + ".string_xref.tsv"));
                 BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(targetsFile), StandardCharsets.UTF_8))) {
                pw.println("label\ttarget_va\tref_va\tref_type\tfunction");
                String line;
                Charset sjis = Charset.forName("windows-31j");
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    List<Address> targets = new ArrayList<>();
                    String label;
                    if (line.startsWith("f:")) {
                        Address a = toAddr(line.substring(2).trim());
                        Function f = fm.getFunctionContaining(a);
                        if (f != null) seeds.computeIfAbsent(f, k -> new LinkedHashSet<>()).add("direct");
                        continue;
                    } else if (line.startsWith("s:")) {
                        label = line.substring(2);
                        byte[] needle = label.getBytes(sjis);
                        byte[] withNul = Arrays.copyOf(needle, needle.length + 1);
                        Address start = currentProgram.getMinAddress();
                        while (start != null) {
                            Address found = mem.findBytes(start, withNul, null, true, monitor);
                            if (found == null) break;
                            targets.add(found);
                            start = found.add(1);
                        }
                    } else {
                        String[] parts = line.split("\\s+", 2);
                        targets.add(toAddr(parts[0]));
                        label = parts.length > 1 ? parts[1] : parts[0];
                    }
                    for (Address t : targets) {
                        ReferenceIterator ri = rm.getReferencesTo(t);
                        boolean any = false;
                        while (ri.hasNext()) {
                            Reference r = ri.next();
                            any = true;
                            Function f = fm.getFunctionContaining(r.getFromAddress());
                            pw.printf("%s\t%s\t%s\t%s\t%s%n", label, t, r.getFromAddress(), r.getReferenceType(), fname(f));
                            if (f != null) seeds.computeIfAbsent(f, k -> new LinkedHashSet<>()).add("str:" + label);
                        }
                        if (!any) pw.printf("%s\t%s\t-\t-\t<no refs>%n", label, t);
                    }
                }
            }
        }
        for (String d : directDecomp) {
            if (d.isEmpty()) continue;
            Address da = toAddr(d);
            Function f = fm.getFunctionContaining(da);
            if (f == null && mkfunc) {
                // analysis missed this code: create a function at the given entry (in-memory only when -readOnly)
                f = createFunction(da, null);
            }
            if (f != null) seeds.computeIfAbsent(f, k -> new LinkedHashSet<>()).add("direct");
            else println("no function at " + d);
        }
        // whole address ranges (all functions whose entry lies in [start,end))
        for (String r : ranges) {
            String[] se = r.split("-");
            Address s0 = toAddr(se[0]);
            Address e0 = toAddr(se[1]);
            FunctionIterator it = fm.getFunctions(s0, true);
            while (it.hasNext()) {
                Function f = it.next();
                if (f.getEntryPoint().compareTo(e0) >= 0) break;
                seeds.computeIfAbsent(f, k -> new LinkedHashSet<>()).add("range:" + r);
            }
        }

        // (d) callers up to depth
        Map<Function, String> order = new LinkedHashMap<>();
        for (Map.Entry<Function, Set<String>> e : seeds.entrySet()) order.put(e.getKey(), "L0 " + String.join(",", e.getValue()));
        List<Function> frontier = new ArrayList<>(seeds.keySet());
        for (int d = 1; d <= depth; d++) {
            List<Function> next = new ArrayList<>();
            for (Function f : frontier) {
                for (Function c : f.getCallingFunctions(monitor)) {
                    if (!order.containsKey(c)) {
                        order.put(c, "L" + d + " caller-of " + fname(f));
                        next.add(c);
                    }
                }
            }
            frontier = next;
        }

        DecompInterface di = new DecompInterface();
        DecompileOptions opts = new DecompileOptions();
        di.setOptions(opts);
        di.openProgram(currentProgram);
        int n = 0;
        try (PrintWriter pw = open(new File(outDir, prog + tag + ".decomp.txt"))) {
            for (Map.Entry<Function, String> e : order.entrySet()) {
                if (monitor.isCancelled() || n >= max) break;
                Function f = e.getKey();
                if (f.isThunk() || f.isExternal()) continue;
                n++;
                pw.println("/* ==================================================================");
                pw.println(" * " + fname(f) + "  size=" + f.getBody().getNumAddresses());
                pw.println(" * why: " + e.getValue());
                StringBuilder callers = new StringBuilder();
                for (Function c : f.getCallingFunctions(monitor)) callers.append(c.getEntryPoint()).append(' ');
                pw.println(" * callers: " + callers);
                pw.println(" * ================================================================== */");
                DecompileResults res = di.decompileFunction(f, 90, monitor);
                if (res != null && res.decompileCompleted()) pw.println(res.getDecompiledFunction().getC());
                else pw.println("// decompile failed: " + (res == null ? "null" : res.getErrorMessage()));
            }
        }
        di.dispose();
        println("LoghExport: seeds=" + seeds.size() + " decompiled=" + n + " out=" + outDir);
    }
}
