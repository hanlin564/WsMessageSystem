package com.whl.messagesystem.service.session;

import com.whl.messagesystem.commons.constant.LoginResultConstant;
import com.whl.messagesystem.commons.constant.UserConstant;
import com.whl.messagesystem.commons.utils.ResultUtil;
import com.whl.messagesystem.commons.utils.verifyCode.IVerifyCodeGen;
import com.whl.messagesystem.dao.UserDao;
import com.whl.messagesystem.model.Result;
import com.whl.messagesystem.model.VerifyCode;
import com.whl.messagesystem.model.dto.LoginDto;
import com.whl.messagesystem.model.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.sql.SQLException;

/**
 * @author whl
 * @date 2021/12/7 18:47
 */
@Slf4j
@Service
public class SessionServiceImpl implements SessionService {

    @Resource
    IVerifyCodeGen iVerifyCodeGen;

    @Resource
    UserDao userDao;

    /**
     * 获取当前会话的信息
     */
    @Override
    public Result getSessionInfo(HttpSession session) {
        try {
            User user = (User) session.getAttribute(UserConstant.USER);
            log.info("当前用户信息: {}", user);
            return ResultUtil.success(user);
        } catch (Exception e) {
            log.error("获取当前用户信息异常: {}", e.getMessage());
            return ResultUtil.error();
        }
    }

    /**
     * 用户登录
     */
    @Override
    public Result userLogin(LoginDto loginDto, HttpServletRequest request, HttpServletResponse response) {
        try {
            HttpSession session = request.getSession();

            // 没太弄懂这行的作用，也许是在登录之前确保之前的账号已经被登出了
            session.removeAttribute(UserConstant.USER);

            // 判断用户输入的验证码是否正确
            String sessionVerifyCode = (String) session.getAttribute("VerifyCode");
            sessionVerifyCode = sessionVerifyCode.toLowerCase();
            if (!loginDto.getServerCode().toLowerCase().equals(sessionVerifyCode)) {
                return new Result(LoginResultConstant.WRONG_VERIFYCODE.getCode(), LoginResultConstant.WRONG_VERIFYCODE.getMessage(), null);
            } else {

                /*
                  在user表中进行查找，若找到了就表示登录成功，且把用户信息放入session中
                 */
                String userName = loginDto.getUserName();
                String password = loginDto.getPassword();

                if (userDao.getActiveUsersCountWithNameAndPassword(userName, password) == 1) {
                    User user = new User();
                    user.setUserId(userDao.getUserIdWithName(userName));
                    user.setUserName(userName);
                    user.setPassword(password);
                    session.setAttribute(UserConstant.USER, user);
                    return ResultUtil.success();
                }

                throw new SQLException("该用户不存在");
            }
        } catch (Exception e) {
            log.error("登录异常: {}", e.getMessage());
            return ResultUtil.error();
        }
    }

    /**
     * 登出，销毁当前会话
     */
    @Override
    public Result logout(HttpSession session) {
        try {
            session.removeAttribute(UserConstant.USER);
            log.info("当前用户登出成功");
            return ResultUtil.success();
        } catch (Exception e) {
            log.error("登出异常: {}", e.getMessage());
            return ResultUtil.error();
        }
    }

    /**
     * 生成验证码并返回图片
     */
    @Override
    public void generateVerifyCode(HttpServletRequest request, HttpServletResponse response) {
        try {
            //设置长宽
            VerifyCode verifyCode = iVerifyCodeGen.generate(80, 28);
            String code = verifyCode.getCode();
            log.info("验证码: {}", code);
            //将VerifyCode绑定session
            request.getSession().setAttribute("VerifyCode", code);
            //设置响应头
            response.setHeader("Pragma", "no-cache");
            //设置响应头
            response.setHeader("Cache-Control", "no-cache");
            //在代理服务器端防止缓冲
            response.setDateHeader("Expires", 0);
            //设置响应内容类型
            response.setContentType("image/jpeg");
            response.getOutputStream().write(verifyCode.getImgBytes());
            response.getOutputStream().flush();
        } catch (IOException e) {
            log.error("生成验证码失败: {}", e.getMessage());
        }
    }
}
