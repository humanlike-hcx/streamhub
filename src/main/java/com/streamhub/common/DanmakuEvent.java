package com.streamhub.common;

/**
 * 一条弹幕在系统内部流转的样子（写进 MQ 的消息体就是它的 JSON）。
 * id 由发送节点用雪花算法生成，后面所有环节（推送、落库、去重）都认这个 id。
 */
public record DanmakuEvent(long id, long videoId, long userId, String content, String color,
                           double playbackTime, long sentAt) {}
