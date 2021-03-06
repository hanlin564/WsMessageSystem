package com.whl.messagesystem.service.admin;

import com.alibaba.fastjson.JSONObject;
import com.whl.messagesystem.commons.channel.Channel;
import com.whl.messagesystem.commons.channel.management.AdminListChannel;
import com.whl.messagesystem.commons.channel.management.user.UserWithAdminListChannel;
import com.whl.messagesystem.commons.constant.ResultEnum;
import com.whl.messagesystem.commons.constant.RoleConstant;
import com.whl.messagesystem.commons.constant.StringConstant;
import com.whl.messagesystem.commons.utils.ResultUtil;
import com.whl.messagesystem.commons.utils.WsResultUtil;
import com.whl.messagesystem.dao.AdminDao;
import com.whl.messagesystem.dao.GroupDao;
import com.whl.messagesystem.dao.PublicGroupDao;
import com.whl.messagesystem.dao.UserAdminDao;
import com.whl.messagesystem.model.Result;
import com.whl.messagesystem.model.dto.AdminInfo;
import com.whl.messagesystem.model.dto.AdminRegisterDTO;
import com.whl.messagesystem.model.dto.SessionInfo;
import com.whl.messagesystem.model.dto.UserGroupInfoDTO;
import com.whl.messagesystem.model.entity.*;
import com.whl.messagesystem.service.group.GroupService;
import com.whl.messagesystem.service.message.MessageServiceImpl;
import com.whl.messagesystem.service.session.SessionService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.sql.SQLException;
import java.util.List;

import static com.whl.messagesystem.commons.constant.StringConstant.SESSION_INFO;

/**
 * @author whl
 * @date 2022/1/22 11:25
 */
@Service
@Slf4j
public class AdminServiceImpl implements AdminService {

    @Resource
    AdminDao adminDao;

    @Resource
    GroupDao groupDao;

    @Resource
    UserAdminDao userAdminDao;

    @Resource
    PublicGroupDao publicGroupDao;

    @Resource
    SessionService sessionService;

    @Resource
    GroupService groupService;

    @Resource
    MessageServiceImpl messageService;

    @Override
    public ResponseEntity<Result> listAdmin() {
        try {
            List<Admin> admins = adminDao.selectAllAdmins();
            return ResponseEntity.ok(ResultUtil.success(admins));
        } catch (Exception e) {
            log.error("???????????????????????????: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ResultUtil.error());
        }
    }

    @Override
    public ResponseEntity<Result> register(AdminRegisterDTO adminRegisterDTO) {
        try {
            if (ObjectUtils.isEmpty(adminRegisterDTO)) {
                throw new NullPointerException("????????????");
            }

            String adminName = adminRegisterDTO.getAdminName();
            String password = adminRegisterDTO.getPassword();
            Admin admin = new Admin();
            admin.setAdminName(adminName);
            admin.setPassword(password);
            log.info("??????????????????????????????: {}", admin);

            if (adminDao.getAdminCountByAdminName(adminName) > 0) {
                log.error("??????????????????");
                return ResponseEntity.ok(new Result<>(ResultEnum.ERROR.getStatus(), "??????????????????", null));
            }

            if (adminDao.insertAnAdmin(admin)) {
                admin = adminDao.selectAdminByAdminName(adminName);

                // ???????????????????????????
                Channel channel = new AdminListChannel();
                String message = JSONObject.toJSONString(WsResultUtil.registerAdmin(admin));
                messageService.publish(channel.getChannelName(), new TextMessage(message));

                return ResponseEntity.ok(ResultUtil.success());
            }

            throw new SQLException("admin?????????????????????");
        } catch (Exception e) {
            log.error("????????????????????????: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ResultUtil.error());
        }
    }

    @Override
    public synchronized ResponseEntity<Result> deleteAdmin(String adminId, HttpSession session) {
        try {
            if (StringUtils.isEmpty(adminId)) {
                throw new NullPointerException("????????????");
            }

            SessionInfo sessionInfo = (SessionInfo) session.getAttribute(StringConstant.SESSION_INFO);
            if (RoleConstant.ADMIN.equals(sessionInfo.getRole()) && adminId.equals(sessionInfo.getAdmin().getAdminId())) {
                // ?????????????????????????????????????????????
                if (adminDao.selectAdminCount() == 1) {
                    log.warn("??????????????????????????????????????????");
                    return ResponseEntity.ok(ResultUtil.error("??????????????????????????????????????????"));
                }

                // ?????????????????????????????????
                List<Group> privateGroups = groupDao.selectGroupsByAdminId(Integer.parseInt(adminId));
                privateGroups.stream().map(Group::getGroupId).forEach(groupId -> groupService.giveUpManagePrivateGroup(groupId, session));

                // ?????????????????????????????????
                List<PublicGroup> publicGroups = publicGroupDao.selectPublicGroupsWithAdminId(Integer.parseInt(adminId));
                publicGroups.stream().map(PublicGroup::getGroupId).forEach(groupId -> groupService.dismissPublicGroup(groupId));

                if (adminDao.deleteAdminByAdminId(Integer.parseInt(adminId))) {
                    sessionService.logout(session);

                    // ???????????????????????????
                    AdminListChannel adminListChannel = new AdminListChannel();
                    String message = JSONObject.toJSONString(WsResultUtil.deleteAdmin(adminId));
                    messageService.publish(adminListChannel.getChannelName(), new TextMessage(message));

                    return ResponseEntity.ok(ResultUtil.success());
                }

                throw new SQLException("admin?????????????????????");
            }

            return ResponseEntity.ok(ResultUtil.error("??????????????????????????????????????????"));
        } catch (Exception e) {
            log.error("???????????????????????????: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ResultUtil.error());
        }
    }

    @Override
    public ResponseEntity<Result> updateAdminNameAndPassword(AdminInfo adminInfo, HttpSession session) {
        try {
            if (ObjectUtils.isEmpty(adminInfo)) {
                throw new NullPointerException("????????????");
            }

            Admin admin = new Admin();
            admin.setAdminId(adminInfo.getAdminId());
            admin.setAdminName(adminInfo.getAdminName());
            admin.setPassword(adminInfo.getPassword());

            if (adminDao.updateAdminNameAndPassword(admin)) {
                // ????????????
                SessionInfo sessionInfo = (SessionInfo) session.getAttribute(SESSION_INFO);
                sessionInfo.setAdmin(admin);
                session.setAttribute(SESSION_INFO, sessionInfo);

                AdminListChannel adminListChannel = new AdminListChannel();
                String message = JSONObject.toJSONString(WsResultUtil.updateAdminNameAndPassword(admin));
                messageService.publish(adminListChannel.getChannelName(), new TextMessage(message));

                return ResponseEntity.ok(ResultUtil.success());
            }

            throw new SQLException("admin?????????????????????");
        } catch (Exception e) {
            log.error("??????????????????????????????????????????????????????{}??????????????????{}", adminInfo, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ResultUtil.error());
        }
    }

    @Override
    public ResponseEntity<Result> choiceAnAdmin(String adminId, HttpSession session) {
        try {
            if (StringUtils.isEmpty(adminId)) {
                throw new NullPointerException("????????????");
            }

            SessionInfo sessionInfo = (SessionInfo) session.getAttribute(SESSION_INFO);
            String userId = sessionInfo.getUser().getUserId();

            if (groupDao.selectGroupByCreatorId(Integer.parseInt(userId)) == null) {
                userAdminDao.insertAnUserAdmin(new UserAdmin(userId, adminId));
                User user = sessionInfo.getUser();
                Admin admin = adminDao.selectAdminByAdminId(Integer.parseInt(adminId));
                sessionInfo.setAdmin(admin);
                String message = JSONObject.toJSONString(WsResultUtil.choiceAdmin(user));
                Channel userWithAdminListChannel = new UserWithAdminListChannel(adminId);
                messageService.publish(userWithAdminListChannel.getChannelName(), new TextMessage(message));
                return ResponseEntity.ok(ResultUtil.success());
            } else {
                if (userAdminDao.insertAnUserAdmin(new UserAdmin(userId, adminId))) {
                    List<User> members = groupDao.selectUsersWithGroupId(Integer.parseInt(sessionInfo.getGroup().getGroupId()));
                    members.forEach(member -> userAdminDao.insertAnUserAdmin(new UserAdmin(member.getUserId(), adminId)));

                    groupDao.updateAdminIdByGroupId(Integer.parseInt(adminId), Integer.parseInt(sessionInfo.getGroup().getGroupId()));

                    Admin admin = adminDao.selectAdminByAdminId(Integer.parseInt(adminId));
                    sessionInfo.setAdmin(admin);

                    // ???????????????????????????????????????
                    User user = sessionInfo.getUser();
                    Group group = sessionInfo.getGroup();
                    UserGroupInfoDTO userGroupInfoDTO = new UserGroupInfoDTO();
                    userGroupInfoDTO.setGroup(group);
                    userGroupInfoDTO.setUser(user);
                    String message = JSONObject.toJSONString(WsResultUtil.choiceAdmin(userGroupInfoDTO));
                    Channel userWithAdminListChannel = new UserWithAdminListChannel(adminId);
                    messageService.publish(userWithAdminListChannel.getChannelName(), new TextMessage(message));

                    return ResponseEntity.ok(ResultUtil.success());
                }
            }



            throw new SQLException("user_admin???????????????");
        } catch (Exception e) {
            log.error("?????????????????????????????????{}??????????????????{}", adminId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ResultUtil.error());
        }
    }
}
