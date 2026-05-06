package com.localcloud.emulators.memorystore;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages Pub/Sub subscriptions and message delivery.
 * Tracks channel subscribers and delivers PUBLISH messages to them.
 */
public class PubSubManager {

    private final Map<String, List<ChannelHandlerContext>> channelSubscribers = new ConcurrentHashMap<>();
    private final Map<String, List<ChannelHandlerContext>> patternSubscribers = new ConcurrentHashMap<>();

    /**
     * Subscribe a channel to receive messages.
     */
    public void subscribe(ChannelHandlerContext ctx, String channel) {
        channelSubscribers.computeIfAbsent(channel.toLowerCase(), k -> new CopyOnWriteArrayList<>()).add(ctx);
    }

    /**
     * Subscribe using a pattern (PSUBSCRIBE).
     */
    public void psubscribe(ChannelHandlerContext ctx, String pattern) {
        patternSubscribers.computeIfAbsent(pattern.toLowerCase(), k -> new CopyOnWriteArrayList<>()).add(ctx);
    }

    /**
     * Unsubscribe from a specific channel.
     */
    public void unsubscribe(ChannelHandlerContext ctx, String channel) {
        List<ChannelHandlerContext> subs = channelSubscribers.get(channel.toLowerCase());
        if (subs != null) {
            subs.remove(ctx);
            if (subs.isEmpty()) {
                channelSubscribers.remove(channel.toLowerCase());
            }
        }
    }

    /**
     * Unsubscribe from a pattern.
     */
    public void punsubscribe(ChannelHandlerContext ctx, String pattern) {
        List<ChannelHandlerContext> subs = patternSubscribers.get(pattern.toLowerCase());
        if (subs != null) {
            subs.remove(ctx);
            if (subs.isEmpty()) {
                patternSubscribers.remove(pattern.toLowerCase());
            }
        }
    }

    /**
     * Unsubscribe all for a channel context (used on disconnect).
     */
    public void unsubscribeAll(ChannelHandlerContext ctx) {
        for (List<ChannelHandlerContext> subs : channelSubscribers.values()) {
            subs.remove(ctx);
        }
        for (List<ChannelHandlerContext> subs : patternSubscribers.values()) {
            subs.remove(ctx);
        }
    }

    /**
     * Publish a message to subscribers.
     * @return number of subscribers that received the message
     */
    public int publish(String channel, String message) {
        int count = 0;
        String channelLower = channel.toLowerCase();
        
        // Direct channel subscribers
        List<ChannelHandlerContext> directSubs = channelSubscribers.get(channelLower);
        if (directSubs != null) {
            for (ChannelHandlerContext ctx : directSubs) {
                if (ctx.channel().isActive()) {
                    try {
                        ctx.writeAndFlush(formatMessage(channel, message));
                        count++;
                    } catch (Exception e) {
                        // Channel may have been closed
                    }
                }
            }
        }
        
        // Pattern subscribers
        if (!patternSubscribers.isEmpty()) {
            for (Map.Entry<String, List<ChannelHandlerContext>> entry : patternSubscribers.entrySet()) {
                if (matchesPattern(channelLower, entry.getKey())) {
                    for (ChannelHandlerContext ctx : entry.getValue()) {
                        if (ctx.channel().isActive()) {
                            try {
                                ctx.writeAndFlush(formatPatternMessage(entry.getKey(), channel, message));
                                count++;
                            } catch (Exception e) {
                                // Channel may have been closed
                            }
                        }
                    }
                }
            }
        }
        
        return count;
    }

    /**
     * Check if channel matches pattern.
     */
    private boolean matchesPattern(String channel, String pattern) {
        String regex = pattern.replace("*", ".*").replace("?", ".");
        return channel.matches(regex);
    }

    /**
     * Format message for SUBSCRIBE response.
     */
    private io.netty.handler.codec.redis.RedisMessage formatMessage(String channel, String message) {
        // RESP3 format: ["message", channel, message]
        return new io.netty.handler.codec.redis.ArrayRedisMessage(List.of(
            new io.netty.handler.codec.redis.SimpleStringRedisMessage("message"),
            new io.netty.handler.codec.redis.SimpleStringRedisMessage(channel),
            new io.netty.handler.codec.redis.SimpleStringRedisMessage(message)
        ));
    }

    /**
     * Format message for PSUBSCRIBE response (pmessage).
     */
    private io.netty.handler.codec.redis.RedisMessage formatPatternMessage(String pattern, String channel, String message) {
        return new io.netty.handler.codec.redis.ArrayRedisMessage(List.of(
            new io.netty.handler.codec.redis.SimpleStringRedisMessage("pmessage"),
            new io.netty.handler.codec.redis.SimpleStringRedisMessage(channel),
            new io.netty.handler.codec.redis.SimpleStringRedisMessage(message)
        ));
    }

    /**
     * Get subscriber count for a channel.
     */
    public int subscriberCount(String channel) {
        List<ChannelHandlerContext> subs = channelSubscribers.get(channel.toLowerCase());
        return subs != null ? (int) subs.stream().filter(c -> c.channel().isActive()).count() : 0;
    }

    /**
     * List subscribed channels for debugging.
     */
    public Set<String> getChannels() {
        return new HashSet<>(channelSubscribers.keySet());
    }
}