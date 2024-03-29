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
            log.error("获取管理员列表异常: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ResultUtil.error());
        }
    }

    @Override
    public ResponseEntity<Result> register(AdminRegisterDTO adminRegisterDTO) {
        try {
            if (ObjectUtils.isEmpty(adminRegisterDTO)) {
                throw new NullPointerException("参数为空");
            }

            String adminName = adminRegisterDTO.getAdminName();
            String password = adminRegisterDTO.getPassword();
            Admin admin = new Admin();
            admin.setAdminName(adminName);
            admin.setPassword(password);
            log.info("要插入的管理员信息为: {}", admin);

            if (adminDao.getAdminCountByAdminName(adminName) > 0) {
                log.error("管理员已存在");
                return ResponseEntity.ok(new Result<>(ResultEnum.ERROR.getStatus(), "管理员已存在", null));
            }

            if (adminDao.insertAnAdmin(admin)) {
                admin = adminDao.selectAdminByAdminName(adminName);

                // 实时更新管理员列表
                Channel channel = new AdminListChannel();
                String message = JSONObject.toJSONString(WsResultUtil.registerAdmin(admin));
                messageService.publish(channel.getChannelName(), new TextMessage(message));

                return ResponseEntity.ok(ResultUtil.success());
            }

            throw new SQLException("admin表插入记录失败");
        } catch (Exception e) {
            log.error("注册新管理员异常: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ResultUtil.error());
        }
    }

    @Override
    public synchronized ResponseEntity<Result> deleteAdmin(String adminId, HttpSession session) {
        try {
            if (StringUtils.isEmpty(adminId)) {
                throw new NullPointerException("参数为空");
            }

            SessionInfo sessionInfo = (SessionInfo) session.getAttribute(StringConstant.SESSION_INFO);
            if (RoleConstant.ADMIN.equals(sessionInfo.getRole()) && adminId.equals(sessionInfo.getAdmin().getAdminId())) {
                // 只剩一个管理员时是不允许注销的
                if (adminDao.selectAdminCount() == 1) {
                    log.warn("当前只剩一个管理员，无法注销");
                    return ResponseEntity.ok(ResultUtil.error("当前只剩一个管理员，无法注销"));
                }

                // 放弃管理自己的私有分组
                List<Group> privateGroups = groupDao.selectGroupsByAdminId(Integer.parseInt(adminId));
                privateGroups.stream().map(Group::getGroupId).forEach(groupId -> groupService.giveUpManagePrivateGroup(groupId, session));

                // 解散自己管理的公共分组
                List<PublicGroup> publicGroups = publicGroupDao.selectPublicGroupsWithAdminId(Integer.parseInt(adminId));
                publicGroups.stream().map(PublicGroup::getGroupId).forEach(groupId -> groupService.dismissPublicGroup(groupId));

                if (adminDao.deleteAdminByAdminId(Integer.parseInt(adminId))) {
                    sessionService.logout(session);

                    // 实时更新管理员列表
                    AdminListChannel adminListChannel = new AdminListChannel();
                    String message = JSONObject.toJSONString(WsResultUtil.deleteAdmin(adminId));
                    messageService.publish(adminListChannel.getChannelName(), new TextMessage(message));

                    return ResponseEntity.ok(ResultUtil.success());
                }

                throw new SQLException("admin表删除记录失败");
            }

            return ResponseEntity.ok(ResultUtil.error("必须由管理员本人执行注销操作"));
        } catch (Exception e) {
            log.error("注销管理员账号异常: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ResultUtil.error());
        }
    }

    @Override
    public ResponseEntity<Result> updateAdminNameAndPassword(AdminInfo adminInfo, HttpSession session) {
        try {
            if (ObjectUtils.isEmpty(adminInfo)) {
                throw new NullPointerException("参数为空");
            }

            Admin admin = new Admin();
            admin.setAdminId(adminInfo.getAdminId());
            admin.setAdminName(adminInfo.getAdminName());
            admin.setPassword(adminInfo.getPassword());

            if (adminDao.updateAdminNameAndPassword(admin)) {
                // 更新会话
                SessionInfo sessionInfo = (SessionInfo) session.getAttribute(SESSION_INFO);
                sessionInfo.setAdmin(admin);
                session.setAttribute(SESSION_INFO, sessionInfo);

                AdminListChannel adminListChannel = new AdminListChannel();
                String message = JSONObject.toJSONString(WsResultUtil.updateAdminNameAndPassword(admin));
                messageService.publish(adminListChannel.getChannelName(), new TextMessage(message));

                return ResponseEntity.ok(ResultUtil.success());
            }

            throw new SQLException("admin表更新记录失败");
        } catch (Exception e) {
            log.error("更新管理员的用户名与密码失败，参数：{}，异常信息：{}", adminInfo, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ResultUtil.error());
        }
    }

    @Override
    public ResponseEntity<Result> choiceAnAdmin(String adminId, HttpSession session) {
        try {
            if (StringUtils.isEmpty(adminId)) {
                throw new NullPointerException("参数为空");
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

                    // 实时更新管理员端的用户列表
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



            throw new SQLException("user_admin表插入失败");
        } catch (Exception e) {
            log.error("选择管理员异常，参数：{}，异常信息：{}", adminId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ResultUtil.error());
        }
    }
}
