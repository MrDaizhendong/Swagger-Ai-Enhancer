package com.swagger.ai.enhancer.ai.skill;

import com.swagger.ai.enhancer.ai.dto.RagConfigDto;
import com.swagger.ai.enhancer.ai.service.RagConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Skill 文档加载与缓存服务。
 *
 * <p>职责：
 * <ul>
 *   <li>根据 docType 优先加载用户在 RAG 设置中配置的目录 {@code skillPaths}，
 *       扫描其中所有 {@code .md} 文件，按文件名排序后拼接为一份 Skill 文档；</li>
 *   <li>若未配置 skillPaths 或扫描失败，回退读取 classpath 下
 *       {@code skills/{docType}.md} 文件内容；</li>
 *   <li>使用内存缓存按 docType 缓存加载结果，避免每次生成文档重复扫描文件系统。</li>
 * </ul>
 *
 * <p>所有异常都会被捕获并降级返回空字符串，确保不阻断 AI 文档生成主流程。
 */
@Slf4j
public class SkillService {

    /** Skill 文档的 classpath 根目录：{@code skills/{docType}.md} */
    private static final String CLASSPATH_SKILL_ROOT = "skills";
    private static final String MARKDOWN_SUFFIX = ".md";
    private static final String SECTION_SEP = "\n\n---\n\n";

    /** docType -> 拼接后的 Skill 文档内容（空字符串表示"已加载、但未发现可用文档"）。 */
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    private final RagConfigService ragConfigService;

    public SkillService(RagConfigService ragConfigService) {
        this.ragConfigService = ragConfigService;
    }

    /**
     * 根据 docType 加载 Skill 文档内容。
     *
     * @param docType 文档类型，例如 product-doc / api / integration-guide
     * @return 拼接后的 Skill 文档全文；未发现任何文档或加载失败时返回空字符串
     */
    public String loadSkillContext(String docType) {
        if (docType == null || docType.isBlank()) {
            return "";
        }

        String cached = cache.get(docType);
        if (cached != null) {
            return cached;
        }

        String result;
        try {
            RagConfigDto dto = getRagConfigDto(docType);
            String userSkillDir = null;
            if (dto != null) {
                if (dto.getSkillPaths() != null && !dto.getSkillPaths().isBlank()) {
                    userSkillDir = dto.getSkillPaths().trim();
                } else if (dto.getKnowledgePath() != null && !dto.getKnowledgePath().isBlank()) {
                    // 兼容：若用户未显式配置 skillPaths，回退使用知识库目录下的 skills 子目录，
                    // 避免用户需要配置两个路径。仅当此子目录存在时才使用。
                    File candidate = new File(dto.getKnowledgePath().trim(), "skills");
                    if (candidate.isDirectory()) {
                        userSkillDir = candidate.getAbsolutePath();
                    }
                }
            }

            if (userSkillDir != null) {
                String userContent = scanDirectoryForMarkdown(userSkillDir, docType);
                if (userContent != null && !userContent.isBlank()) {
                    result = userContent;
                } else {
                    result = loadClasspathSkill(docType);
                }
            } else {
                result = loadClasspathSkill(docType);
            }
        } catch (Exception e) {
            log.warn("[skill] 加载 docType={} 的 Skill 文档失败，降级为空: {}",
                    docType, e.getMessage());
            result = "";
        }

        if (result == null) {
            result = "";
        }
        cache.put(docType, result);
        return result;
    }

    /** 允许在 RAG 设置变更后主动失效缓存（例如同步/重建索引完成后）。 */
    public void invalidateCache(String docType) {
        if (docType == null || docType.isBlank()) {
            cache.clear();
            log.info("[skill] 已清空全部 Skill 缓存");
            return;
        }
        cache.remove(docType);
        log.info("[skill] 已清空 docType={} 的 Skill 缓存", docType);
    }

    // ======================== 内部工具方法 ========================

    private RagConfigDto getRagConfigDto(String docType) {
        try {
            if (ragConfigService == null) {
                return null;
            }
            return ragConfigService.getConfigOrDefault(docType);
        } catch (Exception e) {
            log.warn("[skill] 读取 RAG 配置（docType={}）异常，将使用 classpath Skill: {}",
                    docType, e.getMessage());
            return null;
        }
    }

    /**
     * 扫描目录下的所有 {@code .md} 文件，按文件名排序后读取并拼接为一份 Skill 文档。
     *
     * @return 拼接后的 Skill 文档内容，若目录不存在或无可读文件则返回 {@code null}
     */
    private String scanDirectoryForMarkdown(String dirPath, String docType) {
        if (dirPath == null || dirPath.isBlank()) {
            return null;
        }

        File base = new File(dirPath);
        if (!base.exists()) {
            log.warn("[skill] docType={} 的 Skill 目录不存在: {}", docType, dirPath);
            return null;
        }
        if (!base.isDirectory()) {
            log.warn("[skill] Skill 路径不是目录（docType={}）: {}", docType, dirPath);
            return null;
        }

        // 优先扫描与 docType 同名的子目录，若存在则只读取该子目录；否则在根目录扫描所有 .md
        File docTypeDir = new File(base, docType);
        File scanRoot = docTypeDir.isDirectory() ? docTypeDir : base;

        List<Path> mdFiles;
        try (Stream<Path> stream = Files.list(scanRoot.toPath())) {
            mdFiles = stream
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(MARKDOWN_SUFFIX))
                    .filter(Files::isRegularFile)
                    .sorted((a, b) -> a.getFileName().toString()
                            .compareToIgnoreCase(b.getFileName().toString()))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("[skill] 扫描 Skill 目录失败（docType={}）: {}", docType, e.getMessage());
            return null;
        }

        if (mdFiles == null || mdFiles.isEmpty()) {
            log.info("[skill] 目录 {} 中未发现任何 .md 文件（docType={}）",
                    scanRoot.getAbsolutePath(), docType);
            return null;
        }

        List<String> contents = new ArrayList<>(mdFiles.size());
        for (Path p : mdFiles) {
            try {
                String content = Files.readString(p, StandardCharsets.UTF_8);
                if (content == null) continue;
                content = content.trim();
                if (content.isEmpty()) continue;
                String header = "# " + p.getFileName().toString() + "\n";
                contents.add(header + content);
            } catch (IOException e) {
                log.warn("[skill] 读取 Skill 文件失败（docType={}）: {}", docType, p);
            }
        }

        if (contents.isEmpty()) {
            return null;
        }
        return String.join(SECTION_SEP, contents);
    }

    /** 从 classpath 加载内置 Skill 文档：{@code skills/{docType}.md} */
    private String loadClasspathSkill(String docType) {
        String resourcePath = CLASSPATH_SKILL_ROOT + "/" + docType + MARKDOWN_SUFFIX;
        try {
            ClassPathResource resource = new ClassPathResource(resourcePath);
            if (!resource.exists()) {
                log.debug("[skill] classpath Skill 文档不存在: {}", resourcePath);
                return "";
            }
            try (InputStream is = resource.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                String content = sb.toString().trim();
                log.debug("[skill] docType={} 已从 classpath 加载 Skill（{} 字符）",
                        docType, content.length());
                return content;
            }
        } catch (Exception e) {
            log.warn("[skill] 读取 classpath Skill 失败（docType={}）: {}", docType, e.getMessage());
            return "";
        }
    }

    /** 供调试/监控使用，返回当前缓存中的 docType 列表。 */
    public List<String> cachedDocTypes() {
        List<String> keys = new ArrayList<>(cache.keySet());
        Collections.sort(keys);
        return keys;
    }
}
