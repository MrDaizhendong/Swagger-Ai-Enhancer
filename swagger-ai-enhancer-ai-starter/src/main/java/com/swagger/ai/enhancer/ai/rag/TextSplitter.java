package com.swagger.ai.enhancer.ai.rag;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本切块器：按字符数进行滑动窗口切分，保留重叠以维持上下文连贯。
 * 在句号 / 换行等标点处优先截断，避免在单词中间切断。
 */
@Slf4j
public class TextSplitter {

    private static final String PREFERRED_BREAK_CHARS = "。！？.!?\n";

    private final int chunkSize;
    private final int chunkOverlap;

    public TextSplitter(int chunkSize, int chunkOverlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须 > 0");
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException(
                    "chunkOverlap 必须 >= 0 且 < chunkSize，实际：" + chunkOverlap);
        }
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    /**
     * 对输入文本进行切块。
     *
     * @param text 原始文本
     * @return 切分后的片段列表；输入为空或空白时返回空列表。
     */
    public List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            log.debug("输入文本为空，返回空列表");
            return chunks;
        }
        String trimmed = text.trim();
        int pos = 0;
        int len = trimmed.length();
        int step = Math.max(1, chunkSize - chunkOverlap);

        while (pos < len) {
            int end = Math.min(pos + chunkSize, len);
            // 若非最后一个 chunk，则尝试在标点附近截断
            if (end < len) {
                int breakPos = findPreferredBreakPosition(trimmed, pos, end);
                if (breakPos > pos) {
                    end = breakPos;
                }
            }
            String chunk = trimmed.substring(pos, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end >= len) {
                break;
            }
            pos = pos + step;
            // 保护：防止 step 计算异常造成死循环
            if (pos >= end) {
                pos = end;
            }
        }
        log.debug("文本切分完成：字符数 {}，切分数量 {}", len, chunks.size());
        return chunks;
    }

    private static int findPreferredBreakPosition(String text, int from, int end) {
        // 在 [end - chunkOverlap/2, end] 范围内优先找标点符号或换行符
        int startSearch = Math.min(from + 1, end - 1);
        for (int i = end - 1; i >= startSearch; i--) {
            char c = text.charAt(i);
            if (PREFERRED_BREAK_CHARS.indexOf(c) >= 0 || Character.isWhitespace(c)) {
                return i + 1;
            }
        }
        return end;
    }
}
