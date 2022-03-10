package com.whl.messagesystem.controller;

import com.whl.messagesystem.model.Result;
import com.whl.messagesystem.model.dto.UserRegisterDTO;
import com.whl.messagesystem.model.dto.UserInfo;
import com.whl.messagesystem.service.user.UserService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.Arrays;

/**
 * @author whl
 * @date 2021/12/7 15:29
 */
@Api("用户")
@RequestMapping("/user")
@RestController
public class UserController {

    @Resource
    UserService userService;

    /**
     * 注册新用户
     */
    @ApiOperation("注册新用户(用户)")
    @PostMapping("/register")
    public ResponseEntity<Result> register(@RequestBody UserRegisterDTO userRegisterDto) {
        return userService.register(userRegisterDto.getUserName(), userRegisterDto.getPassword(), userRegisterDto.getAdminId());
    }

    /**
     * 更新用户名、密码
     */
    @ApiOperation("更新用户名、密码(用户)")
    @PutMapping("/updateNameAndPassword")
    public ResponseEntity<Result> updateUserNameAndPassword(@RequestBody UserInfo userInfo, HttpSession session) {
        return userService.updateUserNameAndPassword(userInfo.getUserId(), userInfo.getUserName(), userInfo.getPassword(), session);
    }

    /**
     * 逻辑删除用户
     */
    @ApiOperation("逻辑删除用户(用户)(管理员)")
    @DeleteMapping("/logicalDeleteUser/{userId}")
    public ResponseEntity<Result> logicalDeleteUser(@PathVariable("userId") String userId) {
        return userService.logicalDeleteUser(Integer.parseInt(userId));
    }

    /**
     * 彻底删除用户
     */
    @ApiOperation("彻底删除用户(管理员)")
    @DeleteMapping("/completelyDeleteUser/{userIds}")
    public ResponseEntity<Result> completelyDeleteUser(@PathVariable("userIds") String[] userIds) {
        return userService.completelyDeleteUser(Arrays.stream(userIds).mapToInt(Integer::parseInt).toArray());
    }

    /**
     * 恢复用户
     */
    @ApiOperation("恢复被逻辑删除的用户(管理员)")
    @PutMapping("/recover")
    public ResponseEntity<Result> recoverUser(@RequestBody String[] userIds) {
        return userService.recoverUser(Arrays.stream(userIds).mapToInt(Integer::parseInt).toArray());
    }

    /**
     * 根据管理员id展示用户列表
     */
    @ApiOperation("根据管理员id展示用户列表(管理员)")
    @PutMapping("/list/{adminId}")
    public ResponseEntity<Result> listUsersByAdminId(@PathVariable("adminId") String adminId) {
        return userService.listUsersByAdminId(adminId);
    }

}
