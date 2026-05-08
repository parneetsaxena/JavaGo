package javago.transpiler;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public final class JavaGoTranspiler {
    private static final Pattern INPUT_PROMPT_ASSIGNMENT = Pattern.compile(
            "^(\\s*)([A-Za-z_$][A-Za-z0-9_$\\[\\]]*)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*$"
    );
    private static final Map<String, UtilityDef> UTILITIES = Map.of(
            "Scanner", new UtilityDef("java.util.Scanner", "new Scanner(System.in)"),
            "Random", new UtilityDef("java.util.Random", "new Random()"),
            "ArrayList", new UtilityDef("java.util.ArrayList", "new ArrayList<>()"),
            "HashMap", new UtilityDef("java.util.HashMap", "new HashMap<>()"),
            "FileWriter", new UtilityDef("java.io.FileWriter", null),
            "FileReader", new UtilityDef("java.io.FileReader", null),
            "Connection", new UtilityDef("java.sql.Connection", null)
    );
    private static final Map<String, String> INPUT_METHODS = Map.of(
            "int", "nextInt()",
            "long", "nextLong()",
            "double", "nextDouble()",
            "float", "nextFloat()",
            "boolean", "nextBoolean()",
            "char", "next().charAt(0)",
            "String", "nextLine()"
    );

    public String transpile(String input) {
        return transpile(input, "Main").javaCode();
    }

    public TranspileResult transpile(String input, String fallbackClassName) {
        Set<String> sourceScannerVariables = scannerVariablesFromSource(input);
        input = preprocessLines(input, sourceScannerVariables);
        List<Token> tokens = tokenize(input);
        List<UtilitySpec> utilities = removeUtilities(tokens);
        Set<String> scannerVariables = scannerVariables(utilities);
        List<String> warnings = new ArrayList<>();
        ensureArraysImport(tokens);
        replaceGoPrintln(tokens);
        replaceInputCalls(tokens, scannerVariables);
        expandMain(tokens);
        injectUtilities(tokens, utilities, warnings);

        String className = findClassName(tokens, fallbackClassName);
        return new TranspileResult(generate(tokens), className, warnings);
    }

    private Set<String> scannerVariables(List<UtilitySpec> utilities) {
        Set<String> scannerVariables = new LinkedHashSet<>();
        for (UtilitySpec utility : utilities) {
            if ("Scanner".equals(utility.type) && utility.name != null && !utility.name.isBlank()) {
                scannerVariables.add(utility.name);
            }
        }
        return scannerVariables;
    }

    private Set<String> scannerVariablesFromSource(String input) {
        Set<String> scannerVariables = new LinkedHashSet<>();
        int searchFrom = 0;

        while (true) {
            int utilityIndex = input.indexOf("Go.utilities(", searchFrom);
            if (utilityIndex < 0) {
                return scannerVariables;
            }

            int openParen = input.indexOf('(', utilityIndex);
            int closeParen = findClosingParen(input, openParen);
            if (openParen < 0 || closeParen < 0) {
                return scannerVariables;
            }

            for (String param : splitArguments(input.substring(openParen + 1, closeParen))) {
                String[] parts = param.trim().split("\\s+");
                if (parts.length >= 2 && "Scanner".equals(parts[0])) {
                    scannerVariables.add(parts[1]);
                }
            }
            searchFrom = closeParen + 1;
        }
    }

    private String preprocessLines(String input, Set<String> scannerVariables) {
        StringBuilder output = new StringBuilder();
        int start = 0;

        for (int index = 0; index < input.length(); index++) {
            if (input.charAt(index) == '\n') {
                output.append(processUtilityFunctions(input.substring(start, index), scannerVariables));
                output.append('\n');
                start = index + 1;
            }
        }

        if (start < input.length()) {
            output.append(processUtilityFunctions(input.substring(start), scannerVariables));
        } else if (!input.isEmpty() && input.charAt(input.length() - 1) == '\n') {
            output.append(processUtilityFunctions("", scannerVariables));
        }

        return output.toString();
    }

    private String processUtilityFunctions(String line, Set<String> scannerVariables) {
        String updated = processOutputCall(line, "println", "System.out.println");
        updated = processOutputCall(updated, "print", "System.out.print");
        updated = processPrintArr(updated);
        updated = processSwap(updated);
        updated = processInputPrompt(updated, scannerVariables);
        return processPrintMatrix(updated);
    }

    private String processOutputCall(String line, String methodName, String replacementMethod) {
        String currentLine = line;
        int searchFrom = 0;
        String pattern = "Go." + methodName + "(";

        while (true) {
            int printIndex = currentLine.indexOf(pattern, searchFrom);
            if (printIndex < 0) {
                return currentLine;
            }

            int openParen = currentLine.indexOf('(', printIndex);
            int closeParen = findClosingParen(currentLine, openParen);
            if (openParen < 0 || closeParen < 0) {
                return currentLine;
            }

            String argsText = currentLine.substring(openParen + 1, closeParen);
            List<String> args = splitArguments(argsText);
            StringBuilder replacement = new StringBuilder(replacementMethod).append("(");
            if (args.isEmpty()) {
                replacement.append(")");
            } else {
                replacement.append(buildPrintExpression(args)).append(")");
            }

            currentLine = currentLine.substring(0, printIndex)
                    + replacement
                    + currentLine.substring(closeParen + 1);
            searchFrom = printIndex + replacement.length();
        }
    }

    private String processPrintArr(String line) {
        return replaceSingleArgumentCall(line, "Go.printArr(", argument ->
                "System.out.println(Arrays.toString(" + argument + "))");
    }

    private String processSwap(String line) {
        return replaceArgumentListCall(line, "Go.swap(", args -> {
            if (args.size() != 3) {
                throw new IllegalArgumentException("Go.swap(...) requires exactly 3 arguments.");
            }
            String array = args.get(0);
            String firstIndex = args.get(1);
            String secondIndex = args.get(2);
            return "int temp = " + array + "[" + firstIndex + "]; "
                    + array + "[" + firstIndex + "] = " + array + "[" + secondIndex + "]; "
                    + array + "[" + secondIndex + "] = temp";
        });
    }

    private String processInputPrompt(String line, Set<String> scannerVariables) {
        int promptIndex = line.indexOf("Go.inputPrompt(");
        if (promptIndex < 0) {
            return line;
        }
        if (scannerVariables.isEmpty()) {
            throw new IllegalArgumentException("Go.inputPrompt(...) requires a Scanner from Go.utilities(...).");
        }

        int openParen = line.indexOf('(', promptIndex);
        int closeParen = findClosingParen(line, openParen);
        if (openParen < 0 || closeParen < 0) {
            return line;
        }

        String promptValue = line.substring(openParen + 1, closeParen).trim();
        Matcher matcher = INPUT_PROMPT_ASSIGNMENT.matcher(line.substring(0, promptIndex));
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Go.inputPrompt(...) requires an explicit typed assignment.");
        }

        String indentation = matcher.group(1);
        String type = matcher.group(2);
        String variable = matcher.group(3);
        String scannerCall = INPUT_METHODS.get(type);
        if (scannerCall == null) {
            throw new IllegalArgumentException("Unsupported type for Go.inputPrompt(...): " + type);
        }

        String scannerName = scannerVariables.iterator().next();
        String trailing = line.substring(closeParen + 1).trim();
        if (!trailing.isEmpty() && !";".equals(trailing)) {
            return line;
        }

        return indentation + "System.out.print(" + promptValue + "); "
                + type + " " + variable + " = " + scannerName + "." + scannerCall + ";";
    }

    private String processPrintMatrix(String line) {
        int callIndex = line.indexOf("Go.printMatrix(");
        if (callIndex < 0) {
            return line;
        }

        int openParen = line.indexOf('(', callIndex);
        int closeParen = findClosingParen(line, openParen);
        if (openParen < 0 || closeParen < 0) {
            return line;
        }

        List<String> args = splitArguments(line.substring(openParen + 1, closeParen));
        if (args.size() != 1) {
            throw new IllegalArgumentException("Go.printMatrix requires exactly 1 argument.");
        }

        String suffix = line.substring(closeParen + 1);
        if (suffix.stripLeading().startsWith(";")) {
            int semicolonIndex = suffix.indexOf(';');
            suffix = suffix.substring(semicolonIndex + 1);
        }

        return line.substring(0, callIndex)
                + "for (int[] row : " + args.get(0) + ") { System.out.println(Arrays.toString(row)); }"
                + suffix;
    }

    private String replaceSingleArgumentCall(String line, String callPrefix, ReplacementBuilder replacementBuilder) {
        return replaceArgumentListCall(line, callPrefix, args -> {
            if (args.size() != 1) {
                throw new IllegalArgumentException(callPrefix.substring(0, callPrefix.length() - 1)
                        + " requires exactly 1 argument.");
            }
            return replacementBuilder.build(args.get(0));
        });
    }

    private String replaceArgumentListCall(String line, String callPrefix, ArgsReplacementBuilder replacementBuilder) {
        int callIndex = line.indexOf(callPrefix);
        if (callIndex < 0) {
            return line;
        }

        int openParen = line.indexOf('(', callIndex);
        int closeParen = findClosingParen(line, openParen);
        if (openParen < 0 || closeParen < 0) {
            return line;
        }

        List<String> args = splitArguments(line.substring(openParen + 1, closeParen));
        String replacement = replacementBuilder.build(args);
        return line.substring(0, callIndex) + replacement + line.substring(closeParen + 1);
    }

    private int findClosingParen(String line, int openParen) {
        if (openParen < 0) {
            return -1;
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int index = openParen; index < line.length(); index++) {
            char current = line.charAt(index);
            if (inString) {
                if (current == '"' && !escaped) {
                    inString = false;
                }
                escaped = current == '\\' && !escaped;
                if (current != '\\') {
                    escaped = false;
                }
                continue;
            }

            if (current == '"') {
                inString = true;
                escaped = false;
                continue;
            }

            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }

        return -1;
    }

    private List<String> splitArguments(String argsText) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int index = 0; index < argsText.length(); index++) {
            char ch = argsText.charAt(index);
            if (inString) {
                current.append(ch);
                if (ch == '"' && !escaped) {
                    inString = false;
                }
                escaped = ch == '\\' && !escaped;
                if (ch != '\\') {
                    escaped = false;
                }
                continue;
            }

            if (ch == '"') {
                inString = true;
                escaped = false;
                current.append(ch);
                continue;
            }

            if (ch == '(') {
                depth++;
                current.append(ch);
                continue;
            }

            if (ch == ')') {
                depth--;
                current.append(ch);
                continue;
            }

            if (ch == ',' && depth == 0) {
                args.add(current.toString().trim());
                current.setLength(0);
                continue;
            }

            current.append(ch);
        }

        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            args.add(tail);
        }
        return args;
    }

    private String buildPrintExpression(List<String> args) {
        if (args.size() == 1) {
            return normalizePrintArgument(args.get(0));
        }

        StringBuilder expression = new StringBuilder();
        String previous = null;

        for (int index = 0; index < args.size(); index++) {
            String current = normalizePrintArgument(args.get(index));
            if (index > 0) {
                if (needsSpaceBetween(previous, current)) {
                    expression.append(" + \" \"");
                }
                expression.append(" + ");
            }
            expression.append(current);
            previous = current;
        }

        return expression.toString();
    }

    private String normalizePrintArgument(String argument) {
        String trimmed = argument.trim();
        if (trimmed.isEmpty()) {
            return "\"\"";
        }
        if (isStringLiteral(trimmed)) {
            return trimmed;
        }
        if (isExpression(trimmed)) {
            return "(" + trimmed + ")";
        }
        return trimmed;
    }

    private boolean needsSpaceBetween(String previous, String current) {
        return !endsWithWhitespace(previous) && !startsWithWhitespace(current);
    }

    private boolean startsWithWhitespace(String value) {
        if (!isStringLiteral(value) || value.length() < 2) {
            return false;
        }
        return Character.isWhitespace(value.charAt(1));
    }

    private boolean endsWithWhitespace(String value) {
        if (!isStringLiteral(value) || value.length() < 2) {
            return false;
        }
        return Character.isWhitespace(value.charAt(value.length() - 2));
    }

    private boolean isStringLiteral(String value) {
        return value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"';
    }

    private boolean isExpression(String value) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (inString) {
                if (ch == '"' && !escaped) {
                    inString = false;
                }
                escaped = ch == '\\' && !escaped;
                if (ch != '\\') {
                    escaped = false;
                }
                continue;
            }

            if (ch == '"') {
                inString = true;
                escaped = false;
                continue;
            }

            if (ch == '(') {
                depth++;
                continue;
            }

            if (ch == ')') {
                depth = Math.max(0, depth - 1);
                continue;
            }

            if (depth == 0 && isExpressionOperator(value, index, ch)) {
                return true;
            }
        }

        return false;
    }

    private boolean isExpressionOperator(String value, int index, char ch) {
        if (ch == '+' || ch == '*' || ch == '/') {
            return true;
        }
        if (ch == '-') {
            return index > 0 && !Character.isWhitespace(value.charAt(index - 1))
                    && value.charAt(index - 1) != '(' && value.charAt(index - 1) != ',';
        }
        return false;
    }

    private List<Token> tokenize(String input) {
        List<Token> tokens = new ArrayList<>();
        int index = 0;

        while (index < input.length()) {
            char current = input.charAt(index);

            if (Character.isWhitespace(current)) {
                index++;
                continue;
            }

            if (current == '"') {
                int start = index++;
                boolean escaped = false;
                boolean closed = false;
                while (index < input.length()) {
                    char ch = input.charAt(index);
                    if (ch == '"' && !escaped) {
                        index++;
                        closed = true;
                        break;
                    }
                    escaped = ch == '\\' && !escaped;
                    if (ch != '\\') {
                        escaped = false;
                    }
                    index++;
                }
                if (!closed) {
                    throw new IllegalArgumentException("Unterminated string literal.");
                }
                tokens.add(new Token(input.substring(start, index), true));
                continue;
            }

            if (Character.isJavaIdentifierStart(current)) {
                int start = index++;
                while (index < input.length() && Character.isJavaIdentifierPart(input.charAt(index))) {
                    index++;
                }
                tokens.add(new Token(input.substring(start, index), false));
                continue;
            }

            if (Character.isDigit(current)) {
                int start = index++;
                while (index < input.length() && Character.isDigit(input.charAt(index))) {
                    index++;
                }
                tokens.add(new Token(input.substring(start, index), false));
                continue;
            }

            tokens.add(new Token(String.valueOf(current), false));
            index++;
        }

        return tokens;
    }

    private List<UtilitySpec> removeUtilities(List<Token> tokens) {
        List<UtilitySpec> utilities = new ArrayList<>();
        int index = 0;

        while (index <= tokens.size() - 4) {
            if (!matches(tokens, index, "Go", ".", "utilities", "(")) {
                index++;
                continue;
            }

            int closeParen = findClosingParen(tokens, index + 3);
            if (closeParen == -1) {
                throw new IllegalArgumentException("Invalid Go.utilities(...) directive.");
            }

            utilities.addAll(parseUtilityParams(tokens.subList(index + 4, closeParen)));
            int end = closeParen + 1;
            if (end < tokens.size() && tokens.get(end).is(";")) {
                end++;
            }
            tokens.subList(index, end).clear();
        }

        return utilities;
    }

    private int findClosingParen(List<Token> tokens, int openParen) {
        int depth = 0;
        for (int index = openParen; index < tokens.size(); index++) {
            if (tokens.get(index).is("(")) {
                depth++;
            } else if (tokens.get(index).is(")")) {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    private List<UtilitySpec> parseUtilityParams(List<Token> params) {
        List<UtilitySpec> utilities = new ArrayList<>();
        StringBuilder text = new StringBuilder();

        for (Token token : params) {
            if (token.is(",")) {
                text.append(",");
            } else {
                if (text.length() > 0 && text.charAt(text.length() - 1) != ',') {
                    text.append(' ');
                }
                text.append(token.text);
            }
        }

        String[] paramsByComma = text.toString().split(",");
        for (String param : paramsByComma) {
            String trimmed = param.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            String[] parts = trimmed.split("\\s+");
            String type = parts[0];
            String name = parts.length >= 2 ? parts[1] : null;
            utilities.add(new UtilitySpec(type, name));
        }

        return utilities;
    }

    private void replaceGoPrintln(List<Token> tokens) {
        int index = 0;
        while (index <= tokens.size() - 4) {
            if (matches(tokens, index, "Go", ".", "println", "(")) {
                tokens.subList(index, index + 3).clear();
                tokens.add(index, new Token("println", false));
                tokens.add(index, new Token(".", false));
                tokens.add(index, new Token("out", false));
                tokens.add(index, new Token(".", false));
                tokens.add(index, new Token("System", false));
                index += 5;
                continue;
            }
            index++;
        }
    }

    private void ensureArraysImport(List<Token> tokens) {
        if (contains(tokens, "Arrays", ".", "toString", "(")) {
            ensureImport(tokens, "java.util.Arrays");
        }
    }

    private void replaceInputCalls(List<Token> tokens, Set<String> scannerVariables) {
        int index = 1;
        while (index <= tokens.size() - 3) {
            if (!tokens.get(index).is("(")
                    || !tokens.get(index + 1).is(")")
                    || !tokens.get(index + 2).is(";")
                    || !isIdentifierToken(tokens.get(index - 1))) {
                index++;
                continue;
            }

            String scannerName = tokens.get(index - 1).text;
            int assignmentIndex = index - 2;
            if (assignmentIndex < 0 || !tokens.get(assignmentIndex).is("=")) {
                index++;
                continue;
            }

            int declarationStart = findDeclarationStart(tokens, assignmentIndex);
            if (assignmentIndex - declarationStart != 2) {
                throw new IllegalArgumentException(scannerName + "() requires an explicit type declaration.");
            }

            String type = tokens.get(declarationStart).text;
            String scannerCall = INPUT_METHODS.get(type);
            if (scannerCall == null) {
                throw new IllegalArgumentException("Unsupported type for scanner input: " + type);
            }

            if (scannerVariables.isEmpty()) {
                throw new IllegalArgumentException("No Scanner variable defined in Go.utilities(...).");
            }

            if (!scannerVariables.contains(scannerName)) {
                index++;
                continue;
            }

            List<Token> replacement = tokenize(scannerName + "." + scannerCall);
            tokens.subList(index - 1, index + 2).clear();
            tokens.addAll(index - 1, replacement);
            index += replacement.size() - 1;
        }
    }

    private boolean isIdentifierToken(Token token) {
        return token != null
                && !token.stringLiteral
                && !token.text.isEmpty()
                && Character.isJavaIdentifierStart(token.text.charAt(0));
    }

    private void expandMain(List<Token> tokens) {
        int index = 0;
        while (index <= tokens.size() - 4) {
            if (!tokens.get(index).is("main")
                    || !tokens.get(index + 1).is("(")
                    || !tokens.get(index + 2).is(")")
                    || !tokens.get(index + 3).is("{")) {
                index++;
                continue;
            }

            int start = findDeclarationStart(tokens, index);
            List<Token> prefix = new ArrayList<>(tokens.subList(start, index));
            List<Token> replacement = mainSignature(prefix);
            tokens.subList(start, index + 3).clear();
            tokens.addAll(start, replacement);
            index = start + replacement.size();
        }
    }

    private int findDeclarationStart(List<Token> tokens, int mainIndex) {
        int index = mainIndex - 1;
        while (index >= 0) {
            String text = tokens.get(index).text;
            if (";".equals(text) || "{".equals(text) || "}".equals(text)) {
                return index + 1;
            }
            index--;
        }
        return 0;
    }

    private List<Token> mainSignature(List<Token> prefix) {
        String access = "public";
        List<Token> result = new ArrayList<>();

        for (Token token : prefix) {
            if ("public".equals(token.text) || "private".equals(token.text) || "protected".equals(token.text)) {
                access = token.text;
            }
        }

        result.add(new Token(access, false));
        result.add(new Token("static", false));
        result.add(new Token("void", false));
        result.add(new Token("main", false));
        result.add(new Token("(", false));
        result.add(new Token("String", false));
        result.add(new Token("[", false));
        result.add(new Token("]", false));
        result.add(new Token("args", false));
        result.add(new Token(")", false));
        return result;
    }

    private void injectUtilities(List<Token> tokens, List<UtilitySpec> utilities, List<String> warnings) {
        if (utilities.isEmpty()) {
            return;
        }

        for (UtilitySpec utility : utilities) {
            ensureUtilityImport(tokens, utility.type);
        }

        List<Token> declarations = new ArrayList<>();
        for (UtilitySpec utility : utilities) {
            if (utility.name != null) {
                addUtilityDeclaration(declarations, utility, warnings);
            }
        }

        if (!declarations.isEmpty()) {
            int classBody = findClassBodyStart(tokens);
            if (classBody == -1) {
                throw new IllegalArgumentException("Go.utilities(...) requires a class body.");
            }
            tokens.addAll(classBody + 1, declarations);
        }
    }

    private void ensureUtilityImport(List<Token> tokens, String type) {
        UtilityDef utility = UTILITIES.get(type);
        if (utility == null || containsImport(tokens, utility.importPath)) {
            return;
        }

        List<Token> importTokens = importTokens(utility.importPath);
        int insertAt = importInsertIndex(tokens);
        tokens.addAll(insertAt, importTokens);
    }

    private void ensureImport(List<Token> tokens, String importName) {
        if (containsImport(tokens, importName)) {
            return;
        }

        List<Token> importTokens = importTokens(importName);
        int insertAt = importInsertIndex(tokens);
        tokens.addAll(insertAt, importTokens);
    }

    private int importInsertIndex(List<Token> tokens) {
        int insertAt = 0;
        while (insertAt < tokens.size() && tokens.get(insertAt).is("import")) {
            while (insertAt < tokens.size() && !tokens.get(insertAt).is(";")) {
                insertAt++;
            }
            if (insertAt < tokens.size()) {
                insertAt++;
            }
        }
        return insertAt;
    }

    private boolean containsImport(List<Token> tokens, String importName) {
        List<Token> importTokens = importTokens(importName);
        String[] values = new String[importTokens.size()];
        for (int index = 0; index < importTokens.size(); index++) {
            values[index] = importTokens.get(index).text;
        }
        return contains(tokens, values);
    }

    private List<Token> importTokens(String importName) {
        List<Token> tokens = new ArrayList<>();
        tokens.add(new Token("import", false));
        String[] parts = importName.split("\\.");
        for (int index = 0; index < parts.length; index++) {
            tokens.add(new Token(parts[index], false));
            if (index < parts.length - 1) {
                tokens.add(new Token(".", false));
            }
        }
        tokens.add(new Token(";", false));
        return tokens;
    }

    private void addUtilityDeclaration(List<Token> declarations, UtilitySpec utility, List<String> warnings) {
        if (contains(declarations, "static", utility.type, utility.name)) {
            return;
        }

        UtilityDef definition = UTILITIES.get(utility.type);
        if (definition == null) {
            return;
        }

        declarations.addAll(list("static", utility.type, utility.name));
        if (definition.initCode == null) {
            warnings.add("Warning: " + utility.type + " requires manual initialization");
        } else {
            declarations.add(new Token("=", false));
            declarations.addAll(tokenize(definition.initCode));
        }
        declarations.add(new Token(";", false));
    }

    private int findClassBodyStart(List<Token> tokens) {
        for (int index = 0; index < tokens.size(); index++) {
            if (tokens.get(index).is("class")) {
                while (index < tokens.size()) {
                    if (tokens.get(index).is("{")) {
                        return index;
                    }
                    index++;
                }
            }
        }
        return -1;
    }

    private String findClassName(List<Token> tokens, String fallbackClassName) {
        for (int index = 0; index < tokens.size() - 1; index++) {
            if (tokens.get(index).is("class")) {
                return tokens.get(index + 1).text;
            }
        }
        return fallbackClassName == null || fallbackClassName.isBlank() ? "Main" : fallbackClassName;
    }

    private String generate(List<Token> tokens) {
        StringBuilder output = new StringBuilder();
        int indent = 0;
        boolean lineStart = true;

        for (int index = 0; index < tokens.size(); index++) {
            Token current = tokens.get(index);
            Token previous = index > 0 ? tokens.get(index - 1) : null;
            Token next = index + 1 < tokens.size() ? tokens.get(index + 1) : null;

            if (current.is("}")) {
                indent = Math.max(0, indent - 1);
                if (!lineStart) {
                    output.append(System.lineSeparator());
                }
                indent(output, indent);
                output.append("}");
                lineStart = false;
                if (next != null) {
                    output.append(System.lineSeparator());
                    lineStart = true;
                }
                continue;
            }

            if (lineStart) {
                indent(output, indent);
                lineStart = false;
            } else if (needsSpace(previous, current)) {
                output.append(' ');
            }

            output.append(current.text);

            if (current.is("{")) {
                indent++;
                output.append(System.lineSeparator());
                lineStart = true;
            } else if (current.is(";")) {
                output.append(System.lineSeparator());
                lineStart = true;
                if (previousStatementWasImport(tokens, index) && next != null && !next.is("import")) {
                    output.append(System.lineSeparator());
                }
            }
        }

        if (output.length() > 0 && output.charAt(output.length() - 1) != '\n') {
            output.append(System.lineSeparator());
        }
        return output.toString();
    }

    private boolean previousStatementWasImport(List<Token> tokens, int semicolonIndex) {
        int index = semicolonIndex;
        while (index > 0 && !tokens.get(index - 1).is(";") && !tokens.get(index - 1).is("{") && !tokens.get(index - 1).is("}")) {
            index--;
        }
        return tokens.get(index).is("import");
    }

    private boolean needsSpace(Token previous, Token current) {
        if (previous == null) {
            return false;
        }
        if (current.is("(") || current.is("[") || current.is("]") || current.is(".") || current.is(",") || current.is(";")) {
            return false;
        }
        if (previous.is("(") || previous.is("[") || previous.is(".")) {
            return false;
        }
        if (current.is("{")) {
            return true;
        }
        return isWord(previous) && isWord(current) || current.is("=") || previous.is("=");
    }

    private boolean isWord(Token token) {
        return token.stringLiteral || Character.isJavaIdentifierStart(token.text.charAt(0)) || Character.isDigit(token.text.charAt(0));
    }

    private boolean matches(List<Token> tokens, int start, String... values) {
        if (start + values.length > tokens.size()) {
            return false;
        }
        for (int offset = 0; offset < values.length; offset++) {
            if (!tokens.get(start + offset).is(values[offset])) {
                return false;
            }
        }
        return true;
    }

    private boolean contains(List<Token> tokens, String... values) {
        for (int index = 0; index <= tokens.size() - values.length; index++) {
            if (matches(tokens, index, values)) {
                return true;
            }
        }
        return false;
    }

    private List<Token> list(String... values) {
        List<Token> tokens = new ArrayList<>();
        for (String value : values) {
            tokens.add(new Token(value, false));
        }
        return tokens;
    }

    private void indent(StringBuilder output, int indent) {
        for (int i = 0; i < indent; i++) {
            output.append("    ");
        }
    }

    private static final class Token {
        private final String text;
        private final boolean stringLiteral;

        private Token(String text, boolean stringLiteral) {
            this.text = text;
            this.stringLiteral = stringLiteral;
        }

        private boolean is(String expected) {
            return text.equals(expected);
        }
    }

    private record UtilitySpec(String type, String name) {
    }

    private record UtilityDef(String importPath, String initCode) {
    }

    @FunctionalInterface
    private interface ReplacementBuilder {
        String build(String argument);
    }

    @FunctionalInterface
    private interface ArgsReplacementBuilder {
        String build(List<String> arguments);
    }
}
