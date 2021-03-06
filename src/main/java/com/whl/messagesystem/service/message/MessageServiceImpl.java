package com.whl.messagesystem.service.message;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @author whl
 * @date 2022/1/26 17:10
 */
@Slf4j
@Component
public class MessageServiceImpl extends TextWebSocketHandler implements MessageService {

    /**
     * channelName -> List<WebSocketSession> <br>
     * channelName是专门用于广播与订阅的组名，和小组名不同 <br>
     * 具体的channelName是根据Channel接口的不同实现来决定的，通常来说每个场景都有自己独立的Channel实现
     */
    protected final Map<String, CopyOnWriteArrayList<WebSocketSession>> webSocketSessionsMap = new ConcurrentHashMap<>();


    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        super.handleTextMessage(session, message);
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        super.handleMessage(session, message);

        String payload = (String) message.getPayload();
        String channelName = getChannelName(session);
        passWebsocketPayload(channelName, payload);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        super.afterConnectionClosed(session, status);

        String channelName = getChannelName(session);
        webSocketSessionsMap.get(channelName).remove(session);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        super.afterConnectionEstablished(session);

        String channelName = getChannelName(session);
        if (webSocketSessionsMap.get(channelName) == null) {
            //多个比赛的集合初始化是同步的
            synchronized (this) {
                if (webSocketSessionsMap.get(channelName) == null) {
                    CopyOnWriteArrayList<WebSocketSession> webSocketSessionList = new CopyOnWriteArrayList<>();
                    webSocketSessionList.add(session);
                    webSocketSessionsMap.put(channelName, webSocketSessionList);
                }
            }
        } else {
            //对集合的操作也是同步的
            synchronized (webSocketSessionsMap.get(channelName)) {
                webSocketSessionsMap.get(channelName).add(session);
            }
        }
    }


    @Override
    public void publish(String channelName, WebSocketMessage<?> message) {
        //判断这个频道是否存在
        if (webSocketSessionsMap.get(channelName) != null) {
            //这里表示在频道广播消息的时候是没办法同时增加会话进来的
            synchronized (webSocketSessionsMap.get(channelName)) {
                CopyOnWriteArrayList<WebSocketSession> webSocketSessions = webSocketSessionsMap.get(channelName);
                webSocketSessions.forEach(webSocketSession -> {
                    if (webSocketSession.isOpen()) {
                        try {
                            webSocketSession.sendMessage(message);
                        } catch (IOException e) {
                            log.warn("webSocket出现异常并断开，频道名: {}，异常信息: {}", channelName, e.getMessage());
                            webSocketSessions.remove(webSocketSession);
                        }
                    } else {
                        webSocketSessions.remove(webSocketSession);
                    }
                });
            }
        }
    }

    @Override
    public void deleteChannel(String channelName) {
        /*
          若此channel尚未被初始化，则需要做判断
          若直接删除可能会出现NPE
         */
        if (webSocketSessionsMap.containsKey(channelName)) {
            List<WebSocketSession> webSocketSessionList = webSocketSessionsMap.get(channelName);
            webSocketSessionList.forEach(webSocketSession -> {
                try {
                    if (webSocketSession != null && webSocketSession.isOpen()) {
                        webSocketSession.close();
                    }
                } catch (IOException e) {
                    log.error("删除websocket频道异常: {}", e.getMessage());
                }
            });
            webSocketSessionsMap.remove(channelName);
        }
    }

    /**
     * 从WebSocketSession中获取当前会话的channelName
     *
     * @param session
     * @return
     */
    private String getChannelName(WebSocketSession session) {
        return (String) session.getAttributes().get("channelName");
    }

    /**
     * 向指定的频道转发信息
     *
     * @param channelName
     * @param payload
     */
    private void passWebsocketPayload(String channelName, String payload) {
        CopyOnWriteArrayList<WebSocketSession> webSocketSessions = webSocketSessionsMap.get(channelName);
        webSocketSessions.forEach(webSocketSession -> {
            try {
                webSocketSession.sendMessage(new TextMessage(payload));
            } catch (IOException e) {
                webSocketSessions.remove(webSocketSession);
                log.warn("webSocket出现异常并断开，频道名: {}，异常信息: {}", channelName, e.getMessage());
            }
        });
    }

    /**
     * 判断一个频道是否已存在
     * @param channelName
     * @return
     */
    public boolean containsChannel(String channelName) {
        return webSocketSessionsMap.containsKey(channelName);
    }

    /**
     * 获取一个频道中的连接数
     * @param channelName
     * @return
     */
    public int connectionsCount(String channelName) {
        return containsChannel(channelName) ? webSocketSessionsMap.get(channelName).size() : 0;
    }

}

