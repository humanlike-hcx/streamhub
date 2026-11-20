package com.streamhub.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 服务端 → 客户端的 JSON 帧（协议见 docs/design.md 2.2）。
 * 全部集中在这里，改协议只改一个文件。
 * 注意 id/userId 用字符串：JS 的 Number 只能精确表示到 2^53，64 位雪花 ID 会丢精度。
 */
public final class Protocol {
    public static final ObjectMapper JSON = new ObjectMapper();

    public enum ErrorCode { UNAUTHORIZED, RATE_LIMITED, BUSY, INVALID, VIDEO_CLOSED }

    private Protocol() {}

    public static String authOk(long userId) {
        ObjectNode n = JSON.createObjectNode().put("op", "authOk").put("userId", Long.toString(userId));
        return write(n);
    }

    public static String ack(Long clientSeq, long id) {
        ObjectNode n = JSON.createObjectNode().put("op", "ack");
        if (clientSeq != null) {
            n.put("clientSeq", clientSeq);
        }
        n.put("id", Long.toString(id));
        return write(n);
    }

    public static String error(Long clientSeq, ErrorCode code, String msg) {
        ObjectNode n = JSON.createObjectNode().put("op", "error");
        if (clientSeq != null) {
            n.put("clientSeq", clientSeq);
        }
        n.put("code", code.name()).put("msg", msg);
        return write(n);
    }

    public static String pong() {
        return "{\"op\":\"pong\"}";
    }

    /** 推送给观众的一条弹幕（batch 帧里的一个元素）。 */
    public static String item(DanmakuEvent e) {
        ObjectNode n = JSON.createObjectNode()
                .put("id", Long.toString(e.id()))
                .put("userId", Long.toString(e.userId()))
                .put("content", e.content())
                .put("color", e.color())
                .put("playbackTime", e.playbackTime())
                .put("sentAt", e.sentAt());
        return write(n);
    }

    private static String write(ObjectNode n) {
        try {
            return JSON.writeValueAsString(n);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
