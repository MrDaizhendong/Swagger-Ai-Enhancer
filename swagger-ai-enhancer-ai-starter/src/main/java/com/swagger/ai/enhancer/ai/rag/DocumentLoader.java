package com.swagger.ai.enhancer.ai.rag;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.tika.Tika;
import org.jsoup.Jsoup;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 文档加载器：根据文件扩展名选择对应的解析器，将本地文件内容解析为纯文本字符串。
 * <p>
 * 已知支持格式：txt / md / pdf / doc / docx / xls / xlsx / csv / html /
 * chm / ppt / pptx / epub / rtf / odt / ods / xml / json / log /
 * yml / yaml / rst / wiki / mediawiki / tex / mobi 。
 * 其他未知格式会回退到 Apache Tika 的 {@link Tika#parseToString(InputStream)}
 * 自动检测解析；Tika 也无法解析时返回空字符串并记录 warn 日志。
 * </p>
 */
@Slf4j
public class DocumentLoader {

    /**
     * 已显式实现解析分支的扩展名集合；
     * 不在这里列出的扩展名会走 Tika 自动检测兜底。
     */
    private static final List<String> EXPLICIT_EXTENSIONS = List.of(
            "txt", "md",
            "pdf",
            "doc", "docx",
            "xls", "xlsx",
            "csv",
            "html",
            "chm",
            "ppt", "pptx",
            "epub",
            "rtf",
            "odt", "ods",
            "xml", "json", "log", "yml", "yaml",
            "rst", "wiki", "mediawiki", "tex", "mobi"
    );

    /** 懒加载的 Tika 实例；AutoDetectParser 线程安全。 */
    private static final Tika TIKA = new Tika();

    /**
     * 读取指定路径的文件，解析为纯文本字符串。
     *
     * @param filePath 文件路径
     * @return 解析后的纯文本内容（永远不为 null；解析失败时返回空字符串）
     */
    public String load(Path filePath) {
        if (filePath == null) {
            throw new IllegalArgumentException("filePath 不能为 null");
        }
        String fileName = filePath.getFileName().toString().toLowerCase();
        int dot = fileName.lastIndexOf('.');
        String ext = dot < 0 ? "" : fileName.substring(dot + 1);

        log.info("[rag] 开始解析文件：{}（扩展名：.{}）", filePath, ext);
        long start = System.currentTimeMillis();
        String result;
        try {
            result = switch (ext) {
                case "txt", "md", "json", "log", "yml", "yaml" -> loadPlainText(filePath);
                case "pdf" -> loadPdf(filePath);
                case "docx" -> loadDocx(filePath);
                case "doc" -> loadDoc(filePath);
                case "xlsx" -> loadXlsx(filePath);
                case "xls" -> loadXls(filePath);
                case "csv" -> loadCsv(filePath);
                case "html" -> loadHtml(filePath);
                case "xml" -> loadXml(filePath);
                // 以下格式统一走 Tika；如果 Tika 不可用则尝试 plain 文本回退
                case "chm", "epub", "rtf", "odt", "ods", "rst",
                        "wiki", "mediawiki", "tex", "mobi", "ppt", "pptx" ->
                        loadByTika(filePath, ext);
                case "" -> {
                    log.warn("[rag] 文件无扩展名：{}，尝试 Tika 自动检测", filePath);
                    yield loadByTika(filePath, "unknown");
                }
                default -> {
                    log.warn("[rag] 未知扩展名：.{}（文件：{}），尝试 Tika 自动检测", ext, filePath);
                    yield loadByTika(filePath, ext);
                }
            };
        } catch (IOException e) {
            log.error("[rag] 文件解析失败：{}（{}）", filePath, e.getMessage());
            result = "";
        }

        if (result == null) {
            result = "";
        }
        log.info("[rag] 文件解析完成：{}，字符数：{}，耗时：{} ms",
                filePath, result.length(), System.currentTimeMillis() - start);
        return result;
    }

    // ============ 显式解析分支 ============

    private static String loadPlainText(Path filePath) throws IOException {
        return Files.readString(filePath, StandardCharsets.UTF_8);
    }

    private static String loadPdf(Path filePath) throws IOException {
        try (PDDocument doc = PDDocument.load(filePath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }

    private static String loadDocx(Path filePath) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(filePath.toFile());
             XWPFDocument doc = new XWPFDocument(fis)) {
            for (XWPFParagraph p : doc.getParagraphs()) {
                sb.append(p.getText()).append('\n');
            }
        }
        return sb.toString();
    }

    /** .doc（老版 Word）解析；失败时回退 Tika。 */
    private static String loadDoc(Path filePath) throws IOException {
        try (FileInputStream fis = new FileInputStream(filePath.toFile());
             HWPFDocument doc = new HWPFDocument(fis)) {
            return doc.getDocumentText();
        } catch (Exception e) {
            log.warn("[rag] .doc POI 解析失败（{}），回退 Tika", e.getMessage());
            return loadByTika(filePath, "doc");
        }
    }

    private static String loadXlsx(Path filePath) throws IOException {
        try (FileInputStream fis = new FileInputStream(filePath.toFile());
             Workbook wb = new XSSFWorkbook(fis)) {
            return renderWorkbook(wb);
        }
    }

    /** .xls（老版 Excel）解析；失败时回退 Tika。 */
    private static String loadXls(Path filePath) throws IOException {
        try (FileInputStream fis = new FileInputStream(filePath.toFile());
             Workbook wb = new HSSFWorkbook(fis)) {
            return renderWorkbook(wb);
        } catch (Exception e) {
            log.warn("[rag] .xls POI 解析失败（{}），回退 Tika", e.getMessage());
            return loadByTika(filePath, "xls");
        }
    }

    private static String renderWorkbook(Workbook wb) {
        StringBuilder sb = new StringBuilder();
        for (Sheet sheet : wb) {
            sb.append("=== Sheet: ").append(sheet.getSheetName()).append(" ===\n");
            for (Row row : sheet) {
                StringBuilder rowBuilder = new StringBuilder();
                for (Cell cell : row) {
                    if (!rowBuilder.isEmpty()) {
                        rowBuilder.append(" | ");
                    }
                    rowBuilder.append(formatCellValue(cell));
                }
                sb.append(rowBuilder).append('\n');
            }
        }
        return sb.toString();
    }

    private static String formatCellValue(Cell cell) {
        if (cell == null) {
            return "";
        }
        try {
            return switch (cell.getCellType()) {
                case STRING -> cell.getStringCellValue();
                case NUMERIC -> String.valueOf(cell.getNumericCellValue());
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                case FORMULA -> cell.getCellFormula();
                default -> "";
            };
        } catch (Exception e) {
            return "";
        }
    }

    private static String loadCsv(Path filePath) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(filePath.toFile());
             java.io.BufferedReader br = new java.io.BufferedReader(
                     new java.io.InputStreamReader(fis, StandardCharsets.UTF_8));
             com.opencsv.CSVReader reader = new com.opencsv.CSVReader(br)) {
            List<String[]> rows = reader.readAll();
            for (String[] row : rows) {
                sb.append(String.join(" | ", row)).append('\n');
            }
        } catch (Exception e) {
            throw new IOException("CSV 解析失败：" + e.getMessage(), e);
        }
        return sb.toString();
    }

    private static String loadHtml(Path filePath) throws IOException {
        org.jsoup.nodes.Document doc = Jsoup.parse(filePath.toFile(), "UTF-8");
        return doc.text();
    }

    /**
     * XML 文本提取：先尝试 Jsoup（对类 HTML XML 效果好）；
     * 失败时回退到 Java 自带 DOM 按 Text 节点展开。
     */
    private static String loadXml(Path filePath) throws IOException {
        try {
            org.jsoup.nodes.Document doc = Jsoup.parse(filePath.toFile(), "UTF-8");
            return doc.text();
        } catch (Exception e) {
            log.warn("[rag] XML Jsoup 解析失败（{}），改用 Tika", e.getMessage());
            return loadByTika(filePath, "xml");
        }
    }

    // ============ Tika 兜底解析 ============

    /**
     * 使用 Tika {@link Tika#parseToString(InputStream)} 解析；失败时尝试纯文本读取。
     * 所有读取资源通过 try-with-resources 自动关闭。
     */
    private static String loadByTika(Path filePath, String ext) throws IOException {
        long start = System.currentTimeMillis();
        try (InputStream is = Files.newInputStream(filePath)) {
            String text = TIKA.parseToString(is);
            if (text != null && !text.isBlank()) {
                return text;
            }
            log.warn("[rag] Tika 解析 .{} 返回空白内容（文件：{}），回退纯文本读取", ext, filePath);
        } catch (Exception e) {
            log.warn("[rag] Tika 解析 .{} 失败（文件：{}，原因：{}），回退纯文本读取",
                    ext, filePath, e.getMessage());
        } finally {
            log.debug("[rag] Tika 解析 .{} 耗时：{} ms（文件：{}）",
                    ext, System.currentTimeMillis() - start, filePath);
        }
        // 最后回退：按 UTF-8 文本读取
        return loadPlainText(filePath);
    }

    /** 返回当前显式支持的扩展名集合（调试/展示用）。 */
    public static List<String> getExplicitExtensions() {
        return EXPLICIT_EXTENSIONS;
    }
}
