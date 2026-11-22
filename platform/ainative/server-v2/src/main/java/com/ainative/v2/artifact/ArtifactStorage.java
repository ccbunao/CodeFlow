package com.ainative.v2.artifact;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 产物存储:本地受控目录(默认 <workdir>/artifacts/<dispatchId>/)。
 * 对象存储(S3/MinIO)接入留此接口扩展点。
 */
@Component
public class ArtifactStorage {
    private final Path root;

    public ArtifactStorage(@Value("${ainative.artifact-dir:}") String configuredDir) {
        String dir = configuredDir == null || configuredDir.isBlank()
                ? System.getProperty("java.io.tmpdir") + "/ainative-artifacts"
                : configuredDir;
        this.root = Paths.get(dir).toAbsolutePath();
    }

    public Stored store(long dispatchId, String filename, byte[] content) throws IOException {
        return storeIn(String.valueOf(dispatchId), filename, content);
    }

    /**
     * 落在 <root>/<namespace>/ 下。namespace 可用 '/' 分层(如 conversations/12)。
     *
     * <p>会话产物必须与派发产物分目录:两者都是数字 id,共用一层会互相覆盖。
     */
    public Stored storeIn(String namespace, String filename, byte[] content) throws IOException {
        Path dir = root;
        if (namespace != null && !namespace.isBlank()) {
            for (String segment : namespace.split("/")) {
                if (!segment.isBlank()) {
                    dir = dir.resolve(segment.replaceAll("[^A-Za-z0-9._-]", "_"));
                }
            }
        }
        Files.createDirectories(dir);
        String safe = filename == null || filename.isBlank() ? "artifact" : filename.replaceAll("[^A-Za-z0-9._-]", "_");
        Path target = dir.resolve(safe);
        Files.write(target, content);
        return new Stored(root.relativize(target).toString(), content.length);
    }

    public record Stored(String objectKey, long sizeBytes) {}
}
