package com.whl.messagesystem.service.message;

import com.whl.messagesystem.commons.constant.GroupConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @author whl
 * @date 2021/12/21 20:52
 */
@Slf4j
@Service
public class MessageServiceImpl extends TextWebSocketHandler implements MessageService {

    /**
     * 用于存储每一个比赛里面的session数据，每一个比赛的所有的session数据被存在同一个集合里面
     * groupName -> CopyOnWriteArrayList<WebSocketSession>
     */
    protected final Map<String, CopyOnWriteArrayList<WebSocketSession>> webSocketSessionsMap = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        super.afterConnectionClosed(session, status);
        log.info("afterConnectionClosed");
        //获取链接
        final String groupName = (String) session.getAttributes().get(GroupConstant.GROUP_NAME);
        webSocketSessionsMap.get(groupName).remove(session);
        log.info("从{}小组中移除当前用户的websocket会话", groupName);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        super.afterConnectionEstablished(session);
        final String groupName = (String) session.getAttributes().get(GroupConstant.GROUP_NAME);
        log.info("组名：{}", groupName);
        if (webSocketSessionsMap.get(groupName) == null) {
            log.warn("{}小组的websocket尚未初始化，现在进行初始化", groupName);
            //多个比赛的集合初始化是同步的
            synchronized (this) {
                if (webSocketSessionsMap.get(groupName) == null) {
                    List<WebSocketSession> webSocketSessionList = new CopyOnWriteArrayList<>();
                    webSocketSessionList.add(session);
                    webSocketSessionsMap.put(groupName, (CopyOnWriteArrayList<WebSocketSession>) webSocketSessionList);
                }
            }
        } else {
            //对集合的操作也是同步的
            log.info("{}小组的websocket已被初始化，把当前用户的会话加入该小组的websocket", groupName);
            synchronized (webSocketSessionsMap.get(groupName)) {
                webSocketSessionsMap.get(groupName).add(session);
            }
        }
        log.info("afterConnectionEstablished ------ 连接已建立");
    }

    /**
     * 在指定的分组内广播消息
     *
     * @param groupName
     * @param message
     */
    @Override
    public void publish(String groupName, WebSocketMessage<?> message) {
        //判断这个比赛是否存在
        if (webSocketSessionsMap.get(groupName) != null) {
            //这里表示在比赛广播消息的时候是没办法同时增加会话进来的
            synchronized (webSocketSessionsMap.get(groupName)) {
                final Iterator<WebSocketSession> iterator = webSocketSessionsMap.get(groupName).iterator();
                //迭代发送消息
                while (iterator.hasNext()) {
                    final WebSocketSession webSocketSession = iterator.next();
                    //判断该会话是否还是开启的
                    if (webSocketSession.isOpen()) {
                        try {
                            //向该回话发送消息
                            webSocketSession.sendMessage(message);
                        } catch (IOException e) {
                            log.warn(groupName + "有一个websocket会话连接断开" + e.getMessage());
                            //移除这个出现异常的会话
                            iterator.remove();
                        }
                    } else {
                        //移除这个已经关闭的会话
                        iterator.remove();
                        log.warn(groupName + "移除一个已经断开了连接的websocket");
                    }
                }
            }
        }
    }

    /**
     * 删除指定分组的WebSocket连接
     *
     * @param groupName
     * @param session
     */
    @Override
    public void deleteGroup(String groupName, HttpSession session) {
        List<WebSocketSession> webSocketSessionList = webSocketSessionsMap.get(groupName);
        for (WebSocketSession webSocketSession : webSocketSessionList) {
            try {
                if (webSocketSession.isOpen()) {
                    webSocketSession.close();
                    log.info("WebSocketSessionId={}已断开", webSocketSession.getId());
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}