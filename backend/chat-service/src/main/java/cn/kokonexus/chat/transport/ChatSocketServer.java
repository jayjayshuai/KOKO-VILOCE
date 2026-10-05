package cn.kokonexus.chat.transport;

import cn.kokonexus.chat.application.ChatService;
import cn.kokonexus.chat.interfaces.ChatViews.MessageView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** 单实例 Netty 连接网关；所有外部 I/O 在有界业务线程执行，消息事实在 MySQL。 */
@Component
public class ChatSocketServer implements SmartLifecycle {

    /** 日志不记录令牌、Cookie 或消息正文。 */
    private static final Logger LOG = LoggerFactory.getLogger(ChatSocketServer.class);
    /** 已认证连接身份，不接受 SEND 帧中的发送者字段。 */
    private static final AttributeKey<Session> SESSION = AttributeKey.valueOf("chat.session");
    /** 生命周期资源，包含尚未握手连接，停机时全部释放。 */
    private final DefaultChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    /** 每个用户最多三个设备连接；仅本机，用于单实例阶段容量保护。 */
    private final Map<Long, AtomicInteger> counts = new ConcurrentHashMap<>();
    /** 持久化事务代理。 */
    private final ChatService service;
    /** 序列化协议对象，不输出内部实体或堆栈。 */
    private final ObjectMapper json;
    /** 内部密钥、Origin、共享 Sa-Token 校验。 */
    private final ChatSecurity security;
    /** 内网监听端口，不直接映射公网。 */
    private final int port;
    /** 双线程、有界队列；拒绝时关闭/返回 BUSY，避免无界堆积。 */
    private ThreadPoolExecutor business;
    /** Netty 接入线程组。 */
    private EventLoopGroup boss;
    /** Netty 非阻塞网络线程组。 */
    private EventLoopGroup workers;
    /** 监听 Channel。 */
    private Channel listener;
    /** 生命周期运行状态。 */
    private volatile boolean running;

    public ChatSocketServer(
        ChatService service,
        ObjectMapper json,
        ChatSecurity security,
        @Value("${koko.chat.websocket-port}") int port
    ) {
        this.service = service;
        this.json = json;
        this.security = security;
        this.port = port;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        business = new ThreadPoolExecutor(
            2,
            2,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128),
            runnable -> new Thread(runnable, "chat-business"),
            new ThreadPoolExecutor.AbortPolicy()
        );
        boss = new NioEventLoopGroup(1);
        workers = new NioEventLoopGroup(2);
        try {
            listener = new ServerBootstrap()
                .group(boss, workers)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 64)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(16 * 1024, 32 * 1024))
                .childHandler(
                    new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            synchronized (channels) {
                                if (channels.size() >= 500) {
                                    channel.close();
                                    return;
                                }
                                channels.add(channel);
                            }
                            channel
                                .pipeline()
                                .addLast(
                                    new HttpServerCodec(),
                                    new HttpObjectAggregator(8192),
                                    new IdleStateHandler(75, 0, 0),
                                    new HandshakeGuard(),
                                    new WebSocketServerProtocolHandler(
                                        WebSocketServerProtocolConfig.newBuilder()
                                            .websocketPath("/api/chat/ws")
                                            .maxFramePayloadLength(8192)
                                            .allowExtensions(false)
                                            .handshakeTimeoutMillis(10000)
                                            .build()
                                    ),
                                    new WebSocketFrameAggregator(8192),
                                    new Frames()
                                );
                            channel.eventLoop().schedule(
                                () -> {
                                    if (channel.attr(SESSION).get() == null) channel.close();
                                },
                                10,
                                TimeUnit.SECONDS
                            );
                        }
                    }
                )
                .bind(port)
                .sync()
                .channel();
            running = true;
            LOG.info("Netty messaging listener started on internal port {}", port);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            stop();
            throw new IllegalStateException("Netty 启动被中断", interrupted);
        } catch (RuntimeException failure) {
            stop();
            throw failure;
        }
    }

    /** 仅推送无正文同步提示；成员移除与推送竞争也不会泄露消息。 */
    public void syncAll() {
        if (!running) return;
        for (Channel channel : channels)
            if (channel.attr(SESSION).get() != null) write(channel, Map.of("type", "SYNC"));
    }

    /** 慢客户端断开后查库恢复；不无限缓存待发送帧。 */
    private void write(Channel channel, Object payload) {
        if (!channel.isActive()) return;
        if (!channel.isWritable()) {
            channel.close();
            return;
        }
        try {
            channel
                .writeAndFlush(new TextWebSocketFrame(json.writeValueAsString(payload)))
                .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            LOG.error("Messaging response serialization failed", failure);
            channel.close();
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (listener != null) listener.close().awaitUninterruptibly();
        channels.close().awaitUninterruptibly();
        if (business != null) {
            business.shutdown();
            try {
                if (!business.awaitTermination(10, TimeUnit.SECONDS)) business.shutdownNow();
            } catch (InterruptedException interrupted) {
                business.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (boss != null) boss.shutdownGracefully().awaitUninterruptibly();
        if (workers != null) workers.shutdownGracefully().awaitUninterruptibly();
        counts.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 网络级自动化测试查询随机绑定端口，不对外提供 HTTP 接口。 */
    int boundPort() {
        return ((java.net.InetSocketAddress) listener.localAddress()).getPort();
    }

    /** 不阻塞 EventLoop 的握手校验；保留引用直到异步完成，拒绝/异常同样释放。 */
    private final class HandshakeGuard extends SimpleChannelInboundHandler<FullHttpRequest> {

        /** 每连接只允许一个握手请求，防止异步校验绕过。 */
        private boolean pending;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            if (
                pending ||
                !"/api/chat/ws".equals(request.uri()) ||
                request.method() != HttpMethod.GET ||
                !security.trusted(request.headers().get("X-Koko-Gateway-Key")) ||
                !security.allowedOrigin(request.headers().get(HttpHeaderNames.ORIGIN))
            ) {
                reject(ctx);
                return;
            }
            pending = true;
            request.retain();
            try {
                business.execute(() -> {
                    Session session = null;
                    try {
                        long userId = Long.parseLong(request.headers().get("X-Koko-User-Id"));
                        String token = ServerCookieDecoder.STRICT.decode(
                            request.headers().get(HttpHeaderNames.COOKIE, "")
                        )
                            .stream()
                            .filter(cookie -> "koko-nexus-token".equals(cookie.name()))
                            .map(io.netty.handler.codec.http.cookie.Cookie::value)
                            .findFirst()
                            .orElse(null);
                        if (!security.active(userId, token)) {
                            request.release();
                            ctx.executor().execute(() -> reject(ctx));
                            return;
                        }
                        synchronized (counts) {
                            AtomicInteger count = counts.computeIfAbsent(userId, ignored -> new AtomicInteger());
                            if (count.get() >= 3) {
                                request.release();
                                ctx.executor().execute(() -> reject(ctx));
                                return;
                            }
                            count.incrementAndGet();
                        }
                        session = new Session(userId, token);
                        Session validated = session;
                        ctx.executor().execute(() -> {
                            if (!ctx.channel().isActive()) {
                                releaseSession(validated);
                                request.release();
                                return;
                            }
                            ctx.channel().attr(SESSION).set(validated);
                            ctx.fireChannelRead(request); // 将保留的所有权移交给 WebSocket 握手处理器。
                        });
                    } catch (Exception failure) {
                        if (session != null) releaseSession(session);
                        request.release();
                        ctx.executor().execute(() -> reject(ctx));
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException busy) {
                request.release();
                reject(ctx);
            }
        }

        private void reject(ChannelHandlerContext ctx) {
            var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN);
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
            if (event instanceof IdleStateEvent) ctx.close();
            else super.userEventTriggered(ctx, event);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            Session session = ctx.channel().attr(SESSION).getAndSet(null);
            if (session != null) releaseSession(session);
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    private void releaseSession(Session session) {
        synchronized (counts) {
            counts.computeIfPresent(session.userId, (id, count) -> count.decrementAndGet() <= 0 ? null : count);
        }
    }

    /** 每条连接仅允许一项业务任务；限速及 pending 在 EventLoop 中维护。 */
    private final class Frames extends SimpleChannelInboundHandler<WebSocketFrame> {

        /** 上一帧任务是否未完成。 */
        private boolean pending;
        /** 当前秒窗口起点，使用单调时钟。 */
        private long window = System.nanoTime();
        /** 本秒处理帧数，单连接最多 5 帧。 */
        private int frameCount;

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
            if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete) write(
                ctx.channel(),
                Map.of("type", "READY")
            );
            else super.userEventTriggered(ctx, event);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
            if (!(frame instanceof TextWebSocketFrame text)) {
                ctx.close();
                return;
            }
            if (System.nanoTime() - window >= TimeUnit.SECONDS.toNanos(1)) {
                window = System.nanoTime();
                frameCount = 0;
            }
            if (++frameCount > 5) {
                write(ctx.channel(), Map.of("type", "ERROR", "code", "RATE_LIMIT", "message", "发送过快，请稍后重试"));
                ctx.close();
                return;
            }
            if (pending) {
                write(ctx.channel(), Map.of("type", "ERROR", "code", "BUSY", "message", "上一项操作尚未完成，请重试"));
                return;
            }
            Session session = ctx.channel().attr(SESSION).get();
            if (session == null) {
                ctx.close();
                return;
            }
            String wire = text.text();
            pending = true;
            try {
                business.execute(() -> {
                    String clientId = "";
                    try {
                        if (!security.active(session.userId, session.token)) {
                            write(
                                ctx.channel(),
                                Map.of("type", "ERROR", "code", "AUTH_REQUIRED", "message", "登录已过期")
                            );
                            ctx.close();
                            return;
                        }
                        JsonNode command = json.readTree(wire);
                        if (!command.isObject()) throw new IllegalArgumentException("帧必须为 JSON 对象");
                        String type = command.path("type").asText();
                        if ("PING".equals(type)) {
                            write(ctx.channel(), Map.of("type", "PONG"));
                            return;
                        }
                        if (!"SEND".equals(type)) throw new IllegalArgumentException("未知消息类型");
                        if (
                            !command.path("conversationId").isTextual() ||
                            !command.path("clientMessageId").isTextual() ||
                            !command.path("body").isTextual()
                        ) throw new IllegalArgumentException("消息字段必须为字符串");
                        clientId = command.path("clientMessageId").asText();
                        var message = service.send(
                            session.userId,
                            command.path("conversationId").asText(),
                            clientId,
                            command.path("body").asText()
                        );
                        write(ctx.channel(), Map.of("type", "ACK", "message", MessageView.from(message)));
                        syncAll(); // 事务代理返回即已提交；SYNC 失败不回滚消息。
                    } catch (cn.kokonexus.common.api.ForbiddenOperationException failure) {
                        write(
                            ctx.channel(),
                            Map.of(
                                "type",
                                "ERROR",
                                "code",
                                "FORBIDDEN",
                                "clientMessageId",
                                clientId,
                                "message",
                                "当前用户关系不允许发送新私信"
                            )
                        );
                    } catch (
                        IllegalArgumentException
                        | IllegalStateException
                        | cn.kokonexus.common.api.ResourceNotFoundException failure
                    ) {
                        write(
                            ctx.channel(),
                            Map.of(
                                "type",
                                "ERROR",
                                "code",
                                "REJECTED",
                                "clientMessageId",
                                clientId,
                                "message",
                                failure.getMessage()
                            )
                        );
                    } catch (Exception failure) {
                        LOG.error("Messaging operation failed for user {}", session.userId, failure);
                        write(
                            ctx.channel(),
                            Map.of(
                                "type",
                                "ERROR",
                                "code",
                                "UNAVAILABLE",
                                "clientMessageId",
                                clientId,
                                "message",
                                "聊天服务暂不可用，请使用原消息 UUID 重试"
                            )
                        );
                    } finally {
                        ctx.executor().execute(() -> pending = false);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException busy) {
                pending = false;
                write(ctx.channel(), Map.of("type", "ERROR", "code", "BUSY", "message", "聊天服务繁忙，请重试"));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    /** 服务端连接会话；令牌不序列化、不用于日志。 */
    private record Session(
        @io.swagger.v3.oas.annotations.media.Schema(description = "操作用户 ID") long userId,
        @io.swagger.v3.oas.annotations.media.Schema(
            description = "共享 Sa-Token 会话令牌，逐帧验证，禁止序列化或日志输出"
        )
        String token
    ) {}
}
