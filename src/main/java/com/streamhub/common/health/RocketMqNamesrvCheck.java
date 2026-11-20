package com.streamhub.common.health;

import java.net.InetSocketAddress;
import java.net.Socket;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 只检查 NameServer 端口能不能连上（TCP 连接）。
 * 为什么这么简单：M0 还没引入 RocketMQ 客户端，先确认"服务在不在"；
 * M3 接入客户端后，可以换成真正的生产者探活。
 */
@Component
public class RocketMqNamesrvCheck implements DependencyCheck {
    private static final int TIMEOUT_MS = 1000;
    private final String host;
    private final int port;

    public RocketMqNamesrvCheck(@Value("${streamhub.rocketmq.name-server}") String nameServer) {
        // 配置格式是 host:port，比如 127.0.0.1:9876
        int colon = nameServer.lastIndexOf(':');
        this.host = nameServer.substring(0, colon);
        this.port = Integer.parseInt(nameServer.substring(colon + 1));
    }

    @Override
    public String name() {
        return "rocketmq-namesrv";
    }

    @Override
    public void check() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
        }
    }
}
