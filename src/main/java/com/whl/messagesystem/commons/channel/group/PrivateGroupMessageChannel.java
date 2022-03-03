package com.whl.messagesystem.commons.channel.group;

import com.whl.messagesystem.commons.channel.Channel;

/**
 * @author whl
 * @date 2022/2/28 18:07
 */
public class PrivateGroupMessageChannel implements Channel {

    private static final String scene = "privateGroupMessage";

    private String groupName = null;

    public PrivateGroupMessageChannel(String groupName) {
        this.groupName = groupName;
    }

    @Override
    public String getChannelName() {
        StringBuilder name = new StringBuilder(scene);
        return name.append("#").append(groupName).toString();
    }
}
