package com.whl.messagesystem.service.group;

import com.alibaba.fastjson.JSONObject;
import com.whl.messagesystem.commons.channel.Channel;
import com.whl.messagesystem.commons.channel.group.PrivateGroupMessageChannel;
import com.whl.messagesystem.commons.channel.group.PublicGroupMessageChannel;
import com.whl.messagesystem.commons.channel.management.group.PrivateGroupWithoutAdminListChannel;
import com.whl.messagesystem.commons.channel.management.group.PublicGroupCreatedByOutsideListChannel;
import com.whl.messagesystem.commons.channel.management.user.UserWithAdminListChannel;
import com.whl.messagesystem.commons.channel.management.user.UserWithoutAdminListChannel;
import com.whl.messagesystem.commons.channel.user.GroupHallListChannel;
import com.whl.messagesystem.commons.constant.ResultEnum;
import com.whl.messagesystem.commons.utils.ResultUtil;
import com.whl.messagesystem.commons.utils.WsResultUtil;
import com.whl.messagesystem.dao.*;
import com.whl.messagesystem.model.Result;
import com.whl.messagesystem.model.dto.CreateGroupDTO;
import com.whl.messagesystem.model.dto.CreatePublicGroupDTO;
import com.whl.messagesystem.model.dto.OutsideCreatePublicGroupDTO;
import com.whl.messagesystem.model.dto.SessionInfo;
import com.whl.messagesystem.model.entity.*;
import com.whl.messagesystem.model.vo.GroupVO;
import com.whl.messagesystem.service.message.MessageServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.TextMessage;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import javax.xml.bind.ValidationException;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.whl.messagesystem.commons.constant.StringConstant.SESSION_INFO;

/**
 * @author whl
 * @date 2021/12/21 22:03
 */
@Slf4j
@Service
public class GroupServiceImpl implements GroupService {

    private static final int DEFAULT_MEMBER_COUNT = 20;

    @Resource
    GroupDao groupDao;

    @Resource
    PublicGroupDao publicGroupDao;

    @Resource
    UserGroupDao userGroupDao;

    @Resource
    AdminDao adminDao;

    @Resource
    UserDao userDao;

    @Resource
    UserAdminDao userAdminDao;

    @Resource
    MessageServiceImpl messageService;


    @Override
    public ResponseEntity<Result> getGroupsList() {
        try {
            List<GroupVO> groupVos = groupDao.selectAllGroupsAndCreators();
            return ResponseEntity.ok(ResultUtil.success(groupVos));
        } catch (Exception e) {
            log.error("????????????????????????: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ResultUtil.error());
        }
    }

    @Override
    public ResponseEntity<Result> getGroupsListByAdminId(String adminId) {
        try {
            if (StringUtils.isEmpty(adminId)) {
                throw new NullPointerException("????????????");
            }

            List<GroupVO> groupVOWithoutCreator = groupDao.selectGroupVOWithoutCreatorByAdminId(Integer.parseInt(adminId));
            List<GroupVO> groupVOWithCreator = groupDao.selectAllGroupsAndCreatorsByAdminId(Integer.parseInt(adminId));

            Stream<GroupVO> s1 = groupVOWithCreator.stream();
            Stream<GroupVO> s2 = groupVOWithoutCreator.stream();

            List<GroupVO> result = Stream.concat(s1, s2).sorted(Comparator.comparing(GroupVO::getGroupId)).collect(Collectors.toList());

            return ResponseEntity.ok(ResultUtil.success(result));
        } catch (Exception e) {
            log.error("?????????????????????????????????: {}???????????????: {}", adminId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ResultUtil.error());
        }
    }

    @Override
    public boolean isExistGroup(String groupName) {
        try {
            if (groupDao.findGroupByGroupName(groupName) != null) {
                return true;
            } else {
                return false;
            }
        } catch (Exception e) {
            log.error("??????????????????????????????: {}", e.getMessage());
            return false;
        }
    }


    @Override
    public ResponseEntity<Result> createGroup(CreateGroupDTO createGroupDto, HttpSession session) {
        try {
            if (ObjectUtils.isEmpty(createGroupDto)) {
                throw new NullPointerException("????????????");
            }

            String groupName = createGroupDto.getGroupName();
            String creatorId = createGroupDto.getCreatorId();
            String adminId = createGroupDto.getAdminId();
            int maxCount = createGroupDto.getMaxCount();
            Boolean adminCreated = createGroupDto.getAdminCreated();

            /*
            ??????????????????????????????????????????????????????
             */
            if (isExistGroup(groupName)) {
                log.warn("?????????????????????");
                return ResponseEntity.ok(new Result(ResultEnum.ERROR.getStatus(), "?????????????????????!", null));
            }

            /*
            ???????????????????????????????????????????????????
             */
            if (groupDao.selectGroupCountByCreatorId(Integer.parseInt(creatorId)) > 0) {
                log.warn("???????????????????????????");
                return ResponseEntity.ok(new Result(ResultEnum.ERROR.getStatus(), "???????????????????????????", null));
            }

            /*
            ???????????????????????????????????????????????????20
             */
            if (groupDao.insertAGroup(groupName, creatorId, adminId, maxCount == 0 ? DEFAULT_MEMBER_COUNT : maxCount, adminCreated)) {
                // ????????????????????????????????????
                Group group = groupDao.findGroupByGroupName(groupName);
                SessionInfo sessionInfo = (SessionInfo) session.getAttribute(SESSION_INFO);
                sessionInfo.setGroup(group);
                GroupVO groupVo = new GroupVO(group);
                groupVo.setAdminName(adminDao.selectAdminByUserId(Integer.parseInt(creatorId)).getAdminName());
                groupVo.setCreatorName(userDao.selectUserWithUserId(Integer.parseInt(creatorId)).getUserName());

                String message = JSONObject.toJSONString(WsResultUtil.createGroup(groupVo));
                TextMessage textMessage = new TextMessage(message);
                // ????????????????????????????????????????????????
                Channel groupHallListChannel = new GroupHallListChannel(adminId);
                messageService.publish(groupHallListChannel.getChannelName(), textMessage);

                return ResponseEntity.ok(ResultUtil.success(group));
            }

            throw new SQLException("group?????????????????????");
        } catch (Exception e) {
            log.error("??????????????????????????????: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ResultUtil.error());
        }
    }

    @Override
    public ResponseEntity<Result> remove(String groupId, HttpSession session) {
        try {
            if (StringUtils.isEmpty(groupId)) {
                throw new NullPointerException("????????????");
            }

            Group group = groupDao.selectGroupByGroupId(Integer.parseInt(groupId));
            log.info("????????????????????????: {}", group);

            if (group != null && userGroupDao.deleteUserGroupsByGroupId(Integer.parseInt(groupId)) >= 0) {
                String message = JSONObject.toJSONString(WsResultUtil.deleteGroup(group));
                TextMessage textMessage = new TextMessage(message);

                // ????????????????????????????????????
                Channel privateGroupMessageChannel = new PrivateGroupMessageChannel(group.getGroupName());
                messageService.publish(privateGroupMessageChannel.getChannelName(), textMessage);
                // ??????????????????ws??????
                messageService.deleteChannel(privateGroupMessageChannel.getChannelName());

                if (groupDao.deleteGroupByGroupId(Integer.parseInt(groupId))) {
                    SessionInfo sessionInfo = (SessionInfo) session.getAttribute(SESSION_INFO);
                    String adminId = sessionInfo.getAdmin().getAdminId();

                    // ?????????????????????????????????id
                    Channel groupHallListChannel = new GroupHallListChannel(adminId);
                    messageService.publish(groupHallListChannel.getChannelName(), textMessage);

                    // ??????"?????????????????????????????????"??????
                    Channel privateGroupWithoutAdminListChannel = new PrivateGroupWithoutAdminListChannel();
                    messageService.publish(privateGroupWithoutAdminListChannel.getChannelName(), textMessage);

                    return ResponseEntity.ok(ResultUtil.success());
                }
            }

            throw new SQLException("group?????????????????????");
        } catch (Exception e) {
            log.error("???????????????????????????: {}???????????????: {}", groupId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ResultUtil.error());
        }
    }

    @Override
    public ResponseEntity<Result> updateGroupInfo(Group group) {
        try {
            if (ObjectUtils.isEmpty(group)) {
                throw new NullPointerException("????????????");
            }

            if (groupDao.updateGroup(group) > 0) {
                log.info("????????????");
                return ResponseEntity.ok(ResultUtil.success());
            }

            throw new SQLException("group???????????????");
        } catch (Exception e) {
            log.error("????????????????????????: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ResultUtil.error());
        }
    }

    @Override
    public ResponseEntity<Result> joinGroup(UserGroup userGroup, HttpSession session) {
        try {
            if (ObjectUtils.isEmpty(userGroup)) {
                throw new ValidationException("????????????");
            }

            if (userGroupDao.selectUserGroupCountByUserId(Integer.parseInt(userGroup.getUserId())) != 0) {
                log.warn("???????????????????????????");
                return ResponseEntity.ok(new Result(ResultEnum.ERROR.getStatus(), "???????????????????????????", null));
            }

            if (userGroupDao.insertAnUserGroup(userGroup)) {
                Group group = groupDao.selectGroupByGroupId(Integer.parseInt(userGroup.getGroupId()));

                SessionInfo sessionInfo = (SessionInfo) session.getAttribute(SESSION_INFO);
                sessionInfo.setGroup(group);
                session.setAttribute(SESSION_INFO, sessionInfo);
                String userName = sessionInfo.getUser().getUserName();

                Map<String, Object> data = new HashMap<>();
                data.put("userName", userName);
                data.put("userId", userGroup.getUserId());
                data.put("groupId", userGroup.getGroupId());
                data.put("groupName", group.getGroupName());

                // ?????????????????????????????????
                String message = JSONObject.toJSONString(WsResultUtil.joinGroup(data));
                TextMessage textMessage = new TextMessage(message);
                Channel privateGroupMessageChannel = new PrivateGroupMessageChannel(group.getGroupName());
                messageService.publish(privateGroupMessageChannel.getChannelName(), textMessage);

                Channel userWithAdminListChannel = new UserWithAdminListChannel(sessionInfo.getAdmin().getAdminId());
                messageService.publish(userWithAdminListChannel.getChannelName(), textMessage);

                return ResponseEntity.ok(ResultUtil.success());
            }

            throw new SQLException("user_group?????????????????????");
        } catch (Exception e) {
            log.error("??????????????????: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ResultUtil.error());
        }
    }

    @Override
    public ResponseEntity<Result> quitGroup(int userId, HttpSession session) {
        try {
            if (userGroupDao.deleteAnUserGroupByUserId(userId)) {
                SessionInfo sessionInfo = (SessionInfo) session.getAttribute(SESSION_INFO);
                String groupName = sessionInfo.getGroup().getGroupName();

                Map<String, Object> data = new HashMap<>(3);
                data.put("userName", sessionInfo.getUser().getUserName());
                data.put("userId", userId);
                data.put("groupId", sessionInfo.getGroup().getGroupId());

                sessionInfo.setGroup(null);

                // ????????????????????????????????????
                String message = JSONObject.toJSONString(WsResultUtil.quitGroup(data));
                TextMessage textMessage = new TextMessage(message);
                Channel privateGroupMessageChannel = new PrivateGroupMessageChannel(groupName);
                messageService.publish(privateGroupMessageChannel.getChannelName(), textMessage);

                // ???????????????????????????????????????
                Channel userWithAdminListChannel = new UserWithAdminListChannel(sessionInfo.getAdmin().getAdminId());
                messageService.publish(userWithAdminListChannel.getChannelName(), textMessage);

                return ResponseEntity.ok(ResultUtil.success());
            }

            throw new SQLException("user_group?????????????????????");
        } catch (Exception e) {
            log.error("??????????????????: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ResultUtil.error());
        }
    }

    @Override
    public ResponseEntity<Result> listGroupMembers(String groupId, HttpSession session) {
        try {
            if (StringUtils.isEmpty(groupId)) {
                throw new NullPointerException("????????????");
            }

            List<User> groupMembersList = groupDao.selectUsersWithGroupId(Integer.parseInt(groupId));

            SessionInfo sessionInfo = (SessionInfo) session.getAttribute(SESSION_INFO);
            if (sessionInfo.getGroup().getCreatorId() != null) {
                int creatorId = Integer.parseInt(sessionInfo.getGroup().getCreatorId());
                User creator = userDao.selectUserWithUserId(creatorId);
                creator.setPassword(null);

                Map<String, Object> groupMembersAndCreator = new HashMap<>();
                groupMembersAndCreator.put("creator", creator);
                groupMembersAndCreator.put("members", groupMembersList);

                return ResponseEntity.ok(ResultUtil.success(groupMembersAndCreator));
            } else {
                Map<String, Object> groupMembersAndCreator = new HashMap<>();
                groupMembersAndCreator.put("creator", null);
                groupMembersAndCreator.put("members", groupMembersList);

                return ResponseEntity.ok(ResultUtil.success(groupMembersAndCreator));
            }


        } catch (Exception e) {
            log.error("????????????????????????: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ResultUtil.error());
        }
    }

    @Override
    public ResponseEntity<Result> createPublicGroup(CreatePublicGroupDTO createPublicGroupDTO) {
        try {
            if (ObjectUtils.isEmpty(createPublicGroupDTO)) {
                throw new NullPointerException("????????????");
            }

            String groupName = createPublicGroupDTO.getGroupName();
            Integer maxCount = createPublicGroupDTO.getMaxCount();
            String adminId = createPublicGroupDTO.getAdminId();

            // ????????????????????????????????????
            if (isExistPublicGroup(groupName)) {
                log.warn("?????????????????????");
                return ResponseEntity.ok(new Result<>(ResultEnum.ERROR.getStatus(), "?????????????????????!", null));
            }

            PublicGroup publicGroup = new PublicGroup();
            publicGroup.setGroupName(groupName);
            publicGroup.setMaxCount(maxCount == 0 ? DEFAULT_MEMBER_COUNT : maxCount);
            publicGroup.setAdminCreated(true);
            publicGroup.setAdminId(adminId);

            if (publicGroupDao.insertPublicGroup(publicGroup)) {
                publicGroup = publicGroupDao.selectPublicGroupByName(groupName);
                // ????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
                return ResponseEntity.ok(ResultUtil.success(publicGroup));
            }

            throw new SQLException("public_group?????????????????????");
        } catch (Exception e) {
            log.error("????????????????????????: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ResultUtil.error());
        }
    }

    private boolean isExistPublicGroup(String groupName) {
        try {
            if (publicGroupDao.selectPublicGroupCountByName(groupName) == 0) {
                return false;
            }

            return true;
        } catch (Exception e) {
            log.error("????????????????????????????????????: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public ResponseEntity<Result> kickGroupMember(String userId) {
        try {
            if (StringUtils.isEmpty(userId)) {
                throw new NullPointerException("????????????");
            }

            String groupName = groupDao.selectGroupByUserId(Integer.parseInt(userId)).getGroupName();

            if (userGroupDao.deleteAnUserGroupByUserId(Integer.parseInt(userId))) {
                String message = JSONObject.toJSONString(WsResultUtil.kickMember(userId));
                TextMessage textMessage = new TextMessage(message);

                // ???????????????????????????????????????
                Channel channel = new PrivateGroupMessageChannel(groupName);
                messageService.publish(channel.getChannelName(), textMessage);

                // ??????????????????????????????????????????
                Admin admin = adminDao.selectAdminByUserId(Integer.parseInt(userId));
                Channel userWithAdminListChannel = new UserWithAdminListChannel(admin.getAdminId());
                messageService.publish(userWithAdminListChannel.getChannelName(), textMessage);

                return ResponseEntity.ok(ResultUtil.success());
            }

            throw new SQLException("user_group?????????????????????");
        } catch (Exception e) {
            log.error("??????????????????");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ResultUtil.error());
        }
    }

    @Override
    public ResponseEntity<Result> listPublicGroupsCreatedByAdmin(String adminId) {
        try {
            if (StringUtils.isEmpty(adminId) || "0".equals(adminId)) {
                throw new NullPointerException("????????????");
            }

            List<PublicGroup> publicGroups = publicGroupDao.selectPublicGroupsWithAdminId(Integer.parseInt(adminId));
            return ResponseEntity.ok(ResultUtil.success(publicGroups));
        } catch (Exception e) {
            log.error("????????????????????????????????????????????????????????????id: {}???????????????: {}", adminId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ResultUtil.error());
        }
    }

    @Override
    public ResponseEntity<Result> listPublicGroupsCreatedByOutside() {
        try {
            List<PublicGroup> publicGroups = publicGroupDao.selectPublicGroupsCreatedByOutside();
            return ResponseEntity.ok(ResultUtil.success(publicGroups));
        } catch (Exception e) {
            log.error("???????????????????????????????????????????????????????????????: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ResultUtil.error());
        }
    }

    @Override
    public ResponseEntity<Result> dismissGroup(String groupId, HttpSession session) {
        try {
            if (StringUtils.isEmpty(groupId)) {
                throw new NullPointerException("????????????");
            }

            if (userGroupDao.deleteUserGroupsByGroupId(Integer.parseInt(groupId)) >= 0 && groupDao.deleteGroupByGroupId(Integer.parseInt(groupId))) {
                SessionInfo sessionInfo = (SessionInfo) session.getAttribute(SESSION_INFO);
                String groupName = sessionInfo.getGroup().getGroupName();
                String adminId = sessionInfo.getAdmin().getAdminId();
                sessionInfo.setGroup(null);

                Map<String, Object> map = new HashMap<>(2);
                map.put("creatorId", sessionInfo.getUser().getUserId());
                map.put("groupId", groupId);
                String deleteGroupMessage = JSONObject.toJSONString(WsResultUtil.dismissGroup(map));
                TextMessage textMessage = new TextMessage(deleteGroupMessage);

                Channel privateGroupMessageChannel = new PrivateGroupMessageChannel(groupName);
                messageService.publish(privateGroupMessageChannel.getChannelName(), textMessage);
                // ??????????????????????????????websocket??????
                messageService.deleteChannel(privateGroupMessageChannel.getChannelName());

                Channel groupHallListChannel = new GroupHallListChannel(adminId);
                messageService.publish(groupHallListChannel.getChannelName(), textMessage);

                return ResponseEntity.ok(ResultUtil.success());
            }

            throw new SQLException("user_group????????????????????? || group?????????????????????");
        } catch (Exception e) {
            log.error("????????????????????????id: {}???????????????: {}", groupId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ResultUtil.error());
        }
    }

    @Override
    public ResponseEntity<Result> dismissPublicGroup(String groupId) {
        try {
            if (StringUtils.isEmpty(groupId)) {
                throw new NullPointerException("????????????");
            }

            PublicGroup publicGroup = publicGroupDao.selectPublicGroupById(Integer.parseInt(groupId));
            if (ObjectUtils.isEmpty(publicGroup)) {
                return ResponseEntity.ok(new Result<>(ResultEnum.ERROR.getStatus(), "?????????????????????????????????", null));
            }

            boolean adminCreated = publicGroup.isAdminCreated();

            if ((publicGroupDao.deletePublicGroupById(Integer.parseInt(groupId)) == 1)) {
                // ????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
                if (!adminCreated) {
                    Channel channel = new PublicGroupCreatedByOutsideListChannel();
                    String message = JSONObject.toJSONString(WsResultUtil.dismissPublicGroup(new HashMap<String, Object>(1).put("groupId", groupId)));
                    messageService.publish(channel.getChannelName(), new TextMessage(message));
                }

                // ?????????????????????????????????websocket??????
                PublicGroupMessageChannel publicGroupMessageChannel = new PublicGroupMessageChannel(publicGroup.getGroupName());
                messageService.deleteChannel(publicGroupMessageChannel.getChannelName());

                // ???????????????????????????????????????????????????ws????????????????????????????????????????????????????????????????????????
                return ResponseEntity.ok(ResultUtil.success());
            }

            throw new SQLException("public_group?????????????????????");
        } catch (Exception e) {
            log.error("??????????????????????????????id: {}???????????????: {}", groupId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ResultUtil.error());
        }
    }

    @Override
    public ResponseEntity<Result> outsideCreatePublicGroup(OutsideCreatePublicGroupDTO outsideCreatePublicGroupDTO) {
        try {
            if (ObjectUtils.isEmpty(outsideCreatePublicGroupDTO)) {
                throw new NullPointerException("????????????");
            }

            String groupName = outsideCreatePublicGroupDTO.getGroupName();
            Integer maxCount = outsideCreatePublicGroupDTO.getMaxCount();

            // ????????????????????????????????????
            if (isExistPublicGroup(groupName)) {
                log.warn("?????????????????????");
                return ResponseEntity.ok(new Result<>(ResultEnum.ERROR.getStatus(), "?????????????????????!", null));
            }

            PublicGroup publicGroup = new PublicGroup();
            publicGroup.setGroupName(groupName);
            publicGroup.setMaxCount(maxCount == 0 ? DEFAULT_MEMBER_COUNT : maxCount);
            publicGroup.setAdminCreated(false);
            publicGroup.setAdminId(null);

            if (publicGroupDao.insertPublicGroup(publicGroup)) {
                publicGroup = publicGroupDao.selectPublicGroupByName(groupName);

                // ????????????????????????"???????????????????????????"??????
                Channel channel = new PublicGroupCreatedByOutsideListChannel();
                String message = JSONObject.toJSONString(WsResultUtil.createPublicGroup(publicGroup));
                messageService.publish(channel.getChannelName(), new TextMessage(message));

                // ????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
                return ResponseEntity.ok(ResultUtil.success(publicGroup));
            }

            throw new SQLException("public_group?????????????????????");
        } catch (Exception e) {
            log.error("?????????????????????????????????????????????: {}???????????????: {}", outsideCreatePublicGroupDTO, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ResultUtil.error());
        }
    }

    @Override
    public ResponseEntity<Result> listGroupsWithoutAdmin() {
        try {
            List<GroupVO> groupVos = groupDao.selectAllGroupsAndCreatorsWithoutAdmin();
            return ResponseEntity.ok(ResultUtil.success(groupVos));
        } catch (Exception e) {
            log.error("????????????????????????????????????????????????????????????: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ResultUtil.error());
        }
    }

    @Override
    public ResponseEntity<Result> adminCreateGroup(CreateGroupDTO createGroupDTO, HttpSession session) {
        try {
            if (ObjectUtils.isEmpty(createGroupDTO)) {
                log.error("????????????");
                throw new NullPointerException("????????????");
            }

            String groupName = createGroupDTO.getGroupName();
            String creatorId = createGroupDTO.getCreatorId();
            String adminId = createGroupDTO.getAdminId();
            int maxCount = createGroupDTO.getMaxCount();
            Boolean adminCreated = createGroupDTO.getAdminCreated();

            /*
            ??????????????????????????????????????????????????????
             */
            if (isExistGroup(groupName)) {
                log.warn("?????????????????????");
                return ResponseEntity.ok(new Result(ResultEnum.ERROR.getStatus(), "?????????????????????!", null));
            }

            /*
            ???????????????????????????????????????????????????20
             */
            if (groupDao.insertAGroup(groupName, creatorId, adminId, maxCount == 0 ? DEFAULT_MEMBER_COUNT : maxCount, adminCreated)) {
                // ????????????????????????????????????
                Group group = groupDao.findGroupByGroupName(groupName);
                GroupVO groupVo = new GroupVO(group);
                SessionInfo sessionInfo = (SessionInfo) session.getAttribute(SESSION_INFO);
                Admin admin = sessionInfo.getAdmin();
                groupVo.setAdminName(admin.getAdminName());

                // ????????????????????????????????????????????????
                String message = JSONObject.toJSONString(WsResultUtil.createGroup(groupVo));
                Channel channel = new GroupHallListChannel(adminId);
                messageService.publish(channel.getChannelName(), new TextMessage(message));

                return ResponseEntity.ok(ResultUtil.success(group));
            }

            throw new SQLException("group?????????????????????");
        } catch (Exception e) {
            log.error("?????????????????????????????????: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ResultUtil.error());
        }
    }

    @Override
    public ResponseEntity<Result> giveUpManagePrivateGroup(String groupId, HttpSession session) {
        try {
            if (StringUtils.isEmpty(groupId)) {
                throw new NullPointerException("????????????");
            }

            SessionInfo sessionInfo = (SessionInfo) session.getAttribute(SESSION_INFO);
            String adminId = sessionInfo.getAdmin().getAdminId();
            log.info("adminId: {}", adminId);

            GroupVO groupVO = groupDao.selectGroupVOByGroupId(Integer.parseInt(groupId));
            List<User> users = groupDao.selectUsersWithGroupId(Integer.parseInt(groupId));
            User creator = userDao.selectUserWithUserId(Integer.parseInt(groupVO.getCreatorId()));
            users.add(creator);

            if (groupDao.clearAdminId(Integer.parseInt(groupId))) {
                users.forEach(user -> userAdminDao.deleteUserAdminByUserId(Integer.parseInt(user.getUserId())));

                // ?????????????????????????????????
                String message = JSONObject.toJSONString(WsResultUtil.giveUpManageGroup(groupId));
                TextMessage textMessage = new TextMessage(message);

                // ??????????????????????????????????????????
                Channel privateGroupMessageChannel = new PrivateGroupMessageChannel(groupVO.getGroupName());
                messageService.publish(privateGroupMessageChannel.getChannelName(), textMessage);

                // ????????????"?????????????????????????????????"??????
                Channel privateGroupWithoutAdminListChannel = new PrivateGroupWithoutAdminListChannel();
                messageService.publish(privateGroupWithoutAdminListChannel.getChannelName(), textMessage);

                // ??????????????????
                Channel groupHallListChannel = new GroupHallListChannel(adminId);
                messageService.publish(groupHallListChannel.getChannelName(), textMessage);

                // ????????????"???????????????????????????"??????
                Map<String, Object> map = new HashMap<>();
                map.put("users", users);
                map.put("groupName", groupVO.getGroupName());
                String json = JSONObject.toJSONString(WsResultUtil.giveUpManageUser(map));
                Channel userWithoutAdminListChannel = new UserWithoutAdminListChannel();
                messageService.publish(userWithoutAdminListChannel.getChannelName(), new TextMessage(json));

                return ResponseEntity.ok(ResultUtil.success());
            }

            throw new SQLException("group?????????adminId?????? || user_admin???");
        } catch (Exception e) {
            log.error("???????????????????????????????????????: {}???????????????: {}", groupId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ResultUtil.error());
        }
    }

    @Override
    public ResponseEntity<Result> choiceManagePrivateGroup(String groupId, HttpSession session) {
        try {
            if (StringUtils.isEmpty(groupId)) {
                throw new NullPointerException("????????????");
            }

            Group group = groupDao.selectGroupByGroupId(Integer.parseInt(groupId));
            String creatorId = group.getCreatorId();
            SessionInfo sessionInfo = (SessionInfo) session.getAttribute(SESSION_INFO);
            String adminId = sessionInfo.getAdmin().getAdminId();

            if (groupDao.updateAdminIdByGroupId(Integer.parseInt(adminId), Integer.parseInt(groupId))) {
                // ?????????????????????userId??????List???
                List<UserGroup> userGroups = userGroupDao.selectUserGroupsByGroupId(Integer.parseInt(groupId));
                List<String> userIds = userGroups.stream().map(UserGroup::getUserId).collect(Collectors.toList());
                userIds.add(creatorId);

                // ???user_admin???????????????
                userIds.forEach(userId -> userAdminDao.insertAnUserAdmin(new UserAdmin(userId, adminId)));

                Map<String, Object> map = new HashMap<>(2);
                map.put("groupId", groupId);
                map.put("admin", sessionInfo.getAdmin());
                TextMessage textMessage = new TextMessage(JSONObject.toJSONString(WsResultUtil.choiceManagePrivateGroup(map)));

                // ????????????????????????????????????
                Channel privateGroupMessageChannel = new PrivateGroupMessageChannel(group.getGroupName());
                messageService.publish(privateGroupMessageChannel.getChannelName(), textMessage);

                // ????????????"?????????????????????????????????"??????
                Channel privateGroupWithoutAdminListChannel = new PrivateGroupWithoutAdminListChannel();
                messageService.publish(privateGroupWithoutAdminListChannel.getChannelName(), textMessage);

                // ?????????????????????????????????
                Channel groupHallListChannel = new GroupHallListChannel(adminId);
                messageService.publish(groupHallListChannel.getChannelName(), textMessage);

                // ????????????"???????????????????????????"??????
                List<User> users = userDao.selectUsersWithAdminId(Integer.parseInt(adminId));
                String message = JSONObject.toJSONString(WsResultUtil.choiceManageUser(users));
                Channel userWithoutAdminListChannel = new UserWithoutAdminListChannel();
                messageService.publish(userWithoutAdminListChannel.getChannelName(), new TextMessage(message));

                group.setAdminId(adminId);

                return ResponseEntity.ok(ResultUtil.success(group));
            }

            throw new SQLException("??????user_group????????? || ??????user_admin?????????");
        } catch (Exception e) {
            log.error("??????????????????????????????????????????????????????????????????{}??????????????????{}", groupId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ResultUtil.error());
        }
    }
}
