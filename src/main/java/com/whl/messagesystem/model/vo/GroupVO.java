package com.whl.messagesystem.model.vo;

import com.whl.messagesystem.model.entity.Group;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author whl
 * @date 2022/1/22 18:05
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class GroupVO {
    private String groupId;
    private String groupName;
    private Integer maxCount;
    private String adminId;
    private String adminName;
    private String creatorId;
    private String creatorName;
    private Boolean adminCreated;

    public GroupVO(Group group) {
        this.groupId = group.getGroupId();
        this.groupName = group.getGroupName();
        this.maxCount = group.getMaxCount();
        this.adminId = group.getAdminId();
        this.creatorId = group.getCreatorId();
        this.adminCreated = group.isAdminCreated();
    }
}
