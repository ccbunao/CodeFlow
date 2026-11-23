package com.ainative.v2.daemon;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 端点:
 * <ul>
 *   <li>/ws/executor —— 执行器通道(与运行器 ymai-codeflow 协议兼容)</li>
 *   <li>/ws —— 浏览器实时通道(澄清流式回复等;鉴权走带内 auth 帧)</li>
 * </ul>
 */
@Configuration
@EnableWebSocket
public class ExecutorWebSocketConfig implements WebSocketConfigurer {
    private final ExecutorWsHandler handler;
    private final com.ainative.v2.realtime.BrowserWsHandler browserHandler;

    public ExecutorWebSocketConfig(ExecutorWsHandler handler,
                                   com.ainative.v2.realtime.BrowserWsHandler browserHandler) {
        this.handler = handler;
        this.browserHandler = browserHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/executor").setAllowedOrigins("*");
        registry.addHandler(browserHandler, "/ws").setAllowedOrigins("*");
    }
}
