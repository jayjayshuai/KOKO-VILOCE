package cn.kokonexus.chat.transport;

import cn.kokonexus.chat.application.ChatService;
import cn.kokonexus.chat.interfaces.ChatViews.MessageView;
import cn.kokonexus.common.diagnostics.SafeFailureDetails;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Netty 连接节点；外部 I/O 在有界业务线程执行，跨节点由持久同步版本驱动查库。 */
@Component
public class ChatSocketServer implements SmartLifecycle {

    /** 日志不记录令牌、Cookie 或消息正文。 */
    private static final Logger LOG = LoggerFactory.getLogger(ChatSocketServer.class);
    /** 已认证连接身份，不接受 SEND 帧中的发送者字段。 */
    private static final AttributeKey<Session> SESSION = AttributeKey.valueOf("chat.session");
    /** 生命周期资源，包含尚未握手连接，停机时全部释放。 */
    private final DefaultChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    /** 每节点三连接快速保护；全局有效租约由共享Redis另外原子控制。 */
    private final Map<Long, AtomicInteger> counts = new ConcurrentHashMap<>();
    /** 持久化事务代理。 */
    private final ChatService service;
    /** 序列化协议对象，不输出内部实体或堆栈。 */
    private final ObjectMapper json;
    /** 内部密钥、Origin、共享 Sa-Token 校验。 */
    private final ChatSecurity security;
    /** 所有节点共享的租约事实，不回退为本机准入。 */
    private final ChatConnectionQuota quota;
    /** 释放队列饱和/停机拒绝计数，不包含账号或连接标签。 */
    private final Counter releaseRejected;
    /** 内网监听端口，不直接映射公网。 */
    private final int port;
    /** 内网绑定地址；隔离验收使用环回，不因随机端口监听所有网卡。 */
    private final String bindAddress;
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
    /** 每次启停改变轮次，旧认证/帧任务不得附着或扣减新轮次连接。 */
    private volatile long lifecycleRevision;

    @org.springframework.beans.factory.annotation.Autowired
    public ChatSocketServer(
        ChatService service,
        ObjectMapper json,
        ChatSecurity security,
        ChatConnectionQuota quota,
        MeterRegistry meters,
        @Value("${koko.chat.websocket-port}") int port,
        @Value("${koko.chat.websocket-bind-address:0.0.0.0}") String bindAddress
    ) {
        this.service = service;
        this.json = json;
        this.security = security;
        this.quota = quota;
        this.releaseRejected = meters.counter("koko.chat.quota.release.rejected");
        this.port = port;
        if (bindAddress == null || bindAddress.isBlank()) throw new IllegalStateException("Netty 监听地址不可为空");
        this.bindAddress = bindAddress;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        lifecycleRevision++;
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
                .bind(bindAddress, port)
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

    /** 只返回本机已完成身份校验且仍在线的用户；不包含令牌或连接对象。 */
    public Set<Long> connectedUsers() {
        var users = new java.util.HashSet<Long>();
        if (running) for (Channel channel : channels) {
            Session session = channel.attr(SESSION).get();
            if (channel.isActive() && session != null) users.add(session.userId);
        }
        return Set.copyOf(users);
    }

    /** 定向无正文失效提示；版本合并合法，不能作为成员权限或客户端已收确认。 */
    public void syncUsers(Set<Long> userIds) {
        if (!running || userIds.isEmpty()) return;
        for (Channel channel : channels) {
            Session session = channel.attr(SESSION).get();
            if (session != null && userIds.contains(session.userId)) write(channel, Map.of("type", "SYNC"));
        }
    }

    /** 先接入连接，后启动版本观察；停机按相反顺序释放。 */
    @Override
    public int getPhase() {
        return 0;
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
        lifecycleRevision++;
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
                request.headers().getAll("X-Koko-Gateway-Key").size() != 1 ||
                request.headers().getAll("X-Koko-User-Id").size() != 1 ||
                request.headers().getAll(HttpHeaderNames.ORIGIN).size() != 1 ||
                request.headers().getAll(HttpHeaderNames.COOKIE).size() != 1 ||
                !security.trusted(request.headers().get("X-Koko-Gateway-Key")) ||
                !security.allowedOrigin(request.headers().get(HttpHeaderNames.ORIGIN))
            ) {
                reject(ctx);
                return;
            }
            pending = true;
            long revision = lifecycleRevision;
            AtomicBoolean retained = new AtomicBoolean(true);
            request.retain();
            try {
                business.execute(() -> {
                    Session session = null;
                    try {
                        String identity = request.headers().get("X-Koko-User-Id");
                        if (identity == null || !identity.matches("[1-9][0-9]{0,18}")) {
                            throw new IllegalArgumentException("连接身份不是规范正数 ID");
                        }
                        long userId = Long.parseLong(identity);
                        String cookieHeader = request.headers().get(HttpHeaderNames.COOKIE);
                        // Cookie decoder 会合并同名 Cookie；先拒绝歧义身份，不能任意选取其中一个令牌。
                        if (
                            java.util.Arrays.stream(cookieHeader.split(";", -1))
                                .map(String::trim)
                                .filter(part -> part.startsWith("koko-nexus-token="))
                                .count() != 1
                        ) {
                            throw new IllegalArgumentException("连接会话 Cookie 缺失或重复");
                        }
                        String token = ServerCookieDecoder.STRICT.decode(cookieHeader)
                            .stream()
                            .filter(cookie -> "koko-nexus-token".equals(cookie.name()))
                            .map(io.netty.handler.codec.http.cookie.Cookie::value)
                            .findFirst()
                            .orElse(null);
                        if (!security.active(userId, token)) {
                            releaseRequest(request, retained);
                            respond(ctx, HttpResponseStatus.FORBIDDEN);
                            return;
                        }
                        session = new Session(userId, token, UUID.randomUUID().toString(), revision);
                        synchronized (counts) {
                            if (!running || revision != lifecycleRevision || !ctx.channel().isActive()) {
                                releaseRequest(request, retained);
                                ctx.close();
                                return;
                            }
                            AtomicInteger count = counts.computeIfAbsent(userId, ignored -> new AtomicInteger());
                            if (count.get() >= 3) {
                                releaseRequest(request, retained);
                                respond(ctx, HttpResponseStatus.TOO_MANY_REQUESTS);
                                return;
                            }
                            count.incrementAndGet();
                            session.localHeld.set(true);
                        }
                        session.leaseAttempted.set(true);
                        if (!quota.acquire(userId, session.connectionId)) {
                            releaseSession(session);
                            releaseRequest(request, retained);
                            respond(ctx, HttpResponseStatus.TOO_MANY_REQUESTS);
                            return;
                        }
                        Session validated = session;
                        ctx.executor().execute(() -> {
                            if (!running || revision != lifecycleRevision || !ctx.channel().isActive()) {
                                releaseSession(validated);
                                releaseRequest(request, retained);
                                return;
                            }
                            ctx.channel().attr(SESSION).set(validated);
                            retained.set(false);
                            ctx.fireChannelRead(request); // 将保留的所有权移交给 WebSocket 握手处理器。
                        });
                    } catch (IllegalArgumentException failure) {
                        if (session != null) releaseSession(session);
                        releaseRequest(request, retained);
                        respond(ctx, HttpResponseStatus.FORBIDDEN);
                    } catch (Exception failure) {
                        if (session != null) releaseSession(session);
                        releaseRequest(request, retained);
                        LOG.warn("Chat admission unavailable: {}", SafeFailureDetails.describe(failure));
                        respond(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException busy) {
                releaseRequest(request, retained);
                reject(ctx);
            }
        }

        private void reject(ChannelHandlerContext ctx) {
            reject(ctx, HttpResponseStatus.FORBIDDEN);
        }

        private void reject(ChannelHandlerContext ctx, HttpResponseStatus status) {
            var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
            response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
            if (
                status.equals(HttpResponseStatus.TOO_MANY_REQUESTS) ||
                status.equals(HttpResponseStatus.SERVICE_UNAVAILABLE)
            ) response.headers().set(HttpHeaderNames.RETRY_AFTER, "1");
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }

        private void respond(ChannelHandlerContext ctx, HttpResponseStatus status) {
            try {
                ctx.executor().execute(() -> reject(ctx, status));
            } catch (java.util.concurrent.RejectedExecutionException stopped) {
                ctx.close();
            }
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
        if (!session.released.compareAndSet(false, true)) return;
        if (session.localHeld.getAndSet(false)) synchronized (counts) {
            if (session.revision == lifecycleRevision) counts.computeIfPresent(session.userId, (id, count) ->
                count.decrementAndGet() <= 0 ? null : count
            );
        }
        if (!session.leaseAttempted.get()) return;
        try {
            business.execute(() -> {
                try {
                    quota.release(session.userId, session.connectionId);
                } catch (RuntimeException failure) {
                    LOG.warn(
                        "Chat quota release unavailable; bounded TTL recovery: {}",
                        SafeFailureDetails.describe(failure)
                    );
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            releaseRejected.increment(); // 不无限重试或另起线程；未知占用最长为原租约TTL。
        }
    }

    /** 仅管理握手异步保留的那一份引用，不重复释放已经移交协议处理器的所有权。 */
    private static void releaseRequest(FullHttpRequest request, AtomicBoolean retained) {
        if (retained.compareAndSet(true, false)) request.release();
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
                        if (
                            !running ||
                            session.revision != lifecycleRevision ||
                            session.released.get() ||
                            !ctx.channel().isActive()
                        ) return;
                        if (!security.active(session.userId, session.token)) {
                            write(
                                ctx.channel(),
                                Map.of("type", "ERROR", "code", "AUTH_REQUIRED", "message", "登录已过期")
                            );
                            ctx.close();
                            return;
                        }
                        if (!quota.renew(session.userId, session.connectionId)) {
                            write(
                                ctx.channel(),
                                Map.of(
                                    "type",
                                    "ERROR",
                                    "code",
                                    "CONNECTION_EXPIRED",
                                    "message",
                                    "连接租约已失效，请等待重新连接"
                                )
                            );
                            ctx.close();
                            return;
                        }
                        if (
                            !running ||
                            session.revision != lifecycleRevision ||
                            session.released.get() ||
                            !ctx.channel().isActive()
                        ) return;
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
                        syncUsers(Set.of(session.userId)); // 发送者即时提示；其他用户由持久版本观察触发。
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
                        LOG.warn("Messaging operation unavailable: {}", SafeFailureDetails.describe(failure));
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
                        ctx.close();
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

    /** 内部敏感会话不生成toString/序列化访问器，关闭只释放一次。 */
    @RequiredArgsConstructor
    private static final class Session {

        /** 已确认的账号。 */ private final long userId;
        /** 共享登录令牌，禁止日志。 */ private final String token;
        /** 服务端独立连接UUID，不是登录令牌。 */ private final String connectionId;
        /** 所属生命周期轮次。 */ private final long revision;
        /** 本机名额是否已登记。 */ private final AtomicBoolean localHeld = new AtomicBoolean();
        /** 包括未知Redis写回复，须幂等尝试释放自己的UUID。 */ private final AtomicBoolean leaseAttempted =
            new AtomicBoolean();
        /** 关闭/迟到认证结果的一次性释放围栏。 */ private final AtomicBoolean released = new AtomicBoolean();
    }
}
