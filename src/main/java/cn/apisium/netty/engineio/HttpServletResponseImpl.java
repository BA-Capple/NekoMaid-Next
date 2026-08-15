package cn.apisium.netty.engineio;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.*;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

public class HttpServletResponseImpl implements HttpServletResponse {
    private final FullHttpResponse originalResponse;
    private final ServletOutputStream outputStream;
    private final PrintWriter printWriter;
    public HttpServletResponseImpl(FullHttpResponse originalResponse, Channel channel) {
        this.originalResponse = originalResponse;
        outputStream = new ServletOutputStream(originalResponse, channel);
        // Unbuffered writer: engine.io-server writes the response via PrintWriter and never
        // flushes it explicitly, so a buffered PrintWriter (the default wraps a BufferedWriter)
        // would silently drop the payload when only ServletOutputStream#close() is invoked.
        printWriter = new PrintWriter(new Writer() {
            @Override
            public void write(char[] cbuf, int off, int len) throws IOException {
                outputStream.write(new String(cbuf, off, len).getBytes(StandardCharsets.UTF_8));
            }
            @Override
            public void flush() throws IOException { outputStream.flush(); }
            @Override
            public void close() throws IOException { outputStream.close(); }
        });
    }

    public void addHeader(String name, String value) {
        originalResponse.headers().add(name, value);
    }

    public void setStatus(int sc) {
        this.originalResponse.setStatus(HttpResponseStatus.valueOf(sc));
    }

    public void setStatus(int sc, String sm) {
        this.originalResponse.setStatus(new HttpResponseStatus(sc, sm));
    }

    public ServletOutputStream getOutputStream() {
        return outputStream;
    }

    public PrintWriter getWriter() {
        return printWriter;
    }

    public void setCharacterEncoding(String charset) {
        originalResponse.headers().set(HttpHeaderNames.CONTENT_ENCODING, charset);
    }

    public void setContentLength(int len) {
        HttpUtil.setContentLength(this.originalResponse, len);
    }

    public void setContentType(String type) {
        originalResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, type);
    }
}
