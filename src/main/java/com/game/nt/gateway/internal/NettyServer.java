package com.game.nt.gateway.internal;

import com.game.nt.proto.GameProto.GameMessage;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;
import java.util.List;
import io.netty.util.concurrent.DefaultThreadFactory;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Netty WebSocket 接入服务器（唯一游戏通道；原 TCP+Varint32 管线已移除）。
 *
 * <p>保留的改进：写缓冲水位背压、独立命名线程池、优雅停机、
 * 帧长上限（{@code WebSocketServerProtocolHandler} 的 maxFramePayloadLength）。
 */
@Component
public class NettyServer {

    private static final Logger log = LoggerFactory.getLogger(NettyServer.class);

    private final NettyProperties props;
    private final GameChannelHandler gameChannelHandler;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private Channel wsServerChannel;

    public NettyServer(NettyProperties props, GameChannelHandler gameChannelHandler) {
        this.props = props;
        this.gameChannelHandler = gameChannelHandler;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("nt-netty-boss"));
        workerGroup = new NioEventLoopGroup(props.workerThreads(), new DefaultThreadFactory("nt-netty-worker"));

        ServerBootstrap wsBootstrap = new ServerBootstrap();
        wsBootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                        new WriteBufferWaterMark(props.writeBufferLowWaterMark(), props.writeBufferHighWaterMark()))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(65536));
                        ch.pipeline().addLast(new IdleStateHandler(
                                props.readerIdleSeconds(), props.writerIdleSeconds(), 0, TimeUnit.SECONDS));
                        ch.pipeline().addLast(new WebSocketServerProtocolHandler("/ws", null, true, 65536));
                        // 出站：GameMessage → WS 二进制帧（会话推送复用同一 writeAndFlush 路径）
                        ch.pipeline().addLast(new MessageToMessageEncoder<GameMessage>() {
                            @Override
                            protected void encode(ChannelHandlerContext c, GameMessage msg, List<Object> out) {
                                out.add(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(msg.toByteArray())));
                            }
                        });
                        ch.pipeline().addLast(gameChannelHandler);
                    }
                });

        try {
            wsServerChannel = wsBootstrap.bind(props.wsPort()).sync().channel();
            log.info("Netty 游戏服务已启动: WS端口={}(/ws), worker线程={}",
                    props.wsPort(), props.workerThreads());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Netty 启动失败", ex);
        }
    }

    @PreDestroy
    public void stop() {
        log.info("正在优雅关闭 Netty ...");
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (wsServerChannel != null) {
            wsServerChannel.close();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(1, 5, TimeUnit.SECONDS);
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(1, 5, TimeUnit.SECONDS);
        }
        log.info("Netty 已关闭");
    }
}
