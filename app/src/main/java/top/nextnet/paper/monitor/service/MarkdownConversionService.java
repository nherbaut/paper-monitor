package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class MarkdownConversionService {

    public byte[] convertWithPandoc(String markdown, String format) throws IOException {
        java.nio.file.Path inputFile = Files.createTempFile("paper-monitor-export-", ".md");
        java.nio.file.Path outputFile = Files.createTempFile("paper-monitor-export-", "." + format);
        try {
            Files.writeString(inputFile, markdown, StandardCharsets.UTF_8);
            List<String> command = new ArrayList<>();
            command.add("pandoc");
            command.add(inputFile.toString());
            if ("pdf".equals(format)) {
                command.add("--pdf-engine=xelatex");
            }
            command.add("-o");
            command.add(outputFile.toString());

            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            byte[] processOutput = process.getInputStream().readAllBytes();
            int exitCode;
            try {
                exitCode = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Pandoc conversion interrupted", e);
            }

            if (exitCode != 0) {
                String detail = new String(processOutput, StandardCharsets.UTF_8).trim();
                throw new IOException(detail.isEmpty() ? "Pandoc conversion failed" : detail);
            }

            return Files.readAllBytes(outputFile);
        } finally {
            Files.deleteIfExists(inputFile);
            Files.deleteIfExists(outputFile);
        }
    }
}
