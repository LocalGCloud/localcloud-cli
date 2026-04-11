package com.localcloud.emulators.memorystore;

import com.localcloud.emulators.AbstractEmulator;
import com.localcloud.persistence.PostgresDataSource;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.redis.RedisArrayAggregator;
import io.netty.handler.codec.redis.RedisBulkStringAggregator;
import io.netty.handler.codec.redis.RedisDecoder;
import io.netty.handler.codec.redis.RedisEncoder;

/**
 * Memorystore (Redis) emulator.
 * Listens on a TCP port speaking the RESP2 protocol via Netty's Redis codec.
 * Data is persisted in PostgreSQL using {@link MemorystoreStore}.
 */
public class MemorystoreEmulator extends AbstractEmulator {

    private final MemorystoreStore store;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public MemorystoreEmulator(PostgresDataSource dataSource, int port, String projectId) {
        super("memorystore", "Memorystore (Redis)", port, "redis", "REDIS_HOST");
        this.store = new MemorystoreStore(dataSource, projectId);
    }

    @Override
    protected void doStart() throws Exception {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(2);
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
         .channel(NioServerSocketChannel.class)
         .childHandler(new ChannelInitializer<SocketChannel>() {
             @Override
             protected void initChannel(SocketChannel ch) {
                 ChannelPipeline p = ch.pipeline();
                 p.addLast(new RedisDecoder(true)); // true = support inline commands
                 p.addLast(new RedisBulkStringAggregator());
                 p.addLast(new RedisArrayAggregator());
                 p.addLast(new RedisEncoder()); // encoder before handler so writeAndFlush reaches it
                 p.addLast(new RedisCommandHandler(store, MemorystoreEmulator.this));
             }
         });
        serverChannel = b.bind(getPort()).sync().channel();
        logger.info("Memorystore (Redis) RESP2 listener started on port {}", getPort());
    }

    @Override
    protected void doStop() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
    }

    @Override
    protected void doReset() {
        store.flushAll();
    }

    @Override
    public String getEnvVarValue(String host) {
        return host + ":" + getPort();
    }
}
