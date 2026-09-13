import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.lang.Register;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.Reference;

public class ExportExhaustiveUi extends GhidraScript {
    private static final long ROOT_DISPATCH = 0x0054E570L;
    private static final long[] ROOT_BUILDERS = new long[] {0x005123B0L, 0x004FF3C0L, 0x0051CA30L};
    private static final long MANAGER_CONSTRUCTOR = 0x0050BB40L;
    private static final long MANAGER_LOOKUP = 0x0050CF40L;
    private static final long WIDGET_CONSTRUCTOR = 0x00503A10L;
    private static final long DESCRIPTOR_LOADER = 0x004FE930L;
    private static final long LABEL_LOOKUP = 0x00522010L;
    private static final long EVENT_PREDICATE = 0x005015F0L;
    private static final long ENABLE_WRITER = 0x005024E0L;
    private static final long VISIBILITY_WRITER = 0x00503910L;
    private static final long CHILD_ATTACH = 0x00504450L;
    private static final long WIN32_INPUT = 0x00500B70L;
    private static final long INPUT_SNAPSHOT = 0x00500580L;
    private static final long DIRECT_INPUT_POLL = 0x00525C80L;
    private static final long[] RENDER_ANCHORS = new long[] {0x00507100L, 0x0050C880L};
    private static final long MODE2_MENU_BUILD_CALLSITE = 0x0054EB6AL;
    private static final long MODE2_MENU_BUILDER = 0x0054B420L;
    private static final long MODE2_MENU_CONSUMER = 0x0054BB50L;
    private static final long MODE2_MENU_CELL_WRITER = 0x00505B50L;
    private static final long MODE2_MENU_JUMP_TABLE = 0x0054BE40L;
    private static final long MODE2_MENU_RESET_TARGET = 0x0054BD10L;
    private static final long MODE2_MANAGER16_BUILD_CALLSITE = 0x0054EB6AL;
    private static final long MODE2_MANAGER0B_BUILD_CALLSITE = 0x0054EB94L;
    private static final long MODE2_MANAGER0B_BUILDER = 0x00519C50L;
    private static final long MODE2_MANAGER0B_WIDGET_LOOP = 0x00519F1FL;
    private static final long UI_CODE_MIN = 0x004E0000L;
    private static final long UI_CODE_MAX = 0x005A0000L;

    private static String hex(long value, int width) {
        return String.format(Locale.ROOT, "0x%0" + width + "X", value);
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest(bytes)) result.append(String.format(Locale.ROOT, "%02X", value));
        return result.toString();
    }

    private String functionName(Function function) {
        return function == null ? "UNDEFINED_FUNCTION" : function.getName().toUpperCase(Locale.ROOT);
    }

    private boolean isDirectCallTo(Instruction instruction, long target) {
        if (instruction == null || !instruction.getMnemonicString().equalsIgnoreCase("CALL")) return false;
        for (Address flow : instruction.getFlows()) if (flow.getOffset() == target) return true;
        return false;
    }

    private List<Instruction> directCallsites(long target) {
        List<Instruction> result = new ArrayList<>();
        for (Reference reference : getReferencesTo(toAddr(target))) {
            Instruction instruction = getInstructionAt(reference.getFromAddress());
            if (isDirectCallTo(instruction, target)) result.add(instruction);
        }
        result.sort(Comparator.comparing(instruction -> instruction.getAddress().getOffset()));
        return result;
    }

    private Long pushedScalar(Instruction instruction) {
        if (instruction == null || !instruction.getMnemonicString().equalsIgnoreCase("PUSH")) return null;
        for (int operand = 0; operand < instruction.getNumOperands(); operand++) {
            for (Object object : instruction.getOpObjects(operand)) {
                if (object instanceof Scalar) return ((Scalar)object).getUnsignedValue();
            }
        }
        return null;
    }

    private List<Map<String, Object>> precedingPushes(Instruction callsite, int maximum) {
        List<Map<String, Object>> result = new ArrayList<>();
        Instruction cursor = currentProgram.getListing().getInstructionBefore(callsite.getAddress());
        int inspected = 0;
        while (cursor != null && inspected++ < 20 && result.size() < maximum) {
            if (cursor.getMnemonicString().equalsIgnoreCase("CALL")) break;
            if (cursor.getMnemonicString().equalsIgnoreCase("PUSH")) {
                Map<String, Object> push = new LinkedHashMap<>();
                push.put("address", cursor.getAddress().toString());
                push.put("operand", cursor.getDefaultOperandRepresentation(0));
                Long scalar = pushedScalar(cursor);
                push.put("scalar", scalar == null ? null : hex(scalar, 1));
                result.add(push);
            }
            cursor = currentProgram.getListing().getInstructionBefore(cursor.getAddress());
        }
        return result;
    }

    private List<Instruction> precedingPushInstructions(Instruction endpoint, int maximum) {
        List<Instruction> result = new ArrayList<>();
        Instruction cursor = currentProgram.getListing().getInstructionBefore(endpoint.getAddress());
        int inspected = 0;
        while (cursor != null && inspected++ < 20 && result.size() < maximum) {
            if (cursor.getMnemonicString().equalsIgnoreCase("CALL")) break;
            if (cursor.getMnemonicString().equalsIgnoreCase("PUSH")) result.add(cursor);
            cursor = currentProgram.getListing().getInstructionBefore(cursor.getAddress());
        }
        return result;
    }

    private Long scalarOperand(Instruction instruction, int operand) {
        for (Object object : instruction.getOpObjects(operand)) {
            if (object instanceof Scalar) return ((Scalar)object).getUnsignedValue();
        }
        return null;
    }

    private Register registerOperand(Instruction instruction, int operand) {
        for (Object object : instruction.getOpObjects(operand)) {
            if (object instanceof Register) return (Register)object;
        }
        return null;
    }

    private Long constantAssignedBefore(Instruction use, Register register) {
        Function function = getFunctionContaining(use.getAddress());
        Long value = null;
        for (Instruction instruction : currentProgram.getListing().getInstructions(function.getBody(), true)) {
            if (instruction.getAddress().compareTo(use.getAddress()) >= 0) break;
            if (!instruction.getDefaultOperandRepresentation(0).equalsIgnoreCase(register.getName())) continue;
            String mnemonic = instruction.getMnemonicString().toUpperCase(Locale.ROOT);
            if (mnemonic.equals("MOV")) value = scalarOperand(instruction, 1);
            else if (mnemonic.equals("XOR") && instruction.getDefaultOperandRepresentation(1).equalsIgnoreCase(register.getName())) value = 0L;
            else if (mnemonic.equals("OR") && scalarOperand(instruction, 1) != null) value = scalarOperand(instruction, 1);
            else if (Set.of("ADD", "SUB", "INC", "DEC", "POP", "AND", "LEA").contains(mnemonic)) value = null;
        }
        return value;
    }

    private Long resolvedOperandValue(Instruction instruction, int operand) {
        Long scalar = scalarOperand(instruction, operand);
        if (scalar != null) return scalar;
        Register register = registerOperand(instruction, operand);
        if (register == null) return null;
        String name = register.getName().toUpperCase(Locale.ROOT);
        if (name.equals("BL") || name.equals("CL") || name.equals("DL") || name.equals("AL")) {
            Register parent = currentProgram.getRegister("E" + name.charAt(0) + "X");
            Long parentValue = constantAssignedBefore(instruction, parent);
            return parentValue == null ? null : parentValue & 0xffL;
        }
        return constantAssignedBefore(instruction, register);
    }

    private long onlyFlow(Instruction instruction) {
        Address[] flows = instruction == null ? new Address[0] : instruction.getFlows();
        if (flows.length != 1) throw new IllegalStateException("expected one control-flow target");
        return flows[0].getOffset();
    }

    private List<String> instructionWindow(Instruction callsite, int before) {
        Deque<String> lines = new ArrayDeque<>();
        Instruction cursor = currentProgram.getListing().getInstructionBefore(callsite.getAddress());
        for (int index = 0; cursor != null && index < before; index++) {
            lines.addFirst(cursor.getAddress() + " " + cursor.toString());
            cursor = currentProgram.getListing().getInstructionBefore(cursor.getAddress());
        }
        lines.addLast(callsite.getAddress() + " " + callsite.toString());
        return new ArrayList<>(lines);
    }

    private Set<Long> directCallees(Function function) {
        Set<Long> result = new LinkedHashSet<>();
        if (function == null) return result;
        for (Instruction instruction : currentProgram.getListing().getInstructions(function.getBody(), true)) {
            if (!instruction.getMnemonicString().equalsIgnoreCase("CALL")) continue;
            for (Address flow : instruction.getFlows()) {
                Function callee = getFunctionAt(flow);
                if (callee != null) result.add(callee.getEntryPoint().getOffset());
            }
        }
        return result;
    }

    private Map<Long, Set<String>> computeModeReachability() {
        Map<Long, Set<String>> result = new LinkedHashMap<>();
        for (int modeIndex = 0; modeIndex < ROOT_BUILDERS.length; modeIndex++) {
            String mode = hex(modeIndex + 1, 2);
            Deque<long[]> queue = new ArrayDeque<>();
            Set<Long> visited = new LinkedHashSet<>();
            queue.add(new long[] {ROOT_BUILDERS[modeIndex], 0});
            while (!queue.isEmpty()) {
                long[] item = queue.removeFirst();
                long entry = item[0];
                int depth = (int)item[1];
                if (!visited.add(entry)) continue;
                result.computeIfAbsent(entry, ignored -> new LinkedHashSet<>()).add(mode);
                if (depth >= 12) continue;
                for (long callee : directCallees(getFunctionAt(toAddr(entry)))) {
                    if (callee >= UI_CODE_MIN && callee < UI_CODE_MAX) queue.addLast(new long[] {callee, depth + 1});
                }
            }
        }
        return result;
    }

    private List<String> modesFor(Function function, Map<Long, Set<String>> reachability) {
        if (function == null) return new ArrayList<>();
        List<String> result = new ArrayList<>(
            reachability.getOrDefault(function.getEntryPoint().getOffset(), Collections.emptySet())
        );
        Collections.sort(result);
        return result;
    }

    private Set<Long> managerIdsIn(Function function) {
        Set<Long> result = new LinkedHashSet<>();
        if (function == null) return result;
        for (Instruction callsite : directCallsites(MANAGER_CONSTRUCTOR)) {
            if (!function.getBody().contains(callsite.getAddress())) continue;
            List<Map<String, Object>> pushes = precedingPushes(callsite, 1);
            if (pushes.isEmpty()) continue;
            Object scalarText = pushes.get(0).get("scalar");
            if (scalarText == null) continue;
            long scalar = Long.decode(String.valueOf(scalarText));
            if (scalar <= 0xff) result.add(scalar);
        }
        return result;
    }

    private String decompile(DecompInterface decompiler, long entry) {
        Function function = getFunctionAt(toAddr(entry));
        DecompileResults results = decompiler.decompileFunction(function, 120, monitor);
        if (!results.decompileCompleted()) throw new IllegalStateException("decompile failed: " + hex(entry, 8));
        return results.getDecompiledFunction().getC();
    }

    private long unsignedInt(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(
            (bytes[offset] & 0xff) |
            ((bytes[offset + 1] & 0xff) << 8) |
            ((bytes[offset + 2] & 0xff) << 16) |
            ((bytes[offset + 3] & 0xff) << 24)
        );
    }

    private List<String> readConstmsgTable(File path, int table, int expectedRows) throws Exception {
        byte[] bytes = Files.readAllBytes(path.toPath());
        if (!new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("HFWR")) {
            throw new IllegalStateException("constmsg magic mismatch");
        }
        long stringCount = unsignedInt(bytes, 8);
        long tableCount = unsignedInt(bytes, 12);
        if (table < 0 || table >= tableCount) throw new IllegalStateException("constmsg table is absent");
        int stringBase = Math.toIntExact(0x10 + tableCount * 4);
        String[] strings = new String(bytes, stringBase, bytes.length - stringBase, Charset.forName("windows-31j"))
            .split("\\u0000", -1);
        int start = Math.toIntExact(unsignedInt(bytes, 0x10 + table * 4));
        int end = table + 1 < tableCount
            ? Math.toIntExact(unsignedInt(bytes, 0x10 + (table + 1) * 4))
            : Math.toIntExact(stringCount + 1);
        if (end - start < expectedRows || end > strings.length) {
            throw new IllegalStateException("constmsg table row count mismatch");
        }
        List<String> result = new ArrayList<>();
        for (int row = 0; row < expectedRows; row++) result.add(strings[start + row]);
        return result;
    }

    private List<Map<String, Object>> menuRows(
        DecompInterface decompiler, File messageData, String expectedMessageDataSha
    ) throws Exception {
        if (!sha256(Files.readAllBytes(messageData.toPath())).equals(expectedMessageDataSha.toUpperCase(Locale.ROOT))) {
            throw new IllegalStateException("message data SHA-256 mismatch");
        }
        Instruction buildCall = getInstructionAt(toAddr(MODE2_MENU_BUILD_CALLSITE));
        if (!isDirectCallTo(buildCall, MODE2_MENU_BUILDER)) {
            throw new IllegalStateException("mode-2 menu builder callsite mismatch");
        }
        List<Map<String, Object>> buildArgs = precedingPushes(buildCall, 2);
        if (buildArgs.size() < 2 || !"0x16".equals(buildArgs.get(1).get("scalar"))) {
            throw new IllegalStateException("mode-2 menu manager ID is not derived at the builder callsite");
        }
        String builderText = decompile(decompiler, MODE2_MENU_BUILDER).toLowerCase(Locale.ROOT);
        if (!builderText.contains("fun_00522010(0x25") || !builderText.contains("< 7") ||
            !builderText.contains("fun_00505b50(")) {
            throw new IllegalStateException("mode-2 menu row loop mismatch");
        }
        String consumerText = decompile(decompiler, MODE2_MENU_CONSUMER).toLowerCase(Locale.ROOT);
        if (!consumerText.contains("switch") || !consumerText.contains("fun_00506280")) {
            throw new IllegalStateException("mode-2 menu consumer mismatch");
        }
        List<String> labels = readConstmsgTable(messageData, 0x25, 7);
        long[] targets = new long[7];
        for (int row = 0; row < targets.length; row++) {
            targets[row] = Integer.toUnsignedLong(
                currentProgram.getMemory().getInt(toAddr(MODE2_MENU_JUMP_TABLE + row * 4L))
            );
        }
        long[] expectedTargets = new long[] {
            0x0054BC8CL, MODE2_MENU_RESET_TARGET, 0x0054BCD1L, MODE2_MENU_RESET_TARGET,
            0x0054BCF8L, MODE2_MENU_RESET_TARGET, MODE2_MENU_RESET_TARGET
        };
        for (int row = 0; row < targets.length; row++) {
            if (targets[row] != expectedTargets[row]) {
                throw new IllegalStateException("mode-2 menu jump-table mismatch at row " + row);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (int row = 0; row < 7; row++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("candidateId", "MENU_ROW:0054B6B3:" + String.format(Locale.ROOT, "%02X", row));
            item.put("constructionSite", hex(0x0054B6B3L, 8));
            item.put("builderFunction", functionName(getFunctionAt(toAddr(MODE2_MENU_BUILDER))));
            item.put("modes", List.of("0x02"));
            item.put("managerIds", List.of("0x16"));
            item.put("category", "0x04");
            item.put("index", 0);
            item.put("row", row);
            item.put("constructor", functionName(getFunctionAt(toAddr(MODE2_MENU_CELL_WRITER))));
            item.put("constructorDefaultHitTest", 0);
            item.put("interactionKind", "INTERACTIVE");
            Map<String, Object> label = new LinkedHashMap<>();
            label.put("status", "BOUND_CONSUMER");
            label.put("text", labels.get(row));
            label.put("source", "constmsg:0x25:" + row);
            label.put("consumerFunctions", List.of(functionName(getFunctionAt(toAddr(MODE2_MENU_CELL_WRITER)))));
            label.put("evidence", List.of("message-data:table:0x25:row:" + row, "ghidra:callsite:0x0054B6B3"));
            item.put("label", label);
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("status", "PROVEN");
            event.put("namespace", "INTERNAL_WIDGET");
            event.put("types", List.of("0x0E"));
            event.put("predicates", List.of("FUN_0054BE80"));
            event.put("evidence", List.of("ghidra:menu-event:0x0054BE80"));
            item.put("event", event);
            Map<String, Object> handler = new LinkedHashMap<>();
            handler.put("status", "PROVEN");
            handler.put("functions", List.of(functionName(getFunctionAt(toAddr(MODE2_MENU_CONSUMER)))));
            handler.put("reason", targets[row] == MODE2_MENU_RESET_TARGET
                ? "STATIC_RESET_ONLY_IN_INSPECTED_CONSUMER" : "DOWNSTREAM_BRANCH_PRESENT");
            handler.put("evidence", List.of("ghidra:jump-table:" + hex(MODE2_MENU_JUMP_TABLE + row * 4L, 8)));
            item.put("handler", handler);
            item.put("enablement", unknownState("widget+0x15"));
            item.put("visibility", unknownState(null));
            item.put("childManagers", unknownChild());
            item.put("reachability", "UNKNOWN");
            item.put("reachabilityEvidence", List.of("ghidra:menu-builder:" + hex(MODE2_MENU_BUILDER, 8)));
            item.put("evidence", List.of(
                "ghidra:menu-builder:" + hex(MODE2_MENU_BUILDER, 8),
                "ghidra:jump-table:" + hex(MODE2_MENU_JUMP_TABLE + row * 4L, 8),
                "message-data:table:0x25:row:" + row
            ));
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> rootModes() {
        List<Map<String, Object>> result = new ArrayList<>();
        Function dispatch = getFunctionAt(toAddr(ROOT_DISPATCH));
        int mode = 0;
        boolean afterCommonBuilder = false;
        for (Instruction instruction : currentProgram.getListing().getInstructions(dispatch.getBody(), true)) {
            if (isDirectCallTo(instruction, 0x0054E760L)) {
                afterCommonBuilder = true;
                continue;
            }
            if (!afterCommonBuilder || mode == ROOT_BUILDERS.length) continue;
            if (!instruction.getMnemonicString().equalsIgnoreCase("DEC") ||
                !instruction.getDefaultOperandRepresentation(0).equalsIgnoreCase("EAX")) continue;
            Instruction branch = instruction.getNext();
            if (branch != null && !branch.getMnemonicString().equalsIgnoreCase("JZ")) branch = branch.getNext();
            if (branch == null || !branch.getMnemonicString().equalsIgnoreCase("JZ")) continue;
            mode++;
            long target = onlyFlow(branch);
            Instruction cursor = getInstructionAt(toAddr(target));
            Instruction builderCall = null;
            while (cursor != null && dispatch.getBody().contains(cursor.getAddress())) {
                if (cursor.getMnemonicString().equalsIgnoreCase("CALL")) {
                    builderCall = cursor;
                    break;
                }
                if (cursor.getMnemonicString().equalsIgnoreCase("JMP")) break;
                cursor = cursor.getNext();
            }
            if (builderCall == null || builderCall.getFlows().length != 1) {
                throw new IllegalStateException("root mode branch lacks a unique builder call");
            }
            long builder = builderCall.getFlows()[0].getOffset();
            if (mode > ROOT_BUILDERS.length || builder != ROOT_BUILDERS[mode - 1]) {
                throw new IllegalStateException("root mode branch derivation mismatch for mode " + mode);
            }
            String builderName = functionName(getFunctionAt(toAddr(builder)));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("candidateId", "MODE:" + String.format(Locale.ROOT, "%02X", mode));
            item.put("mode", hex(mode, 2));
            item.put("dispatchFunction", functionName(dispatch));
            item.put("builderFunction", builderName);
            item.put("branchCallsite", hex(builderCall.getAddress().getOffset(), 8));
            item.put("branchEvidence", List.of(
                instruction.getAddress() + " " + instruction,
                branch.getAddress() + " " + branch
            ));
            item.put("evidence", List.of("ghidra:root-dispatch:" + hex(ROOT_DISPATCH, 8), "ghidra:callsite:" + hex(builderCall.getAddress().getOffset(), 8)));
            result.add(item);
        }
        if (mode != ROOT_BUILDERS.length) throw new IllegalStateException("root mode closed-world chain mismatch");
        return result;
    }

    private List<Map<String, Object>> managerConstructions(
        Map<Long, Set<String>> modes, List<Map<String, Object>> rootModes
    ) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Instruction callsite : directCallsites(MANAGER_CONSTRUCTOR)) {
            Function caller = getFunctionContaining(callsite.getAddress());
            List<Map<String, Object>> pushes = precedingPushes(callsite, 1);
            Long managerId = null;
            if (!pushes.isEmpty() && pushes.get(0).get("scalar") != null) {
                long candidate = Long.decode(String.valueOf(pushes.get(0).get("scalar")));
                if (candidate <= 0xff) managerId = candidate;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("candidateId", "MANAGER:" + callsite.getAddress().toString().toUpperCase(Locale.ROOT));
            item.put("constructionSite", hex(callsite.getAddress().getOffset(), 8));
            item.put("builderFunction", functionName(caller));
            item.put("modes", modesFor(caller, modes));
            item.put("managerId", managerId == null ? null : hex(managerId, 2));
            item.put("constructor", functionName(getFunctionAt(toAddr(MANAGER_CONSTRUCTOR))));
            item.put("argumentEvidence", pushes);
            item.put("instructionWindow", instructionWindow(callsite, 8));
            item.put("evidence", List.of("ghidra:callsite:" + hex(callsite.getAddress().getOffset(), 8)));
            result.add(item);
        }
        result.add(wrapperManager16Construction(rootModes));
        result.add(wrapperManager0BConstruction());
        result.sort(Comparator.comparing(item -> String.valueOf(item.get("candidateId"))));
        return result;
    }

    private Map<String, Object> wrapperManager16Construction(List<Map<String, Object>> rootModes) {
        long callsiteAddress = MODE2_MANAGER16_BUILD_CALLSITE;
        long target = MODE2_MENU_BUILDER;
        Instruction callsite = getInstructionAt(toAddr(callsiteAddress));
        if (!isDirectCallTo(callsite, target)) {
            throw new IllegalStateException("manager 0x16 wrapper call mismatch");
        }
        List<Instruction> pushes = precedingPushInstructions(callsite, 2);
        Long managerId = pushes.size() == 2 ? resolvedOperandValue(pushes.get(1), 0) : null;
        if (managerId == null || managerId != 0x16) throw new IllegalStateException("manager 0x16 argument derivation mismatch");
        Function caller = getFunctionContaining(callsite.getAddress());
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("candidateId", "MANAGER:COMMON:16");
        item.put("constructionSite", hex(callsiteAddress, 8));
        item.put("builderFunction", functionName(caller));
        item.put("modes", rootModes.stream().map(root -> String.valueOf(root.get("mode"))).toList());
        item.put("managerId", hex(managerId, 2));
        item.put("constructor", functionName(getFunctionAt(toAddr(MANAGER_CONSTRUCTOR))));
        item.put("wrapperFunction", functionName(getFunctionAt(toAddr(target))));
        item.put("argumentEvidence", precedingPushes(callsite, 4));
        item.put("instructionWindow", instructionWindow(callsite, 12));
        item.put("evidence", List.of("ghidra:callsite:" + hex(callsiteAddress, 8)));
        return item;
    }

    private Map<String, Object> wrapperManager0BConstruction() {
        Instruction callsite = getInstructionAt(toAddr(MODE2_MANAGER0B_BUILD_CALLSITE));
        if (!isDirectCallTo(callsite, MODE2_MANAGER0B_BUILDER)) {
            throw new IllegalStateException("manager 0x0B wrapper call mismatch");
        }
        List<Instruction> fallthroughPushes = precedingPushInstructions(callsite, 4);
        List<Instruction> jumpPushes = null;
        Instruction jump = null;
        for (Reference reference : getReferencesTo(callsite.getAddress())) {
            Instruction candidate = getInstructionAt(reference.getFromAddress());
            if (candidate != null && candidate.getMnemonicString().equalsIgnoreCase("JMP") && onlyFlow(candidate) == callsite.getAddress().getOffset()) {
                if (jump != null) throw new IllegalStateException("manager 0x0B has multiple jump predecessors");
                jump = candidate;
                jumpPushes = precedingPushInstructions(candidate, 4);
            }
        }
        if (fallthroughPushes.size() != 4 || jumpPushes == null || jumpPushes.size() != 4) {
            throw new IllegalStateException("manager 0x0B predecessor argument count mismatch");
        }
        long fallthroughManager = resolvedOperandValue(fallthroughPushes.get(1), 0);
        long jumpManager = resolvedOperandValue(jumpPushes.get(1), 0);
        long fallthroughSelector = resolvedOperandValue(fallthroughPushes.get(2), 0);
        long jumpSelector = resolvedOperandValue(jumpPushes.get(2), 0);
        long fallthroughChild = resolvedOperandValue(fallthroughPushes.get(3), 0);
        long jumpChild = resolvedOperandValue(jumpPushes.get(3), 0);
        if (fallthroughManager != 0x0b || jumpManager != 0x0b ||
            fallthroughSelector != 0 || jumpSelector != 1 ||
            fallthroughChild != 0x16 || jumpChild != 0x16) {
            throw new IllegalStateException("manager 0x0B CFG argument derivation mismatch");
        }
        Instruction firstModeDec = getInstructionAt(toAddr(0x0054EB79L));
        Instruction firstModeBranch = firstModeDec.getNext();
        Instruction secondModeDec = firstModeBranch.getNext();
        Instruction secondModeBranch = secondModeDec.getNext();
        if (!firstModeDec.toString().equalsIgnoreCase("DEC EAX") ||
            !firstModeBranch.getMnemonicString().equalsIgnoreCase("JZ") || onlyFlow(firstModeBranch) != fallthroughPushes.get(3).getAddress().subtract(3).getOffset() ||
            !secondModeDec.toString().equalsIgnoreCase("DEC EAX") ||
            !secondModeBranch.getMnemonicString().equalsIgnoreCase("JNZ") || onlyFlow(secondModeBranch) <= callsite.getAddress().getOffset()) {
            throw new IllegalStateException("manager 0x0B mode predicate chain mismatch");
        }
        List<Map<String, Object>> pathEvidence = new ArrayList<>();
        for (List<Instruction> path : List.of(fallthroughPushes, jumpPushes)) {
            Map<String, Object> record = new LinkedHashMap<>();
            long selector = resolvedOperandValue(path.get(2), 0);
            record.put("predicate", "rootMode==" + hex(selector + 1, 2));
            record.put("managerId", hex(resolvedOperandValue(path.get(1), 0), 2));
            record.put("selector", hex(selector, 2));
            record.put("childManagerId", hex(resolvedOperandValue(path.get(3), 0), 2));
            record.put("pushes", path.stream().map(instruction -> instruction.getAddress() + " " + instruction).toList());
            pathEvidence.add(record);
        }
        Function caller = getFunctionContaining(callsite.getAddress());
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("candidateId", "MANAGER:MODES1_2:0B");
        item.put("constructionSite", hex(callsite.getAddress().getOffset(), 8));
        item.put("builderFunction", functionName(caller));
        List<String> derivedModes = new ArrayList<>();
        derivedModes.add(hex(fallthroughSelector + 1, 2));
        derivedModes.add(hex(jumpSelector + 1, 2));
        Collections.sort(derivedModes);
        item.put("modes", derivedModes);
        item.put("managerId", "0x0B");
        item.put("constructor", functionName(getFunctionAt(toAddr(MANAGER_CONSTRUCTOR))));
        item.put("wrapperFunction", functionName(getFunctionAt(toAddr(MODE2_MANAGER0B_BUILDER))));
        item.put("argumentEvidence", pathEvidence);
        item.put("instructionWindow", instructionWindow(callsite, 12));
        item.put("evidence", List.of("ghidra:cfg-callsite:" + hex(callsite.getAddress().getOffset(), 8)));
        return item;
    }

    private List<Map<String, Object>> widgetConstructions(Map<Long, Set<String>> modes) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Instruction callsite : directCallsites(WIDGET_CONSTRUCTOR)) {
            Function caller = getFunctionContaining(callsite.getAddress());
            List<Map<String, Object>> pushes = precedingPushes(callsite, 3);
            Long category = null;
            Long index = null;
            if (pushes.size() >= 1 && pushes.get(0).get("scalar") != null) {
                long value = Long.decode(String.valueOf(pushes.get(0).get("scalar")));
                if (value <= 0xff) category = value;
            }
            if (pushes.size() >= 2 && pushes.get(1).get("scalar") != null) {
                long value = Long.decode(String.valueOf(pushes.get(1).get("scalar")));
                if (value <= 0xffff) index = value;
            }
            Set<Long> managerIds = managerIdsIn(caller);
            List<String> managers = new ArrayList<>();
            if (managerIds.size() == 1) managers.add(hex(managerIds.iterator().next(), 2));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("candidateId", "WIDGET:" + callsite.getAddress().toString().toUpperCase(Locale.ROOT));
            item.put("constructionSite", hex(callsite.getAddress().getOffset(), 8));
            item.put("builderFunction", functionName(caller));
            item.put("modes", modesFor(caller, modes));
            item.put("managerIds", managers);
            item.put("category", category == null ? null : hex(category, 2));
            item.put("index", index == null ? null : index);
            item.put("constructor", functionName(getFunctionAt(toAddr(WIDGET_CONSTRUCTOR))));
            item.put("constructorDefaultHitTest", 0);
            item.put("interactionKind", "UNKNOWN");
            item.put("label", unknownLabel());
            item.put("event", unknownEvent());
            item.put("handler", unknownHandler());
            item.put("enablement", unknownState("widget+0x15"));
            item.put("visibility", unknownState(null));
            item.put("childManagers", unknownChild());
            item.put("reachability", "UNKNOWN");
            item.put("reachabilityEvidence", List.of("ghidra:callsite:" + hex(callsite.getAddress().getOffset(), 8)));
            item.put("argumentEvidence", pushes);
            item.put("instructionWindow", instructionWindow(callsite, 10));
            item.put("evidence", List.of("ghidra:callsite:" + hex(callsite.getAddress().getOffset(), 8)));
            if (callsite.getAddress().getOffset() == MODE2_MANAGER0B_WIDGET_LOOP) {
                item.put("status", "EXCLUDED");
                item.put("exclusionReason", "EXPANDED_SELECTOR_LOOP_TEMPLATE");
            }
            result.add(item);
        }
        result.addAll(mode2Manager0BRows());
        result.sort(Comparator.comparing(item -> String.valueOf(item.get("candidateId"))));
        return result;
    }

    private void requireInstructionContains(long address, String... fragments) {
        Instruction instruction = getInstructionAt(toAddr(address));
        if (instruction == null) throw new IllegalStateException("missing instruction at " + hex(address, 8));
        String text = instruction.toString().toUpperCase(Locale.ROOT).replace(" ", "");
        for (String fragment : fragments) {
            if (!text.contains(fragment.toUpperCase(Locale.ROOT).replace(" ", ""))) {
                throw new IllegalStateException("instruction mismatch at " + hex(address, 8) + ": " + text);
            }
        }
    }

    private List<Map<String, Object>> derivedStackWrites(
        long start, long end, String width, long firstOffset, int count
    ) {
        List<Map<String, Object>> result = new ArrayList<>();
        long stride = width.equalsIgnoreCase("DWORD") ? 4L : 1L;
        long lastOffset = firstOffset + (count - 1L) * stride;
        for (Instruction instruction : currentProgram.getListing().getInstructions(toAddr(start), true)) {
            if (instruction.getAddress().getOffset() > end) break;
            if (!instruction.getMnemonicString().equalsIgnoreCase("MOV")) continue;
            String destination = instruction.getDefaultOperandRepresentation(0)
                .toUpperCase(Locale.ROOT).replace(" ", "");
            if (!destination.startsWith(width.toUpperCase(Locale.ROOT) + "PTR[ESP+")) continue;
            Long offset = scalarOperand(instruction, 0);
            if (offset == null || offset < firstOffset || offset > lastOffset) continue;
            Long value = resolvedOperandValue(instruction, 1);
            if (value == null) throw new IllegalStateException("unresolved stack write at " + instruction.getAddress());
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("address", instruction.getAddress().getOffset());
            record.put("offset", offset);
            record.put("value", value);
            record.put("instruction", instruction.toString());
            result.add(record);
        }
        result.sort(Comparator.comparingLong(item -> ((Number)item.get("offset")).longValue()));
        if (result.size() != count) throw new IllegalStateException("stack write conservation mismatch");
        for (int ordinal = 0; ordinal < count; ordinal++) {
            if (((Number)result.get(ordinal).get("offset")).longValue() != firstOffset + ordinal * stride) {
                throw new IllegalStateException("stack write offsets are not contiguous");
            }
        }
        return result;
    }

    private List<Map<String, Object>> mode2Manager0BRows() {
        if (!isDirectCallTo(getInstructionAt(toAddr(MODE2_MANAGER0B_WIDGET_LOOP)), WIDGET_CONSTRUCTOR)) {
            throw new IllegalStateException("manager 0x0B selector widget loop mismatch");
        }
        requireInstructionContains(0x00519C70L, "XOR", "EBX,EBX");
        requireInstructionContains(0x00519CC1L, "OR", "EDX,0xFFFFFFFF");
        requireInstructionContains(0x00519CCDL, "MOV", "EBP,0x9");
        List<Map<String, Object>> indexWrites = derivedStackWrites(
            0x00519D2EL, 0x00519D7EL, "DWORD", 0x84, 9
        );
        long sentinel = ((Number)indexWrites.get(8).get("value")).longValue();
        if (sentinel != 0xffffffffL) throw new IllegalStateException("selector index sentinel mismatch");
        int[] indexes = new int[8];
        for (int ordinal = 0; ordinal < indexes.length; ordinal++) {
            indexes[ordinal] = Math.toIntExact(((Number)indexWrites.get(ordinal).get("value")).longValue());
        }
        int[] expectedIndexes = new int[] {0x0B, 0x07, 0x09, 0x02, 0x00, 0x08, 0x0A, 0x0C};
        if (!java.util.Arrays.equals(indexes, expectedIndexes)) throw new IllegalStateException("selector indexes changed");
        List<Map<String, Object>> gateWrites = derivedStackWrites(
            0x00519DAAL, 0x00519E75L, "BYTE", 0x2d, 8
        );
        int[] initialGates = new int[8];
        for (int ordinal = 0; ordinal < initialGates.length; ordinal++) {
            initialGates[ordinal] = Math.toIntExact(((Number)gateWrites.get(ordinal).get("value")).longValue());
        }
        int[] expectedInitialGates = new int[] {1, 0, 0, 1, 1, 0, 0, 0};
        if (!java.util.Arrays.equals(initialGates, expectedInitialGates)) throw new IllegalStateException("selector gates changed");
        Instruction childLookup = getInstructionAt(toAddr(0x00519FC0L));
        List<Instruction> childLookupPushes = precedingPushInstructions(childLookup, 2);
        if (!isDirectCallTo(childLookup, 0x00502780L) || childLookupPushes.size() != 2) {
            throw new IllegalStateException("manager 0x0B child lookup mismatch");
        }
        int finalIndex = Math.toIntExact(resolvedOperandValue(childLookupPushes.get(1), 0));
        if (!isDirectCallTo(getInstructionAt(toAddr(0x0051A006L)), CHILD_ATTACH) ||
            !isDirectCallTo(getInstructionAt(toAddr(0x0051A010L)), ENABLE_WRITER)) {
            throw new IllegalStateException("manager 0x0B index-7 finalizer mismatch");
        }
        List<Instruction> finalEnablePushes = precedingPushInstructions(getInstructionAt(toAddr(0x0051A010L)), 2);
        if (finalEnablePushes.size() != 2) throw new IllegalStateException("manager 0x0B final gate argument mismatch");
        int finalGate = Math.toIntExact(resolvedOperandValue(finalEnablePushes.get(1), 0));
        int[] finalGates = initialGates.clone();
        int finalOrdinal = -1;
        for (int ordinal = 0; ordinal < indexes.length; ordinal++) if (indexes[ordinal] == finalIndex) finalOrdinal = ordinal;
        if (finalOrdinal < 0) throw new IllegalStateException("manager 0x0B final index is absent");
        finalGates[finalOrdinal] = finalGate;
        int[] expectedFinalGates = new int[] {1, 1, 0, 1, 1, 0, 0, 0};
        if (!java.util.Arrays.equals(finalGates, expectedFinalGates)) throw new IllegalStateException("selector final gates changed");
        List<Map<String, Object>> result = new ArrayList<>();
        for (int ordinal = 0; ordinal < indexes.length; ordinal++) {
            int index = indexes[ordinal];
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("candidateId", "WIDGET:MODE2:MANAGER0B:CAT1:INDEX:" + String.format(Locale.ROOT, "%02X", index));
            item.put("constructionSite", hex(MODE2_MANAGER0B_WIDGET_LOOP, 8));
            item.put("builderFunction", functionName(getFunctionAt(toAddr(MODE2_MANAGER0B_BUILDER))));
            item.put("modes", List.of("0x02"));
            item.put("managerIds", List.of("0x0B"));
            item.put("category", "0x01");
            item.put("index", index);
            item.put("constructor", functionName(getFunctionAt(toAddr(WIDGET_CONSTRUCTOR))));
            item.put("constructorDefaultHitTest", 0);
            item.put("interactionKind", index == 7 ? "INTERACTIVE" : "UNKNOWN");
            item.put("label", unknownLabel());
            item.put("event", unknownEvent());
            item.put("handler", unknownHandler());
            Map<String, Object> enablement = new LinkedHashMap<>();
            enablement.put("status", "WRITER_PROVEN");
            enablement.put("stateFields", List.of("widget+0x15"));
            enablement.put("writers", List.of(functionName(getFunctionAt(toAddr(ENABLE_WRITER)))));
            enablement.put("predicates", List.of(
                "constructionGate=" + initialGates[ordinal],
                "finalGate=" + finalGates[ordinal]
            ));
            enablement.put("evidence", List.of(
                "ghidra:gate-write:" + hex(((Number)gateWrites.get(ordinal).get("address")).longValue(), 8),
                index == 7 ? "ghidra:finalizer:0x0051A010" : "ghidra:selector-loop:0x00519F1F"
            ));
            item.put("enablement", enablement);
            item.put("visibility", unknownState(null));
            if (index == 7) {
                Map<String, Object> child = new LinkedHashMap<>();
                child.put("status", "OBSERVED");
                child.put("targetKeys", List.of("UI:MODE:0x02:MANAGER:0x16:CATEGORY:MANAGER_ROOT:INDEX:0000"));
                child.put("reason", "FUN_00504450 attachment");
                child.put("evidence", List.of("ghidra:child-attach:0x0051A006"));
                item.put("childManagers", child);
            }
            else item.put("childManagers", unknownChild());
            item.put("reachability", "UNKNOWN");
            item.put("reachabilityEvidence", List.of("ghidra:mode2-manager0b-selector1"));
            item.put("evidence", List.of(
                "ghidra:index-write:" + hex(((Number)indexWrites.get(ordinal).get("address")).longValue(), 8),
                "ghidra:gate-write:" + hex(((Number)gateWrites.get(ordinal).get("address")).longValue(), 8),
                "ghidra:widget-loop:" + hex(MODE2_MANAGER0B_WIDGET_LOOP, 8)
            ));
            result.add(item);
        }
        return result;
    }

    private Map<String, Object> unknownLabel() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UNKNOWN");
        result.put("text", null);
        result.put("source", null);
        result.put("consumerFunctions", new ArrayList<>());
        result.put("evidence", List.of("ghidra:ui-surface:unjoined"));
        return result;
    }

    private Map<String, Object> unknownEvent() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UNKNOWN");
        result.put("namespace", "UNKNOWN");
        result.put("types", new ArrayList<>());
        result.put("predicates", new ArrayList<>());
        result.put("evidence", List.of("ghidra:ui-surface:unjoined"));
        return result;
    }

    private Map<String, Object> unknownHandler() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UNKNOWN");
        result.put("functions", new ArrayList<>());
        result.put("reason", "not yet joined");
        result.put("evidence", List.of("ghidra:ui-surface:unjoined"));
        return result;
    }

    private Map<String, Object> unknownState(String field) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", field == null ? "UNKNOWN" : "CANDIDATE");
        result.put("stateFields", field == null ? new ArrayList<>() : List.of(field));
        result.put("writers", new ArrayList<>());
        result.put("predicates", new ArrayList<>());
        result.put("evidence", List.of("ghidra:ui-surface:unjoined"));
        return result;
    }

    private Map<String, Object> unknownChild() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UNKNOWN");
        result.put("targetKeys", new ArrayList<>());
        result.put("reason", "not yet joined");
        result.put("evidence", List.of("ghidra:ui-surface:unjoined"));
        return result;
    }

    private List<Map<String, Object>> callCandidates(long target, String prefix) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Instruction callsite : directCallsites(target)) {
            Function caller = getFunctionContaining(callsite.getAddress());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("candidateId", prefix + ":" + callsite.getAddress().toString().toUpperCase(Locale.ROOT));
            item.put("callsite", hex(callsite.getAddress().getOffset(), 8));
            item.put("function", functionName(caller));
            item.put("target", functionName(getFunctionAt(toAddr(target))));
            item.put("arguments", precedingPushes(callsite, 4));
            item.put("status", "UNJOINED");
            item.put("instructionWindow", instructionWindow(callsite, 8));
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> handlerCandidates() {
        Map<Long, Map<String, Object>> unique = new LinkedHashMap<>();
        for (Instruction callsite : directCallsites(EVENT_PREDICATE)) {
            Function caller = getFunctionContaining(callsite.getAddress());
            if (caller == null) continue;
            long entry = caller.getEntryPoint().getOffset();
            if (unique.containsKey(entry)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("candidateId", "HANDLER:" + caller.getEntryPoint().toString().toUpperCase(Locale.ROOT));
            item.put("function", functionName(caller));
            item.put("entry", hex(entry, 8));
            item.put("source", "CALLS_EVENT_PREDICATE");
            item.put("status", "UNJOINED");
            unique.put(entry, item);
        }
        return new ArrayList<>(unique.values());
    }

    private List<Map<String, Object>> eventTypeCases(DecompInterface decompiler) {
        String text = decompile(decompiler, EVENT_PREDICATE);
        Pattern pattern = Pattern.compile("(?m)^\\s*case\\s+(0x[0-9a-fA-F]+|[0-9]+)\\s*:");
        Matcher matcher = pattern.matcher(text);
        Set<Long> derived = new LinkedHashSet<>();
        while (matcher.find()) derived.add(Long.decode(matcher.group(1)));
        Set<Long> expected = new LinkedHashSet<>();
        for (long value = 0; value <= 0x0f; value++) expected.add(value);
        for (long value = 0x12; value <= 0x17; value++) expected.add(value);
        if (!derived.equals(expected)) {
            throw new IllegalStateException("UI event-type case set mismatch: " + derived);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (long eventType : derived) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("candidateId", "EVENT_TYPE:" + String.format(Locale.ROOT, "%02X", eventType));
            item.put("function", functionName(getFunctionAt(toAddr(EVENT_PREDICATE))));
            item.put("eventType", hex(eventType, 2));
            item.put("source", "SWITCH_CASE");
            item.put("status", "UNJOINED");
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> inputSourceCandidates() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (long entry : new long[] {WIN32_INPUT, INPUT_SNAPSHOT, DIRECT_INPUT_POLL}) {
            Function function = getFunctionAt(toAddr(entry));
            for (Instruction instruction : currentProgram.getListing().getInstructions(function.getBody(), true)) {
                if (!instruction.getMnemonicString().equalsIgnoreCase("CALL")) continue;
                List<String> callees = new ArrayList<>();
                for (Address flow : instruction.getFlows()) {
                    Function callee = getFunctionAt(flow);
                    callees.add(functionName(callee) + "@" + flow);
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("candidateId", "INPUT:" + instruction.getAddress().toString().toUpperCase(Locale.ROOT));
                item.put("callsite", hex(instruction.getAddress().getOffset(), 8));
                item.put("function", functionName(function));
                item.put("callees", callees);
                item.put("instruction", instruction.toString());
                item.put("status", "UNJOINED");
                result.add(item);
            }
        }
        return result;
    }

    private List<Map<String, Object>> renderCandidates() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (long target : RENDER_ANCHORS) result.addAll(callCandidates(target, "RENDER" + hex(target, 8)));
        result.sort(Comparator.comparing(item -> String.valueOf(item.get("candidateId"))));
        return result;
    }

    @Override
    protected void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length != 6) {
            throw new IllegalArgumentException("usage: <output> <expected-executable-sha256> <exporter-sha256> <ghidra-repository-sha256> <message-data-path> <message-data-sha256>");
        }
        String executableSha = currentProgram.getExecutableSHA256().toUpperCase(Locale.ROOT);
        if (!executableSha.equals(args[1].toUpperCase(Locale.ROOT))) {
            throw new IllegalStateException("program executable SHA-256 mismatch");
        }
        DecompInterface decompiler = new DecompInterface();
        decompiler.openProgram(currentProgram);
        List<Map<String, Object>> rootModes;
        List<Map<String, Object>> menuRows;
        List<Map<String, Object>> eventTypeCases;
        try {
            rootModes = rootModes();
            menuRows = menuRows(decompiler, new File(args[4]), args[5]);
            eventTypeCases = eventTypeCases(decompiler);
        }
        finally {
            decompiler.dispose();
        }
        Map<Long, Set<String>> modeReachability = computeModeReachability();
        Map<String, Object> surface = new LinkedHashMap<>();
        surface.put("rootModes", rootModes);
        surface.put("managerConstructions", managerConstructions(modeReachability, rootModes));
        surface.put("managerLookupCandidates", callCandidates(MANAGER_LOOKUP, "MANAGER_LOOKUP"));
        surface.put("widgetConstructions", widgetConstructions(modeReachability));
        surface.put("menuRows", menuRows);
        surface.put("descriptorLoaderCandidates", callCandidates(DESCRIPTOR_LOADER, "DESCRIPTOR_LOADER"));
        surface.put("labelCandidates", callCandidates(LABEL_LOOKUP, "LABEL"));
        List<Map<String, Object>> eventCandidates = callCandidates(EVENT_PREDICATE, "EVENT");
        eventCandidates.addAll(eventTypeCases);
        eventCandidates.sort(Comparator.comparing(item -> String.valueOf(item.get("candidateId"))));
        surface.put("eventCandidates", eventCandidates);
        surface.put("handlerCandidates", handlerCandidates());
        surface.put("enablementCandidates", callCandidates(ENABLE_WRITER, "ENABLEMENT"));
        surface.put("visibilityCandidates", callCandidates(VISIBILITY_WRITER, "VISIBILITY"));
        surface.put("childManagerCandidates", callCandidates(CHILD_ATTACH, "CHILD_MANAGER"));
        surface.put("inputSourceCandidates", inputSourceCandidates());
        surface.put("renderCandidates", renderCandidates());

        if (((List<?>)surface.get("rootModes")).size() != 3 ||
            ((List<?>)surface.get("managerConstructions")).size() != 50 ||
            ((List<?>)surface.get("widgetConstructions")).size() != 348 ||
            ((List<?>)surface.get("menuRows")).size() != 7 ||
            ((List<?>)surface.get("managerLookupCandidates")).size() != 260 ||
            ((List<?>)surface.get("descriptorLoaderCandidates")).size() != 11 ||
            ((List<?>)surface.get("labelCandidates")).size() != 598 ||
            ((List<?>)surface.get("eventCandidates")).size() != 277 ||
            ((List<?>)surface.get("handlerCandidates")).size() != 50 ||
            ((List<?>)surface.get("enablementCandidates")).size() != 453 ||
            ((List<?>)surface.get("visibilityCandidates")).size() != 19 ||
            ((List<?>)surface.get("childManagerCandidates")).size() != 4 ||
            ((List<?>)surface.get("inputSourceCandidates")).size() != 37 ||
            ((List<?>)surface.get("renderCandidates")).size() != 2) {
            throw new IllegalStateException("UI anchor conservation mismatch");
        }
        long getAsyncKeyStateCalls = ((List<Map<String, Object>>)surface.get("inputSourceCandidates")).stream()
            .flatMap(item -> ((List<String>)item.get("callees")).stream())
            .filter(name -> name.startsWith("GETASYNCKEYSTATE@"))
            .count();
        if (getAsyncKeyStateCalls != 16) {
            throw new IllegalStateException("GetAsyncKeyState call conservation mismatch");
        }

        Gson compact = new Gson();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("schemaVersion", 1);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("program", currentProgram.getName());
        source.put("executableSha256", executableSha);
        source.put("language", currentProgram.getLanguageID().toString());
        source.put("compiler", currentProgram.getCompilerSpec().getCompilerSpecID().toString());
        source.put("imageBase", currentProgram.getImageBase().toString());
        source.put("messageDataPath", new File(args[4]).getAbsolutePath());
        source.put("messageDataSha256", args[5].toUpperCase(Locale.ROOT));
        output.put("source", source);
        Map<String, Object> exporter = new LinkedHashMap<>();
        exporter.put("class", getClass().getSimpleName());
        exporter.put("sha256", args[2].toUpperCase(Locale.ROOT));
        exporter.put("ghidraRepositorySha256", args[3].toUpperCase(Locale.ROOT));
        output.put("exporter", exporter);
        output.put("surfaceSha256", sha256(compact.toJson(surface).getBytes(StandardCharsets.UTF_8)));
        output.put("successMarker", "EXPORT_EXHAUSTIVE_UI_OK");
        output.putAll(surface);

        File outputFile = new File(args[0]);
        File parent = outputFile.getParentFile();
        if (parent != null && !parent.isDirectory()) throw new IllegalStateException("output directory does not exist");
        Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(outputFile), StandardCharsets.UTF_8))) {
            writer.print(gson.toJson(output));
            writer.print("\n");
        }
        println("EXPORT_EXHAUSTIVE_UI_OK output=" + outputFile.getAbsolutePath());
    }
}
