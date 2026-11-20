package com.streamhub.gateway;

import com.streamhub.common.StreamhubProperties;
import com.streamhub.ingest.IngestService;
import com.streamhub.ingest.RocketMqPublisher;
import com.streamhub.room.RoomRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * WebSocket 服务器。
 * boss 线程：只负责接受新连接；worker（EventLoop）线程：负责已建立连接的读写。
 * 原则：worker 线程上绝不做阻塞调用（查库、同步发 MQ），否则这个线程上的几千个连接都会卡住。
 *
 * 最后启动、最先关闭（phase 最大）：保证收到弹幕时，MQ 生产者和消费者都已就绪。
 */
@Component
public class NettyServer implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(NettyServer.class);
    private final StreamhubProperties props;
    private final RoomRegistry rooms;
    private final IngestService ingest;
    private final JwtVerifier jwt;
    private EventLoopGroup boss;
    private EventLoopGroup workers;
    private Channel server;

    public NettyServer(StreamhubProperties props, RoomRegistry rooms, IngestService ingest, JwtVerifier jwt) {
        this.props = props;
        this.rooms = rooms;
        this.ingest = ingest;
        this.jwt = jwt;
    }

    @Override
    public void start() {
        boss = new NioEventLoopGroup(1);
        workers = new NioEventLoopGroup(props.ioThreads());
        try {
            server = new ServerBootstrap()
                    .group(boss, workers)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 4096)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    // 发送缓冲超过 1MB 就算"写不动"（isWritable=false），推送时跳过这个慢连接
                    .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(512 * 1024, 1024 * 1024))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            configure(ch.pipeline(), rooms, ingest, jwt);
                        }
                    })
                    .bind(props.wsPort()).sync().channel();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("netty start interrupted", e);
        }
        log.info("WebSocket listening on :{}/ws/room/{{videoId}}", props.wsPort());
    }

    /** 处理链：HTTP 解码 → 升级 WebSocket → 空闲检测 → 业务。单独成方法，测试可以复用。 */
    static void configure(ChannelPipeline p, RoomRegistry rooms, IngestService ingest, JwtVerifier jwt) {
        p.addLast(new HttpServerCodec());
        p.addLast(new HttpObjectAggregator(8192));
        p.addLast(new WebSocketServerProtocolHandler(WebSocketServerProtocolConfig.newBuilder()
                .websocketPath("/ws/room")
                .checkStartsWith(true)            // /ws/room/{videoId} 都交给它握手，具体 id 由 WsHandler 校验
                .maxFramePayloadLength(4096)      // 单帧上限，防止有人发超大消息占内存
                .dropPongFrames(false)            // pong 交给 WsHandler，用来判断连接还活着
                .handshakeTimeoutMillis(10_000)
                .build()));
        p.addLast(new IdleStateHandler(30, 0, 0, TimeUnit.SECONDS));
        p.addLast(new WsHandler(rooms, ingest, jwt));
    }

    @Override
    public void stop() {
        if (server != null) {
            server.close().syncUninterruptibly();
        }
        workers.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        boss.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        server = null;
    }

    @Override
    public boolean isRunning() {
        return server != null;
    }

    @Override
    public int getPhase() {
        return RocketMqPublisher.PHASE + 20;
    }
}
