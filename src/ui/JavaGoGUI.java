package ui;

import core.Runner;
import core.Transpiler;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;

public final class JavaGoGUI {
    private JavaGoGUI() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(JavaGoGUI::show);
    }

    private static void show() {
        JFrame frame = new JFrame("JavaGo IDE");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1200, 800);

        Transpiler transpiler = new Transpiler();
        Runner runner = new Runner();

        JTextArea input = area(sampleProgram());
        JTextArea generated = area("");
        JTextArea output = area("");
        generated.setEditable(false);
        output.setEditable(false);

        JButton transpile = new JButton("Transpile");
        transpile.addActionListener(event -> {
            try {
                Transpiler.Result result = transpiler.transpile(input.getText(), "Test");
                generated.setText(result.getJavaCode());
                output.setText(String.join(System.lineSeparator(), result.getWarnings()));
            } catch (RuntimeException ex) {
                output.setText(message(ex));
            }
        });

        JButton run = new JButton("Run");
        run.addActionListener(event -> {
            try {
                Transpiler.Result result = transpiler.transpile(input.getText(), "Test");
                generated.setText(result.getJavaCode());
                Runner.RunResult runResult = runner.compileAndRun(result.getJavaCode(), result.getClassName());
                String warnings = String.join(System.lineSeparator(), result.getWarnings());
                if (!warnings.isBlank() && !runResult.getOutput().isBlank()) {
                    output.setText(warnings + System.lineSeparator() + runResult.getOutput());
                } else if (!warnings.isBlank()) {
                    output.setText(warnings);
                } else {
                    output.setText(runResult.getOutput());
                }
            } catch (Exception ex) {
                output.setText(message(ex));
            }
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        buttons.add(transpile);
        buttons.add(run);

        JScrollPane inputPane = new JScrollPane(input);
        inputPane.setBorder(BorderFactory.createTitledBorder("JavaGo Code"));
        JScrollPane generatedPane = new JScrollPane(generated);
        generatedPane.setBorder(BorderFactory.createTitledBorder("Generated Java"));
        JScrollPane outputPane = new JScrollPane(output);
        outputPane.setBorder(BorderFactory.createTitledBorder("Output"));

        JSplitPane editorSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, inputPane, generatedPane);
        editorSplit.setResizeWeight(0.5);
        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorSplit, outputPane);
        mainSplit.setResizeWeight(0.7);

        frame.add(buttons, BorderLayout.NORTH);
        frame.add(mainSplit, BorderLayout.CENTER);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static JTextArea area(String text) {
        JTextArea area = new JTextArea(text);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        return area;
    }

    private static String message(Exception ex) {
        return ex.getMessage() == null ? "JavaGo failed." : ex.getMessage();
    }

    private static String sampleProgram() {
        return "class Test {\n"
                + "    main() {\n"
                + "        Go.println(\"Hello from JavaGo\");\n"
                + "    }\n"
                + "}\n";
    }
}
