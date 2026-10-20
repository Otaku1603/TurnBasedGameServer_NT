package com.game.nt.gateway.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Netty 接入层配置（对应 application.yml 的 {@code app.netty.*}）。
 * 使用 record + 构造器绑定；缺省值在紧凑构造器里兜底。
 */
@ConfigurationProperties(prefix = "app.netty")
public record NettyProperties(
        int wsPort,
        boolean useProxyProtocol,
        int workerThreads,
        int maxFrameLength,
        int readerIdleSeconds,
        int writerIdleSeconds,
        int writeBufferLowWaterMark,
        int writeBufferHighWaterMark
) {

    public NettyProperties {
        if (wsPort <= 0) {
            wsPort = 9510;
        }
        if (workerThreads <= 0) {
            workerThreads = 4;
        }
        if (maxFrameLength <= 0) {
            maxFrameLength = 65536; // 单帧上限，防 OOM 放大
        }
        if (readerIdleSeconds < 0) {
            readerIdleSeconds = 30;
        }
        if (writerIdleSeconds < 0) {
            writerIdleSeconds = 0;
        }
        if (writeBufferLowWaterMark <= 0) {
            writeBufferLowWaterMark = 32 * 1024;
        }
        if (writeBufferHighWaterMark <= 0) {
            writeBufferHighWaterMark = 64 * 1024;
        }
    }
}
