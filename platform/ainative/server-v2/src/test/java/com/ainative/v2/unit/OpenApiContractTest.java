package com.ainative.v2.unit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档一致性单元测试:OpenAPI 文档必须与真实代码同步。
 *
 * 锁死两条:
 * 1) 所有 Controller 都有 @Tag(领域分组)与每个端点的 @Operation(说明)——
 *    文档里出现 "agent-controller" 这种机器名或空 summary,视为文档失真;
 * 2) @Tag / @Operation 里不得出现会破坏 Java 字符串的裸引号。
 *
 * 纯静态扫描,不启动容器。
 */
class OpenApiContractTest {

    private static final Path SRC = Path.of("src/main/java");

    private List<Path> controllers() throws IOException {
        try (var stream = Files.walk(SRC)) {
            return stream.filter(p -> p.getFileName().toString().endsWith("Controller.java")).toList();
        }
    }

    @Test
    void every_controller_has_domain_tag() throws IOException {
        List<String> missing = new ArrayList<>();
        for (Path c : controllers()) {
            String text = Files.readString(c, StandardCharsets.UTF_8);
            if (!text.contains("@Tag(name =")) {
                missing.add(c.getFileName().toString());
            }
        }
        assertThat(missing).as("缺少 @Tag 领域分组的 Controller").isEmpty();
    }

    @Test
    void every_mapping_has_operation_summary() throws IOException {
        Pattern mapping = Pattern.compile("@(Get|Post|Put|Delete|Patch)Mapping");
        Pattern operation = Pattern.compile("@Operation\\(summary\\s*=\\s*\"(.+?)\"\\s*\\)");
        List<String> missing = new ArrayList<>();
        for (Path c : controllers()) {
            String text = Files.readString(c, StandardCharsets.UTF_8);
            Matcher m = mapping.matcher(text);
            while (m.find()) {
                // 取该 mapping 之后、下一个 mapping 之前的片段
                int start = m.end();
                int next = text.indexOf("@GetMapping", start);
                if (text.indexOf("@PostMapping", start) > 0 && (next < 0 || text.indexOf("@PostMapping", start) < next)) {
                    next = text.indexOf("@PostMapping", start);
                }
                int end = next < 0 ? text.length() : next;
                String seg = text.substring(start, Math.min(end, start + 900));
                Matcher op = operation.matcher(seg);
                if (!op.find()) {
                    missing.add(c.getFileName().toString() + " @ " + m.group(0));
                } else if (op.group(1).trim().isEmpty()) {
                    missing.add(c.getFileName().toString() + " 空 summary");
                }
            }
        }
        assertThat(missing).as("缺少 @Operation 说明的端点").isEmpty();
    }

    @Test
    void summaries_quote_marks_are_balanced() throws IOException {
        // 每个 @Operation 行里的引号数必须是偶数(成对)——转义引号 \" 不计入。
        Pattern opLine = Pattern.compile("@Operation\\(summary\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*\\)");
        List<String> broken = new ArrayList<>();
        for (Path c : controllers()) {
            for (String line : Files.readAllLines(c, StandardCharsets.UTF_8)) {
                if (!line.contains("@Operation(summary")) continue;
                if (!opLine.matcher(line).find()) {
                    broken.add(c.getFileName() + ": " + line.strip());
                }
            }
        }
        assertThat(broken).as("summary 引号不配对/整行不再是合法 @Operation(会编译失败)").isEmpty();
    }
}
