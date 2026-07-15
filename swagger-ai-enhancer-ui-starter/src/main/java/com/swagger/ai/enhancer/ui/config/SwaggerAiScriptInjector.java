package com.swagger.ai.enhancer.ui.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.resource.ResourceTransformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * 在 springdoc 2.5.x 生成的 {@code swagger-initializer.js} 响应体末尾追加同步脚本注入代码：
 * <pre>
 * // 同步加载 marked.js、highlight.js、highlight CSS，然后加载插件脚本
 * (function(){var s1=document.createElement('script');s1.src='/webjars/swagger-ui/5.32.8/marked.min.js';s1.charset='UTF-8';s1.async=false;document.head.appendChild(s1);})();
 * (function(){var s2=document.createElement('script');s2.src='/webjars/swagger-ui/5.32.8/highlight.min.js';s2.charset='UTF-8';s2.async=false;document.head.appendChild(s2);})();
 * (function(){var l=document.createElement('link');l.rel='stylesheet';l.href='/webjars/swagger-ui/5.32.8/highlight-github.min.css';document.head.appendChild(l);})();
 * (function(){var s3=document.createElement('script');s3.src='/webjars/swagger-ui/5.32.8/swagger-ai-plugin.js';s3.charset='UTF-8';s3.async=false;document.head.appendChild(s3);})();
 * </pre>
 *
 * <p>springdoc 不暴露 {@code script}/`scripts` 配置项，且其 {@link ResourceTransformer} 链
 * ({@code org.springdoc.webmvc.ui.SwaggerIndexPageTransformer}) 仅对 swagger-initializer.js 本身
 * 做配置 URL 的替换，不支持自定义 JS 注入。本类通过实现 {@link Filter}（而不是
 * {@link ResourceTransformer}）在响应最终写回前拦截一次，用缓存流捕获 springdoc 输出后
 * 在末尾注入脚本引用，保证 springdoc 原有转换逻辑（CSRF、配置 URL 等）仍被完整保留。
 *
 * <p>使用同步脚本标签（async=false）确保 marked.js 和 highlight.js 在插件脚本之前加载完成，
 * 避免运行时依赖缺失导致的渲染失败。
 */
public class SwaggerAiScriptInjector implements Filter {

    private static final Logger log = LoggerFactory.getLogger(SwaggerAiScriptInjector.class);

    /** 仅对文件名结尾为 swagger-initializer.js 的请求进行注入 —— 避免影响其它静态资源。 */
    private static final String TARGET_NAME = "swagger-initializer.js";

    /** 在 initializer 末尾追加的脚本。使用同步脚本标签按顺序加载依赖库和插件脚本。 */
    private static final String INJECT_SCRIPT =
            // 1. 加载 marked.js
            "(function(){" +
            "var s1=document.createElement('script');" +
            "s1.src='/webjars/swagger-ui/5.32.8/marked.min.js';" +
            "s1.charset='UTF-8';" +
            "s1.async=false;" +
            "document.head.appendChild(s1);" +
            "})();" +
            // 2. 加载 highlight.js
            "(function(){" +
            "var s2=document.createElement('script');" +
            "s2.src='/webjars/swagger-ui/5.32.8/highlight.min.js';" +
            "s2.charset='UTF-8';" +
            "s2.async=false;" +
            "document.head.appendChild(s2);" +
            "})();" +
            // 3. 加载 highlight CSS
            "(function(){" +
            "var l=document.createElement('link');" +
            "l.rel='stylesheet';" +
            "l.href='/webjars/swagger-ui/5.32.8/highlight-github.min.css';" +
            "document.head.appendChild(l);" +
            "})();" +
            // 4. 加载插件脚本（放在最后，确保库先加载）
            "(function(){" +
            "var s3=document.createElement('script');" +
            "s3.src='/webjars/swagger-ui/5.32.8/swagger-ai-plugin.js';" +
            "s3.charset='UTF-8';" +
            "s3.async=false;" +
            "s3.onload=function(){" +
            "console.log('[swagger-ai] 插件脚本已加载');" +
            "};" +
            "s3.onerror=function(){" +
            "console.error('[swagger-ai] 插件脚本加载失败');" +
            "};" +
            "document.head.appendChild(s3);" +
            "})();\n";

    @Override
    public void doFilter(jakarta.servlet.ServletRequest request,
                         jakarta.servlet.ServletResponse response,
                         jakarta.servlet.FilterChain chain) throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        if (!isTarget(httpRequest)) {
            chain.doFilter(request, response);
            return;
        }

        // 缓存响应，等待 springdoc 自身处理完 initializer 之后再做追加注入
        ScriptInjectionResponseWrapper wrapped = new ScriptInjectionResponseWrapper(httpResponse);
        chain.doFilter(request, wrapped);
        wrapped.finishWithInjection();
    }

    private boolean isTarget(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String uri = request.getRequestURI();
        return uri != null && uri.endsWith(TARGET_NAME);
    }

    /**
     * 缓存响应体的 wrapper。{@link HttpServletResponseWrapper} 拦截 {@link #getOutputStream()} /
     * {@link #getWriter()} 写入的所有字节，最后在 {@code finishWithInjection()} 时先写原内容，再追加
     * 注入脚本（因为 swagger-initializer.js 是在浏览器端执行的 JS，原内容 + 新的 document.write 行
     * 被浏览器视为同一脚本依次执行，效果等同于原 initializer 执行完毕后立即加载插件）。
     */
    private static final class ScriptInjectionResponseWrapper extends HttpServletResponseWrapper {

        private final ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
        private final ServletOutputStream outputStream = new CachedServletOutputStream(baos);
        private final PrintWriter writer = new PrintWriter(baos, true, StandardCharsets.UTF_8);
        private final HttpServletResponse delegate;
        private boolean useWriter;
        private boolean useStream;
        private boolean finished;

        ScriptInjectionResponseWrapper(HttpServletResponse response) {
            super(response);
            this.delegate = response;
        }

        @Override
        public ServletOutputStream getOutputStream() {
            useStream = true;
            return outputStream;
        }

        @Override
        public PrintWriter getWriter() {
            useWriter = true;
            return writer;
        }

        /** 在调用者完成写响应后，真正把 (springdoc 内容 + 注入脚本) 写回给客户端。 */
        void finishWithInjection() throws IOException {
            if (finished) {
                return;
            }
            finished = true;
            if (useWriter) {
                writer.flush();
            } else if (useStream) {
                outputStream.flush();
            }
            byte[] original = baos.toByteArray();
            if (original.length == 0) {
                // 没有内容的情况直接交给 Servlet 容器
                delegate.getOutputStream().flush();
                return;
            }

            byte[] inject = INJECT_SCRIPT.getBytes(StandardCharsets.UTF_8);
            byte[] combined = new byte[original.length + inject.length];
            System.arraycopy(original, 0, combined, 0, original.length);
            System.arraycopy(inject, 0, combined, original.length, inject.length);

            if (!delegate.isCommitted()) {
                delegate.setContentLength(combined.length);
                // 如果原响应声明了非 UTF-8 的字符集，这里保持原来的 Content-Type；
                // JS 文件本身必须是 UTF-8 文本，所以同时设置为 application/javascript 更规范
                String originalContentType = delegate.getContentType();
                if (originalContentType == null || !originalContentType.toLowerCase().contains("javascript")) {
                    delegate.setContentType(MediaType.parseMediaType("application/javascript;charset=UTF-8").toString());
                }
            }

            ServletOutputStream out = delegate.getOutputStream();
            out.write(combined);
            out.flush();
            log.info("[swagger-ai-enhancer] swagger-initializer.js 注入完成（{} bytes -> {} bytes）",
                    original.length, combined.length);
        }
    }

    /** 把字节写入 {@link ByteArrayOutputStream} 的简单 {@link ServletOutputStream}。 */
    private static final class CachedServletOutputStream extends ServletOutputStream {

        private final ByteArrayOutputStream target;

        CachedServletOutputStream(ByteArrayOutputStream target) {
            this.target = target;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
            // 同步写入，不需要异步回调
        }

        @Override
        public void write(int b) {
            target.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            target.write(b, off, len);
        }
    }
}
